import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth.deps import get_current_user
from app.db import get_db
from app.models.enums import Role
from app.models.membership import Membership
from app.models.org import Org
from app.models.user import User
from app.rbac.deps import get_org_context, require_perm
from app.schemas.orgs import InviteIn, MemberOut, OrgCreateIn, OrgOut
from app.billing.gates import enforce_billing_writable, enforce_free_limits

router = APIRouter(prefix="/orgs", tags=["orgs"])

@router.post("", response_model=OrgOut)
def create_org(
    payload: OrgCreateIn,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> OrgOut:
    org = Org(name=payload.name)
    db.add(org)
    db.flush()

    db.add(Membership(user_id=user.id, org_id=org.id, role=Role.owner))
    db.commit()

    return OrgOut(id=org.id, name=org.name)

@router.get("", response_model=list[OrgOut])
def list_orgs(
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[OrgOut]:
    q = (
        select(Org)
        .join(Membership, Membership.org_id == Org.id)
        .where(Membership.user_id == user.id)
        .order_by(Org.created_at.desc())
    )
    orgs = db.scalars(q).all()
    return [OrgOut(id=o.id, name=o.name) for o in orgs]

@router.get("/{org_id}", response_model=OrgOut)
def get_org(ctx=Depends(get_org_context)) -> OrgOut:
    return OrgOut(id=ctx.org.id, name=ctx.org.name)

@router.post("/{org_id}/invites", response_model=MemberOut)
def invite_user(
    org_id: uuid.UUID,
    payload: InviteIn,
    ctx=Depends(require_perm("org:invite")),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> MemberOut:
    enforce_billing_writable(ctx.org)
    if ctx.org.plan == "free":
        enforce_free_limits(db, org_id, "members")
    allowed_roles_by_inviter = {
        Role.owner: {Role.admin, Role.member},
        Role.admin: {Role.member},
    }
    allowed = allowed_roles_by_inviter.get(ctx.membership.role, set())
    if payload.role not in allowed:
        raise HTTPException(status_code=403, detail="forbidden")
    email = payload.email.lower().strip()
    invited = db.scalar(select(User).where(User.email == email))
    if invited is None:
        invited = User(email=email)
        db.add(invited)
        db.flush()

    existing = db.get(Membership, {"user_id": invited.id, "org_id": org_id})
    if existing is not None:
        return MemberOut(user_id=existing.user_id, org_id=existing.org_id, role=existing.role)

    m = Membership(user_id=invited.id, org_id=org_id, role=payload.role)
    db.add(m)
    db.commit()
    return MemberOut(user_id=m.user_id, org_id=m.org_id, role=m.role)

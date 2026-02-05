import uuid

from fastapi import Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth.deps import get_current_user
from app.db import get_db
from app.models.enums import Role
from app.models.membership import Membership
from app.models.org import Org
from app.models.user import User
from app.rbac.perms import PERMS

class OrgContext:
    def __init__(self, org: Org, membership: Membership):
        self.org = org
        self.membership = membership

def get_org_context(
    org_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> OrgContext:
    org = db.get(Org, org_id)
    if org is None:
        raise HTTPException(status_code=404, detail="org not found")

    membership = db.get(Membership, {"user_id": user.id, "org_id": org_id})
    if membership is None:
        raise HTTPException(status_code=403, detail="not a member of this org")

    return OrgContext(org=org, membership=membership)

def require_perm(action: str):
    allowed = PERMS.get(action)
    if allowed is None:
        raise RuntimeError(f"unknown permission action: {action}")

    def _checker(org_id: uuid.UUID, ctx: OrgContext = Depends(get_org_context)) -> OrgContext:
        if ctx.org.id != org_id:
            raise HTTPException(status_code=400, detail="org context mismatch")

        if ctx.membership.role not in allowed:
            raise HTTPException(status_code=403, detail="forbidden")
        return ctx

    return _checker

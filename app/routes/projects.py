import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db import get_db
from app.models.project import Project
from app.rbac.deps import OrgContext, require_perm
from app.schemas.projects import ProjectCreateIn, ProjectOut, ProjectUpdateIn
from app.billing.gates import enforce_billing_writable, enforce_free_limits

router = APIRouter(prefix="/orgs/{org_id}/projects", tags=["projects"])

@router.post("", response_model=ProjectOut)
def create_project(
    org_id: uuid.UUID,
    payload: ProjectCreateIn,
    ctx: OrgContext = Depends(require_perm("projects:create")),
    db: Session = Depends(get_db),
) -> ProjectOut:
    enforce_billing_writable(ctx.org)
    if ctx.org.plan == "free":
        enforce_free_limits(db, org_id, "projects")

    p = Project(org_id=org_id, name=payload.name)
    db.add(p)
    db.commit()
    db.refresh(p)
    return ProjectOut(id=p.id, org_id=p.org_id, name=p.name)

@router.get("", response_model=list[ProjectOut])
def list_projects(
    org_id: uuid.UUID,
    ctx: OrgContext = Depends(require_perm("projects:read")),
    db: Session = Depends(get_db),
) -> list[ProjectOut]:
    enforce_billing_writable(ctx.org)
    if ctx.org.plan == "free":
        enforce_free_limits(db, org_id, "tasks")

    q = select(Project).where(Project.org_id == org_id).order_by(Project.created_at.desc())
    rows = db.scalars(q).all()
    return [ProjectOut(id=r.id, org_id=r.org_id, name=r.name) for r in rows]

@router.patch("/{project_id}", response_model=ProjectOut)
def update_project(
    org_id: uuid.UUID,
    project_id: uuid.UUID,
    payload: ProjectUpdateIn,
    ctx: OrgContext = Depends(require_perm("projects:update")),
    db: Session = Depends(get_db),
) -> ProjectOut:
    enforce_billing_writable(ctx.org)
    if ctx.org.plan == "free":
        enforce_free_limits(db, org_id, "tasks")

    p = db.scalar(select(Project).where(Project.id == project_id, Project.org_id == org_id))
    if p is None:
        raise HTTPException(status_code=404, detail="project not found")
    p.name = payload.name
    db.add(p)
    db.commit()
    db.refresh(p)
    return ProjectOut(id=p.id, org_id=p.org_id, name=p.name)

@router.delete("/{project_id}")
def delete_project(
    org_id: uuid.UUID,
    project_id: uuid.UUID,
    ctx: OrgContext = Depends(require_perm("projects:delete")),
    db: Session = Depends(get_db),
) -> dict:
    enforce_billing_writable(ctx.org)
    if ctx.org.plan == "free":
        enforce_free_limits(db, org_id, "tasks")

    p = db.scalar(select(Project).where(Project.id == project_id, Project.org_id == org_id))
    if p is None:
        raise HTTPException(status_code=404, detail="project not found")
    db.delete(p)
    db.commit()
    return {"deleted": True}

import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth.deps import get_current_user
from app.db import get_db
from app.models.enums import Role
from app.models.project import Project
from app.models.task import Task
from app.models.user import User
from app.rbac.deps import OrgContext, require_perm
from app.schemas.tasks import TaskCreateIn, TaskOut, TaskUpdateIn
from app.billing.gates import enforce_billing_writable, enforce_free_limits

router = APIRouter(prefix="/orgs/{org_id}", tags=["tasks"])

@router.post("/projects/{project_id}/tasks", response_model=TaskOut)
def create_task(
    org_id: uuid.UUID,
    project_id: uuid.UUID,
    payload: TaskCreateIn,
    ctx: OrgContext = Depends(require_perm("tasks:create")),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TaskOut:
    enforce_billing_writable(ctx.org)
    if ctx.org.plan == "free":
        enforce_free_limits(db, org_id, "tasks")
    project = db.scalar(select(Project).where(Project.id == project_id, Project.org_id == org_id))
    if project is None:
        raise HTTPException(status_code=404, detail="project not found")

    t = Task(
        org_id=org_id,
        project_id=project_id,
        title=payload.title,
        created_by=user.id,
        assigned_to=payload.assigned_to,
    )
    db.add(t)
    db.commit()
    db.refresh(t)
    return TaskOut(
        id=t.id,
        org_id=t.org_id,
        project_id=t.project_id,
        title=t.title,
        status=t.status,
        created_by=t.created_by,
        assigned_to=t.assigned_to,
    )

@router.get("/projects/{project_id}/tasks", response_model=list[TaskOut])
def list_tasks(
    org_id: uuid.UUID,
    project_id: uuid.UUID,
    ctx: OrgContext = Depends(require_perm("tasks:read")),
    db: Session = Depends(get_db),
) -> list[TaskOut]:
    enforce_billing_writable(ctx.org)
    if ctx.org.plan == "free":
        enforce_free_limits(db, org_id, "tasks")
    project = db.scalar(select(Project).where(Project.id == project_id, Project.org_id == org_id))
    if project is None:
        raise HTTPException(status_code=404, detail="project not found")

    q = (
        select(Task)
        .where(Task.org_id == org_id, Task.project_id == project_id)
        .order_by(Task.created_at.desc())
    )
    rows = db.scalars(q).all()
    return [
        TaskOut(
            id=r.id,
            org_id=r.org_id,
            project_id=r.project_id,
            title=r.title,
            status=r.status,
            created_by=r.created_by,
            assigned_to=r.assigned_to,
        )
        for r in rows
    ]

@router.patch("/tasks/{task_id}", response_model=TaskOut)
def update_task(
    org_id: uuid.UUID,
    task_id: uuid.UUID,
    payload: TaskUpdateIn,
    ctx: OrgContext = Depends(require_perm("tasks:update")),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> TaskOut:
    enforce_billing_writable(ctx.org)
    if ctx.org.plan == "free":
        enforce_free_limits(db, org_id, "tasks")
    t = db.scalar(select(Task).where(Task.id == task_id, Task.org_id == org_id))
    if t is None:
        raise HTTPException(status_code=404, detail="task not found")

    # only creator or assignee can edit
    if ctx.membership.role == Role.member:
        if t.created_by != user.id and t.assigned_to != user.id:
            raise HTTPException(status_code=403, detail="forbidden")

    if payload.title is not None:
        t.title = payload.title
    if payload.status is not None:
        t.status = payload.status

    # allow explicit unassign by sending null
    if "assigned_to" in payload.model_fields_set:
        t.assigned_to = payload.assigned_to

    db.add(t)
    db.commit()
    db.refresh(t)
    return TaskOut(
        id=t.id,
        org_id=t.org_id,
        project_id=t.project_id,
        title=t.title,
        status=t.status,
        created_by=t.created_by,
        assigned_to=t.assigned_to,
    )

@router.delete("/tasks/{task_id}")
def delete_task(
    org_id: uuid.UUID,
    task_id: uuid.UUID,
    ctx: OrgContext = Depends(require_perm("tasks:delete")),
    db: Session = Depends(get_db),
) -> dict:
    enforce_billing_writable(ctx.org)
    if ctx.org.plan == "free":
        enforce_free_limits(db, org_id, "tasks")
    t = db.scalar(select(Task).where(Task.id == task_id, Task.org_id == org_id))
    if t is None:
        raise HTTPException(status_code=404, detail="task not found")
    db.delete(t)
    db.commit()
    return {"deleted": True}

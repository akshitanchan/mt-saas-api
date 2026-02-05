import uuid

from fastapi import HTTPException
from sqlalchemy import select, func
from sqlalchemy.orm import Session

from app.models.membership import Membership
from app.models.project import Project
from app.models.task import Task
from app.models.org import Org

FREE_PROJECT_LIMIT = 3
FREE_TASK_LIMIT = 100
FREE_MEMBER_LIMIT = 4
BLOCKED_STATUSES = {"past_due", "canceled", "unpaid"}

def enforce_billing_writable(org: Org) -> None:
    # if billing says no, no writes
    if org.subscription_status in BLOCKED_STATUSES:
        raise HTTPException(status_code=402, detail="billing_required")

def enforce_free_limits(db: Session, org_id: uuid.UUID, kind: str) -> None:
    # only applies on free plan
    if kind == "projects":
        n = db.scalar(select(func.count()).select_from(Project).where(Project.org_id == org_id)) or 0
        if n >= FREE_PROJECT_LIMIT:
            raise HTTPException(status_code=402, detail="free_plan_project_limit")
        return

    if kind == "tasks":
        n = db.scalar(select(func.count()).select_from(Task).where(Task.org_id == org_id)) or 0
        if n >= FREE_TASK_LIMIT:
            raise HTTPException(status_code=402, detail="free_plan_task_limit")
        return

    if kind == "members":
        n = db.scalar(select(func.count()).select_from(Membership).where(Membership.org_id == org_id)) or 0
        if n >= FREE_MEMBER_LIMIT:
            raise HTTPException(status_code=402, detail="free_plan_member_limit")
        return

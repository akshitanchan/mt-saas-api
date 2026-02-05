import uuid
from pydantic import BaseModel

from app.models.enums import TaskStatus

class TaskCreateIn(BaseModel):
    title: str
    assigned_to: uuid.UUID | None = None

class TaskUpdateIn(BaseModel):
    title: str | None = None
    status: TaskStatus | None = None
    assigned_to: uuid.UUID | None = None

class TaskOut(BaseModel):
    id: uuid.UUID
    org_id: uuid.UUID
    project_id: uuid.UUID
    title: str
    status: TaskStatus
    created_by: uuid.UUID
    assigned_to: uuid.UUID | None

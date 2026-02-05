import uuid
from pydantic import BaseModel

class ProjectCreateIn(BaseModel):
    name: str

class ProjectUpdateIn(BaseModel):
    name: str

class ProjectOut(BaseModel):
    id: uuid.UUID
    org_id: uuid.UUID
    name: str

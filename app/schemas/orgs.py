import uuid
from pydantic import BaseModel, EmailStr

from app.models.enums import Role

class OrgCreateIn(BaseModel):
    name: str

class OrgOut(BaseModel):
    id: uuid.UUID
    name: str

class InviteIn(BaseModel):
    email: EmailStr
    role: Role = Role.member

class MemberOut(BaseModel):
    user_id: uuid.UUID
    org_id: uuid.UUID
    role: Role

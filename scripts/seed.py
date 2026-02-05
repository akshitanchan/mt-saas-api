import uuid
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db import SessionLocal
from app.models.enums import Role
from app.models.membership import Membership
from app.models.org import Org
from app.models.project import Project
from app.models.task import Task
from app.models.user import User

@dataclass
class SeedResult:
    owner_email: str
    admin_email: str
    member_email: str
    org_id: uuid.UUID
    project_id: uuid.UUID
    task_id: uuid.UUID

def get_or_create_user(db: Session, email: str, name: str | None = None) -> User:
    email = email.lower().strip()
    u = db.scalar(select(User).where(User.email == email))
    if u is None:
        u = User(email=email, name=name)
        db.add(u)
        db.flush()
    return u

def get_or_create_membership(db: Session, user_id: uuid.UUID, org_id: uuid.UUID, role: Role) -> Membership:
    m = db.get(Membership, {"user_id": user_id, "org_id": org_id})
    if m is None:
        m = Membership(user_id=user_id, org_id=org_id, role=role)
        db.add(m)
        db.flush()
    else:
        if m.role != role:
            m.role = role
            db.add(m)
            db.flush()
    return m

def get_or_create_org(db: Session, name: str) -> Org:
    o = db.scalar(select(Org).where(Org.name == name))
    if o is None:
        o = Org(name=name)
        db.add(o)
        db.flush()
    return o

def get_or_create_project(db: Session, org_id: uuid.UUID, name: str) -> Project:
    p = db.scalar(select(Project).where(Project.org_id == org_id, Project.name == name))
    if p is None:
        p = Project(org_id=org_id, name=name)
        db.add(p)
        db.flush()
    return p

def get_or_create_task(
    db: Session,
    org_id: uuid.UUID,
    project_id: uuid.UUID,
    title: str,
    created_by: uuid.UUID,
    assigned_to: uuid.UUID | None,
) -> Task:
    t = db.scalar(
        select(Task).where(
            Task.org_id == org_id,
            Task.project_id == project_id,
            Task.title == title,
        )
    )
    if t is None:
        t = Task(
            org_id=org_id,
            project_id=project_id,
            title=title,
            created_by=created_by,
            assigned_to=assigned_to,
        )
        db.add(t)
        db.flush()
    else:
        # keep it stable if you re-run seed
        changed = False
        if t.assigned_to != assigned_to:
            t.assigned_to = assigned_to
            changed = True
        if changed:
            db.add(t)
            db.flush()
    return t

def seed() -> SeedResult:
    db = SessionLocal()
    try:
        owner = get_or_create_user(db, "owner@example.com", "owner")
        admin = get_or_create_user(db, "admin@example.com", "admin")
        member = get_or_create_user(db, "member@example.com", "member")

        org = get_or_create_org(db, "seeded org")

        get_or_create_membership(db, owner.id, org.id, Role.owner)
        get_or_create_membership(db, admin.id, org.id, Role.admin)
        get_or_create_membership(db, member.id, org.id, Role.member)

        project = get_or_create_project(db, org.id, "seeded project")

        task = get_or_create_task(
            db,
            org.id,
            project.id,
            "seeded task",
            created_by=owner.id,
            assigned_to=member.id,
        )

        db.commit()

        return SeedResult(
            owner_email=owner.email,
            admin_email=admin.email,
            member_email=member.email,
            org_id=org.id,
            project_id=project.id,
            task_id=task.id,
        )
    finally:
        db.close()

if __name__ == "__main__":
    r = seed()
    print("seed complete")
    print(f"org_id={r.org_id}")
    print(f"project_id={r.project_id}")
    print(f"task_id={r.task_id}")
    print("users:")
    print(f"  owner:  {r.owner_email}")
    print(f"  admin:  {r.admin_email}")
    print(f"  member: {r.member_email}")

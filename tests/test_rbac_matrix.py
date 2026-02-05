import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.enums import Role
from app.models.membership import Membership
from app.models.user import User

def login(client, email: str) -> str:
    r = client.post("/auth/request-link", json={"email": email})
    assert r.status_code == 200
    token = r.json().get("token")
    assert token, f"no token returned: {r.json()}"

    r = client.post("/auth/redeem", json={"token": token})
    assert r.status_code == 200
    return r.json()["access_token"]

def auth(jwt: str) -> dict[str, str]:
    return {"authorization": f"bearer {jwt}"}

def add_membership(db: Session, email: str, org_id: uuid.UUID, role: Role) -> None:
    user = db.scalar(select(User).where(User.email == email.lower()))
    assert user is not None

    m = Membership(user_id=user.id, org_id=org_id, role=role)
    db.add(m)
    db.commit()

def test_rbac_projects_and_tasks(client, db_session: Session):
    owner_jwt = login(client, "owner@example.com")
    admin_jwt = login(client, "admin@example.com")
    member_jwt = login(client, "member@example.com")

    # owner creates org
    r = client.post("/orgs", json={"name": "rbac-org"}, headers=auth(owner_jwt))
    assert r.status_code == 200
    org_id = r.json()["id"]

    # grant roles (direct db insert for speed + clarity)
    add_membership(db_session, "admin@example.com", uuid.UUID(org_id), Role.admin)
    add_membership(db_session, "member@example.com", uuid.UUID(org_id), Role.member)

    # owner can create project
    r = client.post(f"/orgs/{org_id}/projects", json={"name": "p-owner"}, headers=auth(owner_jwt))
    assert r.status_code == 200

    # admin can create project
    r = client.post(f"/orgs/{org_id}/projects", json={"name": "p-admin"}, headers=auth(admin_jwt))
    assert r.status_code == 200
    project_id = r.json()["id"]

    # member cannot create project
    r = client.post(f"/orgs/{org_id}/projects", json={"name": "p-member"}, headers=auth(member_jwt))
    assert r.status_code == 403

    # member can create task
    r = client.post(
        f"/orgs/{org_id}/projects/{project_id}/tasks",
        json={"title": "t-member"},
        headers=auth(member_jwt),
    )
    assert r.status_code == 200
    task_id = r.json()["id"]

    # member cannot delete task (delete is owner/admin)
    r = client.delete(f"/orgs/{org_id}/tasks/{task_id}", headers=auth(member_jwt))
    assert r.status_code == 403

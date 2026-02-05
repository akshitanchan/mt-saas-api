# tests/test_permissions_sweep.py

import uuid

import pytest

def auth_headers(jwt: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {jwt}"}

def login(client, email: str) -> str:
    r = client.post("/auth/request-link", json={"email": email})
    assert r.status_code == 200, r.text
    token = r.json()["token"]

    r = client.post("/auth/redeem", json={"email": email, "token": token})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]

def create_org(client, jwt: str, name: str) -> str:
    r = client.post("/orgs", headers=auth_headers(jwt), json={"name": name})
    assert r.status_code == 200, r.text
    return r.json()["id"]

def invite(client, jwt: str, org_id: str, email: str, role: str) -> dict:
    r = client.post(
        f"/orgs/{org_id}/invites",
        headers=auth_headers(jwt),
        json={"email": email, "role": role},
    )
    return {"status": r.status_code, "json": r.json() if r.headers.get("content-type", "").startswith("application/json") else None, "text": r.text}

def create_project(client, jwt: str, org_id: str, name: str) -> str:
    r = client.post(
        f"/orgs/{org_id}/projects",
        headers=auth_headers(jwt),
        json={"name": name},
    )
    assert r.status_code == 200, r.text
    return r.json()["id"]

def update_project(client, jwt: str, org_id: str, project_id: str, name: str) -> int:
    r = client.patch(
        f"/orgs/{org_id}/projects/{project_id}",
        headers=auth_headers(jwt),
        json={"name": name},
    )
    return r.status_code

def delete_project(client, jwt: str, org_id: str, project_id: str) -> int:
    r = client.delete(
        f"/orgs/{org_id}/projects/{project_id}",
        headers=auth_headers(jwt),
    )
    return r.status_code

def create_task(client, jwt: str, org_id: str, project_id: str, title: str, assigned_to: str | None = None) -> str:
    payload = {"title": title}
    if assigned_to is not None:
        payload["assigned_to"] = assigned_to

    r = client.post(
        f"/orgs/{org_id}/projects/{project_id}/tasks",
        headers=auth_headers(jwt),
        json=payload,
    )
    assert r.status_code == 200, r.text
    return r.json()["id"]

def update_task(client, jwt: str, org_id: str, task_id: str, title: str) -> int:
    r = client.patch(
        f"/orgs/{org_id}/tasks/{task_id}",
        headers=auth_headers(jwt),
        json={"title": title},
    )
    return r.status_code

def delete_task(client, jwt: str, org_id: str, task_id: str) -> int:
    r = client.delete(
        f"/orgs/{org_id}/tasks/{task_id}",
        headers=auth_headers(jwt),
    )
    return r.status_code

def uniq_email(prefix: str) -> str:
    return f"{prefix}+{uuid.uuid4().hex[:10]}@example.com"

def test_role_matrix_owner_admin_member_core_writes(client):
    owner_email = uniq_email("owner")
    admin_email = uniq_email("admin")
    member_email = uniq_email("member")
    member2_email = uniq_email("member2")

    owner_jwt = login(client, owner_email)
    org_id = create_org(client, owner_jwt, "org-a")

    # owner invites admin + member (org has 3 total members incl owner)
    res = invite(client, owner_jwt, org_id, admin_email, "admin")
    assert res["status"] == 200, res["text"]
    res = invite(client, owner_jwt, org_id, member_email, "member")
    assert res["status"] == 200, res["text"]

    admin_jwt = login(client, admin_email)
    member_jwt = login(client, member_email)

    # role escalation: admin cannot mint admin/owner
    res = invite(client, admin_jwt, org_id, uniq_email("x"), "admin")
    assert res["status"] == 403, res["text"]

    res = invite(client, admin_jwt, org_id, uniq_email("x"), "owner")
    assert res["status"] == 403, res["text"]

    # members cannot invite at all
    res = invite(client, member_jwt, org_id, uniq_email("x"), "member")
    assert res["status"] == 403, res["text"]

    # admin can invite a member (this becomes the 4th member total)
    res = invite(client, admin_jwt, org_id, member2_email, "member")
    assert res["status"] == 200, res["text"]
    member2_jwt = login(client, member2_email)

    # projects: owner/admin can create/update/delete, member cannot
    project_owner = create_project(client, owner_jwt, org_id, "p-owner")
    assert update_project(client, owner_jwt, org_id, project_owner, "p-owner-upd") == 200
    assert delete_project(client, owner_jwt, org_id, project_owner) == 200

    project_admin = create_project(client, admin_jwt, org_id, "p-admin")
    assert update_project(client, admin_jwt, org_id, project_admin, "p-admin-upd") == 200
    assert delete_project(client, admin_jwt, org_id, project_admin) == 200

    r = client.post(
        f"/orgs/{org_id}/projects",
        headers=auth_headers(member_jwt),
        json={"name": "nope"},
    )
    assert r.status_code == 403

    project_member_forbidden = create_project(client, owner_jwt, org_id, "p-member-forbidden")
    assert update_project(client, member_jwt, org_id, project_member_forbidden, "nope") == 403
    assert delete_project(client, member_jwt, org_id, project_member_forbidden) == 403

    # tasks: member can create; member cannot delete; admin/owner can delete
    project_tasks = create_project(client, owner_jwt, org_id, "p-tasks")

    task_by_member = create_task(client, member_jwt, org_id, project_tasks, "t1")
    assert update_task(client, member_jwt, org_id, task_by_member, "t1-upd") == 200

    # member guard: other member cannot edit if not creator/assignee
    assert update_task(client, member2_jwt, org_id, task_by_member, "hacked") == 403

    # deletes: member forbidden; admin/owner ok
    task_delete_check = create_task(client, member_jwt, org_id, project_tasks, "t-delete-check")
    assert delete_task(client, member_jwt, org_id, task_delete_check) == 403
    assert delete_task(client, admin_jwt, org_id, task_delete_check) == 200

    task_delete_owner = create_task(client, member_jwt, org_id, project_tasks, "t-delete-owner")
    assert delete_task(client, owner_jwt, org_id, task_delete_owner) == 200

def test_cross_org_write_routes_denied(client):
    # org a
    owner_a = uniq_email("owner-a")
    owner_a_jwt = login(client, owner_a)
    org_a = create_org(client, owner_a_jwt, "org-a")
    project_a = create_project(client, owner_a_jwt, org_a, "p-a")
    task_a = create_task(client, owner_a_jwt, org_a, project_a, "t-a")

    # org b
    owner_b = uniq_email("owner-b")
    owner_b_jwt = login(client, owner_b)

    # user from org-b should not be able to touch org-a write routes
    r = client.post(f"/orgs/{org_a}/invites", headers=auth_headers(owner_b_jwt), json={"email": uniq_email("x"), "role": "member"})
    assert r.status_code == 403

    r = client.post(f"/orgs/{org_a}/projects", headers=auth_headers(owner_b_jwt), json={"name": "nope"})
    assert r.status_code == 403

    r = client.patch(f"/orgs/{org_a}/projects/{project_a}", headers=auth_headers(owner_b_jwt), json={"name": "nope"})
    assert r.status_code == 403

    r = client.delete(f"/orgs/{org_a}/projects/{project_a}", headers=auth_headers(owner_b_jwt))
    assert r.status_code == 403

    r = client.post(f"/orgs/{org_a}/projects/{project_a}/tasks", headers=auth_headers(owner_b_jwt), json={"title": "nope"})
    assert r.status_code == 403

    r = client.patch(f"/orgs/{org_a}/tasks/{task_a}", headers=auth_headers(owner_b_jwt), json={"title": "nope"})
    assert r.status_code == 403

    r = client.delete(f"/orgs/{org_a}/tasks/{task_a}", headers=auth_headers(owner_b_jwt))
    assert r.status_code == 403

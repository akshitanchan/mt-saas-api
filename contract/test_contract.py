import hashlib
import hmac
import json
import os
import time
import uuid

import pytest

STRIPE_SECRET = os.environ.get("STRIPE_WEBHOOK_SECRET")

def auth_headers(jwt: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {jwt}"}

def create_org(client, jwt: str, name: str | None = None) -> str:
    r = client.post(
        "/orgs",
        json={"name": name or f"contract-org-{uuid.uuid4().hex[:8]}"},
        headers=auth_headers(jwt),
    )
    assert r.status_code == 200, r.text
    return r.json()["id"]

def _stripe_signature(secret: str, raw: bytes, ts: int | None = None) -> str:
    ts = ts if ts is not None else int(time.time())
    signed_payload = f"{ts}.".encode("utf-8") + raw
    v1 = hmac.new(secret.encode("utf-8"), signed_payload, hashlib.sha256).hexdigest()
    return f"t={ts},v1={v1}"

def post_webhook(client, payload: dict | None, *, raw: bytes | None = None, sign: bool = True, extra_headers=None):
    body = raw if raw is not None else json.dumps(payload).encode("utf-8")
    headers = {"content-type": "application/json"}
    if sign and STRIPE_SECRET:
        headers["stripe-signature"] = _stripe_signature(STRIPE_SECRET, body)
    if extra_headers:
        headers.update(extra_headers)
    return client.post("/webhooks/stripe", content=body, headers=headers)

# health

def test_health_returns_ok(client):
    r = client.get("/health")
    assert r.status_code == 200, r.text
    assert r.json() == {"status": "ok"}

def test_ready_returns_ok_shape(client):
    r = client.get("/ready")
    assert r.status_code == 200, r.text
    assert r.json() == {"status": "ok", "checks": {"db": True, "redis": True}}

# auth mechanics

def test_request_link_returns_an_opaque_token_in_non_prod(client):
    r = client.post("/auth/request-link", json={"email": f"contract+{uuid.uuid4().hex}@example.com"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body.keys()) == {"sent", "token", "link"}
    assert body["sent"] is True
    assert isinstance(body["token"], str) and body["token"]
    assert body["link"] is None

def test_redeeming_an_unknown_token_fails(client):
    r = client.post("/auth/redeem", json={"token": "not-a-real-token"})
    assert r.status_code == 400, r.text
    assert r.json() == {"detail": "invalid token"}

def test_redeeming_a_token_twice_fails_on_the_second_attempt(client):
    r = client.post("/auth/request-link", json={"email": f"contract+{uuid.uuid4().hex}@example.com"})
    assert r.status_code == 200, r.text
    token = r.json()["token"]

    r1 = client.post("/auth/redeem", json={"token": token})
    assert r1.status_code == 200, r1.text
    assert set(r1.json().keys()) == {"access_token", "token_type"}
    assert r1.json()["token_type"] == "bearer"

    r2 = client.post("/auth/redeem", json={"token": token})
    assert r2.status_code == 400, r2.text
    assert r2.json() == {"detail": "token already used"}

def test_missing_bearer_token_is_rejected(client, owner):
    _, org_id = owner
    r = client.get(f"/orgs/{org_id}")
    assert r.status_code == 401, r.text
    assert r.json() == {"detail": "missing bearer token"}

def test_invalid_bearer_token_is_rejected(client, owner):
    _, org_id = owner
    r = client.get(f"/orgs/{org_id}", headers={"Authorization": "Bearer not-a-real-jwt"})
    assert r.status_code == 401, r.text
    assert r.json() == {"detail": "invalid token"}

def test_bearer_scheme_is_case_insensitive(client, owner):
    jwt, org_id = owner
    r = client.get(f"/orgs/{org_id}", headers={"Authorization": f"bearer {jwt}"})
    assert r.status_code == 200, r.text

# orgs

def test_org_create_returns_id_and_name(client, owner):
    jwt, _ = owner
    r = client.post("/orgs", json={"name": "contract check org"}, headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body.keys()) == {"id", "name"}
    assert body["name"] == "contract check org"
    uuid.UUID(body["id"])

def test_org_list_is_newest_first(client, owner):
    jwt, _ = owner
    older_id = create_org(client, jwt, "org-older")
    newer_id = create_org(client, jwt, "org-newer")

    r = client.get("/orgs", headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    ids = [o["id"] for o in r.json()]
    assert ids.index(newer_id) < ids.index(older_id)

def test_get_org_by_id_returns_id_and_name(client, owner):
    jwt, org_id = owner
    r = client.get(f"/orgs/{org_id}", headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    assert set(r.json().keys()) == {"id", "name"}
    assert r.json()["id"] == org_id

def test_unknown_org_id_returns_404(client, owner):
    jwt, _ = owner
    r = client.get(f"/orgs/{uuid.uuid4()}", headers=auth_headers(jwt))
    assert r.status_code == 404, r.text
    assert r.json() == {"detail": "org not found"}

def test_malformed_org_id_path_segment_returns_422(client, owner):
    jwt, _ = owner
    r = client.get("/orgs/not-a-uuid", headers=auth_headers(jwt))
    assert r.status_code == 422, r.text
    assert isinstance(r.json()["detail"], list)

def test_unknown_json_fields_are_ignored_on_create(client, owner):
    jwt, _ = owner
    r = client.post(
        "/orgs",
        json={"name": "ignore-extra", "totally_unknown_field": 123},
        headers=auth_headers(jwt),
    )
    assert r.status_code == 200, r.text
    assert r.json()["name"] == "ignore-extra"

# invites

def test_owner_can_invite_admin_and_member(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)

    r = client.post(
        f"/orgs/{org_id}/invites",
        json={"email": f"contract+{uuid.uuid4().hex}@example.com", "role": "admin"},
        headers=auth_headers(jwt),
    )
    assert r.status_code == 200, r.text
    assert set(r.json().keys()) == {"user_id", "org_id", "role"}
    assert r.json()["role"] == "admin"

    r = client.post(
        f"/orgs/{org_id}/invites",
        json={"email": f"contract+{uuid.uuid4().hex}@example.com", "role": "member"},
        headers=auth_headers(jwt),
    )
    assert r.status_code == 200, r.text
    assert r.json()["role"] == "member"

def test_admin_can_invite_member_but_not_admin(client, owner, admin_actor):
    owner_jwt, _ = owner
    admin_email, admin_jwt = admin_actor
    org_id = create_org(client, owner_jwt)

    r = client.post(
        f"/orgs/{org_id}/invites",
        json={"email": admin_email, "role": "admin"},
        headers=auth_headers(owner_jwt),
    )
    assert r.status_code == 200, r.text

    r = client.post(
        f"/orgs/{org_id}/invites",
        json={"email": f"contract+{uuid.uuid4().hex}@example.com", "role": "member"},
        headers=auth_headers(admin_jwt),
    )
    assert r.status_code == 200, r.text

    r = client.post(
        f"/orgs/{org_id}/invites",
        json={"email": f"contract+{uuid.uuid4().hex}@example.com", "role": "admin"},
        headers=auth_headers(admin_jwt),
    )
    assert r.status_code == 403, r.text
    assert r.json() == {"detail": "forbidden"}

def test_member_cannot_invite_anyone(client, owner, member_actor):
    owner_jwt, _ = owner
    member_email, member_jwt = member_actor
    org_id = create_org(client, owner_jwt)

    r = client.post(
        f"/orgs/{org_id}/invites",
        json={"email": member_email, "role": "member"},
        headers=auth_headers(owner_jwt),
    )
    assert r.status_code == 200, r.text

    r = client.post(
        f"/orgs/{org_id}/invites",
        json={"email": f"contract+{uuid.uuid4().hex}@example.com", "role": "member"},
        headers=auth_headers(member_jwt),
    )
    assert r.status_code == 403, r.text
    assert r.json() == {"detail": "forbidden"}

def test_inviting_an_existing_member_returns_membership_unchanged(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)
    email = f"contract+{uuid.uuid4().hex}@example.com"

    r1 = client.post(f"/orgs/{org_id}/invites", json={"email": email, "role": "member"}, headers=auth_headers(jwt))
    assert r1.status_code == 200, r1.text
    user_id = r1.json()["user_id"]

    # role is ignored on the second invite: the existing membership wins
    r2 = client.post(f"/orgs/{org_id}/invites", json={"email": email, "role": "admin"}, headers=auth_headers(jwt))
    assert r2.status_code == 200, r2.text
    assert r2.json() == {"user_id": user_id, "org_id": org_id, "role": "member"}

# rbac resolution order

def test_missing_org_returns_404_before_membership_is_checked(client, owner):
    jwt, _ = owner
    r = client.get(f"/orgs/{uuid.uuid4()}/projects", headers=auth_headers(jwt))
    assert r.status_code == 404, r.text
    assert r.json() == {"detail": "org not found"}

def test_non_member_gets_403_before_role_is_checked(client, owner, member_actor):
    owner_jwt, _ = owner
    _, member_jwt = member_actor
    org_id = create_org(client, owner_jwt)

    r = client.get(f"/orgs/{org_id}/projects", headers=auth_headers(member_jwt))
    assert r.status_code == 403, r.text
    assert r.json() == {"detail": "not a member of this org"}

def test_role_without_permission_returns_403_forbidden(client, owner, member_actor):
    owner_jwt, _ = owner
    member_email, member_jwt = member_actor
    org_id = create_org(client, owner_jwt)

    r = client.post(f"/orgs/{org_id}/invites", json={"email": member_email, "role": "member"}, headers=auth_headers(owner_jwt))
    assert r.status_code == 200, r.text

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "blocked"}, headers=auth_headers(member_jwt))
    assert r.status_code == 403, r.text
    assert r.json() == {"detail": "forbidden"}

def test_cross_tenant_access_is_rejected(client, owner, helper):
    owner_jwt, org_id = owner
    r = client.get(f"/orgs/{org_id}/projects", headers=auth_headers(helper))
    assert r.status_code == 403, r.text
    assert r.json() == {"detail": "not a member of this org"}

# projects

def test_project_crud_happy_path(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "proj-a"}, headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body.keys()) == {"id", "org_id", "name"}
    project_id = body["id"]

    r = client.patch(f"/orgs/{org_id}/projects/{project_id}", json={"name": "proj-a-renamed"}, headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    assert r.json()["name"] == "proj-a-renamed"

    r = client.delete(f"/orgs/{org_id}/projects/{project_id}", headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    assert r.json() == {"deleted": True}

    r = client.patch(f"/orgs/{org_id}/projects/{project_id}", json={"name": "gone"}, headers=auth_headers(jwt))
    assert r.status_code == 404, r.text
    assert r.json() == {"detail": "project not found"}

def test_project_list_is_newest_first(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)

    r1 = client.post(f"/orgs/{org_id}/projects", json={"name": "older"}, headers=auth_headers(jwt))
    r2 = client.post(f"/orgs/{org_id}/projects", json={"name": "newer"}, headers=auth_headers(jwt))
    older_id, newer_id = r1.json()["id"], r2.json()["id"]

    r = client.get(f"/orgs/{org_id}/projects", headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    ids = [p["id"] for p in r.json()]
    assert ids.index(newer_id) < ids.index(older_id)

def test_unknown_project_id_returns_404(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)

    r = client.patch(f"/orgs/{org_id}/projects/{uuid.uuid4()}", json={"name": "x"}, headers=auth_headers(jwt))
    assert r.status_code == 404, r.text
    assert r.json() == {"detail": "project not found"}

    r = client.delete(f"/orgs/{org_id}/projects/{uuid.uuid4()}", headers=auth_headers(jwt))
    assert r.status_code == 404, r.text
    assert r.json() == {"detail": "project not found"}

def test_member_cannot_delete_a_project(client, owner, member_actor):
    owner_jwt, _ = owner
    member_email, member_jwt = member_actor
    org_id = create_org(client, owner_jwt)

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "proj"}, headers=auth_headers(owner_jwt))
    project_id = r.json()["id"]

    r = client.post(f"/orgs/{org_id}/invites", json={"email": member_email, "role": "member"}, headers=auth_headers(owner_jwt))
    assert r.status_code == 200, r.text

    r = client.delete(f"/orgs/{org_id}/projects/{project_id}", headers=auth_headers(member_jwt))
    assert r.status_code == 403, r.text
    assert r.json() == {"detail": "forbidden"}

# tasks

def test_task_crud_happy_path(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)
    project_id = client.post(f"/orgs/{org_id}/projects", json={"name": "proj"}, headers=auth_headers(jwt)).json()["id"]

    r = client.post(f"/orgs/{org_id}/projects/{project_id}/tasks", json={"title": "t1"}, headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body.keys()) == {"id", "org_id", "project_id", "title", "status", "created_by", "assigned_to"}
    assert body["status"] == "todo"
    assert body["assigned_to"] is None
    task_id = body["id"]

    r = client.patch(
        f"/orgs/{org_id}/tasks/{task_id}",
        json={"title": "t1-renamed", "status": "doing"},
        headers=auth_headers(jwt),
    )
    assert r.status_code == 200, r.text
    assert r.json()["title"] == "t1-renamed"
    assert r.json()["status"] == "doing"

    r = client.delete(f"/orgs/{org_id}/tasks/{task_id}", headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    assert r.json() == {"deleted": True}

    r = client.patch(f"/orgs/{org_id}/tasks/{task_id}", json={"title": "gone"}, headers=auth_headers(jwt))
    assert r.status_code == 404, r.text
    assert r.json() == {"detail": "task not found"}

def test_task_list_is_newest_first(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)
    project_id = client.post(f"/orgs/{org_id}/projects", json={"name": "proj"}, headers=auth_headers(jwt)).json()["id"]

    r1 = client.post(f"/orgs/{org_id}/projects/{project_id}/tasks", json={"title": "older"}, headers=auth_headers(jwt))
    r2 = client.post(f"/orgs/{org_id}/projects/{project_id}/tasks", json={"title": "newer"}, headers=auth_headers(jwt))
    older_id, newer_id = r1.json()["id"], r2.json()["id"]

    r = client.get(f"/orgs/{org_id}/projects/{project_id}/tasks", headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    ids = [t["id"] for t in r.json()]
    assert ids.index(newer_id) < ids.index(older_id)

def test_unknown_project_returns_404_for_task_creation(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)

    r = client.post(f"/orgs/{org_id}/projects/{uuid.uuid4()}/tasks", json={"title": "x"}, headers=auth_headers(jwt))
    assert r.status_code == 404, r.text
    assert r.json() == {"detail": "project not found"}

def test_task_partial_update_respects_absent_vs_explicit_null_assigned_to(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)
    project_id = client.post(f"/orgs/{org_id}/projects", json={"name": "proj"}, headers=auth_headers(jwt)).json()["id"]

    r = client.post(f"/orgs/{org_id}/projects/{project_id}/tasks", json={"title": "t"}, headers=auth_headers(jwt))
    task_id = r.json()["id"]
    assert r.json()["assigned_to"] is None

    invite_email = f"contract+{uuid.uuid4().hex}@example.com"
    r = client.post(f"/orgs/{org_id}/invites", json={"email": invite_email, "role": "member"}, headers=auth_headers(jwt))
    assignee_id = r.json()["user_id"]

    r = client.patch(f"/orgs/{org_id}/tasks/{task_id}", json={"assigned_to": assignee_id}, headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    assert r.json()["assigned_to"] == assignee_id

    # assigned_to absent from the body leaves the existing assignment untouched
    r = client.patch(f"/orgs/{org_id}/tasks/{task_id}", json={"title": "still assigned"}, headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    assert r.json()["assigned_to"] == assignee_id

    # assigned_to explicitly null unassigns
    r = client.patch(f"/orgs/{org_id}/tasks/{task_id}", json={"assigned_to": None}, headers=auth_headers(jwt))
    assert r.status_code == 200, r.text
    assert r.json()["assigned_to"] is None

def test_member_can_update_a_task_they_created(client, owner, member_actor):
    owner_jwt, _ = owner
    member_email, member_jwt = member_actor
    org_id = create_org(client, owner_jwt)
    project_id = client.post(f"/orgs/{org_id}/projects", json={"name": "proj"}, headers=auth_headers(owner_jwt)).json()["id"]

    r = client.post(f"/orgs/{org_id}/invites", json={"email": member_email, "role": "member"}, headers=auth_headers(owner_jwt))
    assert r.status_code == 200, r.text

    r = client.post(f"/orgs/{org_id}/projects/{project_id}/tasks", json={"title": "mine"}, headers=auth_headers(member_jwt))
    assert r.status_code == 200, r.text
    task_id = r.json()["id"]

    r = client.patch(f"/orgs/{org_id}/tasks/{task_id}", json={"status": "doing"}, headers=auth_headers(member_jwt))
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "doing"

def test_member_can_update_a_task_assigned_to_them(client, owner, member_actor):
    owner_jwt, _ = owner
    member_email, member_jwt = member_actor
    org_id = create_org(client, owner_jwt)
    project_id = client.post(f"/orgs/{org_id}/projects", json={"name": "proj"}, headers=auth_headers(owner_jwt)).json()["id"]

    r = client.post(f"/orgs/{org_id}/invites", json={"email": member_email, "role": "member"}, headers=auth_headers(owner_jwt))
    member_user_id = r.json()["user_id"]

    r = client.post(
        f"/orgs/{org_id}/projects/{project_id}/tasks",
        json={"title": "assigned-to-member", "assigned_to": member_user_id},
        headers=auth_headers(owner_jwt),
    )
    assert r.status_code == 200, r.text
    task_id = r.json()["id"]

    r = client.patch(f"/orgs/{org_id}/tasks/{task_id}", json={"status": "done"}, headers=auth_headers(member_jwt))
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "done"

def test_member_cannot_update_a_task_they_do_not_own_or_are_not_assigned(client, owner, member_actor):
    owner_jwt, _ = owner
    member_email, member_jwt = member_actor
    org_id = create_org(client, owner_jwt)
    project_id = client.post(f"/orgs/{org_id}/projects", json={"name": "proj"}, headers=auth_headers(owner_jwt)).json()["id"]

    r = client.post(f"/orgs/{org_id}/invites", json={"email": member_email, "role": "member"}, headers=auth_headers(owner_jwt))
    assert r.status_code == 200, r.text

    r = client.post(f"/orgs/{org_id}/projects/{project_id}/tasks", json={"title": "owners-task"}, headers=auth_headers(owner_jwt))
    task_id = r.json()["id"]

    r = client.patch(f"/orgs/{org_id}/tasks/{task_id}", json={"status": "done"}, headers=auth_headers(member_jwt))
    assert r.status_code == 403, r.text
    assert r.json() == {"detail": "forbidden"}

# free plan limits

def test_free_plan_project_limit_blocks_the_fourth_create(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)

    for i in range(3):
        r = client.post(f"/orgs/{org_id}/projects", json={"name": f"p{i}"}, headers=auth_headers(jwt))
        assert r.status_code == 200, r.text

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "p3"}, headers=auth_headers(jwt))
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "free_plan_project_limit"}

def test_free_plan_task_limit_blocks_the_101st_create(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)
    project_id = client.post(f"/orgs/{org_id}/projects", json={"name": "proj"}, headers=auth_headers(jwt)).json()["id"]

    for i in range(100):
        r = client.post(f"/orgs/{org_id}/projects/{project_id}/tasks", json={"title": f"t{i}"}, headers=auth_headers(jwt))
        assert r.status_code == 200, r.text

    r = client.post(f"/orgs/{org_id}/projects/{project_id}/tasks", json={"title": "t100"}, headers=auth_headers(jwt))
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "free_plan_task_limit"}

def test_free_plan_member_limit_blocks_the_fifth_membership(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)

    # the owner is membership #1, so 3 more invites reach the cap of 4
    for _ in range(3):
        r = client.post(
            f"/orgs/{org_id}/invites",
            json={"email": f"contract+{uuid.uuid4().hex}@example.com", "role": "member"},
            headers=auth_headers(jwt),
        )
        assert r.status_code == 200, r.text

    r = client.post(
        f"/orgs/{org_id}/invites",
        json={"email": f"contract+{uuid.uuid4().hex}@example.com", "role": "member"},
        headers=auth_headers(jwt),
    )
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "free_plan_member_limit"}

# billing writable gate

@pytest.fixture()
def blocked_org(client, owner):
    owner_jwt, _ = owner
    org_id = create_org(client, owner_jwt)

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "pre-existing"}, headers=auth_headers(owner_jwt))
    assert r.status_code == 200, r.text
    project_id = r.json()["id"]

    r = client.post(
        f"/orgs/{org_id}/projects/{project_id}/tasks",
        json={"title": "pre-existing"},
        headers=auth_headers(owner_jwt),
    )
    assert r.status_code == 200, r.text
    task_id = r.json()["id"]

    event = {
        "id": f"evt_{uuid.uuid4()}",
        "type": "customer.subscription.updated",
        "data": {
            "object": {
                "id": f"sub_{uuid.uuid4()}",
                "customer": f"cus_{uuid.uuid4()}",
                "status": "past_due",
                "metadata": {"org_id": org_id},
            }
        },
    }
    r = post_webhook(client, event)
    assert r.status_code == 200, r.text

    return owner_jwt, org_id, project_id, task_id

def test_billing_block_stops_invite_writes(client, blocked_org):
    jwt, org_id, _, _ = blocked_org
    r = client.post(
        f"/orgs/{org_id}/invites",
        json={"email": f"contract+{uuid.uuid4().hex}@example.com", "role": "member"},
        headers=auth_headers(jwt),
    )
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "billing_required"}

def test_billing_block_stops_project_writes(client, blocked_org):
    jwt, org_id, project_id, _ = blocked_org

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "blocked"}, headers=auth_headers(jwt))
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "billing_required"}

    r = client.patch(f"/orgs/{org_id}/projects/{project_id}", json={"name": "blocked"}, headers=auth_headers(jwt))
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "billing_required"}

    r = client.delete(f"/orgs/{org_id}/projects/{project_id}", headers=auth_headers(jwt))
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "billing_required"}

def test_billing_block_stops_task_writes(client, blocked_org):
    jwt, org_id, project_id, task_id = blocked_org

    r = client.post(f"/orgs/{org_id}/projects/{project_id}/tasks", json={"title": "blocked"}, headers=auth_headers(jwt))
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "billing_required"}

    r = client.patch(f"/orgs/{org_id}/tasks/{task_id}", json={"title": "blocked"}, headers=auth_headers(jwt))
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "billing_required"}

    r = client.delete(f"/orgs/{org_id}/tasks/{task_id}", headers=auth_headers(jwt))
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "billing_required"}

def test_billing_block_does_not_stop_reads(client, blocked_org):
    jwt, org_id, project_id, _ = blocked_org

    r = client.get(f"/orgs/{org_id}/projects", headers=auth_headers(jwt))
    assert r.status_code == 200, r.text

    r = client.get(f"/orgs/{org_id}/projects/{project_id}/tasks", headers=auth_headers(jwt))
    assert r.status_code == 200, r.text

def test_stripe_subscription_past_due_then_invoice_paid_restores_write_access(client, owner):
    jwt, _ = owner
    org_id = create_org(client, jwt)
    customer_id = f"cus_{uuid.uuid4()}"
    subscription_id = f"sub_{uuid.uuid4()}"

    past_due_event = {
        "id": f"evt_{uuid.uuid4()}",
        "type": "customer.subscription.updated",
        "data": {
            "object": {
                "id": subscription_id,
                "customer": customer_id,
                "status": "past_due",
                "metadata": {"org_id": org_id},
            }
        },
    }
    r = post_webhook(client, past_due_event)
    assert r.status_code == 200, r.text

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "blocked"}, headers=auth_headers(jwt))
    assert r.status_code == 402, r.text
    assert r.json() == {"detail": "billing_required"}

    invoice_paid_event = {
        "id": f"evt_{uuid.uuid4()}",
        "type": "invoice.paid",
        "data": {
            "object": {
                "id": f"in_{uuid.uuid4()}",
                "customer": customer_id,
                "subscription": subscription_id,
                "metadata": {"org_id": org_id},
            }
        },
    }
    r = post_webhook(client, invoice_paid_event)
    assert r.status_code == 200, r.text

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "restored"}, headers=auth_headers(jwt))
    assert r.status_code == 200, r.text

# stripe webhook mechanics

def test_stripe_webhook_accepts_a_known_event_and_returns_ok(client, owner):
    _, org_id = owner
    event_id = f"evt_{uuid.uuid4()}"
    payload = {
        "id": event_id,
        "type": "customer.subscription.updated",
        "data": {
            "object": {
                "id": f"sub_{uuid.uuid4()}",
                "customer": f"cus_{uuid.uuid4()}",
                "status": "active",
                "metadata": {"org_id": org_id},
            }
        },
    }
    r = post_webhook(client, payload)
    assert r.status_code == 200, r.text
    assert r.json() == {"status": "ok", "event_id": event_id}

def test_replayed_stripe_event_is_ignored_once_processed(client, owner):
    _, org_id = owner
    event_id = f"evt_{uuid.uuid4()}"
    payload = {
        "id": event_id,
        "type": "customer.subscription.updated",
        "data": {
            "object": {
                "id": f"sub_{uuid.uuid4()}",
                "customer": f"cus_{uuid.uuid4()}",
                "status": "active",
                "metadata": {"org_id": org_id},
            }
        },
    }
    r1 = post_webhook(client, payload)
    assert r1.status_code == 200, r1.text

    r2 = post_webhook(client, payload)
    assert r2.status_code == 200, r2.text
    assert r2.json() == {"status": "ignored", "reason": "duplicate", "event_id": event_id, "duplicate": True}

def test_replayed_unhandled_stripe_event_is_also_reported_as_duplicate(client):
    event_id = f"evt_{uuid.uuid4()}"
    payload = {"id": event_id, "type": "some.unhandled.type", "data": {"object": {}}}

    r1 = post_webhook(client, payload)
    assert r1.status_code == 200, r1.text
    assert r1.json() == {"status": "ignored", "reason": "unhandled_type", "event_id": event_id}

    r2 = post_webhook(client, payload)
    assert r2.status_code == 200, r2.text
    assert r2.json() == {"status": "ignored", "reason": "duplicate", "event_id": event_id, "duplicate": True}

def test_stripe_webhook_reports_missing_customer(client):
    event_id = f"evt_{uuid.uuid4()}"
    payload = {
        "id": event_id,
        "type": "customer.subscription.updated",
        "data": {"object": {"id": f"sub_{uuid.uuid4()}", "status": "active"}},
    }
    r = post_webhook(client, payload)
    assert r.status_code == 200, r.text
    assert r.json() == {"status": "ignored", "reason": "missing_customer", "event_id": event_id}

def test_stripe_webhook_reports_unknown_customer(client):
    event_id = f"evt_{uuid.uuid4()}"
    payload = {
        "id": event_id,
        "type": "customer.subscription.updated",
        "data": {"object": {"id": f"sub_{uuid.uuid4()}", "customer": f"cus_{uuid.uuid4()}", "status": "active"}},
    }
    r = post_webhook(client, payload)
    assert r.status_code == 200, r.text
    assert r.json() == {"status": "ignored", "reason": "unknown_customer", "event_id": event_id}

def test_stripe_webhook_rejects_malformed_json(client):
    r = post_webhook(client, None, raw=b"not json at all")
    assert r.status_code == 400, r.text
    assert r.json() == {"detail": "invalid json"}

def test_stripe_webhook_rejects_a_payload_missing_id_or_type(client):
    r = post_webhook(client, {"type": "customer.subscription.updated", "data": {"object": {}}})
    assert r.status_code == 400, r.text
    assert r.json() == {"detail": "invalid_stripe_event"}

    r = post_webhook(client, {"id": f"evt_{uuid.uuid4()}", "data": {"object": {}}})
    assert r.status_code == 400, r.text
    assert r.json() == {"detail": "invalid_stripe_event"}

def test_stripe_webhook_marks_failed_event_then_succeeds_on_retry(client, owner):
    _, org_id = owner
    event_id = f"evt_{uuid.uuid4()}"

    bad_payload = {"id": event_id, "type": "customer.subscription.updated", "data": {"object": "not-a-dict"}}
    r1 = post_webhook(client, bad_payload)
    assert r1.status_code == 500, r1.text
    assert r1.json() == {"detail": "webhook_processing_failed"}

    # retry with the same event id and a well-shaped payload; failed rows are retried, not deduped
    good_payload = {
        "id": event_id,
        "type": "customer.subscription.updated",
        "data": {
            "object": {
                "id": f"sub_{uuid.uuid4()}",
                "customer": f"cus_{uuid.uuid4()}",
                "status": "active",
                "metadata": {"org_id": org_id},
            }
        },
    }
    r2 = post_webhook(client, good_payload)
    assert r2.status_code == 200, r2.text
    assert r2.json() == {"status": "ok", "event_id": event_id}

@pytest.mark.skipif(not STRIPE_SECRET, reason="STRIPE_WEBHOOK_SECRET not set")
def test_stripe_webhook_rejects_a_missing_signature(client):
    payload = {"id": f"evt_{uuid.uuid4()}", "type": "some.type", "data": {"object": {}}}
    r = post_webhook(client, payload, sign=False)
    assert r.status_code == 400, r.text
    assert r.json() == {"detail": "missing stripe-signature"}

@pytest.mark.skipif(not STRIPE_SECRET, reason="STRIPE_WEBHOOK_SECRET not set")
def test_stripe_webhook_rejects_an_invalid_signature(client):
    payload = {"id": f"evt_{uuid.uuid4()}", "type": "some.type", "data": {"object": {}}}
    raw = json.dumps(payload).encode("utf-8")
    bad_header = f"t={int(time.time())},v1=" + "0" * 64
    r = post_webhook(client, payload, raw=raw, sign=False, extra_headers={"stripe-signature": bad_header})
    assert r.status_code == 400, r.text
    assert r.json() == {"detail": "invalid stripe-signature"}

def login(client, email: str) -> str:
    r = client.post("/auth/request-link", json={"email": email})
    assert r.status_code == 200
    token = r.json()["token"]
    r = client.post("/auth/redeem", json={"token": token})
    assert r.status_code == 200
    return r.json()["access_token"]

def auth_headers(jwt: str) -> dict[str, str]:
    return {"authorization": f"bearer {jwt}"}

def test_tenant_isolation_projects(client):
    a = login(client, "a@example.com")
    b = login(client, "b@example.com")

    r = client.post("/orgs", json={"name": "org-a"}, headers=auth_headers(a))
    assert r.status_code == 200
    org_a = r.json()["id"]

    r = client.post("/orgs", json={"name": "org-b"}, headers=auth_headers(b))
    assert r.status_code == 200
    org_b = r.json()["id"]

    r = client.post(f"/orgs/{org_a}/projects", json={"name": "p1"}, headers=auth_headers(a))
    assert r.status_code == 200
    project_a = r.json()["id"]

    # b is not a member of org_a, should be blocked
    r = client.get(f"/orgs/{org_a}/projects", headers=auth_headers(b))
    assert r.status_code == 403

    # also block direct project update attempt (even if you guessed id)
    r = client.patch(
        f"/orgs/{org_a}/projects/{project_a}",
        json={"name": "hacked"},
        headers=auth_headers(b),
    )
    assert r.status_code in (403, 404)

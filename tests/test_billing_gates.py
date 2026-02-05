import uuid

from sqlalchemy.orm import Session

from app.models.org import Org

def login(client, email: str) -> str:
    r = client.post("/auth/request-link", json={"email": email})
    assert r.status_code == 200
    token = r.json()["token"]
    r = client.post("/auth/redeem", json={"token": token})
    assert r.status_code == 200
    return r.json()["access_token"]

def auth(jwt: str) -> dict[str, str]:
    return {"authorization": f"bearer {jwt}"}

def test_blocks_writes_when_past_due(client, db_session: Session):
    jwt = login(client, "billblock@example.com")
    r = client.post("/orgs", json={"name": "bill-org"}, headers=auth(jwt))
    assert r.status_code == 200
    org_id = r.json()["id"]

    org = db_session.get(Org, org_id)
    assert org
    org.subscription_status = "past_due"
    db_session.commit()

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "p1"}, headers=auth(jwt))
    assert r.status_code == 402

def test_free_project_limit(client, db_session: Session):
    jwt = login(client, "freelimit@example.com")
    r = client.post("/orgs", json={"name": "free-org"}, headers=auth(jwt))
    assert r.status_code == 200
    org_id = r.json()["id"]

    # default plan should be free
    for i in range(3):
        r = client.post(f"/orgs/{org_id}/projects", json={"name": f"p{i}"}, headers=auth(jwt))
        assert r.status_code == 200

    r = client.post(f"/orgs/{org_id}/projects", json={"name": "p3"}, headers=auth(jwt))
    assert r.status_code == 402

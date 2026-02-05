from sqlalchemy import select, func
from sqlalchemy.orm import Session

from app.models.org import Org
from app.models.webhook_event import WebhookEvent

def login(client, email: str) -> str:
    r = client.post("/auth/request-link", json={"email": email})
    assert r.status_code == 200
    token = r.json().get("token")
    assert token

    r = client.post("/auth/redeem", json={"token": token})
    assert r.status_code == 200
    return r.json()["access_token"]

def auth(jwt: str) -> dict[str, str]:
    return {"authorization": f"bearer {jwt}"}

def test_stripe_webhook_replay_is_idempotent(client, db_session: Session):
    owner_jwt = login(client, "owner-webhooks@example.com")

    r = client.post("/orgs", json={"name": "stripe-org"}, headers=auth(owner_jwt))
    assert r.status_code == 200
    org_id = r.json()["id"]

    org = db_session.get(Org, org_id)
    assert org is not None
    org.stripe_customer_id = "cus_test_123"
    db_session.commit()

    payload = {
        "id": "evt_test_1",
        "type": "customer.subscription.updated",
        "data": {
            "object": {
                "id": "sub_test_123",
                "customer": "cus_test_123",
                "status": "active",
                "current_period_end": 2000000000,
            }
        },
    }

    r1 = client.post("/webhooks/stripe", json=payload)
    assert r1.status_code == 200

    r2 = client.post("/webhooks/stripe", json=payload)
    assert r2.status_code == 200
    assert r2.json().get("duplicate") is True

    org2 = db_session.get(Org, org_id)
    assert org2 is not None
    assert org2.plan == "pro"
    assert org2.stripe_subscription_id == "sub_test_123"
    assert org2.subscription_status == "active"

    count = db_session.scalar(
        select(func.count()).select_from(WebhookEvent).where(WebhookEvent.provider == "stripe", WebhookEvent.event_id == "evt_test_1")
    )
    assert count == 1

def test_stripe_webhook_unknown_customer_is_noop(client, db_session: Session):
    owner_jwt = login(client, "owner-webhooks-2@example.com")

    r = client.post("/orgs", json={"name": "org-1"}, headers=auth(owner_jwt))
    assert r.status_code == 200
    org_1_id = r.json()["id"]

    r = client.post("/orgs", json={"name": "org-2"}, headers=auth(owner_jwt))
    assert r.status_code == 200
    org_2_id = r.json()["id"]

    org1 = db_session.get(Org, org_1_id)
    org2 = db_session.get(Org, org_2_id)
    assert org1 and org2

    org1.stripe_customer_id = "cus_real_1"
    org2.stripe_customer_id = "cus_real_2"
    db_session.commit()

    payload = {
        "id": "evt_unknown_customer",
        "type": "customer.subscription.updated",
        "data": {
            "object": {
                "id": "sub_whatever",
                "customer": "cus_does_not_exist",
                "status": "active",
                "current_period_end": 2000000000,
            }
        },
    }

    r = client.post("/webhooks/stripe", json=payload)
    assert r.status_code == 200

    org1b = db_session.get(Org, org_1_id)
    org2b = db_session.get(Org, org_2_id)
    assert org1b and org2b

    # still free, since we didn't match any org for that customer id
    assert org1b.plan == "free"
    assert org2b.plan == "free"

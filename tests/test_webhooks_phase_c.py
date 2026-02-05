import hmac
import hashlib
import json
import time

from sqlalchemy import select

from app.config import settings
from app.models.org import Org
from app.models.webhook_event import WebhookEvent

def login(client, email: str) -> str:
    r = client.post("/auth/request-link", json={"email": email})
    assert r.status_code == 200
    token = r.json()["token"]
    r = client.post("/auth/redeem", json={"token": token})
    assert r.status_code == 200
    return r.json()["access_token"]

def auth(jwt: str) -> dict[str, str]:
    return {"authorization": f"bearer {jwt}"}

def stripe_sig(secret: str, raw: bytes, ts: int | None = None) -> str:
    ts = ts or int(time.time())
    signed = f"{ts}.".encode("utf-8") + raw
    v1 = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    return f"t={ts},v1={v1}"

def test_stripe_signature_rejects_missing_or_invalid(client):
    settings.STRIPE_WEBHOOK_SECRET = "whsec_test"

    payload = {"id": "evt_sig_1", "type": "customer.subscription.updated", "data": {"object": {}}}
    raw = json.dumps(payload).encode("utf-8")

    r = client.post("/webhooks/stripe", content=raw, headers={"content-type": "application/json"})
    assert r.status_code == 400

    r = client.post(
        "/webhooks/stripe",
        content=raw,
        headers={"content-type": "application/json", "stripe-signature": "t=1,v1=bad"},
    )
    assert r.status_code == 400

def test_stripe_signature_accepts_valid(client):
    settings.STRIPE_WEBHOOK_SECRET = "whsec_test"

    payload = {"id": "evt_sig_2", "type": "customer.subscription.updated", "data": {"object": {}}}
    raw = json.dumps(payload).encode("utf-8")

    r = client.post(
        "/webhooks/stripe",
        content=raw,
        headers={"content-type": "application/json", "stripe-signature": stripe_sig("whsec_test", raw)},
    )
    # it will be ignored (unknown customer) but signature should pass
    assert r.status_code == 200

def test_failed_event_records_failed_and_retry_can_succeed(client, db_session):
    settings.STRIPE_WEBHOOK_SECRET = None  # keep simple for this test

    owner_jwt = login(client, "phasec-owner@example.com")
    r = client.post("/orgs", json={"name": "phasec-org"}, headers=auth(owner_jwt))
    assert r.status_code == 200
    org_id = r.json()["id"]

    org = db_session.get(Org, org_id)
    assert org
    org.stripe_customer_id = "cus_phasec_1"
    db_session.commit()

    # force a processing error: data.object is a string so obj.get(...) blows up
    bad_payload = {
        "id": "evt_fail_1",
        "type": "customer.subscription.updated",
        "data": {"object": "boom"},
    }
    r1 = client.post("/webhooks/stripe", json=bad_payload)
    assert r1.status_code == 500

    ev = db_session.scalar(
        select(WebhookEvent).where(WebhookEvent.provider == "stripe", WebhookEvent.event_id == "evt_fail_1")
    )
    assert ev
    assert ev.status == "failed"
    assert ev.processed_at is None
    assert ev.error

    # retry with a good payload (same event id)
    good_payload = {
        "id": "evt_fail_1",
        "type": "customer.subscription.updated",
        "data": {
            "object": {
                "id": "sub_phasec_1",
                "customer": "cus_phasec_1",
                "status": "active",
                "current_period_end": 2000000000,
            }
        },
    }
    r2 = client.post("/webhooks/stripe", json=good_payload)
    assert r2.status_code == 200

    ev2 = db_session.scalar(
        select(WebhookEvent).where(WebhookEvent.provider == "stripe", WebhookEvent.event_id == "evt_fail_1")
    )
    assert ev2
    assert ev2.status in {"processed", "ignored"}  # processed if org matched

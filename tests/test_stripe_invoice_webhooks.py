import time
from sqlalchemy import select

from app.models.enums import Plan, SubscriptionStatus
from app.models.org import Org
from app.models.webhook_event import WebhookEvent

def _post_invoice(client, event_id: str, event_type: str, customer: str, subscription: str = "sub_test_123"):
    return client.post(
        "/webhooks/stripe",
        json={
            "id": event_id,
            "type": event_type,
            "data": {"object": {"id": f"in_{int(time.time())}", "customer": customer, "subscription": subscription}},
        },
    )

def test_invoice_paid_sets_active_and_is_idempotent(client, db_session, seeded_org):
    # seeded_org fixture should give you an org + owner membership; adapt if your fixture name differs
    org: Org = seeded_org
    org.stripe_customer_id = "cus_invoice_ok"
    db_session.commit()

    event_id = f"evt_invoice_paid_{int(time.time())}"
    r1 = _post_invoice(client, event_id, "invoice.paid", "cus_invoice_ok")
    assert r1.status_code == 200
    db_session.refresh(org)
    assert org.subscription_status == SubscriptionStatus.active
    assert org.plan == Plan.pro

    r2 = _post_invoice(client, event_id, "invoice.paid", "cus_invoice_ok")
    assert r2.status_code == 200
    assert r2.json().get("duplicate") is True

    row = db_session.scalar(select(WebhookEvent).where(WebhookEvent.event_id == event_id))
    assert row is not None
    assert row.status in {"processed", "ignored"}

def test_invoice_payment_failed_blocks_writes(client, db_session, seeded_org, owner_jwt):
    org: Org = seeded_org
    org.stripe_customer_id = "cus_invoice_fail"
    db_session.commit()

    event_id = f"evt_invoice_fail_{int(time.time())}"
    r = _post_invoice(client, event_id, "invoice.payment_failed", "cus_invoice_fail")
    assert r.status_code == 200

    db_session.refresh(org)
    assert org.subscription_status == SubscriptionStatus.past_due
    assert org.plan == Plan.pro

    # try a write (projects:create is owner/admin only, so use owner_jwt)
    pr = client.post(
        f"/orgs/{org.id}/projects",
        headers={"authorization": f"bearer {owner_jwt}"},
        json={"name": "should_fail_billing"},
    )
    assert pr.status_code == 402
    assert pr.json()["detail"] == "billing_required"

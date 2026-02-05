from __future__ import annotations

import hmac
import hashlib
import json
import time
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import settings
from app.db import get_db
from app.models.enums import Plan, SubscriptionStatus
from app.models.org import Org
from app.models.webhook_event import WebhookEvent
from app.ratelimit import rate_limit

router = APIRouter(prefix="/webhooks", tags=["webhooks"])

_ALLOWED_SUB_STATUS = {
    "none",
    "incomplete",
    "trialing",
    "active",
    "canceled",
    "past_due",
    "unpaid",
}

_STRIPE_TOLERANCE_SECONDS = 300

def _now_utc() -> datetime:
    return datetime.now(timezone.utc)

def _verify_stripe_signature(payload_bytes: bytes, signature: str | None) -> None:
    secret = settings.STRIPE_WEBHOOK_SECRET
    if not secret:
        return

    if not signature:
        raise HTTPException(status_code=400, detail="missing stripe-signature")

    # stripe signature
    parts: dict[str, list[str]] = {}
    for item in signature.split(","):
        if "=" not in item:
            continue
        k, v = item.split("=", 1)
        parts.setdefault(k.strip(), []).append(v.strip())

    ts_list = parts.get("t") or []
    v1_list = parts.get("v1") or []
    if not ts_list or not v1_list:
        raise HTTPException(status_code=400, detail="invalid stripe-signature format")

    try:
        ts = int(ts_list[0])
    except Exception:
        raise HTTPException(status_code=400, detail="invalid stripe-signature timestamp")

    now = int(time.time())
    if abs(now - ts) > _STRIPE_TOLERANCE_SECONDS:
        raise HTTPException(status_code=400, detail="stale stripe-signature")

    signed_payload = f"{ts}.".encode("utf-8") + payload_bytes
    expected = hmac.new(secret.encode("utf-8"), signed_payload, hashlib.sha256).hexdigest()

    if not any(hmac.compare_digest(expected, cand) for cand in v1_list):
        raise HTTPException(status_code=400, detail="invalid stripe-signature")

def _find_org(
    db: Session,
    customer_id: str | None,
    sub_id: str | None,
    metadata: dict | None,
) -> Org | None:
    if customer_id:
        org = db.scalar(select(Org).where(Org.stripe_customer_id == customer_id))
        if org:
            return org
    if sub_id:
        org = db.scalar(select(Org).where(Org.stripe_subscription_id == sub_id))
        if org:
            return org
    if metadata and isinstance(metadata, dict):
        raw_org_id = metadata.get("org_id")
        if raw_org_id:
            try:
                org_id = uuid.UUID(str(raw_org_id))
            except Exception:
                return None
            return db.get(Org, org_id)
    return None

def _map_stripe_sub_status(raw: str | None) -> SubscriptionStatus:
    if raw == "active":
        return SubscriptionStatus.active
    if raw == "trialing":
        return SubscriptionStatus.trialing
    if raw == "past_due":
        return SubscriptionStatus.past_due
    if raw == "unpaid":
        return SubscriptionStatus.unpaid
    if raw == "canceled":
        return SubscriptionStatus.canceled
    if raw == "incomplete":
        return SubscriptionStatus.incomplete
    return SubscriptionStatus.none

def _plan_for_status(st: SubscriptionStatus) -> Plan:
    return Plan.pro if st in {SubscriptionStatus.active, SubscriptionStatus.trialing, SubscriptionStatus.past_due} else Plan.free

@router.post("/stripe")
async def stripe_webhook(
    request: Request,
    db: Session = Depends(get_db),
    stripe_signature: str | None = Header(default=None, alias="stripe-signature"),
    _: None = Depends(
        rate_limit(
            "webhooks:stripe",
            limit_per_window=settings.rate_limit_webhooks_per_min,
            window_seconds=60,
        )
    ),
):
    raw = await request.body()
    _verify_stripe_signature(raw, stripe_signature)

    try:
        payload = json.loads(raw.decode("utf-8"))
    except Exception:
        raise HTTPException(status_code=400, detail="invalid json")

    event_id = payload.get("id")
    event_type = payload.get("type")
    obj = (payload.get("data") or {}).get("object")  # no `or {}` here

    if not event_id or not event_type:
        raise HTTPException(status_code=400, detail="invalid_stripe_event")

    existing = db.scalar(
        select(WebhookEvent).where(
            WebhookEvent.provider == "stripe",
            WebhookEvent.event_id == event_id,
        )
    )
    if existing and existing.status in {"processed", "ignored"}:
        return {
            "status": "ignored",
            "reason": "duplicate",
            "event_id": event_id,
            "duplicate": True,
        }

    if existing is None:
        existing = WebhookEvent(
            provider="stripe",
            event_id=event_id,
            event_type=event_type,
            status="received",
            payload=payload,
        )
        db.add(existing)
        db.commit()
        db.refresh(existing)

    try:
        # validate shape for handlers that expect a dict
        if event_type in {
            "customer.subscription.updated",
            "customer.subscription.deleted",
            "invoice.paid",
            "invoice.payment_failed",
        }:
            if not isinstance(obj, dict):
                raise TypeError("stripe event data.object must be an object")

        # subscription events
        if event_type in {
            "customer.subscription.updated",
            "customer.subscription.deleted",
        }:
            customer = obj.get("customer")
            if not customer:
                existing.status = "ignored"
                existing.processed_at = _now_utc()
                db.commit()
                return {
                    "status": "ignored",
                    "reason": "missing_customer",
                    "event_id": event_id,
                }

            org = _find_org(db, customer, obj.get("id"), obj.get("metadata"))
            if not org:
                existing.status = "ignored"
                existing.processed_at = _now_utc()
                db.commit()
                return {
                    "status": "ignored",
                    "reason": "unknown_customer",
                    "event_id": event_id,
                }

            if event_type == "customer.subscription.deleted":
                org.subscription_status = SubscriptionStatus.canceled
                org.plan = Plan.free
            else:
                sub_status = _map_stripe_sub_status(obj.get("status"))
                org.subscription_status = sub_status
                org.plan = _plan_for_status(sub_status)

            org.stripe_subscription_id = obj.get("id") or org.stripe_subscription_id

            existing.status = "processed"
            existing.processed_at = _now_utc()
            db.commit()
            return {"status": "ok", "event_id": event_id}

        # invoice events
        if event_type in {"invoice.paid", "invoice.payment_failed"}:
            customer = obj.get("customer")
            if not customer:
                existing.status = "ignored"
                existing.processed_at = _now_utc()
                db.commit()
                return {
                    "status": "ignored",
                    "reason": "missing_customer",
                    "event_id": event_id,
                }

            org = _find_org(db, customer, obj.get("subscription"), obj.get("metadata"))
            if not org:
                existing.status = "ignored"
                existing.processed_at = _now_utc()
                db.commit()
                return {
                    "status": "ignored",
                    "reason": "unknown_customer",
                    "event_id": event_id,
                }

            # if present, associate subscription id
            org.stripe_subscription_id = (
                obj.get("subscription") or org.stripe_subscription_id
            )

            if event_type == "invoice.paid":
                org.subscription_status = SubscriptionStatus.active
                org.plan = Plan.pro
            else:
                org.subscription_status = SubscriptionStatus.past_due
                org.plan = Plan.pro

            existing.status = "processed"
            existing.processed_at = _now_utc()
            db.commit()
            return {"status": "ok", "event_id": event_id}

        # everything else explicitly ignored
        existing.status = "ignored"
        existing.processed_at = _now_utc()
        db.commit()
        return {
            "status": "ignored",
            "reason": "unhandled_type",
            "event_id": event_id,
        }

    except Exception as e:
        # mark failed but allow retry by re-sending same stripe event id
        existing.status = "failed"
        existing.error = f"{type(e).__name__}: {e}"[:1000]
        existing.processed_at = None
        db.commit()
        raise HTTPException(status_code=500, detail="webhook_processing_failed")

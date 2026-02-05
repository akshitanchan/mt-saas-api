from __future__ import annotations

import os
import time
from typing import Any

import requests
from rich import print

from app.db import SessionLocal
from app.models.org import Org

BASE = os.getenv("API_BASE_URL", "http://127.0.0.1:8000")

def post(path: str, *, jwt: str | None = None, json: dict | None = None) -> requests.Response:
    headers = {"content-type": "application/json"}
    if jwt:
        headers["authorization"] = f"bearer {jwt}"
    return requests.post(f"{BASE}{path}", headers=headers, json=json, timeout=10)

def get(path: str, *, jwt: str | None = None) -> requests.Response:
    headers = {}
    if jwt:
        headers["authorization"] = f"bearer {jwt}"
    return requests.get(f"{BASE}{path}", headers=headers, timeout=10)

def login(email: str) -> str:
    r = post("/auth/request-link", json={"email": email})
    r.raise_for_status()
    token = r.json()["token"]

    r2 = post("/auth/redeem", json={"token": token})
    r2.raise_for_status()
    return r2.json()["access_token"]

def attach_customer_id(org_id: str, customer_id: str) -> None:
    with SessionLocal() as db:
        org = db.get(Org, org_id)
        if not org:
            raise RuntimeError("org not found")
        org.stripe_customer_id = customer_id
        db.commit()

def send_subscription_updated(event_id: str, customer_id: str) -> None:
    post(
        "/webhooks/stripe",
        json={
            "id": event_id,
            "type": "customer.subscription.updated",
            "data": {
                "object": {
                    "id": "sub_demo_123",
                    "customer": customer_id,
                    "status": "active",
                    "current_period_end": 2000000000,
                }
            },
        },
    ).raise_for_status()

def send_invoice_paid(event_id: str, customer_id: str) -> None:
    post(
        "/webhooks/stripe",
        json={
            "id": event_id,
            "type": "invoice.paid",
            "data": {"object": {"id": "in_demo_123", "customer": customer_id, "subscription": "sub_demo_123"}},
        },
    ).raise_for_status()

def wait_ready(timeout_s: float = 30.0) -> None:
    deadline = time.time() + timeout_s
    last_err: Exception | None = None

    while time.time() < deadline:
        try:
            r = get("/ready")
            if r.status_code == 200:
                return
        except Exception as e:
            last_err = e
        time.sleep(0.5)

    if last_err:
        raise RuntimeError(f"api not ready after {timeout_s}s (last error: {last_err})")
    raise RuntimeError(f"api not ready after {timeout_s}s")

def main() -> None:
    print("[bold]demo: auth -> create org -> create project -> create task -> list tasks[/bold]")

    wait_ready()
    print("[green]ready ok[/green]")

    owner_email = "owner@example.com"
    member_email = "member@example.com"

    owner_jwt = login(owner_email)
    print("owner authed")

    # create org
    r = post("/orgs", jwt=owner_jwt, json={"name": f"demo org {int(time.time())}"})
    r.raise_for_status()
    org_id = r.json()["id"]
    print("created org:", org_id)

    # invite user (member role)
    r = post(f"/orgs/{org_id}/invites", jwt=owner_jwt, json={"email": member_email, "role": "member"})
    r.raise_for_status()
    print("invited:", member_email)

    member_jwt = login(member_email)
    print("member authed")

    # create project as owner (rbac)
    r = post(f"/orgs/{org_id}/projects", jwt=owner_jwt, json={"name": "demo project"})
    r.raise_for_status()
    project_id = r.json()["id"]
    print("created project:", project_id)

    # upgrade plan via stripe hooks
    customer_id = f"cus_demo_{int(time.time())}"
    attach_customer_id(org_id, customer_id)
    send_subscription_updated(f"evt_demo_sub_{int(time.time())}", customer_id)
    send_invoice_paid(f"evt_demo_invoice_{int(time.time())}", customer_id)
    print("upgraded plan via webhook")

    # create task as member (rbac ok)
    r = post(
        f"/orgs/{org_id}/projects/{project_id}/tasks",
        jwt=member_jwt,
        json={"title": "demo task after upgrade", "description": "created by invited member"},
    )
    r.raise_for_status()
    task_id = r.json()["id"]
    print("created task:", task_id)

    r = get(f"/orgs/{org_id}/projects/{project_id}/tasks", jwt=member_jwt)
    r.raise_for_status()
    print("listed tasks:", len(r.json()))
    print("[bold green]demo complete[/bold green]")

if __name__ == "__main__":
    main()

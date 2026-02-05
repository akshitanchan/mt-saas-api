# Multi-tenant SaaS API

A multi-tenant SaaS backend API for a simple product: teams manage projects and tasks.

Built with FastAPI, Postgres, and Redis, with local dev orchestration, migrations and seed data, a scripted demo flow, tests, and k6 load runs (including webhook success, replay, and retry metrics).

---

## What’s Included

### Product and Tenancy

* Multi-tenant orgs (teams) with strict org-scoped data isolation.
* Resource model: orgs → projects → tasks.
* Membership model: users join orgs via memberships.

### Auth

* Email magic link auth (dev-friendly: returns the token in local/dev) that redeems into a JWT.
* OAuth is not included (magic link only).

### RBAC

Roles per org:

* `owner`
* `admin`
* `member`

RBAC is enforced on every org-scoped route via dependencies (membership and role checks). Invite flows prevent role escalation.

### Billing

* Plans: `free` vs `pro`.
* Subscription status is stored in the database (for example: `trialing`, `active`, `past_due`, `canceled`, `unpaid`).
* Billing gates are write-only. Reads remain available even if an org is past due.

Free plan hard caps (enforced on create operations only):

* Max projects per org: 3
* Max tasks per org: 100
* Max members per org: 4

### Webhooks

* Stripe webhook endpoint supporting:

	* `invoice.paid`
	* `customer.subscription.updated`
	* Plus: `invoice.payment_failed`, `customer.subscription.deleted`
* Idempotency is handled via a Postgres `webhook_events` ledger (unique event id per provider).
* Retry handling is supported: failed events are recorded and can be replayed safely without double-applying.

### Rate Limiting

* Redis-backed fixed-window rate limiting (fails open if Redis is unavailable).
* Applied to auth endpoints and the webhook endpoint.

### Tooling

* Docker Compose stack (API + Postgres + Redis).
* Alembic migrations and a seed script.
* `make demo` scripted end-to-end flow.
* Pytest unit and integration tests.
* k6 load testing: p95 latency, throughput at multiple concurrency levels, and webhook success/duplicate/retry metrics.

---

## Prereqs

* Docker and Docker Compose v2
* make
* (Optional) bash, curl, jq for `scripts/smoke.sh`

---

## Quickstart

Bring up the stack and wait until the API is ready:

```bash
make up
```

Full reset (clean slate), then run migrations and seed:

```bash
make reset
```

Default base URL:

* `http://localhost:8000`

---

## Smoke, Demo, Seed

Run a full demo flow (auth → org → invite user → upgrade plan via webhook → create task → list tasks):

```bash
make demo
```

Seed a known org, project, task, and users:

```bash
make seed
```

Optional host-level smoke script (also validates migrations and ready checks):

```bash
PROJECT_NAME=mt-saas-api BASE_URL=http://localhost:8000 ./scripts/smoke.sh
```

---

## Tests

Runs a separate Compose stack for tests:

```bash
make test
```

Coverage includes:

* Unit: RBAC matrix (who can do what).
* Unit: Billing gate checks (writes blocked when subscription is in a bad state).
* Integration: Webhook replay (same event twice does not double-apply).
* End-to-end: Create org → invite user → upgrade plan → create task.

---

## Load (k6)

Run the k6 smoke load at multiple VU levels:

```bash
make k6
```

k6 writes comparable JSON summaries to:

* `./k6-results/*.json`

### What We Report From k6

* p95 latency for key endpoints (example set):

	* Auth request-link
	* Create task
	* List tasks
* Throughput (req/s) at 1x, 5x, and 10x concurrency.
* Webhook metrics:

	* Webhook success rate
	* Duplicate replay rate (same event id posted twice is ignored)
	* Retry success rate (a failed event can be replayed successfully without double-apply)

### Latest k6 Numbers

See `scripts/report_metrics.md` for the most recent recorded run.

---

## Health vs Ready

* `GET /health` means the process is up.
* `GET /ready` means dependencies are healthy (db and Redis).

In Docker Compose, the API service healthcheck hits `/health`. If you want Compose “healthy” to mean “ready for dependents,” switch the healthcheck to `/ready`.

---

## Environment Variables

Common variables (see `.env.example` if present, otherwise Compose defaults apply):

* `DATABASE_URL` (Postgres connection string)
* `REDIS_URL`
* `JWT_SECRET`
* `MAGIC_LINK_TTL_SECONDS`
* `RATE_LIMIT_AUTH_PER_MIN`
* `RATE_LIMIT_WEBHOOKS_PER_MIN`

Webhooks:

* `STRIPE_WEBHOOK_SECRET`

	* If set: webhook requests must include a valid `stripe-signature` header (otherwise 400).
	* If unset (local/dev): signature verification is skipped.

---

## Stripe Webhook Notes

* Webhook idempotency is stored in Postgres (`webhook_events`), not Redis.
* Demo behavior:

	* If `STRIPE_WEBHOOK_SECRET` is unset (default local/dev), `make demo` posts unsigned webhook events successfully.
	* If `STRIPE_WEBHOOK_SECRET` is set, the demo must send a valid `stripe-signature`. The demo script is expected to do this when the secret is present.

---

## Behavior Notes

* Free plan limits are enforced only on create:

	* Create project
	* Create task
	* Invite/add member
* Billing gates are enforced only on writes (create, update, delete, invites).
* Reads remain available even when subscription status is `past_due` or `unpaid`.

---

## Repo Map (Where to Look)

* `app/main.py` router wiring
* `app/routes/` auth, orgs, projects, tasks, webhooks, health
* `app/models/` SQLAlchemy models
* `app/schemas/` Pydantic request/response models
* `app/rbac/` role/permission matrix and dependencies
* `app/billing/` plans, limits, billing gates
* `app/ratelimit.py` Redis limiter
* `alembic/` migrations
* `scripts/` seed, demo, smoke, k6, reporting
* `tests/` unit and integration coverage
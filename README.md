# mt-saas-api

A multi-tenant SaaS API (FastAPI + Postgres + Redis) with local dev orchestration, smoke flows, tests, and k6 load runs.

## Prereqs

- Docker + Docker Compose v2
- make
- (optional) bash, curl, jq for `scripts/smoke.sh`

## Quickstart

Bring up the stack and wait until the API is actually ready:

```bash
make up
```

Full reset (clean slate), then run migrations + seed:

```bash
make reset
```

## Smoke / Demo / Seed

Run a simple demo flow (auth -> org -> project -> task -> list):

```bash
make demo
```

Seed a known org/project/task + users:

```bash
make seed
```

Optional host-level smoke script (also validates migrations + ready checks):

```bash
PROJECT_NAME=mt-saas-api BASE_URL=http://localhost:8000 ./scripts/smoke.sh
```

## Tests

Runs a separate compose stack for tests:

```bash
make test
```

## Load (k6)

Run the k6 smoke load at multiple VU levels:

```bash
make k6
```

k6 writes comparable JSON summaries to:

* `./k6-results/*.json`

### Latest k6 numbers

See `scripts/report_metrics.md` for the most recent recorded run.

## Health vs Ready

* `GET /health` = process is up
* `GET /ready` = dependencies are good (db + redis)

In docker-compose, the API service healthcheck is currently hitting `/health`. If you want compose “healthy” to mean “ready for gated dependents”, switch the healthcheck to `/ready`.

## Stripe webhook env notes

When `STRIPE_WEBHOOK_SECRET` is set, requests with missing/invalid signatures should fail fast (400). When it’s not set (local/dev), signature verification is typically skipped.


# Multi-tenant SaaS API

A multi-tenant SaaS backend for organizations, projects, and tasks. Supports magic-link auth, three roles (owner, admin, member), Stripe webhooks with an idempotent ledger, and Redis rate limiting. Built with FastAPI, Postgres, and Redis. A Java port is under way in java/ sharing the contract suite and benchmark table.

Run `make up` to start locally. Run `make test` for tests, `make contract` for the contract suite, `make k6` for load tests.

To deploy to kind: `kind create cluster --config deploy/kind.yaml && make deploy`. This applies Postgres, Redis, migrations, and the API deployment with HPA. CI runs this on every push to main and pushes the image to ghcr.io/akshitanchan/mt-saas-api.

GET /health confirms the process is up; GET /ready confirms Postgres and Redis are responding. Under 30 virtual users for 120 seconds, the API scaled from 1 to 5 replicas; the surviving pod restarted twice on its liveness probe after the load stopped.

Latest numbers (2026-09-10): p95 latencies of 18.25, 16.47, 52.71 ms and throughput of 10.12, 50.26, 47.79 req/s at 1, 5, 10 virtual users. See bench/results.md for full results. Environment variables are in .env.example.

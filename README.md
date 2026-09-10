# Multi-tenant SaaS API

A multi-tenant SaaS backend for organizations, projects, and tasks. Supports magic-link auth, three roles (owner, admin, member), Stripe webhooks with an idempotent ledger, and Redis rate limiting. Built with FastAPI, Postgres, and Redis. I kept the Java service in java/ so both implementations share one contract suite and one benchmark table.

Run `make up` to start both services locally (Python on 8000, Java on 8001). Run `make test` for tests, `make contract` for the contract suite against Python, or `make contract BASE_URL=http://api-java:8000` to run it against Java. To test the Java service run `cd java && ./mvnw verify` for its Testcontainers suite.

To deploy to kind, `kind create cluster --config deploy/kind.yaml && make deploy`. This builds both images, loads them into the cluster, and applies Postgres, Redis, the migration job, and both API deployments with their HPAs. CI runs the same deploy on every push to main and pushes both images to ghcr.io/akshitanchan/mt-saas-api and mt-saas-api-java.

GET /health confirms the process is up; GET /ready confirms Postgres and Redis are responding. Under 30 vus for 120s the api deployment went from 1 to 4 ready replicas. The 10-second sampling interval saw a peak of 3 replicas, while the deployment's events recorded a peak of 5. The original pod restarted three times on its liveness probe at the 500m cpu limit.

Latest numbers (2026-09-10): I measured Python at p95 17.27, 24.11, and 93.50 ms with 10.24, 49.88, and 87.55 req/s at 1, 5, and 10 virtual users, and Java, after a discarded 20-second warm-up, at p95 17.60, 18.68, and 17.39 ms with 10.25, 49.53, and 101.49 req/s. All runs had zero failed requests. See bench/results.md for the full table and cluster spec. Environment variables are in .env.example.

# bench results

these numbers came from running `make bench SERVICE=python` against the kind cluster
(deploy/api.yaml, deploy/hpa.yaml already applied), which drives grafana/k6:latest over
the kind docker network at BASE_URL=http://mt-saas-api-control-plane:30080 for vus 1, 5,
10 at 20s each, then prints scripts/report_k6.py --dir bench/k6/python --latest.
the cluster raises RATE_LIMIT_AUTH_REQUEST_LINK_PER_MIN and RATE_LIMIT_AUTH_REDEEM_PER_MIN
to 600 and RATE_LIMIT_WEBHOOKS_PER_MIN to 6000 (deploy/configmap.yaml) because every
request behind the NodePort arrives from the node IP and would otherwise be rate
limited as a single client.

| service | vus | p95 ms | req/s | fail rate (%) | ready replicas before | ready replicas after | date | cluster spec |
|---|---:|---:|---:|---:|---:|---:|---|---|
| python | 1 | 18.25 | 10.12 | 0.00 | 1 | 1 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi |
| python | 5 | 16.47 | 50.26 | 0.00 | 1 | 3 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi |
| python | 10 | 52.71 | 47.79 | 0.66 | 3 | 1 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi |

no thresholds failed on the three rows above (checks, http_req_failed, fail_rate,
p95_tasks_create, p95_tasks_list, webhook_success_rate all passed on every row). the
vus=5 row shows the hpa already scaling up mid-run (1 to 3 ready replicas) from the
vus=1 row's tail load and the still-cooling-down state; the vus=10 row started before
the deployment had settled back to 1 (scaleDown stabilization window is 60s), so its
"before" reading is 3, and by the time the 20s run ended the hpa had already scaled
back down to 1 even with 10 vus applied, which is why fail rate is nonzero (0.66%) at
that level, some requests landed while a replica was still spinning up or its
readiness probe had not passed.

under 30 vus for 120s the api deployment went from 1 to 5 ready replicas. during that
run readyReplicas fluctuated (1, then briefly 0 as replicas rolled while scaling, then
up to 5) and the k6 thresholds on that run failed (checks, fail_rate, http_req_failed,
p95_tasks_create, p95_tasks_list, webhook_success_rate all breached, k6 exit 99) because
30 vus sustained for 120s is well past the single 50m-cpu-request pod's capacity and the
deployment was still scaling up to meet it; at the end of the 120s window the deployment
had not yet scaled back down from its peak of 5 (scaleDown stabilization window 60s had
not fully elapsed since the last scale-up event).

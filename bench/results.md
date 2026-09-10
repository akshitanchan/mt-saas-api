# bench results

these numbers come from running `make bench SERVICE=python` against the kind cluster (deploy/api.yaml and deploy/hpa.yaml already applied), driving k6 at vus 1, 5, 10 for 20s each over the kind docker network.
the cluster raises rate limits in deploy/configmap.yaml because all NodePort requests arrive from the node IP, otherwise being rate limited as a single client.

| service | vus | p95 ms | req/s | fail rate (%) | ready replicas before | ready replicas after | date | cluster spec |
|---|---:|---:|---:|---:|---:|---:|---|---|
| python | 1 | 18.25 | 10.12 | 0.00 | 1 | 1 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi |
| python | 5 | 16.47 | 50.26 | 0.00 | 1 | 3 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi |
| python | 10 | 52.71 | 47.79 | 0.66 | 3 | 1 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi |

no k6 thresholds failed on the three bench rows. vus 5 and 10 show HPA scaling mid-run (1 to 3, then start at 3, back to 1 by end), which explains the 0.66% failure rate at 10 vus. under 30 vus for 120s, the api deployment went from 1 to 5 ready replicas and breached every k6 threshold because a single 50m-request pod cannot serve that load while the others start; the surviving pod restarted twice on its liveness probe with context deadline exceeded on /health at the 500m cpu limit. java rows will be added to the same table on the same cluster.

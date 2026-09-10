# bench results

these numbers come from running `make bench SERVICE=python` and `make bench SERVICE=java` against the kind cluster (deploy/api.yaml, deploy/hpa.yaml, deploy/api-java.yaml and deploy/hpa-java.yaml already applied), driving k6 at vus 1, 5, 10 for 20s each over the kind docker network.
the cluster raises rate limits in deploy/configmap.yaml because all NodePort requests arrive from the node IP, otherwise being rate limited as a single client.

| service | vus | p95 ms | req/s | fail rate (%) | ready replicas before | ready replicas after | date | cluster spec |
|---|---:|---:|---:|---:|---:|---:|---|---|
| python | 1 | 17.27 | 10.24 | 0.00 | 1 | 1 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi; api-java requests 100m/384Mi limits 1/768Mi |
| python | 5 | 24.11 | 49.88 | 0.00 | 1 | 1 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi; api-java requests 100m/384Mi limits 1/768Mi |
| python | 10 | 93.50 | 87.55 | 0.00 | 1 | 4 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi; api-java requests 100m/384Mi limits 1/768Mi |
| java | 1 | 17.60 | 10.25 | 0.00 | 1 | 5 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi; api-java requests 100m/384Mi limits 1/768Mi |
| java | 5 | 18.68 | 49.53 | 0.00 | 2 | 1 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi; api-java requests 100m/384Mi limits 1/768Mi |
| java | 10 | 17.39 | 101.49 | 0.00 | 1 | 1 | 2026-09-10 | kind v1.37.0, 1 control-plane + 1 worker, on Docker Desktop (8 cpu, 7.75 GB vm) on Apple M2 8 cores 16 GB; api requests 50m/128Mi limits 500m/512Mi; api-java requests 100m/384Mi limits 1/768Mi |

No k6 thresholds failed on any of the six bench rows, and all six runs exited 0.
Java vus 1 ended at 5 ready replicas, a delayed HPA reaction to the discarded warm-up's cpu spike that surfaced through metrics-server lag rather than the 1-vu load itself.
Java vus 5 then started at 2 ready replicas while that scale-up was still unwinding.
Python vus 10 rose from 1 to 4 ready replicas with zero failed requests this run, unlike the earlier cluster instance where the same level produced a 0.66% failure rate.
The java rows follow a discarded 20s warm-up pass at 5 vus, run beforehand so a cold JIT would not skew the first row.
Under 30 vus for 120s the api deployment went from 1 to 4 ready replicas.
The api deployment spent most of the run at zero ready replicas as the sole 50m-request pod failed readiness and liveness checks, scaling to 4 replicas only near the end.
It still breached every threshold and exited 99.
The original pod restarted three times on liveness failures against /health at the 500m cpu limit.

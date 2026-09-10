SHELL := /bin/bash
.DEFAULT_GOAL := up

PROJECT := mt-saas-api
COMPOSE := docker compose -p $(PROJECT)

API_BASE := http://127.0.0.1:8000
READY_URL := $(API_BASE)/ready

BASE_URL ?= http://api:8000

.PHONY: up down reset wait logs api-shell seed demo k6 test contract

up:
	$(COMPOSE) up --build -d --remove-orphans
	$(MAKE) wait

down:
	$(COMPOSE) down --remove-orphans

reset:
	$(COMPOSE) down -v --remove-orphans
	$(COMPOSE) up --build -d --remove-orphans
	$(MAKE) wait
	$(MAKE) seed

wait:
	@echo "waiting for api to be ready..."
	@for i in $$(seq 1 60); do \
		if curl -fsS "$(READY_URL)" >/dev/null; then \
			echo "api ready"; \
			exit 0; \
		fi; \
		sleep 1; \
	done; \
	echo "api not ready after 60s" >&2; \
	exit 1

logs:
	$(COMPOSE) logs -f --tail=200

api-shell:
	$(COMPOSE) exec api bash

seed:
	$(COMPOSE) exec api python -m scripts.seed

demo:
	$(COMPOSE) up --build -d --remove-orphans
	$(MAKE) wait
	$(COMPOSE) exec api python -m scripts.demo

k6:
	$(COMPOSE) up --build -d --remove-orphans
	$(MAKE) wait
	mkdir -p ./k6-results

	@RUN_ID="$$(date -u +%Y%m%d_%H%M%S)"; \
	GIT_SHA="$$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"; \
	echo "k6 run_id=$$RUN_ID git_sha=$$GIT_SHA"; \
	for V in 1 5 10; do \
		echo "==> running k6 vus=$$V"; \
		VUS="$$V" DURATION="20s" RUN_ID="$$RUN_ID" GIT_SHA="$$GIT_SHA" \
		K6_SUMMARY_PATH="/results/$${RUN_ID}_vus$${V}.json" \
		$(COMPOSE) -f docker-compose.yml -f docker-compose.k6.yml up \
			--abort-on-container-exit --exit-code-from k6 k6; \
		$(COMPOSE) -f docker-compose.yml -f docker-compose.k6.yml down --remove-orphans; \
	done

test:
	docker compose -p mt-saas-api-test -f docker-compose.test.yml down -v --remove-orphans
	docker compose -p mt-saas-api-test -f docker-compose.test.yml up --build --abort-on-container-exit --exit-code-from tests
	docker compose -p mt-saas-api-test -f docker-compose.test.yml down -v --remove-orphans

contract:
	$(COMPOSE) run --rm --no-deps -e RUN_MIGRATIONS=0 -e BASE_URL=$(BASE_URL) api pytest contract -q

KIND_CLUSTER := mt-saas-api
KUBE_NS := mt-saas-api
IMAGE := ghcr.io/akshitanchan/mt-saas-api:dev
IMAGE_JAVA := ghcr.io/akshitanchan/mt-saas-api-java:dev
METRICS_SERVER_VERSION := v0.9.0

.PHONY: deploy smoke undeploy

deploy:
	kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/$(METRICS_SERVER_VERSION)/components.yaml
	@if ! kubectl get deployment metrics-server -n kube-system -o jsonpath='{.spec.template.spec.containers[0].args}' | grep -q kubelet-insecure-tls; then \
		kubectl patch deployment metrics-server -n kube-system --type=json \
			-p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'; \
	fi
	kubectl wait --for=condition=Available deployment/metrics-server -n kube-system --timeout=180s
	docker build -f docker/Dockerfile -t $(IMAGE) .
	docker build -f java/Dockerfile -t $(IMAGE_JAVA) java
	kind load docker-image $(IMAGE) --name $(KIND_CLUSTER)
	kind load docker-image $(IMAGE_JAVA) --name $(KIND_CLUSTER)
	kubectl apply -f deploy/namespace.yaml
	kubectl apply -f deploy/configmap.yaml -f deploy/secret.yaml
	kubectl apply -f deploy/postgres.yaml -f deploy/redis.yaml
	kubectl rollout status statefulset/postgres -n $(KUBE_NS) --timeout=180s
	kubectl rollout status deployment/redis -n $(KUBE_NS) --timeout=180s
	kubectl delete job/alembic-migrate -n $(KUBE_NS) --ignore-not-found
	kubectl apply -f deploy/migrate-job.yaml
	kubectl wait --for=condition=complete job/alembic-migrate -n $(KUBE_NS) --timeout=180s
	kubectl apply -f deploy/api.yaml -f deploy/hpa.yaml
	kubectl rollout status deploy/api -n $(KUBE_NS) --timeout=180s
	kubectl apply -f deploy/api-java.yaml -f deploy/hpa-java.yaml
	kubectl rollout status deploy/api-java -n $(KUBE_NS) --timeout=240s
	$(MAKE) smoke

smoke:
	docker run --rm --network kind -e RUN_MIGRATIONS=0 -e BASE_URL=http://$(KIND_CLUSTER)-control-plane:30080 $(IMAGE) pytest contract -q
	docker run --rm --network kind -e RUN_MIGRATIONS=0 -e BASE_URL=http://$(KIND_CLUSTER)-control-plane:30081 $(IMAGE) pytest contract -q

undeploy:
	kubectl delete $(addprefix -f ,$(filter-out deploy/kind.yaml,$(wildcard deploy/*.yaml))) --ignore-not-found

SERVICE ?= python
BENCH_PORT ?= $(if $(filter java,$(SERVICE)),30081,30080)
BENCH_DEPLOY ?= $(if $(filter java,$(SERVICE)),api-java,api)
BENCH_DURATION ?= 20s
HPA_VUS ?= 30
HPA_DURATION ?= 120s

.PHONY: bench hpa

# runs k6 at vus 1 5 10 against the cluster over the kind docker network, recording
# ready replicas before/after each level so hpa scaling during the run is visible.
bench:
	@mkdir -p bench/k6/$(SERVICE)
	@RUN_ID="$$(date -u +%Y%m%d_%H%M%S)"; \
	GIT_SHA="$$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"; \
	echo "bench run_id=$$RUN_ID git_sha=$$GIT_SHA service=$(SERVICE) port=$(BENCH_PORT)"; \
	for V in 1 5 10; do \
		BEFORE="$$(kubectl get deploy $(BENCH_DEPLOY) -n $(KUBE_NS) -o jsonpath='{.status.readyReplicas}')"; BEFORE="$${BEFORE:-0}"; \
		echo "==> vus=$$V ready_before=$$BEFORE"; \
		docker run --rm --network kind -v $(PWD)/scripts:/scripts:ro -v $(PWD)/bench/k6/$(SERVICE):/results \
			-e BASE_URL=http://$(KIND_CLUSTER)-control-plane:$(BENCH_PORT) -e VUS=$$V -e DURATION=$(BENCH_DURATION) \
			-e RUN_ID=$$RUN_ID -e GIT_SHA=$$GIT_SHA -e K6_SUMMARY_PATH=/results/$${RUN_ID}_vus$${V}.json \
			grafana/k6:latest run /scripts/k6_smoke.js; \
		K6_EXIT=$$?; \
		if [ $$K6_EXIT -ne 0 ] && [ $$K6_EXIT -ne 99 ]; then \
			echo "k6 failed with unexpected exit code $$K6_EXIT" >&2; \
			exit $$K6_EXIT; \
		fi; \
		AFTER="$$(kubectl get deploy $(BENCH_DEPLOY) -n $(KUBE_NS) -o jsonpath='{.status.readyReplicas}')"; AFTER="$${AFTER:-0}"; \
		echo "==> vus=$$V ready_after=$$AFTER k6_exit=$$K6_EXIT"; \
		echo "waiting for deploy/$(BENCH_DEPLOY) to settle back to 1 ready replica (max 90s)..."; \
		WAITED=0; \
		while [ "$$WAITED" -lt 90 ]; do \
			CUR="$$(kubectl get deploy $(BENCH_DEPLOY) -n $(KUBE_NS) -o jsonpath='{.status.readyReplicas}')"; CUR="$${CUR:-0}"; \
			if [ "$$CUR" = "1" ]; then break; fi; \
			sleep 5; \
			WAITED=$$((WAITED + 5)); \
		done; \
	done; \
	python3 scripts/report_k6.py --dir bench/k6/$(SERVICE) --latest

# sustained load at HPA_VUS for HPA_DURATION to watch the hpa scale the api deployment;
# samples ready replicas and the hpa line every 10s (metrics-server and the hpa both sync on 15s).
hpa:
	@mkdir -p bench/k6/hpa
	@RUN_ID="$$(date -u +%Y%m%d_%H%M%S)"; \
	GIT_SHA="$$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"; \
	BEFORE="$$(kubectl get deploy api -n $(KUBE_NS) -o jsonpath='{.status.readyReplicas}')"; BEFORE="$${BEFORE:-0}"; \
	echo "hpa run_id=$$RUN_ID git_sha=$$GIT_SHA vus=$(HPA_VUS) duration=$(HPA_DURATION) ready_before=$$BEFORE"; \
	docker run --rm --network kind -v $(PWD)/scripts:/scripts:ro -v $(PWD)/bench/k6/hpa:/results \
		-e BASE_URL=http://$(KIND_CLUSTER)-control-plane:$(BENCH_PORT) -e VUS=$(HPA_VUS) -e DURATION=$(HPA_DURATION) \
		-e RUN_ID=$$RUN_ID -e GIT_SHA=$$GIT_SHA -e K6_SUMMARY_PATH=/results/$${RUN_ID}_hpa_vus$(HPA_VUS).json \
		grafana/k6:latest run /scripts/k6_smoke.js & \
	K6_PID=$$!; \
	MAX_READY=$$BEFORE; \
	while kill -0 $$K6_PID 2>/dev/null; do \
		CUR="$$(kubectl get deploy api -n $(KUBE_NS) -o jsonpath='{.status.readyReplicas}')"; CUR="$${CUR:-0}"; \
		HPA_LINE="$$(kubectl get hpa api -n $(KUBE_NS) --no-headers)"; \
		echo "sample ready=$$CUR hpa=[$$HPA_LINE]"; \
		if [ "$$CUR" -gt "$$MAX_READY" ]; then MAX_READY=$$CUR; fi; \
		sleep 10; \
	done; \
	wait $$K6_PID; \
	K6_EXIT=$$?; \
	FINAL_HPA="$$(kubectl get hpa api -n $(KUBE_NS) --no-headers)"; \
	echo "hpa result: before=$$BEFORE max_ready=$$MAX_READY final_hpa=[$$FINAL_HPA] k6_exit=$$K6_EXIT"; \
	if [ $$K6_EXIT -ne 0 ] && [ $$K6_EXIT -ne 99 ]; then \
		echo "k6 failed with unexpected exit code $$K6_EXIT" >&2; \
		exit $$K6_EXIT; \
	fi

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
	kind load docker-image $(IMAGE) --name $(KIND_CLUSTER)
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
	$(MAKE) smoke

smoke:
	docker run --rm --network kind -e RUN_MIGRATIONS=0 -e BASE_URL=http://$(KIND_CLUSTER)-control-plane:30080 $(IMAGE) pytest contract -q

undeploy:
	kubectl delete -f $(filter-out deploy/kind.yaml,$(wildcard deploy/*.yaml)) --ignore-not-found

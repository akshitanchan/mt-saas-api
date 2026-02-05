SHELL := /bin/bash
.DEFAULT_GOAL := up

PROJECT := mt-saas-api
COMPOSE := docker compose -p $(PROJECT)

API_BASE := http://127.0.0.1:8000
READY_URL := $(API_BASE)/ready

.PHONY: up down reset wait logs api-shell seed demo k6 test

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

#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8000}"
EMAIL="${EMAIL:-owner-webhooks@example.com}"
PROJECT_NAME="${PROJECT_NAME:-mt-saas-api}"
DC="docker compose -p $PROJECT_NAME"

log() { printf "\n==> %s\n" "$*"; }
fail() { echo "❌ $*" >&2; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || fail "missing dependency: $1"
}

need curl
need jq
need docker

# --- helpers ---
http_code() {
  # usage: http_code METHOD URL [JSON_BODY]
  local method="$1"; shift
  local url="$1"; shift
  local body="${1:-}"
  if [[ -n "$body" ]]; then
    curl -sS -o /tmp/resp.json -w "%{http_code}" -X "$method" "$url" \
      -H "Content-Type: application/json" \
      -d "$body"
  else
    curl -sS -o /tmp/resp.json -w "%{http_code}" -X "$method" "$url"
  fi
}

json() { cat /tmp/resp.json; }

auth_post() {
  # usage: auth_post URL JSON_BODY
  local url="$1"
  local body="$2"
  curl -sS -X POST "$url" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "$body"
}

auth_get() {
  local url="$1"
  curl -sS -X GET "$url" -H "Authorization: Bearer $JWT"
}

auth_patch() {
  local url="$1"
  local body="$2"
  curl -sS -X PATCH "$url" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "$body"
}

# --- start ---
log "bring stack up"
$DC up --build -d

log "wait for api port to accept connections (max ~20s)"
for i in {1..40}; do
  if curl -sS "$BASE_URL/health" >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

log "health should be 200"
code="$(http_code GET "$BASE_URL/health")"
[[ "$code" == "200" ]] || { echo "body: $(json)"; fail "/health returned $code"; }
jq -e '.status=="ok"' /tmp/resp.json >/dev/null || { echo "body: $(json)"; fail "/health json unexpected"; }

log "ready should be 200"
code="$(http_code GET "$BASE_URL/ready")"
if [[ "$code" != "200" ]]; then
  echo "body: $(json)"
  fail "/ready returned $code"
fi
jq -e '.status=="ok" and .checks.db==true and .checks.redis==true' /tmp/resp.json >/dev/null || {
  echo "body: $(json)"
  fail "/ready json unexpected"
}

log "alembic current should be head"
$DC exec -T api alembic current | grep -q "0003_webhook_event_status_error (head)" || fail "alembic current not at head"

log "db alembic_version should be 0003_webhook_event_status_error"
$DC exec -T db psql -U app -d app -tAc "select version_num from alembic_version;" | grep -q "0003_webhook_event_status_error" \
  || fail "db alembic_version not 0003_webhook_event_status_error"

log "tables should include webhook_events"
$DC exec -T db psql -U app -d app -tAc "\dt" | grep -q "webhook_events" || fail "webhook_events table missing"

log "auth: request-link -> token"
code="$(http_code POST "$BASE_URL/auth/request-link" "{\"email\":\"$EMAIL\"}")"
[[ "$code" == "200" ]] || { echo "body: $(json)"; fail "request-link returned $code"; }
TOKEN="$(jq -r '.token // empty' /tmp/resp.json)"
[[ -n "$TOKEN" ]] || { echo "body: $(json)"; fail "no .token in request-link response"; }

log "auth: redeem -> access_token"
code="$(http_code POST "$BASE_URL/auth/redeem" "{\"token\":\"$TOKEN\"}")"
[[ "$code" == "200" ]] || { echo "body: $(json)"; fail "redeem returned $code"; }
JWT="$(jq -r '.access_token // empty' /tmp/resp.json)"
[[ -n "$JWT" ]] || { echo "body: $(json)"; fail "no .access_token in redeem response"; }

log "orgs: create org"
ORG_JSON="$(auth_post "$BASE_URL/orgs" '{"name":"smoke-org"}')"
echo "$ORG_JSON" | jq . >/dev/null || fail "org create did not return json"
ORG_ID="$(echo "$ORG_JSON" | jq -r '.id // empty')"
[[ -n "$ORG_ID" ]] || { echo "$ORG_JSON"; fail "org create missing id"; }

log "set stripe_customer_id so webhook matches this org"
$DC exec -T db psql -U app -d app -tAc "update orgs set stripe_customer_id='cus_smoke' where id='${ORG_ID}';"

log "orgs: get org"
ORG_GET="$(auth_get "$BASE_URL/orgs/$ORG_ID")"
echo "$ORG_GET" | jq -e --arg id "$ORG_ID" '.id==$id' >/dev/null || { echo "$ORG_GET"; fail "org get mismatch"; }

log "projects: create project"
PROJ_JSON="$(auth_post "$BASE_URL/orgs/$ORG_ID/projects" '{"name":"smoke-project"}')"
echo "$PROJ_JSON" | jq . >/dev/null || fail "project create did not return json"
PROJ_ID="$(echo "$PROJ_JSON" | jq -r '.id // empty')"
[[ -n "$PROJ_ID" ]] || { echo "$PROJ_JSON"; fail "project create missing id"; }

log "tasks: create task"
TASK_JSON="$(auth_post "$BASE_URL/orgs/$ORG_ID/projects/$PROJ_ID/tasks" '{"title":"smoke task"}')"
echo "$TASK_JSON" | jq . >/dev/null || fail "task create did not return json"
TASK_ID="$(echo "$TASK_JSON" | jq -r '.id // empty')"
[[ -n "$TASK_ID" ]] || { echo "$TASK_JSON"; fail "task create missing id"; }

log "tasks: update task (basic patch)"
# adjust fields here if your schema differs
TASK_PATCH="$(auth_patch "$BASE_URL/orgs/$ORG_ID/tasks/$TASK_ID" '{"title":"smoke task updated"}')"
echo "$TASK_PATCH" | jq . >/dev/null || fail "task patch did not return json"

log "stripe webhook: first delivery should be ok"
EVENT_ID="evt_smoke_$(date +%s)"
PAYLOAD="$(jq -nc --arg eid "$EVENT_ID" '{
  id: $eid,
  type: "customer.subscription.updated",
  data: { object: { id: "sub_smoke", customer: "cus_smoke", status: "active", current_period_end: 2000000000 } }
}')"

code="$(http_code POST "$BASE_URL/webhooks/stripe" "$PAYLOAD")"
[[ "$code" == "200" ]] || { echo "body: $(json)"; fail "webhook first post returned $code"; }
jq -e --arg eid "$EVENT_ID" '.status=="ok" and .event_id==$eid' /tmp/resp.json >/dev/null || {
  echo "body: $(json)"
  fail "webhook first response unexpected"
}

log "stripe webhook: duplicate should be ignored + duplicate=true"
code="$(http_code POST "$BASE_URL/webhooks/stripe" "$PAYLOAD")"
[[ "$code" == "200" ]] || { echo "body: $(json)"; fail "webhook duplicate post returned $code"; }
jq -e --arg eid "$EVENT_ID" '.status=="ignored" and .reason=="duplicate" and .duplicate==true and .event_id==$eid' /tmp/resp.json >/dev/null || {
  echo "body: $(json)"
  fail "webhook duplicate response unexpected"
}

log "ledger: exactly one row for that event"
COUNT="$($DC exec -T db psql -U app -d app -tAc \
  "select count(*) from webhook_events where provider='stripe' and event_id='${EVENT_ID}';" | tr -d '[:space:]')"
[[ "$COUNT" == "1" ]] || fail "expected 1 webhook_events row, got $COUNT"

log "✅ smoke test passed"

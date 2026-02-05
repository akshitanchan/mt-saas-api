#!/bin/sh
set -eu

: "${RUN_MIGRATIONS:=1}"
: "${MIGRATE_MAX_ATTEMPTS:=60}"
: "${MIGRATE_SLEEP_SECONDS:=1}"
: "${PORT:=8000}"

if [ "$RUN_MIGRATIONS" = "1" ]; then
  echo "==> running alembic migrations"
  i=1
  while [ "$i" -le "$MIGRATE_MAX_ATTEMPTS" ]; do
    if alembic upgrade head; then
      echo "==> migrations complete"
      break
    fi
    echo "==> migrations failed (attempt $i/$MIGRATE_MAX_ATTEMPTS), retrying in ${MIGRATE_SLEEP_SECONDS}s..."
    i=$((i + 1))
    sleep "$MIGRATE_SLEEP_SECONDS"
  done

  if [ "$i" -gt "$MIGRATE_MAX_ATTEMPTS" ]; then
    echo "==> migrations failed after $MIGRATE_MAX_ATTEMPTS attempts" >&2
    exit 1
  fi
fi

# if no command is passed, start the api server
if [ "$#" -eq 0 ]; then
  set -- uvicorn app.main:app --host 0.0.0.0 --port "$PORT"
fi

exec "$@"

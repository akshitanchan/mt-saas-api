import sqlalchemy as sa

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.db import db_ping
from app.redis_client import redis_ping

router = APIRouter(tags=["health"])

@router.get("/health")
def health() -> dict:
    return {"status": "ok"}

# readiness probe
@router.get("/ready")
def ready():
    checks: dict[str, bool] = {}
    errors: dict[str, str] = {}

    for name, fn in (("db", db_ping), ("redis", redis_ping)):
        try:
            checks[name] = bool(fn())
        except Exception as e:
            checks[name] = False
            msg = str(e).strip()
            errors[name] = f"{e.__class__.__name__}{(': ' + msg) if msg else ''}"

    ok = all(checks.values())

    body: dict = {"status": "ok" if ok else "unready", "checks": checks}
    if errors:
        body["errors"] = errors

    # returns 200 only when db + redis are reachable
    # returns 503 with details if not
    return JSONResponse(status_code=200 if ok else 503, content=body)

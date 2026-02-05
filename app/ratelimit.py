from __future__ import annotations

import hashlib
from fastapi import HTTPException, Request

from app.config import settings
from app.redis_client import redis_client

def _hash(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()[:24]

# fixed-window limiter using redis INCR + EXPIRE
def rate_limit(name: str, limit_per_window: int, window_seconds: int):
    async def _dep(request: Request) -> None:
        if not settings.rate_limit_enabled:
            return

        ip = (request.client.host if request.client else "unknown").strip()
        key = f"rl:{name}:{_hash(ip)}"

        try:
            pipe = redis_client.pipeline()
            pipe.incr(key)
            pipe.expire(key, window_seconds, nx=True)
            count, _ = pipe.execute()
            if int(count) > int(limit_per_window):
                raise HTTPException(status_code=429, detail="rate_limited")
        except HTTPException:
            raise
        except Exception:
            # fail-open if redis is down
            return

    return _dep

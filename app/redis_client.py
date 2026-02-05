import redis

from app.config import settings

redis_client = redis.Redis.from_url(settings.redis_url, decode_responses=True)

# redis connectivity check
def redis_ping() -> bool:
    try:
        return bool(redis_client.ping())
    except Exception:
        return False

import hashlib
import hmac
import secrets
import uuid
import jwt
from datetime import datetime, timedelta, timezone
from app.config import settings

def now_utc() -> datetime:
    return datetime.now(timezone.utc)

def new_magic_token() -> str:
    return secrets.token_urlsafe(32)

def hash_magic_token(token: str) -> str:
    msg = token.encode("utf-8")
    key = settings.magic_link_pepper.encode("utf-8")
    digest = hmac.new(key, msg, hashlib.sha256).hexdigest()
    return digest

def magic_link_expiry() -> datetime:
    return now_utc() + timedelta(minutes=settings.magic_link_expires_minutes)

def issue_access_token(user_id: str | uuid.UUID) -> str:
    user_id = str(user_id)
    iat = now_utc()
    exp = iat + timedelta(minutes=settings.jwt_expires_minutes)
    payload = {
        "sub": user_id,
        "iss": settings.jwt_issuer,
        "aud": settings.jwt_audience,
        "iat": int(iat.timestamp()),
        "exp": int(exp.timestamp()),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm="HS256")

def decode_access_token(token: str) -> dict:
    return jwt.decode(
        token,
        settings.jwt_secret,
        algorithms=["HS256"],
        audience=settings.jwt_audience,
        issuer=settings.jwt_issuer,
    )

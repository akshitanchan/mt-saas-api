from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.auth.tokens import (
    hash_magic_token,
    issue_access_token,
    magic_link_expiry,
    new_magic_token,
    now_utc,
)
from app.config import settings
from app.db import get_db
from app.models.auth_magic_link import AuthMagicLink
from app.models.user import User
from app.schemas.auth import AccessTokenOut, RedeemIn, RequestLinkIn, RequestLinkOut
from app.ratelimit import rate_limit

router = APIRouter(prefix="/auth", tags=["auth"])

@router.post("/request-link", response_model=RequestLinkOut)
def request_link(
    payload: RequestLinkIn,
    request: Request,
    db: Session = Depends(get_db),
    _: None = Depends(
        rate_limit(
            "auth:request_link",
            limit_per_window=settings.rate_limit_auth_request_link_per_min,
            window_seconds=60,
        )
    ),
) -> RequestLinkOut:
    email = payload.email.lower().strip()

    user = db.scalar(select(User).where(User.email == email))
    if user is None:
        user = User(email=email)
        db.add(user)
        db.flush()

    token = new_magic_token()
    token_hash = hash_magic_token(token)

    db.add(
        AuthMagicLink(
            token_hash=token_hash,
            user_id=user.id,
            expires_at=magic_link_expiry(),
            used_at=None,
        )
    )
    db.commit()

    if settings.app_env == "prod":
        return RequestLinkOut(token=None, link=f"{settings.base_url}/auth/redeem?token={token}")

    return RequestLinkOut(sent=True, token=token, link=None)

@router.post("/redeem", response_model=AccessTokenOut)
def redeem(
    payload: RedeemIn,
    request: Request,
    db: Session = Depends(get_db),
    _: None = Depends(
        rate_limit(
            "auth:redeem",
            limit_per_window=settings.rate_limit_auth_redeem_per_min,
            window_seconds=60,
        )
    ),
) -> AccessTokenOut:
    token = payload.token.strip()
    now = now_utc()

    # atomic single-use + expiry gate
    stmt = (
        update(AuthMagicLink)
        .where(AuthMagicLink.token_hash == hash_magic_token(token))
        .where(AuthMagicLink.used_at.is_(None))
        .where(AuthMagicLink.expires_at > now)
        .values(used_at=now)
        .returning(AuthMagicLink.user_id)
    )

    user_id = db.scalar(stmt)
    if user_id is None:
        row = db.get(AuthMagicLink, hash_magic_token(token))
        if row is None:
            raise HTTPException(status_code=400, detail="invalid token")
        if row.used_at is not None:
            raise HTTPException(status_code=400, detail="token already used")
        if row.expires_at <= now:
            raise HTTPException(status_code=400, detail="token expired")
        raise HTTPException(status_code=400, detail="invalid token")

    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=400, detail="invalid token")

    db.commit()
    return AccessTokenOut(access_token=issue_access_token(str(user.id)))

from datetime import timedelta

from app.auth.tokens import hash_magic_token, now_utc
from app.models.auth_magic_link import AuthMagicLink

def _request_magic_token(client) -> str:
    r = client.post("/auth/request-link", json={"email": "magiclink@example.com"})
    assert r.status_code == 200, r.text
    token = r.json().get("token")
    assert token, "expected token to be returned in non-prod env"
    return token

def test_magic_link_cannot_be_reused(client):
    token = _request_magic_token(client)

    r1 = client.post("/auth/redeem", json={"token": token})
    assert r1.status_code == 200, r1.text
    assert "access_token" in r1.json()

    r2 = client.post("/auth/redeem", json={"token": token})
    assert r2.status_code == 400, r2.text
    assert "used" in r2.json()["detail"].lower()

def test_magic_link_expires(client, db_session):
    token = _request_magic_token(client)
    token_hash = hash_magic_token(token)

    row = db_session.get(AuthMagicLink, token_hash)
    assert row is not None
    row.expires_at = now_utc() - timedelta(seconds=1)
    db_session.add(row)
    db_session.commit()

    r = client.post("/auth/redeem", json={"token": token})
    assert r.status_code == 400, r.text
    assert "expired" in r.json()["detail"].lower()

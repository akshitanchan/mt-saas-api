import os
import uuid

import httpx
import pytest

def _auth_headers(jwt: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {jwt}"}

def _login(client: httpx.Client, email: str) -> str:
    r = client.post("/auth/request-link", json={"email": email})
    assert r.status_code == 200, r.text
    token = r.json()["token"]

    r = client.post("/auth/redeem", json={"token": token})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]

@pytest.fixture(scope="session")
def base_url() -> str:
    return os.environ["BASE_URL"]

@pytest.fixture(scope="session")
def client(base_url):
    with httpx.Client(base_url=base_url, timeout=10) as c:
        yield c

@pytest.fixture(scope="session")
def tenant_factory(client):
    # request-link + redeem + create org, uuid-suffixed so reruns never collide
    def _make() -> tuple[str, str]:
        email = f"contract+{uuid.uuid4().hex}@example.com"
        jwt = _login(client, email)

        r = client.post(
            "/orgs",
            json={"name": f"contract-org-{uuid.uuid4().hex[:8]}"},
            headers=_auth_headers(jwt),
        )
        assert r.status_code == 200, r.text
        return jwt, r.json()["id"]

    return _make

@pytest.fixture(scope="session")
def owner(tenant_factory):
    return tenant_factory()

@pytest.fixture(scope="session")
def helper(client):
    # an authenticated user who is never a member of anyone else's org
    return _login(client, f"contract+{uuid.uuid4().hex}@example.com")

@pytest.fixture(scope="session")
def admin_actor(client):
    email = f"contract+{uuid.uuid4().hex}@example.com"
    return email, _login(client, email)

@pytest.fixture(scope="session")
def member_actor(client):
    email = f"contract+{uuid.uuid4().hex}@example.com"
    return email, _login(client, email)

import os
import time
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import Session, sessionmaker

from app.db import get_db
from app.main import create_app
from app.models.org import Org

@pytest.fixture()
def db_session() -> Session:
    database_url = os.environ["DATABASE_URL"]

    engine = create_engine(database_url, pool_pre_ping=True)

    connection = engine.connect()
    transaction = connection.begin()

    TestingSessionLocal = sessionmaker(bind=connection, autoflush=False, autocommit=False)
    session: Session = TestingSessionLocal()

    # savepoint
    session.begin_nested()

    @event.listens_for(session, "after_transaction_end")
    def _restart_savepoint(sess: Session, trans) -> None:  # type: ignore[no-untyped-def]
        if trans.nested and not trans._parent.nested:
            sess.begin_nested()

    try:
        yield session
    finally:
        session.close()
        transaction.rollback()
        connection.close()
        engine.dispose()

@pytest.fixture()
def client(db_session: Session) -> TestClient:
    app = create_app()

    def _override_get_db():
        yield db_session

    app.dependency_overrides[get_db] = _override_get_db
    return TestClient(app)

def _login(client, email: str) -> str:
    r = client.post("/auth/request-link", json={"email": email})
    assert r.status_code == 200, r.text
    token = r.json()["token"]

    r = client.post("/auth/redeem", json={"token": token})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]

def _auth(jwt: str) -> dict[str, str]:
    return {"authorization": f"bearer {jwt}"}

@pytest.fixture()
def owner_jwt(client) -> str:
    # unique per test run to avoid collisions
    email = f"owner+{int(time.time())}_{uuid.uuid4().hex[:8]}@example.com"
    return _login(client, email)

@pytest.fixture()
def seeded_org(client, db_session, owner_jwt) -> Org:
    r = client.post("/orgs", json={"name": f"seeded-org-{uuid.uuid4().hex[:6]}"}, headers=_auth(owner_jwt))
    assert r.status_code == 200, r.text
    org_id = r.json()["id"]

    org = db_session.get(Org, org_id)
    assert org is not None
    return org


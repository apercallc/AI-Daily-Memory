from collections.abc import Iterator
from typing import cast

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import Engine

from app.main import create_app
from tests.conftest import make_account, settings_for

pytestmark = pytest.mark.anyio


@pytest.fixture
def client(db: Engine, database_url: str) -> Iterator[TestClient]:
    app = create_app(settings_for(database_url), processing_enabled=False)
    with TestClient(app) as test_client:
        yield test_client


def test_signup_returns_a_usable_device_token(client: TestClient) -> None:
    response = client.post(
        "/v1/signup",
        json={"email": "new@example.com", "password": "correct horse battery", "device_name": "P"},
    )

    assert response.status_code == 201
    token = response.json()["token"]
    assert token.startswith("adm_")

    me = client.get("/v1/me", headers={"Authorization": f"Bearer {token}"})
    assert me.status_code == 200


def test_signup_never_echoes_the_password_and_rejects_a_weak_one(client: TestClient) -> None:
    response = client.post(
        "/v1/signup",
        json={"email": "weak@example.com", "password": "short", "device_name": "P"},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "weak_password"
    assert "short" not in response.text


def test_a_duplicate_signup_is_a_conflict(client: TestClient) -> None:
    body = {"email": "dupe@example.com", "password": "x" * 12, "device_name": "P"}
    assert client.post("/v1/signup", json=body).status_code == 201

    conflict = client.post("/v1/signup", json=body)
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "email_taken"


def test_login_issues_a_second_independent_token(client: TestClient) -> None:
    body = {"email": "two@example.com", "password": "x" * 12, "device_name": "phone-1"}
    signup_token = client.post("/v1/signup", json=body).json()["token"]

    login = client.post("/v1/login", json={**body, "device_name": "phone-2"})
    assert login.status_code == 200
    login_token = login.json()["token"]
    assert login_token != signup_token

    for token in (signup_token, login_token):
        assert client.get("/v1/me", headers={"Authorization": f"Bearer {token}"}).status_code == 200

    # Revoking the login token must not touch the signup token (independent devices).
    revoke = client.delete("/v1/tokens/current", headers={"Authorization": f"Bearer {login_token}"})
    assert revoke.status_code == 204
    still_ok = client.get("/v1/me", headers={"Authorization": f"Bearer {signup_token}"})
    assert still_ok.status_code == 200


def test_wrong_password_and_unknown_email_both_return_401_without_a_hint(
    client: TestClient,
) -> None:
    body = {"email": "three@example.com", "password": "x" * 12, "device_name": "P"}
    client.post("/v1/signup", json=body)

    wrong = client.post("/v1/login", json={**body, "password": "y" * 12})
    unknown = client.post("/v1/login", json={**body, "email": "nobody@example.com"})

    assert wrong.status_code == unknown.status_code == 401
    assert wrong.json()["error"]["code"] == unknown.json()["error"]["code"] == "invalid_credentials"


def test_locked_account_and_unknown_email_have_the_same_public_error(client: TestClient) -> None:
    body = {"email": "locked@example.com", "password": "x" * 12, "device_name": "P"}
    assert client.post("/v1/signup", json=body).status_code == 201

    for _ in range(5):
        client.post("/v1/login", json={**body, "password": "wrong-password"})

    locked = client.post("/v1/login", json=body)
    unknown = client.post("/v1/login", json={**body, "email": "nobody@example.com"})

    locked_error = locked.json()["error"]
    unknown_error = unknown.json()["error"]
    assert locked.status_code == unknown.status_code == 401
    assert locked_error["code"] == unknown_error["code"] == "invalid_credentials"
    assert locked_error["message"] == unknown_error["message"]


def test_storage_mode_can_be_switched_and_takes_effect_immediately(
    client: TestClient, db: Engine
) -> None:
    account = make_account(db, storage_mode="phone_only")
    headers = {"Authorization": f"Bearer {account.token}"}

    before = client.get("/v1/me", headers=headers)
    assert before.json()["storage_mode"] == "phone_only"

    switched = client.put("/v1/me", json={"storage_mode": "full_cloud"}, headers=headers)
    assert switched.status_code == 200
    assert switched.json()["storage_mode"] == "full_cloud"

    after = client.get("/v1/me", headers=headers)
    assert after.json()["storage_mode"] == "full_cloud"


def test_an_invalid_storage_mode_is_rejected(client: TestClient, db: Engine) -> None:
    account = make_account(db)
    headers = {"Authorization": f"Bearer {account.token}"}

    response = client.put("/v1/me", json={"storage_mode": "everywhere"}, headers=headers)

    assert response.status_code == 422
    # Unchanged.
    assert client.get("/v1/me", headers=headers).json()["storage_mode"] == "phone_only"


def test_storage_mode_update_requires_authentication(client: TestClient) -> None:
    response = client.put("/v1/me", json={"storage_mode": "full_cloud"})
    assert response.status_code == 401


async def test_signup_rate_limit_returns_retry_after(client: TestClient) -> None:
    limiter = cast(FastAPI, client.app).state.auth_rate_limiter
    for _ in range(5):
        assert await limiter.retry_after("signup:testclient", limit=5, window_seconds=3_600) is None

    response = client.post(
        "/v1/signup",
        json={"email": "limited@example.com", "password": "x" * 12, "device_name": "P"},
    )

    assert response.status_code == 429
    assert response.headers["retry-after"] == "3600"
    assert response.json()["error"]["code"] == "too_many_requests"


async def test_login_rate_limit_returns_retry_after(client: TestClient) -> None:
    limiter = cast(FastAPI, client.app).state.auth_rate_limiter
    for _ in range(10):
        assert await limiter.retry_after("login:testclient", limit=10, window_seconds=60) is None

    response = client.post(
        "/v1/login",
        json={"email": "limited@example.com", "password": "x" * 12, "device_name": "P"},
    )

    assert response.status_code == 429
    assert response.headers["retry-after"] == "60"
    assert response.json()["error"]["code"] == "too_many_requests"


def test_an_extra_field_is_rejected(client: TestClient) -> None:
    response = client.post(
        "/v1/signup",
        json={
            "email": "x@example.com",
            "password": "x" * 12,
            "device_name": "P",
            "is_admin": True,
        },
    )
    assert response.status_code == 422


def test_an_attacker_address_cannot_lock_the_owner_out(db: Engine, database_url: str) -> None:
    app = create_app(
        settings_for(database_url, trust_railway_x_real_ip=True), processing_enabled=False
    )
    body = {"email": "owner@example.com", "password": "x" * 12, "device_name": "P"}
    attacker = {"X-Real-IP": "203.0.113.9"}
    owner = {"X-Real-IP": "198.51.100.7"}
    with TestClient(app) as client:
        assert client.post("/v1/signup", json=body, headers=owner).status_code == 201
        for _ in range(6):
            client.post("/v1/login", json={**body, "password": "wrong-password"}, headers=attacker)

        assert client.post("/v1/login", json=body, headers=attacker).status_code == 401
        assert client.post("/v1/login", json=body, headers=owner).status_code == 200

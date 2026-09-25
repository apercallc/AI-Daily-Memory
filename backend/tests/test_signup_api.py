from collections.abc import Iterator

import pytest
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

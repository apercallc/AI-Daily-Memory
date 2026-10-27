"""The security audit trail: what is recorded, what is withheld, and that it cannot be rewritten."""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text
from sqlalchemy.exc import DBAPIError

from app.audit import actor_ref
from app.main import create_app
from tests.conftest import settings_for

PASSWORD = "correct horse battery"  # noqa: S105 - test credential
EMAIL = "audited@example.com"


@pytest.fixture
def client(db: Engine, database_url: str) -> Iterator[TestClient]:
    with TestClient(create_app(settings_for(database_url))) as test_client:
        yield test_client


def events(db: Engine) -> list[tuple[str, str, str | None, dict[str, object]]]:
    with db.connect() as c:
        rows = c.execute(
            text("SELECT event, outcome, actor_ref, detail FROM audit_events ORDER BY id")
        ).all()
    return [(r.event, r.outcome, r.actor_ref, r.detail) for r in rows]


def sign_up(client: TestClient) -> str:
    response = client.post(
        "/v1/signup",
        json={"email": EMAIL, "password": PASSWORD, "device_name": "Phone", "client": "device"},
    )
    assert response.status_code == 201
    return str(response.json()["token"])


def test_signup_and_login_leave_a_trail_without_the_email_or_password(
    client: TestClient, db: Engine
) -> None:
    sign_up(client)
    login = client.post(
        "/v1/login",
        json={"email": EMAIL, "password": PASSWORD, "device_name": "Laptop", "client": "web"},
    )
    assert login.status_code == 200

    recorded = events(db)
    assert [(e, o) for e, o, _, _ in recorded] == [
        ("auth.signup", "success"),
        ("auth.login", "success"),
    ]
    assert recorded[0][2] == recorded[1][2] is not None
    with db.connect() as c:
        dump = str(c.execute(text("SELECT * FROM audit_events")).all())
    assert EMAIL not in dump
    assert PASSWORD not in dump


def test_a_failed_login_is_recorded_with_the_real_reason_and_no_actor(
    client: TestClient, db: Engine
) -> None:
    sign_up(client)
    for _ in range(6):
        client.post(
            "/v1/login",
            json={"email": EMAIL, "password": "wrong password!!", "device_name": "X"},
        )

    failures = [e for e in events(db) if e[0] == "auth.login"]
    codes = [detail["code"] for _, outcome, actor, detail in failures if outcome == "failure"]
    assert codes[:5] == ["invalid_credentials"] * 5
    assert codes[5] == "account_locked"  # the response hides this; the trail keeps it
    assert all(actor is None for _, _, actor, _ in failures)


def test_password_change_revocation_and_deletion_are_recorded(
    client: TestClient, db: Engine
) -> None:
    token = sign_up(client)
    headers = {"Authorization": f"Bearer {token}"}
    changed = client.post(
        "/v1/me/password",
        headers=headers,
        json={"current_password": PASSWORD, "new_password": "another long passphrase"},
    )
    assert changed.status_code == 204
    wrong = client.post(
        "/v1/me/password",
        headers=headers,
        json={"current_password": "not the password", "new_password": "yet another passphrase"},
    )
    assert wrong.status_code == 401
    assert client.delete("/v1/me", headers=headers).status_code == 204

    names = [(e, o) for e, o, _, _ in events(db)]
    assert names == [
        ("auth.signup", "success"),
        ("auth.password_change", "success"),
        ("auth.password_change", "failure"),
        ("account.delete", "success"),
    ]


def test_the_trail_survives_account_deletion_but_names_no_one(
    client: TestClient, db: Engine
) -> None:
    token = sign_up(client)
    assert client.delete("/v1/me", headers={"Authorization": f"Bearer {token}"}).status_code == 204

    with db.connect() as c:
        users = c.scalar(text("SELECT count(*) FROM users"))
        refs = c.scalars(text("SELECT actor_ref FROM audit_events")).all()
    assert users == 0
    assert len(refs) == 2
    assert all(ref is not None and len(ref) == 64 for ref in refs)


def test_audit_rows_cannot_be_updated_or_deleted(client: TestClient, db: Engine) -> None:
    sign_up(client)
    for statement in ("UPDATE audit_events SET outcome = 'denied'", "DELETE FROM audit_events"):
        with pytest.raises(DBAPIError, match="append-only"), db.begin() as c:
            c.execute(text(statement))
    assert len(events(db)) == 1


def test_actor_ref_is_stable_per_key_and_differs_across_keys() -> None:
    import uuid

    user = uuid.uuid4()
    assert actor_ref(user, b"k1") == actor_ref(user, b"k1")
    assert actor_ref(user, b"k1") != actor_ref(user, b"k2")
    assert actor_ref(user, b"k1") != actor_ref(uuid.uuid4(), b"k1")

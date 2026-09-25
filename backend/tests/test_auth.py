import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.auth import TOKEN_PREFIX, hash_token, issue_token, new_token, revoke_token
from app.db import Database
from app.main import create_app
from tests.conftest import FakeObjectStore, make_account, settings_for


@pytest.fixture
def client(db: Engine, database_url: str) -> Iterator[TestClient]:
    with TestClient(create_app(settings_for(database_url), object_store=FakeObjectStore())) as c:
        yield c


def status_with(client: TestClient, headers: dict[str, str]) -> int:
    status: int = client.get(f"/v1/uploads/{uuid.uuid4()}", headers=headers).status_code
    return status


def test_tokens_are_prefixed_random_and_stored_only_as_hashes() -> None:
    first, second = new_token(), new_token()

    assert first.startswith(TOKEN_PREFIX)
    assert first != second
    assert len(hash_token(first)) == 32
    assert hash_token(first) != first.encode()


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"Authorization": "Basic abc"},
        {"Authorization": "Bearer not-ours"},
        {"Authorization": f"Bearer {TOKEN_PREFIX}{'x' * 200}"},
        {"Authorization": f"Bearer {TOKEN_PREFIX}unknown"},
    ],
)
def test_missing_or_unknown_tokens_get_401_with_a_challenge(
    client: TestClient,
    headers: dict[str, str],
) -> None:
    response = client.get(f"/v1/uploads/{uuid.uuid4()}", headers=headers)

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json()["error"]["code"] == "unauthorized"


def test_revoked_and_expired_tokens_look_exactly_like_unknown_ones(
    client: TestClient,
    db: Engine,
) -> None:
    revoked = make_account(db, revoked=True)
    expired = make_account(db, expires_at=datetime.now(UTC) - timedelta(seconds=1))

    assert status_with(client, revoked.headers) == 401
    assert status_with(client, expired.headers) == 401


def test_a_token_without_the_scope_is_forbidden(client: TestClient, db: Engine) -> None:
    read_only = make_account(db, scopes=("memory:read",))

    assert status_with(client, read_only.headers) == 403


def test_a_valid_token_reaches_the_route_and_records_use_hourly(
    client: TestClient,
    db: Engine,
) -> None:
    fresh = make_account(db)
    recent = make_account(db, last_used_at=datetime.now(UTC) - timedelta(minutes=5))

    assert status_with(client, fresh.headers) == 404
    assert status_with(client, recent.headers) == 404

    with db.connect() as connection:
        result = connection.execute(text("SELECT user_id, last_used_at FROM access_tokens"))
        rows = {row.user_id: row.last_used_at for row in result}
    assert rows[fresh.user_id] is not None
    assert datetime.now(UTC) - rows[recent.user_id] >= timedelta(minutes=4)


@pytest.mark.anyio
async def test_issue_and_revoke_through_the_service(db: Engine, database_url: str) -> None:
    database = Database(database_url)
    user_id = uuid.uuid4()
    try:
        async with database.sessions() as session:
            await session.execute(text("INSERT INTO users (id) VALUES (:id)"), {"id": user_id})
            issued = await issue_token(session, user_id, kind="api", name="my assistant")
            await session.commit()
            token_id = await session.scalar(
                text("SELECT id FROM access_tokens WHERE token_hash = :h"),
                {"h": hash_token(issued.token)},
            )
            assert token_id == issued.token_id
            scopes = await session.scalar(text("SELECT scopes FROM access_tokens"))
            assert scopes == ["memory:read"]  # API keys start read-only

            with pytest.raises(ValueError, match="at least one scope"):
                await issue_token(session, user_id, kind="api", name="none", scopes=())

            assert await revoke_token(session, uuid.uuid4(), token_id) is False
            assert await revoke_token(session, user_id, token_id) is True
            assert await revoke_token(session, user_id, token_id) is False
            await session.commit()
    finally:
        await database.dispose()


def test_me_reports_the_storage_mode_so_connect_can_verify_a_token(
    client: TestClient,
    db: Engine,
) -> None:
    account = make_account(db, storage_mode="reports_cloud")

    response = client.get("/v1/me", headers=account.headers)

    assert response.json() == {
        "storage_mode": "reports_cloud",
        "scopes": ["memory:read", "upload:write"],
    }
    assert client.get("/v1/me").status_code == 401


def test_disconnect_revokes_the_token_on_the_server(client: TestClient, db: Engine) -> None:
    account = make_account(db)

    assert client.delete("/v1/tokens/current", headers=account.headers).status_code == 204
    assert client.get("/v1/me", headers=account.headers).status_code == 401

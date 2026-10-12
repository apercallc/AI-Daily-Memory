import uuid
from collections.abc import Iterator
from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.main import create_app
from app.storage import DeleteIncompleteError
from tests.conftest import FakeObjectStore, make_account, settings_for

pytestmark = pytest.mark.anyio


class IncompleteObjectStore(FakeObjectStore):
    async def delete_prefix(self, prefix: str) -> int:
        raise DeleteIncompleteError(1, frozenset({"AccessDenied"}))


@pytest.fixture
def client(db: Engine, database_url: str, store: FakeObjectStore) -> Iterator[TestClient]:
    with TestClient(create_app(settings_for(database_url), object_store=store)) as test_client:
        yield test_client


@pytest.fixture
def store() -> FakeObjectStore:
    return FakeObjectStore()


def test_account_deletion_removes_storage_and_cascades_database_rows(
    db: Engine,
    client: TestClient,
    store: FakeObjectStore,
) -> None:
    account = make_account(db)
    other = make_account(db)
    owned_key = f"users/{account.user_id}/audio/clip.aac"
    other_key = f"users/{other.user_id}/audio/clip.aac"
    store.put(owned_key, b"audio")
    store.put(other_key, b"other audio")

    response = client.delete("/v1/me", headers=account.headers)

    assert response.status_code == 204
    assert owned_key not in store.objects
    assert store.objects[other_key][0] == b"other audio"
    with db.connect() as connection:
        assert (
            connection.scalar(
                text("SELECT count(*) FROM users WHERE id = :user"),
                {"user": account.user_id},
            )
            == 0
        )
        assert (
            connection.scalar(
                text("SELECT count(*) FROM access_tokens WHERE user_id = :user"),
                {"user": account.user_id},
            )
            == 0
        )
        assert (
            connection.scalar(
                text("SELECT count(*) FROM users WHERE id = :user"),
                {"user": other.user_id},
            )
            == 1
        )


def test_incomplete_storage_deletion_keeps_account_for_retry(
    db: Engine,
    database_url: str,
) -> None:
    account = make_account(db)
    failing_store = IncompleteObjectStore()
    with TestClient(
        create_app(settings_for(database_url), object_store=failing_store),
    ) as client:
        response = client.delete("/v1/me", headers=account.headers)

    assert response.status_code == 503
    with db.connect() as connection:
        assert (
            connection.scalar(
                text("SELECT count(*) FROM users WHERE id = :user"),
                {"user": account.user_id},
            )
            == 1
        )


def test_account_deletion_requires_device_write_scope(
    client: TestClient,
    db: Engine,
) -> None:
    read_only = make_account(db, scopes=("memory:read",))

    response = client.delete("/v1/me", headers=read_only.headers)

    assert response.status_code == 403


def test_missing_object_store_allows_deletion_when_no_uploads_exist(
    db: Engine,
    database_url: str,
) -> None:
    account = make_account(db)
    with TestClient(create_app(settings_for(database_url))) as client:
        response = client.delete("/v1/me", headers=account.headers)

    assert response.status_code == 204
    with db.connect() as connection:
        assert (
            connection.scalar(
                text("SELECT count(*) FROM users WHERE id = :user"),
                {"user": account.user_id},
            )
            == 0
        )


def test_account_deletion_refuses_to_orphan_uploads_without_object_storage(
    db: Engine,
    database_url: str,
) -> None:
    account = make_account(db)
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO audio_uploads (id, user_id, session_id, segment_id, offset_ms, "
                "duration_ms, byte_size, sha256, codec, sample_rate_hz, channel_count, "
                "captured_at, object_key) VALUES (:id, :user, :session, :segment, 0, 1000, "
                "5, :sha, 'aac-lc-adts', 16000, 1, :captured, :key)"
            ),
            {
                "id": uuid.uuid4(),
                "user": account.user_id,
                "session": uuid.uuid4(),
                "segment": uuid.uuid4(),
                "sha": "0" * 64,
                "captured": datetime.now(UTC),
                "key": f"users/{account.user_id}/audio/clip.aac",
            },
        )

    with TestClient(create_app(settings_for(database_url))) as client:
        response = client.delete("/v1/me", headers=account.headers)

    assert response.status_code == 503
    with db.connect() as connection:
        assert (
            connection.scalar(
                text("SELECT count(*) FROM users WHERE id = :user"),
                {"user": account.user_id},
            )
            == 1
        )


def test_account_deletion_does_not_accept_untrusted_user_id(
    db: Engine,
    client: TestClient,
) -> None:
    account = make_account(db)

    response = client.delete(f"/v1/users/{uuid.uuid4()}", headers=account.headers)

    assert response.status_code == 404

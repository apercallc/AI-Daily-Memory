import asyncio
import uuid
from collections.abc import AsyncIterator, Iterator
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from threading import Event
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.config import MAX_UPLOAD_URL_TTL_SECONDS
from app.db import Database
from app.main import create_app
from app.retention import PENDING_GRACE, sweep
from app.storage import DeleteIncompleteError, PresignedPut
from tests.conftest import FakeObjectStore, make_account, settings_for

pytestmark = pytest.mark.anyio


class IncompleteObjectStore(FakeObjectStore):
    async def delete_prefix(self, prefix: str) -> int:
        raise DeleteIncompleteError(1, frozenset({"AccessDenied"}))


@pytest.fixture
async def database(database_url: str) -> AsyncIterator[Database]:
    database = Database(database_url)
    yield database
    await database.dispose()


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


async def test_account_deletion_schedules_and_executes_late_upload_purge(
    db: Engine,
    database_url: str,
    store: FakeObjectStore,
    database: Database,
) -> None:
    account = make_account(db)
    deletion_started = datetime.now(UTC)

    with TestClient(
        create_app(
            settings_for(database_url, upload_url_ttl_seconds=60),
            object_store=store,
        ),
    ) as client:
        response = client.delete("/v1/me", headers=account.headers)

    assert response.status_code == 204
    late_key = f"users/{account.user_id}/audio/late-put.aac"
    store.put(late_key, b"uploaded with a still-valid URL")
    with db.connect() as connection:
        purge_after = connection.scalar(
            text(
                "SELECT purge_after FROM pending_account_object_deletions WHERE account_id = :user"
            ),
            {"user": account.user_id},
        )
    assert purge_after is not None
    assert (
        purge_after
        >= deletion_started + timedelta(seconds=MAX_UPLOAD_URL_TTL_SECONDS) + PENDING_GRACE
    )

    result = await sweep(
        database.sessions,
        store,
        url_ttl_seconds=MAX_UPLOAD_URL_TTL_SECONDS,
        now=purge_after + timedelta(seconds=1),
    )

    assert result.account_prefixes_purged == 1
    assert late_key not in store.objects
    with db.connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM pending_account_object_deletions")) == 0


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


class BlockingDeleteObjectStore(FakeObjectStore):
    def __init__(self) -> None:
        super().__init__()
        self.delete_started = Event()
        self.allow_delete = Event()
        self.presign_started = Event()

    async def delete_prefix(self, prefix: str) -> int:
        self.delete_started.set()
        if not await asyncio.to_thread(self.allow_delete.wait, 5):
            raise TimeoutError("test did not release account deletion")
        return await super().delete_prefix(prefix)

    def presign_put(
        self,
        key: str,
        *,
        byte_size: int,
        sha256: str,
        expires_in: int,
    ) -> PresignedPut:
        self.presign_started.set()
        return super().presign_put(
            key,
            byte_size=byte_size,
            sha256=sha256,
            expires_in=expires_in,
        )


def test_upload_reservation_waits_for_account_deletion(
    db: Engine,
    database_url: str,
) -> None:
    account = make_account(db, storage_mode="full_cloud")
    store = BlockingDeleteObjectStore()
    store.put(f"users/{account.user_id}/audio/old.aac", b"old")
    with TestClient(
        create_app(settings_for(database_url), object_store=store, processing_enabled=True),
    ) as client:
        upload_started = Event()
        body = {
            "upload_id": str(uuid.uuid4()),
            "session_id": str(uuid.uuid4()),
            "segment_id": str(uuid.uuid4()),
            "offset_ms": 0,
            "duration_ms": 1_000,
            "byte_size": 3,
            "sha256": "0" * 64,
            "codec": "aac-lc-adts",
            "sample_rate_hz": 16_000,
            "channel_count": 1,
            "captured_at": "2026-09-25T14:03:00+02:00",
        }

        def create_upload() -> Any:
            upload_started.set()
            return client.post("/v1/uploads", json=body, headers=account.headers)

        with ThreadPoolExecutor(max_workers=2) as executor:
            deletion = executor.submit(client.delete, "/v1/me", headers=account.headers)
            assert store.delete_started.wait(timeout=5)
            upload = executor.submit(create_upload)
            assert upload_started.wait(timeout=5)
            assert not store.presign_started.wait(timeout=0.25)
            store.allow_delete.set()
            assert deletion.result(timeout=10).status_code == 204
            assert upload.result(timeout=10).status_code == 401

    assert not store.presign_started.is_set()
    assert not store.objects

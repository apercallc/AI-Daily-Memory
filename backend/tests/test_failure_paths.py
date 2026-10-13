"""What the backend does when its dependencies fail: every one of these is a client-visible
status and a log line an operator can act on, never a bare 500."""

import logging
import uuid

import httpx
import pytest
from botocore.exceptions import BotoCoreError, ClientError
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text
from sqlalchemy.exc import OperationalError

from app.config import Settings
from app.db import get_session
from app.main import create_app
from app.storage import StoredObject, audio_key
from app.transcription import JobError
from tests.conftest import Account, FakeObjectStore, make_account, settings_for
from tests.test_providers import _LINES, _extractor, _provider
from tests.test_uploads import AUDIO, clip


def _client_error(code: str = "AccessDenied") -> ClientError:
    return ClientError({"Error": {"Code": code, "Message": "x"}}, "Op")


# --- providers ------------------------------------------------------------------------------


@pytest.mark.anyio
@pytest.mark.parametrize("status", [401, 403])
async def test_a_rejected_api_key_is_named_and_not_retried(status: int) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"err": "bad key"})

    with pytest.raises(JobError) as transcribe:
        await _provider(handler).transcribe(b"x", codec="aac-lc-adts", sample_rate_hz=16_000)
    with pytest.raises(JobError) as extract:
        await _extractor(handler).extract(_LINES)
    for caught in (transcribe, extract):
        assert caught.value.code == "provider_auth_failed"
        assert not caught.value.retryable


@pytest.mark.anyio
@pytest.mark.parametrize("status", [425, 529])
async def test_provider_overload_is_retried(status: int) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"err": "busy"})

    with pytest.raises(JobError) as extract:
        await _extractor(handler).extract(_LINES)
    assert extract.value.retryable


# --- database -------------------------------------------------------------------------------


def test_a_database_outage_is_a_retryable_503_not_a_500(
    db: Engine, database_url: str, caplog: pytest.LogCaptureFixture
) -> None:
    app = create_app(settings_for(database_url))

    async def down() -> None:
        raise OperationalError("SELECT 1", {}, Exception("connection refused"))

    app.dependency_overrides[get_session] = down
    with TestClient(app, raise_server_exceptions=False) as client, caplog.at_level(logging.ERROR):
        response = client.get("/v1/me", headers={"Authorization": "Bearer adm_x"})

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "database_unavailable"
    assert response.headers["Retry-After"] == "15"
    assert any(r.getMessage() == "db_unavailable" for r in caplog.records)


def test_readiness_reports_the_database(db: Engine, database_url: str) -> None:
    with TestClient(create_app(settings_for(database_url))) as client:
        ok = client.get("/v1/ready")
        assert ok.status_code == 200
        assert ok.json() == {"status": "ok", "database": "ok"}

        def broken() -> None:
            raise OperationalError("SELECT 1", {}, Exception("down"))

        client.app.state.database.sessions = broken  # type: ignore[attr-defined]
        down = client.get("/v1/ready")
    assert down.status_code == 503
    assert down.json() == {"status": "unavailable", "database": "unavailable"}


def test_readiness_without_a_database_is_unavailable() -> None:
    with TestClient(create_app(Settings(app_env="test"))) as client:
        response = client.get("/v1/ready")
    assert response.status_code == 503
    assert response.json()["database"] == "not_configured"


def test_a_failed_last_used_write_does_not_fail_the_request(
    db: Engine, database_url: str, caplog: pytest.LogCaptureFixture
) -> None:
    account = make_account(db)
    app = create_app(settings_for(database_url))
    with TestClient(app) as client:
        real = app.state.database.sessions
        calls = {"n": 0}

        def flaky() -> object:
            calls["n"] += 1
            # The first session is the request's own; the second is the usage bookkeeping.
            if calls["n"] == 2:
                raise OperationalError("UPDATE", {}, Exception("blip"))
            return real()

        app.state.database.sessions = flaky
        with caplog.at_level(logging.WARNING):
            response = client.get("/v1/me", headers=account.headers)

    assert response.status_code != 503
    assert any(r.getMessage() == "token_last_used_update_failed" for r in caplog.records)


# --- object storage -------------------------------------------------------------------------


class _StatFails(FakeObjectStore):
    async def stat(self, key: str) -> StoredObject | None:
        raise _client_error("SlowDown")


class _DeleteFails(FakeObjectStore):
    async def delete(self, key: str) -> None:
        raise BotoCoreError()


@pytest.fixture
def account(db: Engine) -> Account:
    return make_account(db, storage_mode="full_cloud")


def _pending_upload(
    client: TestClient, account: Account, store: FakeObjectStore, data: bytes
) -> str:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    store.put(audio_key(account.user_id, uuid.UUID(body["upload_id"])), data)
    return str(body["upload_id"])


def test_a_storage_error_while_completing_is_a_retryable_503(
    db: Engine, database_url: str, account: Account, caplog: pytest.LogCaptureFixture
) -> None:
    store = _StatFails()
    with TestClient(
        create_app(settings_for(database_url), object_store=store, processing_enabled=True),
    ) as client:
        upload_id = _pending_upload(client, account, store, AUDIO)
        with caplog.at_level(logging.ERROR):
            response = client.post(f"/v1/uploads/{upload_id}/complete", headers=account.headers)

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "storage_unavailable"
    assert response.headers["Retry-After"] == "30"
    assert any(r.getMessage() == "storage_error" for r in caplog.records)


def test_a_failed_cleanup_still_tells_the_phone_to_upload_again(
    db: Engine, database_url: str, account: Account, caplog: pytest.LogCaptureFixture
) -> None:
    store = _DeleteFails()
    with TestClient(
        create_app(settings_for(database_url), object_store=store, processing_enabled=True),
    ) as client:
        upload_id = _pending_upload(client, account, store, AUDIO + b"extra")
        with caplog.at_level(logging.ERROR):
            response = client.post(f"/v1/uploads/{upload_id}/complete", headers=account.headers)

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "upload_integrity_failed"
    assert any(r.getMessage() == "upload_integrity_cleanup_failed" for r in caplog.records)


def test_account_deletion_survives_a_storage_error_and_says_so(
    db: Engine, database_url: str, caplog: pytest.LogCaptureFixture
) -> None:
    class Failing(FakeObjectStore):
        async def delete_prefix(self, prefix: str) -> int:
            raise _client_error("SlowDown")

    account = make_account(db)
    app = create_app(settings_for(database_url), object_store=Failing())
    with TestClient(app) as client, caplog.at_level(logging.ERROR):
        response = client.delete("/v1/me", headers=account.headers)

    assert response.status_code == 503
    assert response.headers["Retry-After"] == "60"
    assert any(r.getMessage() == "account_delete_storage_failed" for r in caplog.records)
    with db.connect() as connection:
        assert (
            connection.scalar(
                text("SELECT count(*) FROM users WHERE id = :u"), {"u": account.user_id}
            )
            == 1
        )

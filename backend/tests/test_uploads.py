import hashlib
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.config import Settings
from app.main import create_app
from app.storage import StoredObject, audio_key
from tests.conftest import Account, FakeObjectStore, make_account, settings_for

AUDIO = b"\xff\xf1" + bytes(998)
SHA = hashlib.sha256(AUDIO).hexdigest()


@pytest.fixture
def store() -> FakeObjectStore:
    return FakeObjectStore()


@pytest.fixture
def client(db: Engine, database_url: str, store: FakeObjectStore) -> Iterator[TestClient]:
    settings = settings_for(database_url, daily_upload_bytes_quota=5_000, max_upload_bytes=2_000)
    app = create_app(settings, object_store=store, processing_enabled=True)
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def account(db: Engine) -> Account:
    # Protocol tests: full cloud needs no device key (phone modes are in test_deliveries).
    return make_account(db, storage_mode="full_cloud")


def clip(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "upload_id": str(uuid.uuid4()),
        "session_id": str(uuid.uuid4()),
        "segment_id": str(uuid.uuid4()),
        "offset_ms": 1_000,
        "duration_ms": 12_000,
        "byte_size": len(AUDIO),
        "sha256": SHA,
        "codec": "aac-lc-adts",
        "sample_rate_hz": 16_000,
        "channel_count": 1,
        "captured_at": "2026-09-25T14:03:00+02:00",
    }
    body.update(overrides)
    return body


def test_a_new_clip_gets_a_presigned_put(client: TestClient, account: Account) -> None:
    body = clip()

    response = client.post("/v1/uploads", json=body, headers=account.headers)

    assert response.status_code == 201
    data = response.json()
    assert data["state"] == "pending"
    assert data["upload"]["method"] == "PUT"
    key = audio_key(account.user_id, uuid.UUID(body["upload_id"]))
    assert key.startswith(f"users/{account.user_id}/")
    assert key in data["upload"]["url"]


def test_retrying_create_is_idempotent_and_reissues_the_url(
    client: TestClient,
    account: Account,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)

    again = client.post("/v1/uploads", json=body, headers=account.headers)

    assert again.status_code == 200
    assert again.json()["upload"] is not None


def test_same_id_with_different_bytes_is_a_hard_conflict(
    client: TestClient,
    account: Account,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)

    changed = client.post("/v1/uploads", json={**body, "sha256": "0" * 64}, headers=account.headers)

    assert changed.status_code == 409
    assert changed.json()["error"]["code"] == "idempotency_conflict"


def test_another_users_upload_id_is_indistinguishable_from_a_conflict(
    client: TestClient,
    account: Account,
    db: Engine,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    other = make_account(db, storage_mode="full_cloud")

    response = client.post("/v1/uploads", json=body, headers=other.headers)

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "idempotency_conflict"


def test_a_changed_capture_time_is_a_conflict_too(client: TestClient, account: Account) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)

    moved = {**body, "captured_at": "2026-09-25T14:04:00+02:00"}
    response = client.post("/v1/uploads", json=moved, headers=account.headers)

    assert response.status_code == 409


def test_source_timezone_is_optional_but_must_be_a_real_iana_zone(
    client: TestClient, account: Account
) -> None:
    rejected = client.post(
        "/v1/uploads", json=clip(source_timezone="Narnia/Cair_Paravel"), headers=account.headers
    )
    assert rejected.status_code == 422

    accepted = client.post(
        "/v1/uploads", json=clip(source_timezone="America/Chicago"), headers=account.headers
    )
    assert accepted.status_code == 201

    omitted = client.post(
        "/v1/uploads", json=clip(upload_id=str(uuid.uuid4())), headers=account.headers
    )
    assert omitted.status_code == 201


def test_a_changed_source_timezone_is_a_conflict_too(client: TestClient, account: Account) -> None:
    body = clip(source_timezone="America/Chicago")
    client.post("/v1/uploads", json=body, headers=account.headers)

    moved = {**body, "source_timezone": "America/New_York"}
    response = client.post("/v1/uploads", json=moved, headers=account.headers)

    assert response.status_code == 409


def test_a_pending_clip_recreated_under_a_new_id_replaces_the_old_one(
    client: TestClient,
    account: Account,
    db: Engine,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    new_id = str(uuid.uuid4())

    response = client.post(
        "/v1/uploads", json={**body, "upload_id": new_id}, headers=account.headers
    )

    assert response.status_code == 201
    assert response.json()["upload_id"] == new_id
    with db.connect() as connection:
        ids: list[str] = list(
            connection.execute(text("SELECT CAST(id AS text) FROM audio_uploads")).scalars()
        )
    assert ids == [new_id]


def test_a_received_clip_recreated_under_a_new_id_reports_the_existing_upload(
    client: TestClient,
    account: Account,
    store: FakeObjectStore,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    store.put(audio_key(account.user_id, uuid.UUID(body["upload_id"])), AUDIO)
    client.post(f"/v1/uploads/{body['upload_id']}/complete", headers=account.headers)

    again = client.post(
        "/v1/uploads",
        json={**body, "upload_id": str(uuid.uuid4())},
        headers=account.headers,
    )

    assert again.status_code == 200
    assert again.json() == {"upload_id": body["upload_id"], "state": "received", "upload": None}


def test_a_different_clip_at_the_same_position_is_a_conflict(
    client: TestClient,
    account: Account,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)

    other = {**body, "upload_id": str(uuid.uuid4()), "sha256": "a" * 64}
    response = client.post("/v1/uploads", json=other, headers=account.headers)

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "idempotency_conflict"


def test_complete_verifies_and_queues_transcription_once(
    client: TestClient,
    account: Account,
    store: FakeObjectStore,
    db: Engine,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    upload_id = uuid.UUID(body["upload_id"])
    store.put(audio_key(account.user_id, upload_id), AUDIO)

    first = client.post(f"/v1/uploads/{upload_id}/complete", headers=account.headers)
    second = client.post(f"/v1/uploads/{upload_id}/complete", headers=account.headers)

    assert first.json() == {"upload_id": str(upload_id), "state": "received", "upload": None}
    assert second.json()["state"] == "received"
    with db.connect() as connection:
        jobs = connection.execute(
            text("SELECT kind, state, user_id FROM processing_jobs WHERE subject_id = :id"),
            {"id": upload_id},
        ).all()
    assert [(j.kind, j.state, j.user_id) for j in jobs] == [
        ("transcribe", "queued", account.user_id),
    ]
    status = client.get(f"/v1/uploads/{upload_id}", headers=account.headers)
    assert status.json()["state"] == "received"
    assert status.json()["upload"] is None


def test_a_complete_that_races_a_recreate_under_a_new_id_is_a_404(
    client: TestClient,
    account: Account,
    store: FakeObjectStore,
    db: Engine,
) -> None:
    """The concurrent-complete branch when the row is gone entirely: the phone lost this id
    and a re-create replaced the row between the ownership check and the state move. The
    old row's id must 404 instead of crashing on a stale ORM instance."""
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    upload_id = uuid.UUID(body["upload_id"])
    store.put(audio_key(account.user_id, upload_id), AUDIO)

    real_stat = store.stat

    async def stat_that_replaces_the_row(key: str) -> StoredObject | None:
        with db.connect() as connection:
            connection.execute(text("DELETE FROM audio_uploads WHERE id = :id"), {"id": upload_id})
            connection.commit()
        return await real_stat(key)

    store.stat = stat_that_replaces_the_row  # type: ignore[method-assign]

    response = client.post(f"/v1/uploads/{upload_id}/complete", headers=account.headers)

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "not_found"


def test_a_complete_that_races_another_complete_reports_the_new_state(
    client: TestClient,
    account: Account,
    store: FakeObjectStore,
    db: Engine,
) -> None:
    """The concurrent-complete branch when the row survived but someone else moved it out of
    `pending`: the response reports what the row is now, not what this request saw."""
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    upload_id = uuid.UUID(body["upload_id"])
    store.put(audio_key(account.user_id, upload_id), AUDIO)

    real_stat = store.stat

    async def stat_that_completes_first(key: str) -> StoredObject | None:
        with db.connect() as connection:
            connection.execute(
                text("UPDATE audio_uploads SET state = 'received' WHERE id = :id"),
                {"id": upload_id},
            )
            connection.commit()
        return await real_stat(key)

    store.stat = stat_that_completes_first  # type: ignore[method-assign]

    response = client.post(f"/v1/uploads/{upload_id}/complete", headers=account.headers)

    assert response.status_code == 200
    assert response.json() == {"upload_id": str(upload_id), "state": "received", "upload": None}


def test_a_complete_does_not_report_a_row_that_changed_owners(
    client: TestClient,
    account: Account,
    store: FakeObjectStore,
    db: Engine,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    upload_id = uuid.UUID(body["upload_id"])
    store.put(audio_key(account.user_id, upload_id), AUDIO)
    other_account = make_account(db, storage_mode="full_cloud")

    real_stat = store.stat

    async def stat_that_changes_owners(key: str) -> StoredObject | None:
        with db.connect() as connection:
            connection.execute(
                text(
                    "UPDATE audio_uploads SET user_id = :user_id, state = 'received' WHERE id = :id"
                ),
                {"user_id": other_account.user_id, "id": upload_id},
            )
            connection.commit()
        return await real_stat(key)

    store.stat = stat_that_changes_owners  # type: ignore[method-assign]

    response = client.post(f"/v1/uploads/{upload_id}/complete", headers=account.headers)

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "not_found"


def test_complete_before_uploading_says_so(client: TestClient, account: Account) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)

    response = client.post(f"/v1/uploads/{body['upload_id']}/complete", headers=account.headers)

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "upload_missing"


@pytest.mark.parametrize(
    ("data", "sha"),
    [
        (AUDIO + b"x", None),  # wrong size
        (AUDIO, "f" * 64),  # wrong checksum
    ],
)
def test_bytes_that_dont_match_are_deleted_and_must_be_reuploaded(
    client: TestClient,
    account: Account,
    store: FakeObjectStore,
    data: bytes,
    sha: str | None,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    key = audio_key(account.user_id, uuid.UUID(body["upload_id"]))
    store.put(key, data, sha)

    response = client.post(f"/v1/uploads/{body['upload_id']}/complete", headers=account.headers)

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "upload_integrity_failed"
    assert key in store.deleted
    retry = client.post("/v1/uploads", json=body, headers=account.headers)
    assert retry.json()["state"] == "pending"


def test_a_store_without_checksums_fails_closed_without_a_reupload_loop(
    client: TestClient,
    account: Account,
    store: FakeObjectStore,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    key = audio_key(account.user_id, uuid.UUID(body["upload_id"]))
    store.put(key, AUDIO)
    store.report_checksum = False

    response = client.post(f"/v1/uploads/{body['upload_id']}/complete", headers=account.headers)

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "integrity_unverifiable"
    assert key in store.objects  # kept: the bytes are fine, the store is the problem
    status = client.get(f"/v1/uploads/{body['upload_id']}", headers=account.headers)
    assert status.json()["state"] == "pending"


def test_uploads_are_refused_while_nothing_can_process_them(
    db: Engine,
    database_url: str,
    store: FakeObjectStore,
    account: Account,
) -> None:
    with TestClient(create_app(settings_for(database_url), object_store=store)) as client:
        response = client.post("/v1/uploads", json=clip(), headers=account.headers)

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "processing_unavailable"


def test_uploads_are_invisible_to_other_users(
    client: TestClient,
    account: Account,
    db: Engine,
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    other = make_account(db, storage_mode="full_cloud")

    assert client.get(f"/v1/uploads/{body['upload_id']}", headers=other.headers).status_code == 404
    completed = client.post(f"/v1/uploads/{body['upload_id']}/complete", headers=other.headers)
    assert completed.status_code == 404


@pytest.mark.parametrize(
    ("override", "code"),
    [
        ({"byte_size": 2_001}, "upload_too_large"),
        ({"duration_ms": 30 * 60_000 + 1}, "upload_too_long"),
    ],
)
def test_oversized_clips_are_rejected(
    client: TestClient,
    account: Account,
    override: dict[str, int],
    code: str,
) -> None:
    response = client.post("/v1/uploads", json=clip(**override), headers=account.headers)

    assert response.status_code == 413
    assert response.json()["error"]["code"] == code


def test_daily_quota_is_enforced_per_user(client: TestClient, account: Account, db: Engine) -> None:
    for _ in range(5):
        created = client.post("/v1/uploads", json=clip(), headers=account.headers)
        assert created.status_code == 201

    over = client.post("/v1/uploads", json=clip(), headers=account.headers)
    fresh_user = client.post(
        "/v1/uploads",
        json=clip(),
        headers=make_account(db, storage_mode="full_cloud").headers,
    )

    assert over.status_code == 429
    assert over.json()["error"]["code"] == "quota_exceeded"
    assert fresh_user.status_code == 201


@pytest.mark.parametrize(
    "override",
    [
        {"sha256": "NOT-A-DIGEST"},
        {"codec": "mp3-secret"},
        {"captured_at": "2026-09-25T14:03:07"},  # no offset: ambiguous time
        {"offset_ms": -987_654},
        {"unexpected": "private words"},
    ],
)
def test_invalid_bodies_report_fields_not_values(
    client: TestClient,
    account: Account,
    override: dict[str, Any],
) -> None:
    response = client.post("/v1/uploads", json=clip(**override), headers=account.headers)

    assert response.status_code == 422
    assert str(next(iter(override.values()))) not in response.text


def test_uploads_need_a_store(db: Engine, database_url: str, account: Account) -> None:
    with TestClient(create_app(settings_for(database_url), processing_enabled=True)) as client:
        response = client.post("/v1/uploads", json=clip(), headers=account.headers)

    assert response.status_code == 503


def test_old_uploads_do_not_count_toward_todays_quota(
    client: TestClient,
    account: Account,
    db: Engine,
) -> None:
    for _ in range(5):
        client.post("/v1/uploads", json=clip(), headers=account.headers)
    with db.begin() as connection:
        connection.execute(
            text("UPDATE audio_uploads SET created_at = :at"),
            {"at": datetime.now(UTC) - timedelta(days=2)},
        )

    assert client.post("/v1/uploads", json=clip(), headers=account.headers).status_code == 201


def test_routes_needing_the_database_say_so_when_it_is_missing(store: FakeObjectStore) -> None:
    with TestClient(create_app(Settings(app_env="test"), object_store=store)) as client:
        response = client.get(
            f"/v1/uploads/{uuid.uuid4()}",
            headers={"Authorization": "Bearer adm_x"},
        )

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "service_unavailable"


def age_upload(db: Engine, upload_id: str, *, hours: float) -> None:
    with db.begin() as connection:
        connection.execute(
            text(
                "UPDATE audio_uploads SET created_at = now() - make_interval(secs => :s) "
                "WHERE id = :id"
            ),
            {"s": hours * 3600, "id": upload_id},
        )


def test_a_stale_pending_clip_is_not_given_a_url_that_outlives_the_sweep(
    client: TestClient, account: Account, db: Engine
) -> None:
    body = clip()
    assert client.post("/v1/uploads", json=body, headers=account.headers).status_code == 201
    age_upload(db, body["upload_id"], hours=2)

    status = client.get(f"/v1/uploads/{body['upload_id']}", headers=account.headers)

    assert status.status_code == 200
    assert status.json()["state"] == "pending"
    assert status.json()["upload"] is None


def test_retrying_create_for_a_stale_pending_clip_starts_a_fresh_window(
    client: TestClient, account: Account, db: Engine
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    age_upload(db, body["upload_id"], hours=2)

    again = client.post("/v1/uploads", json=body, headers=account.headers)

    assert again.status_code == 201
    assert again.json()["upload"] is not None
    with db.connect() as connection:
        age = connection.scalar(
            text("SELECT extract(epoch FROM now() - created_at) FROM audio_uploads")
        )
    assert age is not None
    assert float(age) < 3600


def test_replaced_clip_object_survives_when_the_replacement_is_rejected(
    client: TestClient, account: Account, db: Engine, store: FakeObjectStore
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    old_key = f"users/{account.user_id}/audio/{body['upload_id']}.aac"
    store.put(old_key, AUDIO)
    # The old row now fills the daily quota, so the replacement (same clip, new id) is
    # refused and rolled back after the old row was staged for deletion.
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO audio_uploads (id, user_id, session_id, segment_id, offset_ms, "
                "duration_ms, byte_size, sha256, codec, sample_rate_hz, channel_count, "
                "captured_at, state, object_key) SELECT gen_random_uuid(), user_id, session_id, "
                "gen_random_uuid(), 0, 1000, 4990, sha256, codec, sample_rate_hz, channel_count, "
                "captured_at, 'received', 'users/x/audio/filler.aac' FROM audio_uploads"
            )
        )
    same_clip_new_id = {**body, "upload_id": str(uuid.uuid4())}
    response = client.post("/v1/uploads", json=same_clip_new_id, headers=account.headers)

    assert response.status_code == 429
    assert old_key in store.objects
    assert old_key not in store.deleted


def test_replaced_pending_clip_object_is_deleted_after_commit(
    client: TestClient, account: Account, store: FakeObjectStore
) -> None:
    body = clip()
    client.post("/v1/uploads", json=body, headers=account.headers)
    old_key = f"users/{account.user_id}/audio/{body['upload_id']}.aac"
    store.put(old_key, AUDIO)

    response = client.post(
        "/v1/uploads", json={**body, "upload_id": str(uuid.uuid4())}, headers=account.headers
    )

    assert response.status_code == 201
    assert old_key in store.deleted


def test_a_create_that_keeps_losing_the_insert_race_is_a_409(
    client: TestClient, account: Account, monkeypatch: pytest.MonkeyPatch
) -> None:
    from sqlalchemy.exc import IntegrityError

    import app.api.uploads as uploads_module

    attempts = 0

    async def always_conflicts(*args: object, **kwargs: object) -> object:
        nonlocal attempts
        attempts += 1
        raise IntegrityError("insert", {}, Exception("duplicate"))

    monkeypatch.setattr(uploads_module, "_create", always_conflicts)

    response = client.post("/v1/uploads", json=clip(), headers=account.headers)

    assert response.status_code == 409
    assert attempts == 2  # one retry, then give up

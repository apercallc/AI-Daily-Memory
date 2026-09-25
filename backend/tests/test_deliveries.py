"""ADR-0014: for phone storage modes the server seals each transcript to the phone and keeps
no plaintext; the phone collects, acknowledges, and the server copy is deleted."""

import base64
import json
import uuid
from collections.abc import AsyncIterator, Iterator
from datetime import UTC, datetime, timedelta

import pytest
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.db import Database
from app.jobs import enqueue
from app.main import create_app
from app.retention import sweep
from app.sealing import SUITE, info_for, public_key, seal
from app.storage import audio_key
from app.transcription import TranscribedSpan, TranscribeHandler
from app.worker import Worker
from tests.conftest import Account, FakeObjectStore, make_account, settings_for
from tests.test_transcription import FakeProvider

NOW = datetime(2026, 9, 25, 12, 0, tzinfo=UTC)
AUDIO = b"\xff\xf1" + bytes(100)


def raw_public(key: X25519PrivateKey) -> bytes:
    return key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


@pytest.fixture
def store() -> FakeObjectStore:
    return FakeObjectStore()


@pytest.fixture
def client(db: Engine, database_url: str, store: FakeObjectStore) -> Iterator[TestClient]:
    app = create_app(settings_for(database_url), object_store=store, processing_enabled=True)
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def phone(db: Engine) -> Account:
    return make_account(db)  # storage_mode defaults to phone_only


def register(client: TestClient, account: Account, key: X25519PrivateKey) -> str:
    body = {"public_key": base64.b64encode(raw_public(key)).decode()}
    response = client.put("/v1/device-key", json=body, headers=account.headers)
    assert response.status_code == 200
    key_id: str = response.json()["key_id"]
    return key_id


def clip() -> dict[str, object]:
    import hashlib

    return {
        "upload_id": str(uuid.uuid4()),
        "session_id": str(uuid.uuid4()),
        "segment_id": str(uuid.uuid4()),
        "offset_ms": 0,
        "duration_ms": 3_000,
        "byte_size": len(AUDIO),
        "sha256": hashlib.sha256(AUDIO).hexdigest(),
        "codec": "aac-lc-adts",
        "sample_rate_hz": 16_000,
        "channel_count": 1,
        "captured_at": "2026-09-25T14:03:00+02:00",
    }


def test_phone_mode_uploads_need_a_device_key_first(client: TestClient, phone: Account) -> None:
    response = client.post("/v1/uploads", json=clip(), headers=phone.headers)

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "device_key_required"


def test_full_cloud_uploads_need_no_device_key(
    client: TestClient,
    phone: Account,
    db: Engine,
) -> None:
    with db.begin() as connection:
        connection.execute(text("UPDATE users SET storage_mode = 'full_cloud'"))

    assert client.post("/v1/uploads", json=clip(), headers=phone.headers).status_code == 201


def test_registering_the_same_key_twice_is_idempotent(client: TestClient, phone: Account) -> None:
    key = X25519PrivateKey.generate()

    assert register(client, phone, key) == register(client, phone, key)


@pytest.mark.parametrize("bad", ["not base64!!" * 4, base64.b64encode(b"short").decode() * 8])
def test_only_x25519_public_keys_are_accepted(
    client: TestClient,
    phone: Account,
    bad: str,
) -> None:
    response = client.put("/v1/device-key", json={"public_key": bad}, headers=phone.headers)

    assert response.status_code == 422


@pytest.fixture
async def database(db: Engine, database_url: str) -> AsyncIterator[Database]:
    database = Database(database_url)
    yield database
    await database.dispose()


async def transcribe_one(
    client: TestClient,
    phone: Account,
    store: FakeObjectStore,
    database: Database,
) -> str:
    body = clip()
    assert client.post("/v1/uploads", json=body, headers=phone.headers).status_code == 201
    upload_id = uuid.UUID(str(body["upload_id"]))
    store.put(audio_key(phone.user_id, upload_id), AUDIO)
    complete = client.post(f"/v1/uploads/{upload_id}/complete", headers=phone.headers)
    assert complete.json()["state"] == "received"
    handler = TranscribeHandler(database.sessions, store, FakeProvider())
    worker = Worker(database, {"transcribe": handler}, worker_id="w")
    assert await worker.run_once()
    return str(upload_id)


@pytest.mark.anyio
async def test_a_phone_mode_transcript_is_sealed_and_never_stored_in_the_clear(
    client: TestClient,
    phone: Account,
    store: FakeObjectStore,
    database: Database,
    db: Engine,
) -> None:
    key = X25519PrivateKey.generate()
    register(client, phone, key)

    upload_id = await transcribe_one(client, phone, store, database)

    with db.connect() as connection:
        assert connection.execute(text("SELECT count(*) FROM transcripts")).scalar() == 0
        assert connection.execute(text("SELECT count(*) FROM transcript_segments")).scalar() == 0
        sealed: bytes = connection.execute(
            text("SELECT ciphertext FROM sealed_deliveries"),
        ).scalar_one()
    assert b"Friday" not in bytes(sealed)
    assert store.objects == {}  # temporary audio deleted after sealing

    page = client.get("/v1/deliveries", headers=phone.headers).json()["deliveries"]
    assert [d["upload_id"] for d in page] == [upload_id]
    opened = SUITE.decrypt(
        base64.b64decode(page[0]["ciphertext"]),
        key,
        info=info_for(uuid.UUID(upload_id)),
    )
    payload = json.loads(opened)
    assert payload["v"] == 1
    assert payload["upload_id"] == upload_id
    assert [s["text"] for s in payload["spans"]] == ["Let's ship on Friday.", "I'll send the deck."]


@pytest.mark.anyio
async def test_a_sealed_transcript_only_opens_for_its_own_upload(
    client: TestClient,
    phone: Account,
    store: FakeObjectStore,
    database: Database,
) -> None:
    key = X25519PrivateKey.generate()
    register(client, phone, key)
    await transcribe_one(client, phone, store, database)
    delivery = client.get("/v1/deliveries", headers=phone.headers).json()["deliveries"][0]

    with pytest.raises(Exception, match=r".*"):
        SUITE.decrypt(base64.b64decode(delivery["ciphertext"]), key, info=info_for(uuid.uuid4()))


@pytest.mark.anyio
async def test_acknowledging_deletes_the_server_copy_and_is_idempotent(
    client: TestClient,
    phone: Account,
    store: FakeObjectStore,
    database: Database,
    db: Engine,
) -> None:
    register(client, phone, X25519PrivateKey.generate())
    upload_id = await transcribe_one(client, phone, store, database)
    delivery_id = client.get("/v1/deliveries", headers=phone.headers).json()["deliveries"][0][
        "delivery_id"
    ]

    first = client.post(
        "/v1/deliveries/ack", json={"delivery_ids": [delivery_id]}, headers=phone.headers
    )
    again = client.post(
        "/v1/deliveries/ack", json={"delivery_ids": [delivery_id]}, headers=phone.headers
    )

    assert first.json() == {"acknowledged": 1}
    assert again.json() == {"acknowledged": 0}
    assert client.get("/v1/deliveries", headers=phone.headers).json() == {"deliveries": []}
    status = client.get(f"/v1/uploads/{upload_id}", headers=phone.headers).json()
    assert status["state"] == "delivered"
    with db.connect() as connection:
        assert connection.execute(text("SELECT count(*) FROM sealed_deliveries")).scalar() == 0


@pytest.mark.anyio
async def test_other_devices_and_users_never_see_or_ack_a_delivery(
    client: TestClient,
    phone: Account,
    store: FakeObjectStore,
    database: Database,
    db: Engine,
) -> None:
    register(client, phone, X25519PrivateKey.generate())
    await transcribe_one(client, phone, store, database)
    delivery_id = client.get("/v1/deliveries", headers=phone.headers).json()["deliveries"][0][
        "delivery_id"
    ]
    stranger = make_account(db)
    register(client, stranger, X25519PrivateKey.generate())
    no_key = make_account(db)

    assert client.get("/v1/deliveries", headers=stranger.headers).json() == {"deliveries": []}
    assert client.get("/v1/deliveries", headers=no_key.headers).json() == {"deliveries": []}
    ack = {"delivery_ids": [delivery_id]}
    assert client.post("/v1/deliveries/ack", json=ack, headers=stranger.headers).json() == {
        "acknowledged": 0,
    }
    assert client.post("/v1/deliveries/ack", json=ack, headers=no_key.headers).json() == {
        "acknowledged": 0,
    }
    assert len(client.get("/v1/deliveries", headers=phone.headers).json()["deliveries"]) == 1


@pytest.mark.anyio
async def test_rotating_the_key_drops_unreadable_copies_and_frees_the_clip_for_reupload(
    client: TestClient,
    phone: Account,
    store: FakeObjectStore,
    database: Database,
) -> None:
    register(client, phone, X25519PrivateKey.generate())
    upload_id = await transcribe_one(client, phone, store, database)

    register(client, phone, X25519PrivateKey.generate())

    assert client.get("/v1/deliveries", headers=phone.headers).json() == {"deliveries": []}
    status = client.get(f"/v1/uploads/{upload_id}", headers=phone.headers).json()
    assert status["state"] == "failed"


@pytest.mark.anyio
async def test_uncollected_deliveries_expire_and_the_clip_can_be_uploaded_again(
    client: TestClient,
    phone: Account,
    store: FakeObjectStore,
    database: Database,
    db: Engine,
) -> None:
    register(client, phone, X25519PrivateKey.generate())
    await transcribe_one(client, phone, store, database)
    with db.begin() as connection:
        body = dict(connection.execute(text("SELECT * FROM audio_uploads")).mappings().one())

    result = await sweep(
        database.sessions,
        store,
        url_ttl_seconds=900,
        now=datetime.now(UTC) + timedelta(days=8),
    )

    assert result.deliveries_expired == 1
    again = clip()
    again.update(
        segment_id=str(body["segment_id"]),
        session_id=str(body["session_id"]),
        captured_at=body["captured_at"].isoformat(),
    )
    response = client.post("/v1/uploads", json=again, headers=phone.headers)
    assert response.status_code == 201
    assert response.json()["upload_id"] == again["upload_id"]


@pytest.mark.anyio
async def test_transcription_without_any_device_key_dead_letters(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    user = make_account(db).user_id
    upload_id = uuid.uuid4()
    key = audio_key(user, upload_id)
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO audio_uploads (id, user_id, session_id, segment_id, offset_ms, "
                "duration_ms, byte_size, sha256, codec, sample_rate_hz, channel_count, "
                "captured_at, state, object_key) VALUES (:id, :u, :s, :g, 0, 3000, 102, :h, "
                "'aac-lc-adts', 16000, 1, :at, 'received', :k)",
            ),
            {
                "id": upload_id,
                "u": user,
                "s": uuid.uuid4(),
                "g": uuid.uuid4(),
                "h": "0" * 64,
                "at": NOW,
                "k": key,
            },
        )
    store.put(key, AUDIO)
    async with database.sessions() as session:
        await enqueue(
            session,
            user_id=user,
            kind="transcribe",
            subject_id=upload_id,
            idempotency_key=f"transcribe:{upload_id}",
        )
        await session.commit()
    handler = TranscribeHandler(database.sessions, store, FakeProvider())

    await Worker(database, {"transcribe": handler}, worker_id="w").run_once()

    with db.connect() as connection:
        row = connection.execute(text("SELECT state, failure_code FROM audio_uploads")).one()
    assert tuple(row) == ("failed", "device_key_missing")


def test_seal_rejects_malformed_public_keys() -> None:
    with pytest.raises(ValueError, match="32 bytes"):
        public_key(b"short")
    with pytest.raises(ValueError, match="32 bytes"):
        seal(b"x" * 31, uuid.uuid4(), {})


def test_spans_survive_the_round_trip_with_unicode() -> None:
    key = X25519PrivateKey.generate()
    upload_id = uuid.uuid4()
    span = TranscribedSpan(0, 10, "Café — über", 0.5, "A")

    sealed = seal(raw_public(key), upload_id, {"spans": [span.text]})

    assert json.loads(SUITE.decrypt(sealed, key, info=info_for(upload_id))) == {
        "spans": [span.text],
    }

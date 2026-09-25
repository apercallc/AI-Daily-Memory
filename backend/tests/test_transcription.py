import asyncio
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from datetime import UTC, datetime

import pytest
from sqlalchemy import Engine, text

from app.config import Settings
from app.db import Database
from app.jobs import ClaimedJob, enqueue
from app.storage import audio_key
from app.transcription import (
    JobError,
    TranscribedSpan,
    TranscribeHandler,
    TranscriptResult,
    validate,
)
from app.worker import Worker, handlers_for
from tests.conftest import FakeObjectStore, make_account

pytestmark = pytest.mark.anyio

NOW = datetime(2026, 9, 25, 12, 0, tzinfo=UTC)
AUDIO = b"\xff\xf1" + bytes(100)


@dataclass
class FakeProvider:
    name: str = "fake"
    model: str = "fake-1"
    spans: list[TranscribedSpan] = field(
        default_factory=lambda: [
            TranscribedSpan(0, 1_500, "Let's ship on Friday.", 0.93, "A"),
            TranscribedSpan(1_600, 3_000, "I'll send the deck.", 0.88, "B"),
        ],
    )
    error: JobError | None = None
    delay: float = 0.0
    calls: int = 0

    async def transcribe(
        self, audio: bytes, *, codec: str, sample_rate_hz: int
    ) -> TranscriptResult:
        self.calls += 1
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        return TranscriptResult(language="en", spans=self.spans)


@pytest.fixture
async def database(db: Engine, database_url: str) -> AsyncIterator[Database]:
    database = Database(database_url)
    yield database
    await database.dispose()


@pytest.fixture
def store() -> FakeObjectStore:
    return FakeObjectStore()


async def received_upload(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
    *,
    storage_mode: str = "full_cloud",
) -> uuid.UUID:
    user = make_account(db).user_id
    with db.begin() as connection:
        connection.execute(
            text("UPDATE users SET storage_mode = :mode WHERE id = :id"),
            {"mode": storage_mode, "id": user},
        )
    upload_id = uuid.uuid4()
    key = audio_key(user, upload_id)
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO audio_uploads (id, user_id, session_id, segment_id, offset_ms, "
                "duration_ms, byte_size, sha256, codec, sample_rate_hz, channel_count, "
                "captured_at, state, object_key) VALUES (:id, :user, :s, :seg, 0, 3000, 102, "
                ":sha, 'aac-lc-adts', 16000, 1, :at, 'received', :key)",
            ),
            {
                "id": upload_id,
                "user": user,
                "s": uuid.uuid4(),
                "seg": uuid.uuid4(),
                "sha": "0" * 64,
                "at": NOW,
                "key": key,
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
            max_attempts=2,
        )
        await session.execute(text("UPDATE processing_jobs SET run_after = :t"), {"t": NOW})
        await session.commit()
    return upload_id


def audio_key_of(db: Engine, upload_id: uuid.UUID) -> str:
    with db.connect() as connection:
        key: str = connection.execute(
            text("SELECT object_key FROM audio_uploads WHERE id = :id"),
            {"id": upload_id},
        ).scalar_one()
    return key


def worker(database: Database, store: FakeObjectStore, provider: FakeProvider) -> Worker:
    handler = TranscribeHandler(database.sessions, store, provider, timeout_seconds=0.2)
    return Worker(database, {"transcribe": handler}, worker_id="w", now=lambda: NOW)


def state(db: Engine) -> tuple[str, str]:
    with db.connect() as connection:
        row = connection.execute(
            text("SELECT u.state, j.state AS job FROM audio_uploads u, processing_jobs j"),
        ).one()
    return row.state, row.job


async def test_a_transcript_is_stored_as_evidence_and_the_audio_deleted(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    upload_id = await received_upload(database, db, store)

    assert await worker(database, store, FakeProvider()).run_once()

    assert state(db) == ("transcribed", "succeeded")
    with db.connect() as connection:
        transcript = connection.execute(
            text(
                "SELECT t.revision, u.active_transcript_id = t.id AS active, t.provider, "
                "t.model, t.language FROM transcripts t JOIN audio_uploads u ON u.id = t.upload_id",
            ),
        ).one()
        spans = connection.execute(
            text("SELECT start_ms, text, speaker FROM transcript_segments ORDER BY start_ms"),
        ).all()
    assert tuple(transcript) == (1, True, "fake", "fake-1", "en")
    assert [tuple(s) for s in spans] == [
        (0, "Let's ship on Friday.", "A"),
        (1_600, "I'll send the deck.", "B"),
    ]
    assert audio_key_of(db, upload_id) in store.deleted
    assert store.objects == {}


async def test_nothing_runnable_returns_false(database: Database, store: FakeObjectStore) -> None:
    assert not await worker(database, store, FakeProvider()).run_once()
    assert not await Worker(database, {}).run_once()


async def test_a_provider_timeout_is_retried_then_dead_lettered(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    await received_upload(database, db, store)
    slow = FakeProvider(delay=1.0)

    await worker(database, store, slow).run_once()

    with db.connect() as connection:
        job = connection.execute(text("SELECT state, last_error_code FROM processing_jobs")).one()
    assert tuple(job) == ("queued", "provider_timeout")
    assert state(db)[0] == "received"
    assert store.objects  # audio kept for the retry


async def test_invalid_provider_output_is_never_stored(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    await received_upload(database, db, store)
    bad = FakeProvider(spans=[TranscribedSpan(0, 99_000, "past the end of the clip")])

    await worker(database, store, bad).run_once()

    assert state(db) == ("failed", "dead")
    with db.connect() as connection:
        assert connection.execute(text("SELECT count(*) FROM transcripts")).scalar() == 0
        code: str = connection.execute(text("SELECT failure_code FROM audio_uploads")).scalar_one()
    assert code == "invalid_provider_result"
    assert store.objects == {}  # a clip that failed for good doesn't linger (ADR-0003)


async def test_a_crash_retries_as_unexpected(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    await received_upload(database, db, store)

    class Boom(FakeProvider):
        async def transcribe(
            self,
            audio: bytes,
            *,
            codec: str,
            sample_rate_hz: int,
        ) -> TranscriptResult:
            raise RuntimeError("provider sdk bug")

    await worker(database, store, Boom()).run_once()

    with db.connect() as connection:
        job = connection.execute(text("SELECT state, last_error_code FROM processing_jobs")).one()
    assert tuple(job) == ("queued", "unexpected_error")


async def test_missing_audio_or_upload_is_permanent(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    await received_upload(database, db, store)
    store.objects.clear()

    await worker(database, store, FakeProvider()).run_once()
    assert state(db) == ("failed", "dead")

    with db.begin() as connection:
        connection.execute(text("DELETE FROM audio_uploads"))
        connection.execute(
            text(
                "INSERT INTO processing_jobs (id, user_id, kind, idempotency_key, subject_id, "
                "run_after) SELECT gen_random_uuid(), id, 'transcribe', 'k2', gen_random_uuid(), "
                ":t FROM users",
            ),
            {"t": NOW},
        )
    await worker(database, store, FakeProvider()).run_once()
    with db.connect() as connection:
        codes = connection.execute(
            text("SELECT last_error_code FROM processing_jobs WHERE idempotency_key = 'k2'"),
        ).scalar()
    assert codes == "upload_missing"


async def test_rerun_after_transcription_only_finishes_the_cleanup(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    await received_upload(database, db, store)
    provider = FakeProvider()
    handler = TranscribeHandler(database.sessions, store, provider)
    async with database.sessions() as session:
        job_id, user_id, subject = (
            await session.execute(text("SELECT id, user_id, subject_id FROM processing_jobs"))
        ).one()
    job = ClaimedJob(job_id, user_id, "transcribe", subject, 1, 2, "w")
    await handler(job)
    await handler(job)

    assert provider.calls == 1
    with db.connect() as connection:
        assert connection.execute(text("SELECT count(*) FROM transcripts")).scalar() == 1


@pytest.mark.parametrize(
    "span",
    [
        TranscribedSpan(-1, 10, "x"),
        TranscribedSpan(10, 5, "x"),
        TranscribedSpan(0, 4_001, "x"),
        TranscribedSpan(0, 10, "x", confidence=1.5),
    ],
)
async def test_validate_rejects_corrupting_spans(span: TranscribedSpan) -> None:
    with pytest.raises(JobError) as caught:
        validate(TranscriptResult("en", [span]), duration_ms=3_000)
    assert caught.value.code == "invalid_provider_result"
    assert not caught.value.retryable


async def test_validate_rejects_out_of_order_spans() -> None:
    spans = [TranscribedSpan(1_000, 1_200, "b"), TranscribedSpan(0, 500, "a")]
    with pytest.raises(JobError):
        validate(TranscriptResult(None, spans), duration_ms=3_000)


async def test_transcription_stays_disabled_until_gate_b() -> None:
    assert handlers_for(Settings(app_env="test")) == {}


async def test_run_forever_stops_when_asked(database: Database, store: FakeObjectStore) -> None:
    stop = asyncio.Event()
    runner = asyncio.create_task(
        worker(database, store, FakeProvider()).run_forever(stop, idle_seconds=0.01),
    )
    await asyncio.sleep(0.05)
    stop.set()
    await asyncio.wait_for(runner, timeout=2)


async def test_users_not_in_full_cloud_are_never_transcribed_on_the_server(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    await received_upload(database, db, store, storage_mode="phone_only")
    provider = FakeProvider()

    await worker(database, store, provider).run_once()

    assert provider.calls == 0
    assert state(db) == ("failed", "dead")
    with db.connect() as connection:
        assert connection.execute(text("SELECT count(*) FROM transcripts")).scalar() == 0


async def test_a_slow_store_read_is_retried(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    await received_upload(database, db, store)

    class SlowStore(FakeObjectStore):
        async def read(self, key: str) -> bytes:
            await asyncio.sleep(1)
            return b""

    slow = SlowStore(objects=store.objects)
    handler = TranscribeHandler(database.sessions, slow, FakeProvider(), read_timeout_seconds=0.05)
    await Worker(database, {"transcribe": handler}, worker_id="w", now=lambda: NOW).run_once()

    with db.connect() as connection:
        job = connection.execute(text("SELECT state, last_error_code FROM processing_jobs")).one()
    assert tuple(job) == ("queued", "store_timeout")


async def test_a_failing_dead_letter_hook_is_logged_not_raised(
    database: Database,
    db: Engine,
    store: FakeObjectStore,
) -> None:
    await received_upload(database, db, store)

    class BrokenDelete(FakeObjectStore):
        async def delete(self, key: str) -> None:
            raise RuntimeError("store down")

    broken = BrokenDelete(objects=store.objects)
    bad = FakeProvider(spans=[TranscribedSpan(10, 5, "reversed")])
    handler = TranscribeHandler(database.sessions, broken, bad)
    await Worker(database, {"transcribe": handler}, worker_id="w", now=lambda: NOW).run_once()

    assert state(db) == ("failed", "dead")  # the sweep deletes the audio later


async def test_the_loop_survives_a_failing_iteration(database: Database) -> None:
    calls = 0

    async def flaky_sweep() -> None:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("database blip")

    stop = asyncio.Event()
    looping = Worker(database, {}, sweeper=flaky_sweep, sweep_every_seconds=0)
    runner = asyncio.create_task(looping.run_forever(stop, idle_seconds=0.01))
    await asyncio.sleep(0.1)
    stop.set()
    await asyncio.wait_for(runner, timeout=2)

    assert calls >= 2


async def test_sweeps_run_at_most_once_per_interval(database: Database) -> None:
    calls = 0

    async def count() -> None:
        nonlocal calls
        calls += 1

    looping = Worker(database, {}, sweeper=count, sweep_every_seconds=3_600)
    await looping._maybe_sweep()
    await looping._maybe_sweep()

    assert calls == 1

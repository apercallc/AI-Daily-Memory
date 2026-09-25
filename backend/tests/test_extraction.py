import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from typing import cast

import pytest
from sqlalchemy import Engine, select, text

from app.db import Database
from app.db.models import (
    AudioUpload,
    DailyReportRow,
    MemoryItem,
    MemoryItemEvidence,
    Transcript,
    TranscriptSegment,
)
from app.extraction import (
    ExtractedItem,
    ExtractHandler,
    ExtractionResult,
    Numbered,
    validate,
)
from app.jobs import ClaimedJob, enqueue
from app.transcription import JobError
from app.worker import Worker
from tests.conftest import make_account

pytestmark = pytest.mark.anyio

NOW = datetime(2026, 9, 25, 12, 0, tzinfo=UTC)


@dataclass
class FakeExtractor:
    name: str = "fake"
    model: str = "fake-1"
    items: tuple[ExtractedItem, ...] = field(default_factory=tuple)
    error: JobError | None = None
    calls: int = 0
    seen: tuple[Numbered, ...] | None = None

    async def extract(self, lines: tuple[Numbered, ...]) -> ExtractionResult:
        self.calls += 1
        self.seen = lines
        if self.error:
            raise self.error
        return ExtractionResult(items=self.items)


@pytest.fixture
async def database(db: Engine, database_url: str) -> AsyncIterator[Database]:
    database = Database(database_url)
    yield database
    await database.dispose()


async def transcribed_session(
    database: Database,
    db: Engine,
    *,
    storage_mode: str = "full_cloud",
    clips: int = 1,
    lines: tuple[tuple[int, str, str | None], ...] = ((0, "Let's ship Friday.", "A"),),
) -> tuple[uuid.UUID, uuid.UUID]:
    """[clips] transcribed uploads sharing one session, each carrying [lines]."""
    user = make_account(db).user_id
    with db.begin() as connection:
        connection.execute(
            text("UPDATE users SET storage_mode = :m WHERE id = :id"),
            {"m": storage_mode, "id": user},
        )
    session_id = uuid.uuid4()
    async with database.sessions() as s:
        for i in range(clips):
            upload = AudioUpload(
                id=uuid.uuid4(),
                user_id=user,
                session_id=session_id,
                segment_id=uuid.uuid4(),
                offset_ms=0,
                duration_ms=3_000,
                byte_size=102,
                sha256="0" * 64,
                codec="aac-lc-adts",
                sample_rate_hz=16_000,
                channel_count=1,
                captured_at=NOW + timedelta(minutes=i),
                state="transcribed",
                object_key=f"users/{user}/audio/{i}.aac",
            )
            s.add(upload)
            await s.flush()
            transcript = Transcript(
                user_id=user,
                upload_id=upload.id,
                revision=1,
                provider="fake",
                model="fake-1",
                language="en",
            )
            s.add(transcript)
            await s.flush()
            for start_ms, text_, speaker in lines:
                s.add(
                    TranscriptSegment(
                        user_id=user,
                        transcript_id=transcript.id,
                        start_ms=start_ms,
                        end_ms=start_ms + 500,
                        text=text_,
                        speaker=speaker,
                        confidence=0.9,
                    ),
                )
            upload.active_transcript_id = transcript.id
        await s.commit()
    return user, session_id


def make_handler(
    database: Database, provider: FakeExtractor, *, version: int = 1
) -> ExtractHandler:
    return ExtractHandler(database.sessions, provider, version=version)


async def run_extraction(
    database: Database,
    provider: FakeExtractor,
    user_id: uuid.UUID,
    session_id: uuid.UUID,
    **kw: int,
) -> None:
    job = ClaimedJob(
        id=uuid.uuid4(),
        user_id=user_id,
        kind="extract",
        subject_id=session_id,
        attempts=1,
        max_attempts=5,
        lease_owner="w",
    )
    await make_handler(database, provider, **kw)(job)


def action(**overrides: object) -> ExtractedItem:
    base: dict[str, object] = {
        "kind": "action",
        "text": "Send the deck",
        "confidence": "likely",
        "evidence_segment_indices": (0,),
        "owner": "me",
    }
    base.update(overrides)
    return ExtractedItem(**base)  # type: ignore[arg-type]


def commitment(**overrides: object) -> ExtractedItem:
    base: dict[str, object] = {
        "kind": "commitment",
        "text": "Send the deck by Friday",
        "confidence": "confirmed",
        "evidence_segment_indices": (0,),
        "direction": "i_owe",
        "counterpart": "Sam",
    }
    base.update(overrides)
    return ExtractedItem(**base)  # type: ignore[arg-type]


# --- validate() -------------------------------------------------------------------------------


def test_validate_accepts_well_formed_items() -> None:
    validate(ExtractionResult(items=(action(), commitment())), segment_count=1)


@pytest.mark.parametrize(
    "item",
    [
        action(kind="not-a-kind"),
        action(confidence="sure"),
        action(text=""),
        action(text="x" * 281),
        action(evidence_segment_indices=()),
        action(evidence_segment_indices=(5,)),
        action(evidence_segment_indices=(-1,)),
        commitment(direction=None),
        commitment(counterpart=None),
        commitment(direction="sideways"),
        action(direction="i_owe"),  # a non-commitment must not carry commitment fields
        action(counterpart="Sam"),
    ],
)
def test_validate_rejects_malformed_items(item: ExtractedItem) -> None:
    with pytest.raises(JobError) as caught:
        validate(ExtractionResult(items=(item,)), segment_count=1)
    assert caught.value.code == "invalid_extraction_result"
    assert not caught.value.retryable


def test_validate_rejects_too_many_items() -> None:
    items = tuple(action(text=f"item {i}") for i in range(51))
    with pytest.raises(JobError):
        validate(ExtractionResult(items=items), segment_count=1)


# --- ExtractHandler -----------------------------------------------------------------------------


async def test_extraction_persists_items_with_evidence_and_builds_the_report(
    database: Database, db: Engine
) -> None:
    user, session_id = await transcribed_session(
        database, db, lines=((0, "Let's ship Friday.", "A"), (600, "I'll send the deck.", "B"))
    )
    provider = FakeExtractor(items=(action(evidence_segment_indices=(1,)), commitment()))

    await run_extraction(database, provider, user, session_id)

    async with database.sessions() as s:
        items = (await s.scalars(select(MemoryItem).where(MemoryItem.user_id == user))).all()
        assert {i.kind for i in items} == {"action", "commitment"}
        evidence = (
            await s.scalars(
                select(MemoryItemEvidence).where(
                    MemoryItemEvidence.memory_item_id.in_([i.id for i in items]),
                ),
            )
        ).all()
        assert len(evidence) == 2
        report = await s.scalar(
            select(DailyReportRow).where(DailyReportRow.user_id == user),
        )
        assert report is not None
        assert report.memory_day == date(2026, 9, 25)
        assert len(cast(list[object], report.report["action_items"])) == 1
        assert len(cast(list[object], report.report["commitments"])) == 1


async def test_audio_still_pending_elsewhere_that_day_makes_the_report_partial(
    database: Database, db: Engine
) -> None:
    user, session_id = await transcribed_session(database, db)
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO audio_uploads (id, user_id, session_id, segment_id, offset_ms, "
                "duration_ms, byte_size, sha256, codec, sample_rate_hz, channel_count, "
                "captured_at, state, object_key) VALUES (:id, :user, :s, :seg, 0, 60000, 102, "
                ":sha, 'aac-lc-adts', 16000, 1, :at, 'received', :key)",
            ),
            {
                "id": uuid.uuid4(),
                "user": user,
                "s": uuid.uuid4(),
                "seg": uuid.uuid4(),
                "sha": "1" * 64,
                "at": NOW,
                "key": "pending-clip",
            },
        )

    await run_extraction(database, FakeExtractor(items=(action(),)), user, session_id)

    async with database.sessions() as s:
        report = await s.scalar(select(DailyReportRow).where(DailyReportRow.user_id == user))
    assert report is not None
    assert report.status == "partial"
    assert report.report["status"] == "partial"
    coverage = cast(dict[str, object], report.report["coverage"])
    assert coverage["pending_minutes"] == 1


async def test_the_provider_sees_every_clips_lines_in_order(database: Database, db: Engine) -> None:
    user, session_id = await transcribed_session(
        database,
        db,
        clips=2,
        lines=((0, "first clip line.", "A"),),
    )
    provider = FakeExtractor()

    await run_extraction(database, provider, user, session_id)

    assert provider.seen is not None
    assert [line.index for line in provider.seen] == [0, 1]
    assert [line.text for line in provider.seen] == ["first clip line.", "first clip line."]


async def test_non_full_cloud_accounts_are_never_extracted(database: Database, db: Engine) -> None:
    user, session_id = await transcribed_session(database, db, storage_mode="reports_cloud")
    provider = FakeExtractor(items=(action(),))

    with pytest.raises(JobError) as caught:
        await run_extraction(database, provider, user, session_id)
    assert caught.value.code == "not_full_cloud"
    assert not caught.value.retryable
    assert provider.calls == 0


async def test_a_session_with_nothing_transcribed_yet_is_a_permanent_failure(
    database: Database, db: Engine
) -> None:
    user = make_account(db, storage_mode="full_cloud").user_id

    with pytest.raises(JobError) as caught:
        await run_extraction(database, FakeExtractor(), user, uuid.uuid4())
    assert caught.value.code == "nothing_transcribed_yet"


async def test_a_transcript_with_no_segments_is_treated_as_nothing_transcribed_yet(
    database: Database, db: Engine
) -> None:
    user = make_account(db, storage_mode="full_cloud").user_id
    session_id = uuid.uuid4()
    async with database.sessions() as s:
        upload = AudioUpload(
            id=uuid.uuid4(),
            user_id=user,
            session_id=session_id,
            segment_id=uuid.uuid4(),
            offset_ms=0,
            duration_ms=3_000,
            byte_size=102,
            sha256="0" * 64,
            codec="aac-lc-adts",
            sample_rate_hz=16_000,
            channel_count=1,
            captured_at=NOW,
            state="transcribed",
            object_key="k",
        )
        s.add(upload)
        await s.flush()
        transcript = Transcript(
            user_id=user, upload_id=upload.id, revision=1, provider="fake", model="fake-1"
        )
        s.add(transcript)
        await s.flush()
        upload.active_transcript_id = transcript.id
        await s.commit()

    with pytest.raises(JobError) as caught:
        await run_extraction(database, FakeExtractor(), user, session_id)
    assert caught.value.code == "nothing_transcribed_yet"


def idea(**overrides: object) -> ExtractedItem:
    base: dict[str, object] = {
        "kind": "idea",
        "text": "A better onboarding flow",
        "confidence": "possible",
        "evidence_segment_indices": (0,),
    }
    base.update(overrides)
    return ExtractedItem(**base)  # type: ignore[arg-type]


def question(**overrides: object) -> ExtractedItem:
    base: dict[str, object] = {
        "kind": "question",
        "text": "Did we ever confirm the venue?",
        "confidence": "possible",
        "evidence_segment_indices": (0,),
    }
    base.update(overrides)
    return ExtractedItem(**base)  # type: ignore[arg-type]


def decision(**overrides: object) -> ExtractedItem:
    base: dict[str, object] = {
        "kind": "decision",
        "text": "Ship on Friday",
        "confidence": "confirmed",
        "evidence_segment_indices": (0,),
    }
    base.update(overrides)
    return ExtractedItem(**base)  # type: ignore[arg-type]


async def test_every_kind_of_item_appears_in_its_own_section_of_the_report(
    database: Database, db: Engine
) -> None:
    user, session_id = await transcribed_session(database, db)
    provider = FakeExtractor(items=(decision(), idea(), question()))

    await run_extraction(database, provider, user, session_id)

    async with database.sessions() as s:
        report = await s.scalar(select(DailyReportRow).where(DailyReportRow.user_id == user))
    assert report is not None
    assert len(cast(list[object], report.report["decisions"])) == 1
    assert len(cast(list[object], report.report["ideas"])) == 1
    assert len(cast(list[object], report.report["open_questions"])) == 1


async def test_provider_failures_propagate_as_job_errors(database: Database, db: Engine) -> None:
    user, session_id = await transcribed_session(database, db)
    provider = FakeExtractor(error=JobError("provider_timeout", retryable=True))

    with pytest.raises(JobError) as caught:
        await run_extraction(database, provider, user, session_id)
    assert caught.value.code == "provider_timeout"
    assert caught.value.retryable


async def test_an_invalid_provider_result_dead_letters_without_writing_anything(
    database: Database, db: Engine
) -> None:
    user, session_id = await transcribed_session(database, db)
    provider = FakeExtractor(items=(action(evidence_segment_indices=(99,)),))

    with pytest.raises(JobError) as caught:
        await run_extraction(database, provider, user, session_id)
    assert caught.value.code == "invalid_extraction_result"

    async with database.sessions() as s:
        assert (await s.scalars(select(MemoryItem).where(MemoryItem.user_id == user))).all() == []


async def test_rerunning_extraction_replaces_the_sessions_items(
    database: Database, db: Engine
) -> None:
    user, session_id = await transcribed_session(database, db)
    await run_extraction(
        database, FakeExtractor(items=(action(text="first pass"),)), user, session_id
    )

    await run_extraction(
        database, FakeExtractor(items=(action(text="second pass"),)), user, session_id, version=2
    )

    async with database.sessions() as s:
        items = (await s.scalars(select(MemoryItem).where(MemoryItem.user_id == user))).all()
        assert [i.text for i in items] == ["second pass"]
        assert items[0].extraction_version == 2


async def test_extraction_runs_end_to_end_through_the_job_queue(
    database: Database, db: Engine
) -> None:
    user, session_id = await transcribed_session(database, db)
    provider = FakeExtractor(items=(action(),))
    async with database.sessions() as s:
        await enqueue(
            s, user_id=user, kind="extract", subject_id=session_id, idempotency_key="extract:x"
        )
        await s.execute(text("UPDATE processing_jobs SET run_after = :t"), {"t": NOW})
        await s.commit()

    worker = Worker(
        database, {"extract": make_handler(database, provider)}, worker_id="w", now=lambda: NOW
    )
    assert await worker.run_once()

    with db.connect() as connection:
        job_state: str = connection.execute(
            text("SELECT state FROM processing_jobs WHERE kind = 'extract'"),
        ).scalar_one()
    assert job_state == "succeeded"

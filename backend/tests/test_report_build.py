"""Direct tests for the report build (app/reports/build.py). The build is total: whatever
the database holds, the result satisfies the report contract — these tests pin the bounds
and fallbacks that keep a pathological day from dead-lettering the extraction job."""

import uuid
from collections.abc import AsyncIterator
from datetime import UTC, date, datetime, timedelta

import pytest
from sqlalchemy import Engine

from app.db import Database
from app.db.models import (
    AudioUpload,
    MemoryItem,
    MemoryItemEvidence,
    Transcript,
    TranscriptSegment,
    User,
)
from app.reports.build import build_daily_report
from app.reports.daily import DailyReport

pytestmark = pytest.mark.anyio

DAY = date(2026, 9, 25)
AT = datetime(2026, 9, 25, 8, 0, tzinfo=UTC)


@pytest.fixture
async def database(db: Engine, database_url: str) -> AsyncIterator[Database]:
    database = Database(database_url)
    yield database
    await database.dispose()


def clip(
    *, session_id: uuid.UUID, n: int, duration_ms: int, state: str = "transcribed"
) -> AudioUpload:
    return AudioUpload(
        id=uuid.uuid4(),
        session_id=session_id,
        segment_id=uuid.uuid4(),
        offset_ms=0,
        duration_ms=duration_ms,
        byte_size=4,
        sha256="0" * 64,
        codec="aac-lc-adts",
        sample_rate_hz=16_000,
        channel_count=1,
        captured_at=AT + timedelta(minutes=n),
        state=state,
        object_key=f"audio/{n}.aac",
    )


def item(*, session_id: uuid.UUID, n: int, **overrides: object) -> MemoryItem:
    values: dict[str, object] = {
        "kind": "decision",
        "text": f"item {n}",
        "confidence": "possible",
        "extraction_model": "test:1",
        "extraction_version": 1,
        # Explicit so truncation ordering is deterministic, not whichever row won now().
        "created_at": AT + timedelta(seconds=n),
    }
    values.update(overrides)
    return MemoryItem(session_id=session_id, memory_day=DAY, **values)


async def seed(
    database: Database, *, clips: list[AudioUpload], items: list[MemoryItem]
) -> tuple[uuid.UUID, dict[uuid.UUID, list[uuid.UUID]]]:
    """Inserts a full-cloud user's day; returns its id and each clip's segment ids in order."""
    user_id = uuid.uuid4()
    segments: dict[uuid.UUID, list[uuid.UUID]] = {}
    async with database.sessions() as session:
        session.add(User(id=user_id, storage_mode="full_cloud"))
        # SQLAlchemy has no ORM relationship to order these inserts by the FK; flush the
        # parent explicitly before adding uploads.
        await session.flush()
        for upload in clips:
            upload.user_id = user_id
            session.add(upload)
            transcript = Transcript(
                user_id=user_id,
                upload_id=upload.id,
                revision=1,
                provider="fake",
                model="fake-1",
                language="en",
            )
            session.add(transcript)
            await session.flush()
            upload.active_transcript_id = transcript.id
            ids = []
            for start_ms in range(0, 70_000, 10_000):
                segment = TranscriptSegment(
                    user_id=user_id,
                    transcript_id=transcript.id,
                    start_ms=start_ms,
                    end_ms=start_ms + 5_000,
                    text=f"line at {start_ms}",
                    confidence=0.9,
                )
                session.add(segment)
                await session.flush()
                ids.append(segment.id)
            segments[upload.id] = ids
        for row in items:
            row.user_id = user_id
            session.add(row)
        await session.commit()
    return user_id, segments


async def cite(
    database: Database, row: MemoryItem, segment_ids: list[uuid.UUID], *, count: int
) -> None:
    async with database.sessions() as session:
        for segment_id in segment_ids[:count]:
            session.add(
                MemoryItemEvidence(
                    id=uuid.uuid4(),
                    user_id=row.user_id,
                    memory_item_id=row.id,
                    transcript_segment_id=segment_id,
                )
            )
        await session.commit()


async def build(database: Database, user_id: uuid.UUID) -> DailyReport:
    async with database.sessions() as session:
        return await build_daily_report(session, user_id=user_id, memory_day=DAY)


async def test_a_day_past_every_contract_bound_truncates_deterministically(
    database: Database,
) -> None:
    session_ids = [uuid.uuid4() for _ in range(55)]
    clips = [clip(session_id=s, n=i, duration_ms=30 * 60_000) for i, s in enumerate(session_ids)]
    # 51 "possible" decisions (created first) plus 1 "confirmed" one: the section cap is 50,
    # and the confirmed item must survive it.
    items = [item(session_id=session_ids[0], n=i) for i in range(51)]
    confirmed = item(
        session_id=session_ids[0], n=99, text="the confirmed one", confidence="confirmed"
    )
    items.append(confirmed)
    user_id, segments = await seed(database, clips=clips, items=items)
    await cite(database, confirmed, segments[clips[0].id], count=7)

    report = await build(database, user_id)

    # Sessions: earliest 50, in start order.
    assert len(report.sessions) == 50
    assert [s.start for s in report.sessions] == sorted(s.start for s in report.sessions)
    # Sections: capped at 50, confidence-ranked, so the confirmed item survives.
    assert len(report.decisions) == 50
    kept = next(d for d in report.decisions if d.text == "the confirmed one")
    # Evidence: at most five entries, earliest first.
    assert len(kept.evidence) == 5
    assert [e.at for e in kept.evidence] == sorted(e.at for e in kept.evidence)
    # Coverage: 55 half-hour sessions claim 1650 minutes; one day has at most 1440.
    assert report.coverage.recorded_minutes == 24 * 60
    assert report.status == "complete"


async def test_pending_audio_rounds_up_to_a_partial_minute(database: Database) -> None:
    session_id = uuid.uuid4()
    pending = clip(session_id=session_id, n=0, duration_ms=30_000, state="pending")
    user_id, _ = await seed(database, clips=[pending], items=[])

    report = await build(database, user_id)

    assert report.coverage.pending_minutes == 1
    assert report.status == "partial"


async def test_an_item_left_without_evidence_rows_still_builds(database: Database) -> None:
    session_id = uuid.uuid4()
    orphan = item(session_id=session_id, n=0)
    user_id, _ = await seed(
        database,
        clips=[clip(session_id=session_id, n=0, duration_ms=60_000)],
        items=[orphan],
    )

    report = await build(database, user_id)

    # Cascading deletes can orphan an item; the contract still requires >=1 evidence entry.
    assert len(report.decisions) == 1
    assert report.decisions[0].evidence[0].session_id == "unknown"


async def test_a_commitment_without_a_counterpart_still_builds(database: Database) -> None:
    session_id = uuid.uuid4()
    # Only reachable for rows written before validate() required a counterpart (or by hand):
    # the DB CHECK constrains direction, not counterpart.
    legacy = item(
        session_id=session_id,
        n=0,
        kind="commitment",
        direction="i_owe",
        counterpart=None,
    )
    user_id, _ = await seed(
        database, clips=[clip(session_id=session_id, n=0, duration_ms=60_000)], items=[legacy]
    )

    report = await build(database, user_id)

    assert len(report.commitments) == 1
    assert report.commitments[0].counterpart  # an honest non-empty fallback, not a crash

"""Metering (Phase 10): server-side daily counters. A client cannot raise or reset its own."""

import uuid
from datetime import UTC, datetime

from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import UsageCounter


async def take(session: AsyncSession, user_id: uuid.UUID, kind: str, *, limit: int) -> bool:
    """Counts one use for today (UTC) and says whether it is within [limit]. The caller commits.

    The row is created or incremented in one statement, so concurrent calls can't both slip
    under the limit. A use over the limit is not counted, so a refused call costs nothing.
    """
    day = datetime.now(UTC).date()
    insert = pg_insert(UsageCounter).values(user_id=user_id, day=day, kind=kind, count=1)
    result = await session.execute(
        insert.on_conflict_do_update(
            index_elements=[UsageCounter.user_id, UsageCounter.day, UsageCounter.kind],
            set_={"count": UsageCounter.count + 1},
            where=UsageCounter.count < limit,
        ).returning(UsageCounter.count)
    )
    return result.scalar_one_or_none() is not None

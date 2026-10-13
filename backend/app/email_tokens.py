"""Single-use tokens for emailed links (password reset, address verification).

Only a SHA-256 of the token is stored. A token expires, works once, and issuing a new one for the
same purpose retires the older ones, so an old email can never be replayed after a newer request.
"""

import hashlib
import secrets
import uuid
from datetime import UTC, datetime, timedelta
from typing import Literal

from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import EmailToken

Purpose = Literal["reset", "verify"]
TTL: dict[str, timedelta] = {"reset": timedelta(hours=1), "verify": timedelta(hours=24)}


def _hash(token: str) -> bytes:
    return hashlib.sha256(token.encode()).digest()


async def issue(session: AsyncSession, user_id: uuid.UUID, purpose: Purpose) -> str:
    """The plaintext token (for the link). The caller commits."""
    await session.execute(
        delete(EmailToken).where(
            EmailToken.user_id == user_id,
            EmailToken.purpose == purpose,
            EmailToken.used_at.is_(None),
        )
    )
    token = secrets.token_urlsafe(32)
    session.add(
        EmailToken(
            user_id=user_id,
            purpose=purpose,
            token_hash=_hash(token),
            expires_at=datetime.now(UTC) + TTL[purpose],
        )
    )
    await session.flush()
    return token


async def consume(session: AsyncSession, token: str, purpose: Purpose) -> uuid.UUID | None:
    """The user the token belongs to, marking it used; None when unknown, used, expired or the
    wrong purpose (all indistinguishable to the caller). The caller commits."""
    if not (20 <= len(token) <= 100):
        return None
    now = datetime.now(UTC)
    row = await session.scalar(
        select(EmailToken)
        .where(EmailToken.token_hash == _hash(token), EmailToken.purpose == purpose)
        .with_for_update()
    )
    if row is None or row.used_at is not None or row.expires_at <= now:
        return None
    await session.execute(update(EmailToken).where(EmailToken.id == row.id).values(used_at=now))
    return row.user_id

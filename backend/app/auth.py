"""Bearer-token authentication for devices and user API keys (AGENTS rule 4).

A token is shown once, when issued. Only its SHA-256 hash is stored: a database leak does not
yield usable credentials. The `adm_` prefix lets secret scanners recognize leaked tokens.
Tokens are 256-bit random values, so a plain (unsalted) hash is not brute-forceable.
"""

import hashlib
import logging
import secrets
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Annotated, Final, Literal

from fastapi import Depends, HTTPException, Request
from sqlalchemy import any_, delete, literal, or_, select, update
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import Database, get_session
from app.db.models import AccessToken, AudioUpload, DeviceKey, SealedDelivery

logger = logging.getLogger("app.auth")

TOKEN_PREFIX: Final = "adm_"  # noqa: S105 - a public prefix, not a secret
Scope = Literal["upload:write", "memory:read", "memory:write", "transcript:read", "account:manage"]
# Devices upload audio and read their own memory. API keys start with memory:read only;
# transcript access is a separate explicit scope.
DEVICE_SCOPES: Final[tuple[Scope, ...]] = ("upload:write", "memory:read")
API_KEY_SCOPES: Final[tuple[Scope, ...]] = ("memory:read",)
# A signed-in browser: reads and corrects memory and manages the account, but never uploads audio.
# Its token expires, unlike a phone's, because a browser is easier to lose or leave open.
WEB_SCOPES: Final[tuple[Scope, ...]] = (
    "memory:read",
    "memory:write",
    "transcript:read",
    "account:manage",
)
WEB_TOKEN_TTL: Final = timedelta(days=30)
# Writing last_used_at on every request would turn reads into writes.
_LAST_USED_RESOLUTION: Final = timedelta(hours=1)
_BEARER: Final = "bearer "


@dataclass(frozen=True)
class IssuedToken:
    """The plaintext is shown once; the id is how it is listed and revoked later."""

    token_id: uuid.UUID
    token: str


@dataclass(frozen=True)
class Principal:
    user_id: uuid.UUID
    token_id: uuid.UUID
    scopes: frozenset[str]


def hash_token(token: str) -> bytes:
    return hashlib.sha256(token.encode()).digest()


def new_token() -> str:
    return TOKEN_PREFIX + secrets.token_urlsafe(32)


async def issue_token(
    session: AsyncSession,
    user_id: uuid.UUID,
    *,
    kind: Literal["device", "api"],
    name: str,
    scopes: tuple[Scope, ...] | None = None,
    expires_at: datetime | None = None,
) -> IssuedToken:
    """Stores the hash and returns the plaintext token with its id. The caller commits."""
    if scopes is None:
        scopes = DEVICE_SCOPES if kind == "device" else API_KEY_SCOPES
    elif not scopes:
        # An explicit empty tuple must never fall back to the (wider) defaults.
        raise ValueError("a token needs at least one scope")
    token = new_token()
    row = AccessToken(
        id=uuid.uuid4(),
        user_id=user_id,
        token_hash=hash_token(token),
        kind=kind,
        scopes=list(scopes),
        name=name,
        expires_at=expires_at,
    )
    session.add(row)
    await session.flush()
    return IssuedToken(row.id, token)


def _unauthorized() -> HTTPException:
    return HTTPException(
        status_code=401,
        detail="Authentication required.",
        headers={"WWW-Authenticate": "Bearer"},
    )


def _bearer(request: Request) -> str:
    header = request.headers.get("authorization", "")
    if not header.lower().startswith(_BEARER):
        raise _unauthorized()
    token = header[len(_BEARER) :].strip()
    if not token.startswith(TOKEN_PREFIX) or len(token) > 128:
        raise _unauthorized()
    return token


def require(scope: Scope, *alternatives: Scope) -> Callable[..., Awaitable[Principal]]:
    """Dependency: an active token carrying [scope] (or any of [alternatives]). Unknown, revoked,
    and expired tokens all get the same 401, so the response never reveals which it was."""
    accepted = (scope, *alternatives)

    async def dependency(
        request: Request,
        session: Annotated[AsyncSession, Depends(get_session)],
    ) -> Principal:
        token_hash = hash_token(_bearer(request))
        row = await session.scalar(select(AccessToken).where(AccessToken.token_hash == token_hash))
        now = datetime.now(UTC)
        if (
            row is None
            or row.revoked_at is not None
            or (row.expires_at is not None and row.expires_at <= now)
        ):
            raise _unauthorized()
        if not any(s in row.scopes for s in accepted):
            raise HTTPException(status_code=403, detail="Token lacks the required scope.")
        if row.last_used_at is None or now - row.last_used_at >= _LAST_USED_RESOLUTION:
            # Its own short transaction: committing the request's session here would end
            # the handler's unit of work too.
            database: Database = request.app.state.database
            try:
                async with database.sessions() as usage:
                    await usage.execute(
                        update(AccessToken)
                        .where(AccessToken.id == row.id)
                        .values(last_used_at=now),
                    )
                    await usage.commit()
            except SQLAlchemyError as error:
                # Bookkeeping only: a valid token must not fail the request because this write did.
                logger.warning(
                    "token_last_used_update_failed", extra={"error_type": type(error).__name__}
                )
        return Principal(user_id=row.user_id, token_id=row.id, scopes=frozenset(row.scopes))

    return dependency


async def revoke_other_browser_sessions(
    session: AsyncSession, user_id: uuid.UUID, *, keep: uuid.UUID
) -> None:
    """Revokes the user's other browser tokens (those without the upload scope). Phones and API
    keys are left alone; they are listed and revoked one by one."""
    await session.execute(
        update(AccessToken)
        .where(
            AccessToken.user_id == user_id,
            AccessToken.id != keep,
            AccessToken.revoked_at.is_(None),
            AccessToken.kind == "device",
            ~(literal("upload:write") == any_(AccessToken.scopes)),
        )
        .values(revoked_at=datetime.now(UTC)),
    )


async def revoke_web_and_api_sessions(session: AsyncSession, user_id: uuid.UUID) -> None:
    """Revokes every browser session and API key (not phones) for [user_id]."""
    await session.execute(
        update(AccessToken)
        .where(
            AccessToken.user_id == user_id,
            AccessToken.revoked_at.is_(None),
            or_(
                AccessToken.kind == "api",
                ~(literal("upload:write") == any_(AccessToken.scopes)),
            ),
        )
        .values(revoked_at=datetime.now(UTC)),
    )


async def revoke_token(session: AsyncSession, user_id: uuid.UUID, token_id: uuid.UUID) -> bool:
    """Revokes one of the user's own tokens and everything bound to it: its device key and any
    transcripts sealed to that key (they could only have been opened with it). Returns False
    if the token isn't theirs or is already revoked."""
    now = datetime.now(UTC)
    result = await session.execute(
        update(AccessToken)
        .where(
            AccessToken.id == token_id,
            AccessToken.user_id == user_id,
            AccessToken.revoked_at.is_(None),
        )
        .values(revoked_at=now),
    )
    if not getattr(result, "rowcount", 0):
        return False
    keys = (
        await session.scalars(
            update(DeviceKey)
            .where(DeviceKey.token_id == token_id, DeviceKey.revoked_at.is_(None))
            .values(revoked_at=now)
            .returning(DeviceKey.id),
        )
    ).all()
    if keys:
        await session.execute(delete(SealedDelivery).where(SealedDelivery.device_key_id.in_(keys)))
        # Only clips whose sealed copy was just deleted change here (their audio is already
        # gone). Pending and received clips still have audio in the bucket: their transcribe
        # job fails on the revoked key and the dead-letter hook or the retention sweep deletes
        # the audio; an upload that never completes is swept as abandoned.
        await session.execute(
            update(AudioUpload)
            .where(AudioUpload.device_key_id.in_(keys), AudioUpload.state == "transcribed")
            .values(state="failed", failure_code="device_key_revoked"),
        )
    return True

"""Bearer-token authentication for devices and user API keys (AGENTS rule 4).

A token is shown once, when issued. Only its SHA-256 hash is stored: a database leak does not
yield usable credentials. The `adm_` prefix lets secret scanners recognize leaked tokens.
Tokens are 256-bit random values, so a plain (unsalted) hash is not brute-forceable.
"""

import hashlib
import secrets
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Annotated, Final, Literal

from fastapi import Depends, HTTPException, Request
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import Database, get_session
from app.db.models import AccessToken

TOKEN_PREFIX: Final = "adm_"  # noqa: S105 - a public prefix, not a secret
Scope = Literal["upload:write", "memory:read"]
# Devices upload audio and read their own memory. API keys start read-only (rule 4).
DEVICE_SCOPES: Final[tuple[Scope, ...]] = ("upload:write", "memory:read")
API_KEY_SCOPES: Final[tuple[Scope, ...]] = ("memory:read",)
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


def require(scope: Scope) -> Callable[..., Awaitable[Principal]]:
    """Dependency: an active token carrying [scope]. Unknown, revoked, and expired tokens all
    get the same 401, so the response never reveals which it was."""

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
        if scope not in row.scopes:
            raise HTTPException(status_code=403, detail="Token lacks the required scope.")
        if row.last_used_at is None or now - row.last_used_at >= _LAST_USED_RESOLUTION:
            # Its own short transaction: committing the request's session here would end
            # the handler's unit of work too.
            database: Database = request.app.state.database
            async with database.sessions() as usage:
                await usage.execute(
                    update(AccessToken).where(AccessToken.id == row.id).values(last_used_at=now),
                )
                await usage.commit()
        return Principal(user_id=row.user_id, token_id=row.id, scopes=frozenset(row.scopes))

    return dependency


async def revoke_token(session: AsyncSession, user_id: uuid.UUID, token_id: uuid.UUID) -> bool:
    """Revokes one of the user's own tokens. Returns False if it isn't theirs or is gone."""
    result = await session.execute(
        update(AccessToken)
        .where(
            AccessToken.id == token_id,
            AccessToken.user_id == user_id,
            AccessToken.revoked_at.is_(None),
        )
        .values(revoked_at=datetime.now(UTC)),
    )
    return bool(getattr(result, "rowcount", 0))

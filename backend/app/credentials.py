"""Self-service sign-in (TODO Phase 1 "Account sign-in flow"): email + password issuing a
device token directly, so a real user no longer needs `python -m app.admin` to get one. The
admin CLI still exists for operator provisioning and tests.

Passwords are hashed with argon2id (OWASP's current recommendation) via `argon2-cffi`, never
compared or logged in the clear. A wrong email and a wrong password return the same error, so
neither response reveals which one it was (AGENTS.md: least privilege, no ad-hoc leaks).

Lockout is per (credential, source), where source is a keyed hash of the client address (never
the address itself; see `hash_source`). Five consecutive wrong passwords from one source lock
that source for 15 minutes; a correct password from it clears its counter. A locked source is
rejected even with the right password, but everyone else can still sign in, so a third party
who knows a victim's email can no longer lock the victim out. A credential-wide backstop covers
distributed guessing: 50 failures across all sources within one hour lock the credential for
everyone for 15 minutes.

Residual trade-off: the sources are only as distinct as the addresses the rate limiter can see
(behind a proxy without TRUST_RAILWAY_X_REAL_IP they all collapse into one), and an attacker
with many addresses can still trip the 50-failure backstop and briefly lock the victim out.
That costs the attacker dozens of addresses per 15 minutes instead of one request stream, and
the argon2 verify cost plus the route's per-address rate limit still apply. Failure rows are
pruned after 24 hours of inactivity.
"""

import asyncio
import hashlib
import hmac
import re
import uuid
from contextlib import suppress
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Literal

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from sqlalchemy import delete, func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import WEB_SCOPES, WEB_TOKEN_TTL, IssuedToken, issue_token
from app.db.models import Credential, LoginFailure, User

# Deliberately permissive (RFC 5322 is not worth re-implementing here): reject the obviously
# malformed, let the real check be "does a confirmation/login round trip work".
_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_MAX_EMAIL_LENGTH = 320
_MIN_PASSWORD_LENGTH = 12
_MAX_PASSWORD_LENGTH = 256  # argon2 hashes the whole input; cap it against abuse, not strength.
_MAX_FAILED_ATTEMPTS = 5
_LOCKOUT = timedelta(minutes=15)
_BACKSTOP_FAILURES = 50
_BACKSTOP_WINDOW = timedelta(hours=1)
_FAILURE_RETENTION = timedelta(hours=24)

_hasher = PasswordHasher()


class SignupError(Exception):
    """`code` is operator/client-facing; see app/api/auth.py for the HTTP mapping."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class LoginError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def normalize_email(email: str) -> str:
    return email.strip().lower()


def _validate(email: str, password: str) -> str:
    normalized = normalize_email(email)
    if not normalized or len(normalized) > _MAX_EMAIL_LENGTH or not _EMAIL.fullmatch(normalized):
        raise SignupError("invalid_email")
    if not (_MIN_PASSWORD_LENGTH <= len(password) <= _MAX_PASSWORD_LENGTH):
        raise SignupError("weak_password")
    return normalized


@dataclass(frozen=True)
class SignedUp:
    user_id: uuid.UUID
    token: IssuedToken


def _token_options(client: Literal["device", "web"]) -> dict[str, Any]:
    """A browser gets the web scopes and an expiry; a phone keeps the device defaults."""
    if client == "web":
        return {"scopes": WEB_SCOPES, "expires_at": datetime.now(UTC) + WEB_TOKEN_TTL}
    return {}


async def sign_up(
    session: AsyncSession,
    *,
    email: str,
    password: str,
    device_name: str,
    client: Literal["device", "web"] = "device",
) -> SignedUp:
    """Creates the user, its credential, and its first device token in one transaction. The
    caller commits; on any failure nothing is persisted (no orphaned `User` row)."""
    normalized = _validate(email, password)
    user = User(id=uuid.uuid4())
    session.add(user)
    await session.flush()
    # Argon2 is deliberately slow (~100 ms); it is CPU work, so keep it off the event loop.
    password_hash = await asyncio.to_thread(_hasher.hash, password)
    session.add(
        Credential(
            id=uuid.uuid4(),
            user_id=user.id,
            email=normalized,
            password_hash=password_hash,
        ),
    )
    try:
        await session.flush()
    except IntegrityError as e:
        raise SignupError("email_taken") from e
    token = await issue_token(
        session, user.id, kind="device", name=device_name, **_token_options(client)
    )
    return SignedUp(user.id, token)


@dataclass(frozen=True)
class LoggedIn:
    user_id: uuid.UUID
    token: IssuedToken


def hash_source(address: str, key: bytes) -> str:
    """Opaque, stable per-deploy identifier for a client address (HMAC-SHA256, hex)."""
    return hmac.new(key, address.encode(), hashlib.sha256).hexdigest()


async def _verify_discarding_outcome(password_hash: str, password: str) -> None:
    # Same hashing cost as an unlocked attempt, so response time doesn't reveal the lock.
    with suppress(VerificationError, InvalidHashError):
        await asyncio.to_thread(_hasher.verify, password_hash, password)


async def _record_failure(
    session: AsyncSession,
    credential: Credential,
    failure: LoginFailure | None,
    source: str,
    now: datetime,
) -> None:
    await session.execute(
        delete(LoginFailure).where(LoginFailure.updated_at < now - _FAILURE_RETENTION)
    )
    if failure is None:
        failure = LoginFailure(credential_id=credential.id, source_hash=source, failed_attempts=0)
        session.add(failure)
    elif failure.locked_until is not None:
        # The lock has expired (a live one is rejected earlier): start a fresh streak.
        failure.failed_attempts = 0
        failure.locked_until = None
    failure.failed_attempts += 1
    failure.updated_at = now
    if failure.failed_attempts >= _MAX_FAILED_ATTEMPTS:
        failure.locked_until = now + _LOCKOUT
    await session.flush()
    recent = await session.scalar(
        select(func.coalesce(func.sum(LoginFailure.failed_attempts), 0)).where(
            LoginFailure.credential_id == credential.id,
            LoginFailure.updated_at > now - _BACKSTOP_WINDOW,
        )
    )
    if (recent or 0) >= _BACKSTOP_FAILURES:
        credential.locked_until = now + _LOCKOUT
        await session.execute(
            delete(LoginFailure).where(LoginFailure.credential_id == credential.id)
        )


async def log_in(
    session: AsyncSession,
    *,
    email: str,
    password: str,
    device_name: str,
    source: str,
    client: Literal["device", "web"] = "device",
) -> LoggedIn:
    """`source` is the opaque `hash_source` value for the caller's address."""
    normalized = normalize_email(email)
    credential = await session.scalar(
        select(Credential).where(Credential.email == normalized).with_for_update()
    )
    now = datetime.now(UTC)
    if credential is None:
        # Pay the same hashing cost as a real check so response timing doesn't reveal whether
        # the email is registered. Off the event loop like the real check below.
        await asyncio.to_thread(_hasher.hash, password)
        raise LoginError("invalid_credentials")
    failure = await session.get(LoginFailure, (credential.id, source))
    credential_locked = credential.locked_until is not None and credential.locked_until > now
    source_locked = (
        failure is not None and failure.locked_until is not None and failure.locked_until > now
    )
    if credential_locked or source_locked:
        # The outcome is discarded: a locked account or source rejects even the right password.
        await _verify_discarding_outcome(credential.password_hash, password)
        raise LoginError("account_locked")
    try:
        await asyncio.to_thread(_hasher.verify, credential.password_hash, password)
    except (VerificationError, InvalidHashError) as e:
        await _record_failure(session, credential, failure, source, now)
        raise LoginError("invalid_credentials") from e
    if failure is not None:
        await session.delete(failure)
    credential.locked_until = None
    token = await issue_token(
        session, credential.user_id, kind="device", name=device_name, **_token_options(client)
    )
    return LoggedIn(credential.user_id, token)


async def user_for_email(session: AsyncSession, email: str) -> uuid.UUID | None:
    return await session.scalar(
        select(Credential.user_id).where(Credential.email == normalize_email(email))
    )


async def email_of(session: AsyncSession, user_id: uuid.UUID) -> str | None:
    return await session.scalar(select(Credential.email).where(Credential.user_id == user_id))


async def verify_password(session: AsyncSession, user_id: uuid.UUID, password: str) -> bool:
    """True when [password] is this account's. An account with no password (provisioned by an
    operator) never verifies, so a sensitive action can't be confirmed by an empty string."""
    stored = await session.scalar(
        select(Credential.password_hash).where(Credential.user_id == user_id)
    )
    if stored is None:
        return False
    try:
        await asyncio.to_thread(_hasher.verify, stored, password)
    except (VerificationError, InvalidHashError):
        return False
    return True


async def set_password(session: AsyncSession, user_id: uuid.UUID, new: str) -> None:
    """Replaces the password with no check of the old one (the caller has proved the right to,
    by the current password or a reset link). The new one must meet the same rules as sign-up."""
    if not (_MIN_PASSWORD_LENGTH <= len(new) <= _MAX_PASSWORD_LENGTH):
        raise SignupError("weak_password")
    new_hash = await asyncio.to_thread(_hasher.hash, new)
    await session.execute(
        update(Credential)
        .where(Credential.user_id == user_id)
        .values(password_hash=new_hash, failed_attempts=0, locked_until=None)
    )


async def change_password(
    session: AsyncSession, user_id: uuid.UUID, *, current: str, new: str
) -> None:
    """Replaces the password and ends every other sign-in. The caller commits.

    Raises LoginError("invalid_credentials") for a wrong current password and SignupError
    ("weak_password") for an unusable new one."""
    if not (_MIN_PASSWORD_LENGTH <= len(new) <= _MAX_PASSWORD_LENGTH):
        raise SignupError("weak_password")
    if not await verify_password(session, user_id, current):
        raise LoginError("invalid_credentials")
    await set_password(session, user_id, new)

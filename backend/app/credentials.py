"""Self-service sign-in (TODO Phase 1 "Account sign-in flow"): email + password issuing a
device token directly, so a real user no longer needs `python -m app.admin` to get one. The
admin CLI still exists for operator provisioning and tests.

Passwords are hashed with argon2id (OWASP's current recommendation) via `argon2-cffi`, never
compared or logged in the clear. A wrong email and a wrong password return the same error, so
neither response reveals which one it was (AGENTS.md: least privilege, no ad-hoc leaks). Five
wrong passwords in a row lock the credential for 15 minutes; the lock clears on the next
correct password.
"""

import asyncio
import re
import uuid
from contextlib import suppress
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import IssuedToken, issue_token
from app.db.models import Credential, User

# Deliberately permissive (RFC 5322 is not worth re-implementing here): reject the obviously
# malformed, let the real check be "does a confirmation/login round trip work".
_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_MAX_EMAIL_LENGTH = 320
_MIN_PASSWORD_LENGTH = 12
_MAX_PASSWORD_LENGTH = 256  # argon2 hashes the whole input; cap it against abuse, not strength.
_MAX_FAILED_ATTEMPTS = 5
_LOCKOUT = timedelta(minutes=15)

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


async def sign_up(
    session: AsyncSession, *, email: str, password: str, device_name: str
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
    token = await issue_token(session, user.id, kind="device", name=device_name)
    return SignedUp(user.id, token)


@dataclass(frozen=True)
class LoggedIn:
    user_id: uuid.UUID
    token: IssuedToken


async def log_in(session: AsyncSession, *, email: str, password: str, device_name: str) -> LoggedIn:
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
    if credential.locked_until is not None and credential.locked_until > now:
        # Same hashing cost as an unlocked attempt, so response time doesn't reveal the lock.
        # The outcome is discarded: a locked account rejects even the right password.
        with suppress(VerificationError, InvalidHashError):
            await asyncio.to_thread(_hasher.verify, credential.password_hash, password)
        raise LoginError("account_locked")
    try:
        await asyncio.to_thread(_hasher.verify, credential.password_hash, password)
    except (VerificationError, InvalidHashError) as e:
        credential.failed_attempts += 1
        if credential.failed_attempts >= _MAX_FAILED_ATTEMPTS:
            credential.locked_until = now + _LOCKOUT
            credential.failed_attempts = 0
        raise LoginError("invalid_credentials") from e
    credential.failed_attempts = 0
    credential.locked_until = None
    token = await issue_token(session, credential.user_id, kind="device", name=device_name)
    return LoggedIn(credential.user_id, token)

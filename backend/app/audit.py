"""Durable security audit trail (ADR-0029).

Application logs rotate with the platform; this table does not. Every authentication and
account-lifecycle decision that someone may later need to reconstruct writes one row in the
same transaction as the change it describes, so a rollback leaves no false record and a commit
never lacks one.

Rows carry no content: an event name, an outcome, a time, the request ID that ties the row to
the structured log line, and a keyed hash of the acting user. The hash lets an operator follow
one account's history during an incident but is meaningless without the server key, so
deleting an account (which removes the key's only use for that user) leaves nothing that
identifies the person. The table is append-only at the database level (migration 0018).
"""

import hashlib
import hmac
import logging
import uuid
from enum import StrEnum
from typing import Any, Literal

from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.db.models import AuditEvent
from app.observability import redact, request_id_var

logger = logging.getLogger("app.audit")

Outcome = Literal["success", "failure", "denied"]


class Event(StrEnum):
    SIGNUP = "auth.signup"
    LOGIN = "auth.login"
    CREDENTIAL_CHANGE = "auth.password_change"
    SESSION_REVOKE = "token.revoke"
    API_KEY_CREATE = "api_key.create"
    ACCOUNT_DELETE = "account.delete"


def actor_ref(user_id: uuid.UUID, key: bytes) -> str:
    """Stable per-deploy pseudonym for a user (HMAC-SHA256, hex)."""
    return hmac.new(key, b"audit:" + user_id.bytes, hashlib.sha256).hexdigest()


def _key(settings: Settings) -> bytes:
    secret = settings.login_source_key or settings.database_url
    return secret.get_secret_value().encode() if secret is not None else b""


async def record(
    session: AsyncSession,
    request: Request,
    event: Event,
    outcome: Outcome,
    *,
    user_id: uuid.UUID | None = None,
    **detail: str | int | bool,
) -> None:
    """Adds one audit row to `session`; the caller commits it with the change it records.

    `detail` is for short, non-identifying facts (a failure code, a client kind). Values pass
    through the log redactor as well, so a sensitive-looking key never reaches the table.
    """
    settings: Settings = request.app.state.settings
    request_id = request_id_var.get()
    session.add(
        AuditEvent(
            event=event.value,
            outcome=outcome,
            actor_ref=actor_ref(user_id, _key(settings)) if user_id is not None else None,
            request_id=request_id,
            detail=_clean(detail),
        )
    )
    logger.info("audit_event", extra={"event": event.value, "outcome": outcome})


def _clean(detail: dict[str, str | int | bool]) -> dict[str, Any]:
    cleaned: dict[str, Any] = redact(detail)
    return cleaned

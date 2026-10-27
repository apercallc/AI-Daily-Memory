"""The person's own sign-ins and API keys.

    GET    /v1/tokens        every active sign-in: phones, browsers and API keys
    DELETE /v1/tokens/{id}   revoke one (a lost phone, an old browser, a leaked key)
    GET    /v1/api-keys      API keys only
    POST   /v1/api-keys      create a read-only key; the secret is shown once

Keys are hashed at rest, read-only by default, and revocable (AGENTS.md rule 4). A key can
never create or revoke keys: only a phone or a signed-in browser manages them.
"""

import uuid
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import ColumnElement, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app import audit
from app.auth import Principal, issue_token, require, revoke_token
from app.db import get_session
from app.db.models import AccessToken
from app.errors import ErrorResponse, error_response

router = APIRouter(tags=["tokens"])

Session = Annotated[AsyncSession, Depends(get_session)]
Manager = Annotated[Principal, Depends(require("upload:write", "account:manage"))]
_ERRORS: dict[int | str, dict[str, object]] = {
    s: {"model": ErrorResponse} for s in (401, 403, 404, 409)
}
MAX_API_KEYS = 10


class TokenOut(BaseModel):
    id: uuid.UUID
    kind: str
    name: str
    scopes: list[str]
    created_at: datetime
    last_used_at: datetime | None
    expires_at: datetime | None
    current: bool


class NewApiKey(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=80)


class CreatedApiKey(BaseModel):
    id: uuid.UUID
    name: str
    token: str  # shown once


def _active(user_id: uuid.UUID) -> list[ColumnElement[bool]]:
    now = datetime.now(UTC)
    return [
        AccessToken.user_id == user_id,
        AccessToken.revoked_at.is_(None),
        or_(AccessToken.expires_at.is_(None), AccessToken.expires_at > now),
    ]


def _out(row: AccessToken, current: uuid.UUID) -> TokenOut:
    return TokenOut(
        id=row.id,
        kind=row.kind,
        name=row.name,
        scopes=sorted(row.scopes),
        created_at=row.created_at,
        last_used_at=row.last_used_at,
        expires_at=row.expires_at,
        current=row.id == current,
    )


@router.get("/tokens", response_model=list[TokenOut], responses=_ERRORS)
async def list_tokens(session: Session, manager: Manager) -> list[TokenOut]:
    rows = (
        await session.scalars(
            select(AccessToken)
            .where(*_active(manager.user_id))
            .order_by(AccessToken.created_at.desc())
            .limit(200),
        )
    ).all()
    return [_out(r, manager.token_id) for r in rows]


@router.delete("/tokens/{token_id}", status_code=204, response_model=None, responses=_ERRORS)
async def revoke(
    token_id: uuid.UUID, request: Request, session: Session, manager: Manager
) -> Response | JSONResponse:
    if not await revoke_token(session, manager.user_id, token_id):
        return error_response(404, "No such active sign-in.", code="token_not_found")
    await audit.record(
        session, request, audit.Event.SESSION_REVOKE, "success", user_id=manager.user_id
    )
    await session.commit()
    return Response(status_code=204)


@router.get("/api-keys", response_model=list[TokenOut], responses=_ERRORS)
async def list_keys(session: Session, manager: Manager) -> list[TokenOut]:
    rows = (
        await session.scalars(
            select(AccessToken)
            .where(*_active(manager.user_id), AccessToken.kind == "api")
            .order_by(AccessToken.created_at.desc()),
        )
    ).all()
    return [_out(r, manager.token_id) for r in rows]


@router.post("/api-keys", response_model=CreatedApiKey, status_code=201, responses=_ERRORS)
async def create_key(
    body: NewApiKey, request: Request, session: Session, manager: Manager
) -> CreatedApiKey | JSONResponse:
    # Two concurrent POSTs for the same account would both pass a count-then-insert check
    # before either commits; serialize them for the length of this transaction (the pattern
    # app/api/sync.py already uses for its own first-write race).
    await session.execute(
        select(func.pg_advisory_xact_lock(func.hashtextextended(f"api_keys:{manager.user_id}", 0)))
    )
    count = await session.scalar(
        select(func.count())
        .select_from(AccessToken)
        .where(*_active(manager.user_id), AccessToken.kind == "api")
    )
    if (count or 0) >= MAX_API_KEYS:
        return error_response(
            409, "Revoke an unused key before creating another.", code="too_many_keys"
        )
    issued = await issue_token(session, manager.user_id, kind="api", name=body.name.strip())
    await audit.record(
        session, request, audit.Event.API_KEY_CREATE, "success", user_id=manager.user_id
    )
    await session.commit()
    return CreatedApiKey(id=issued.token_id, name=body.name.strip(), token=issued.token)

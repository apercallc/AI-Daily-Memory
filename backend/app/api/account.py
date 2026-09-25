"""The calling device's own account view.

GET    /v1/me              who am I (used by Connect to verify a token before storing it)
PUT    /v1/me              change storage_mode (ADR-0014)
DELETE /v1/tokens/current  revoke the calling token (Disconnect; AGENTS rule 4)
"""

from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, ConfigDict
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require, revoke_token
from app.db import get_session
from app.db.models import User
from app.errors import ErrorResponse

router = APIRouter(tags=["account"])

Session = Annotated[AsyncSession, Depends(get_session)]
Device = Annotated[Principal, Depends(require("upload:write"))]
_ERRORS: dict[int | str, dict[str, object]] = {s: {"model": ErrorResponse} for s in (401, 403)}

StorageMode = Literal["phone_only", "reports_cloud", "full_cloud"]


class Me(BaseModel):
    storage_mode: StorageMode
    scopes: list[str]


class StorageModeUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    storage_mode: StorageMode


@router.get("/me", response_model=Me, responses=_ERRORS)
async def me(session: Session, device: Device) -> Me:
    mode = await session.scalar(select(User.storage_mode).where(User.id == device.user_id))
    return Me(storage_mode=mode, scopes=sorted(device.scopes))  # type: ignore[arg-type]


@router.put("/me", response_model=Me, responses=_ERRORS)
async def update_me(body: StorageModeUpdate, session: Session, device: Device) -> Me:
    """Takes effect for uploads from now on. Every already-received clip keeps the device key
    (or lack of one) recorded on it at upload time (ADR-0014): a switch can never expose or
    re-seal audio uploaded under an earlier mode's promise, in either direction. No data moves
    as part of this call — a mode switch that should also migrate what's already stored
    (TODO.md "cloud -> phone: download, verify, then server delete") is not implemented yet."""
    await session.execute(
        update(User).where(User.id == device.user_id).values(storage_mode=body.storage_mode),
    )
    await session.commit()
    return Me(storage_mode=body.storage_mode, scopes=sorted(device.scopes))


@router.delete("/tokens/current", status_code=204, responses=_ERRORS)
async def revoke_current(session: Session, device: Device) -> Response:
    await revoke_token(session, device.user_id, device.token_id)
    await session.commit()
    return Response(status_code=204)

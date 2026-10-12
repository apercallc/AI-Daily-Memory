"""The calling device's own account view.

GET    /v1/me              who am I (used by Connect to verify a token before storing it)
PUT    /v1/me              change storage_mode (ADR-0014)
DELETE /v1/me              delete this account and its server-side data
DELETE /v1/tokens/current  revoke the calling token (Disconnect; AGENTS rule 4)
"""

from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict
from sqlalchemy import delete, func, select, text, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require, revoke_token
from app.db import get_session
from app.db.models import AudioUpload, User
from app.errors import ErrorResponse
from app.storage import DeleteIncompleteError, ObjectStore, user_prefix

router = APIRouter(tags=["account"])

Session = Annotated[AsyncSession, Depends(get_session)]
Device = Annotated[Principal, Depends(require("upload:write"))]
_ERRORS: dict[int | str, dict[str, object]] = {s: {"model": ErrorResponse} for s in (401, 403, 503)}

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


@router.delete("/me", status_code=204, responses=_ERRORS)
async def delete_me(request: Request, session: Session, device: Device) -> Response:
    """Deletes the user's server-side data and the user's entire object-storage prefix.

    Object storage is cleared before the database cascade; if deletion is incomplete, the
    account remains intact so the operation can be retried. The device token is the proof of
    possession and is revoked by the same cascading delete.
    """
    # Serialize account deletion with new upload reservations for this user. The upload route
    # takes the same transaction-scoped lock before it touches storage or issues a PUT URL.
    await session.execute(
        text("SELECT pg_advisory_xact_lock(hashtext(:user))"),
        {"user": str(device.user_id)},
    )
    store: ObjectStore | None = request.app.state.object_store
    uploads = await session.scalar(
        select(func.count()).select_from(AudioUpload).where(AudioUpload.user_id == device.user_id),
    )
    if store is None:
        if uploads:
            raise HTTPException(
                status_code=503,
                detail="Account data deletion is temporarily unavailable. Retry later.",
            )
    else:
        try:
            await store.delete_prefix(user_prefix(device.user_id))
        except DeleteIncompleteError as error:
            raise HTTPException(
                status_code=503,
                detail="Account data deletion is incomplete. Retry later.",
            ) from error

    await session.execute(delete(User).where(User.id == device.user_id))
    await session.commit()
    return Response(status_code=204)


@router.delete("/tokens/current", status_code=204, responses=_ERRORS)
async def revoke_current(session: Session, device: Device) -> Response:
    await revoke_token(session, device.user_id, device.token_id)
    await session.commit()
    return Response(status_code=204)

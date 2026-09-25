"""Device keys and sealed transcript delivery (ADR-0014).

    PUT  /v1/device-key          register (or rotate) this phone's HPKE public key
    GET  /v1/deliveries          sealed transcripts waiting for this phone
    POST /v1/deliveries/ack      phone stored them: delete the server copy

Everything is scoped to the calling device token: a phone only ever sees what was sealed to
its own key.
"""

import base64
import binascii
import logging
import uuid
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.db import get_session
from app.db.models import AccessToken, AudioUpload, DeviceKey, SealedDelivery
from app.errors import ErrorResponse, error_response
from app.sealing import seal

logger = logging.getLogger("app.deliveries")
router = APIRouter(tags=["deliveries"])

Session = Annotated[AsyncSession, Depends(get_session)]
Device = Annotated[Principal, Depends(require("upload:write"))]
_ERRORS: dict[int | str, dict[str, object]] = {
    status: {"model": ErrorResponse} for status in (401, 403, 409, 422)
}


class DeviceKeyIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # Raw X25519 public key, base64 (standard alphabet).
    public_key: str = Field(min_length=40, max_length=64)


class DeviceKeyOut(BaseModel):
    key_id: uuid.UUID


class Delivery(BaseModel):
    delivery_id: uuid.UUID
    upload_id: uuid.UUID
    ciphertext: str
    expires_at: datetime


class DeliveryPage(BaseModel):
    deliveries: list[Delivery]


class AckIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    delivery_ids: list[uuid.UUID] = Field(min_length=1, max_length=200)


class AckOut(BaseModel):
    acknowledged: int


async def active_key(session: AsyncSession, token_id: uuid.UUID) -> DeviceKey | None:
    key: DeviceKey | None = await session.scalar(
        select(DeviceKey).where(DeviceKey.token_id == token_id, DeviceKey.revoked_at.is_(None)),
    )
    return key


@router.put("/device-key", response_model=DeviceKeyOut, responses=_ERRORS)
async def register_device_key(
    body: DeviceKeyIn,
    session: Session,
    device: Device,
) -> DeviceKeyOut | Response:
    """One key per device token, for the token's lifetime. There is no rotation: a phone that
    lost its key has also lost its sealed token (both live behind the Keystore), so it
    reconnects with a new token. Refusing rotation means a stolen token can't redirect the
    victim's transcripts to a key the thief controls."""
    try:
        raw = base64.b64decode(body.public_key, validate=True)
        # A trial seal rejects low-order and otherwise unusable points up front.
        seal(raw, uuid.uuid4(), {})
    except (binascii.Error, ValueError):
        return error_response(422, "Not a usable X25519 public key.", code="invalid_public_key")

    # Serialize registrations for this token (the partial unique index would otherwise 500).
    await session.execute(
        select(AccessToken.id).where(AccessToken.id == device.token_id).with_for_update(),
    )
    current = await active_key(session, device.token_id)
    if current is not None:
        if current.public_key == raw:
            return DeviceKeyOut(key_id=current.id)
        logger.warning("device_key_mismatch", extra={"token_id": str(device.token_id)})
        return error_response(
            409,
            "This device already has a key. Reconnect with a new device token to change it.",
            code="device_key_mismatch",
        )
    key = DeviceKey(user_id=device.user_id, token_id=device.token_id, public_key=raw)
    session.add(key)
    await session.commit()
    return DeviceKeyOut(key_id=key.id)


@router.get("/deliveries", response_model=DeliveryPage, responses=_ERRORS)
async def list_deliveries(
    session: Session,
    device: Device,
    limit: Annotated[int, Query(ge=1, le=100)] = 50,
) -> DeliveryPage:
    key = await active_key(session, device.token_id)
    if key is None:
        return DeliveryPage(deliveries=[])
    rows = (
        await session.scalars(
            select(SealedDelivery)
            .where(
                SealedDelivery.device_key_id == key.id,
                SealedDelivery.user_id == device.user_id,
                SealedDelivery.expires_at > datetime.now(UTC),
            )
            .order_by(SealedDelivery.created_at)
            .limit(limit),
        )
    ).all()
    return DeliveryPage(
        deliveries=[
            Delivery(
                delivery_id=row.id,
                upload_id=row.upload_id,
                ciphertext=base64.b64encode(row.ciphertext).decode("ascii"),
                expires_at=row.expires_at,
            )
            for row in rows
        ],
    )


@router.post("/deliveries/ack", response_model=AckOut, responses=_ERRORS)
async def acknowledge(body: AckIn, session: Session, device: Device) -> AckOut:
    """Idempotent: unknown, foreign, or already-acknowledged ids are ignored."""
    key = await active_key(session, device.token_id)
    if key is None:
        return AckOut(acknowledged=0)
    uploads = (
        await session.scalars(
            delete(SealedDelivery)
            .where(
                SealedDelivery.id.in_(body.delivery_ids),
                SealedDelivery.device_key_id == key.id,
                SealedDelivery.user_id == device.user_id,
            )
            .returning(SealedDelivery.upload_id),
        )
    ).all()
    if uploads:
        await session.execute(
            update(AudioUpload)
            .where(AudioUpload.id.in_(uploads), AudioUpload.state == "transcribed")
            .values(state="delivered"),
        )
    await session.commit()
    return AckOut(acknowledged=len(uploads))

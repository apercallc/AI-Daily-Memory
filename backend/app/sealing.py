"""Sealing transcripts to a phone (ADR-0014, "On this phone" and "Reports in my cloud").

HPKE base mode (RFC 9180) with DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM: the same
suite as Tink's `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW` on Android. The
ciphertext is `enc || ct`. The HPKE `info` binds each ciphertext to its upload, so a sealed
transcript can't be swapped onto another clip.

Only the phone holds the private key. Once a transcript is sealed the server keeps no
plaintext copy, so what it holds until the phone collects it is unreadable to us.
"""

import json
import uuid
from datetime import datetime
from typing import TYPE_CHECKING, Final

from cryptography.hazmat.primitives import hpke
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PublicKey

if TYPE_CHECKING:
    from app.transcription import TranscriptResult

SUITE: Final = hpke.Suite(hpke.KEM.X25519, hpke.KDF.HKDF_SHA256, hpke.AEAD.AES_256_GCM)
INFO_PREFIX: Final = b"aidm:delivery:v1:"
PAYLOAD_VERSION: Final = 1


def info_for(upload_id: uuid.UUID) -> bytes:
    return INFO_PREFIX + str(upload_id).encode("ascii")


def public_key(raw: bytes) -> X25519PublicKey:
    """Raises ValueError for anything but a 32-byte X25519 public key."""
    if len(raw) != 32:
        raise ValueError("X25519 public keys are 32 bytes")
    return X25519PublicKey.from_public_bytes(raw)


def seal(raw_public_key: bytes, upload_id: uuid.UUID, payload: dict[str, object]) -> bytes:
    plaintext = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return SUITE.encrypt(plaintext, public_key(raw_public_key), info=info_for(upload_id))


def transcript_payload(
    *,
    upload_id: uuid.UUID,
    segment_id: uuid.UUID,
    offset_ms: int,
    captured_at: datetime,
    provider: str,
    model: str,
    result: "TranscriptResult",
) -> dict[str, object]:
    """What the phone stores as local evidence. Times are ms from the clip start; the phone
    adds `offset_ms` to place them in the segment."""
    return {
        "v": PAYLOAD_VERSION,
        "upload_id": str(upload_id),
        "segment_id": str(segment_id),
        "offset_ms": offset_ms,
        "captured_at": captured_at.isoformat(),
        "provider": provider,
        "model": model,
        "language": result.language,
        "spans": [
            {
                "start_ms": span.start_ms,
                "end_ms": span.end_ms,
                "text": span.text,
                "speaker": span.speaker,
                "confidence": span.confidence,
            }
            for span in result.spans
        ],
    }

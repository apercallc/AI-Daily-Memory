"""PostgreSQL schema (ADR-0009): every user-owned row carries `user_id`, and children of a user
cascade on delete so account deletion is one statement. The backend is the only client.

Changes go through Alembic migrations (`migrations/`); `tests/test_migrations.py` fails if
the models and the migrations drift apart.
"""

import uuid
from datetime import date, datetime
from typing import Literal, get_args

from sqlalchemy import (
    ARRAY,
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    MetaData,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# Stable constraint names make migrations reviewable and reversible.
NAMING = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}

StorageMode = Literal["phone_only", "reports_cloud", "full_cloud"]
UploadState = Literal["pending", "received", "transcribed", "delivered", "failed"]
STORAGE_MODES: tuple[str, ...] = get_args(StorageMode)
UPLOAD_STATES: tuple[str, ...] = get_args(UploadState)
JOB_STATES = ("queued", "running", "succeeded", "dead")
TOKEN_KINDS = ("device", "api")

# Phase 2 structured extraction (TODO.md, ADR-0019): kept in sync with app/reports/daily.py's
# Confidence and Commitment.direction literals, which are the contract these rows are read into.
MemoryItemKind = Literal["decision", "action", "commitment", "idea", "question"]
Confidence = Literal["confirmed", "likely", "possible"]
CommitmentDirection = Literal["i_owe", "owed_to_me"]
MEMORY_ITEM_KINDS: tuple[str, ...] = get_args(MemoryItemKind)
CONFIDENCE_LEVELS: tuple[str, ...] = get_args(Confidence)
COMMITMENT_DIRECTIONS: tuple[str, ...] = get_args(CommitmentDirection)
ReportStatus = Literal["complete", "partial"]
REPORT_STATUSES: tuple[str, ...] = get_args(ReportStatus)


def _in(column: str, values: tuple[str, ...]) -> str:
    return f"{column} IN ({', '.join(repr(v) for v in values)})"


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING)
    type_annotation_map = {  # noqa: RUF012 - SQLAlchemy reads this class attribute
        datetime: DateTime(timezone=True),
        uuid.UUID: UUID(as_uuid=True),
    }


def _user_fk() -> Mapped[uuid.UUID]:
    return mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)


def _created() -> Mapped[datetime]:
    return mapped_column(server_default=func.now())


class User(Base):
    __tablename__ = "users"
    __table_args__ = (CheckConstraint(_in("storage_mode", STORAGE_MODES), name="storage_mode"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    # Where memory lives (ADR-0014); phone-only is the default the app preselects.
    storage_mode: Mapped[str] = mapped_column(String(16), server_default="phone_only")
    created_at: Mapped[datetime] = _created()


class DeletedAccountObjectPurge(Base):
    """A minimal deletion tombstone that outlives the user's cascading database delete.

    The worker removes objects written through any still-valid presigned URL after its TTL.
    """

    __tablename__ = "pending_account_object_deletions"
    __table_args__ = (Index("ix_pending_account_object_deletions_purge_after", "purge_after"),)

    account_id: Mapped[uuid.UUID] = mapped_column(primary_key=True)
    purge_after: Mapped[datetime]


class Credential(Base):
    """Email + password for self-service sign-in (TODO Phase 1 "Account sign-in flow"). Kept
    separate from `User` so an admin-provisioned, password-less account stays representable."""

    __tablename__ = "credentials"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    # Always lowercased before it reaches this column (app.credentials.normalize_email).
    email: Mapped[str] = mapped_column(String(320), unique=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime] = _created()
    failed_attempts: Mapped[int] = mapped_column(Integer, server_default="0")
    locked_until: Mapped[datetime | None]


class AccessToken(Base):
    """Device tokens and user API keys (AGENTS rule 4): only a SHA-256 hash is stored."""

    __tablename__ = "access_tokens"
    __table_args__ = (CheckConstraint(_in("kind", TOKEN_KINDS), name="kind"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    token_hash: Mapped[bytes] = mapped_column(LargeBinary(32), unique=True)
    kind: Mapped[str] = mapped_column(String(8))
    scopes: Mapped[list[str]] = mapped_column(ARRAY(Text))
    name: Mapped[str] = mapped_column(String(80))
    created_at: Mapped[datetime] = _created()
    last_used_at: Mapped[datetime | None]
    expires_at: Mapped[datetime | None]
    revoked_at: Mapped[datetime | None]


class AudioUpload(Base):
    """One speech clip (ADR-0015) uploaded for transcription. The id is the client's UUIDv7
    (ADR-0006), so retries are idempotent; audio itself lives only temporarily in object
    storage under `users/{user_id}/` (ADR-0003, ADR-0009)."""

    __tablename__ = "audio_uploads"
    __table_args__ = (
        CheckConstraint(_in("state", UPLOAD_STATES), name="state"),
        CheckConstraint("offset_ms >= 0 AND duration_ms > 0 AND byte_size > 0", name="sizes"),
        UniqueConstraint("user_id", "segment_id", "offset_ms"),
        # The daily quota sums a user's recent uploads.
        Index("ix_audio_uploads_user_id_created_at", "user_id", "created_at"),
        # The retention sweep selects candidates by state and age (app/retention.py); without
        # this every pass was a full-table scan.
        Index("ix_audio_uploads_state_created_at", "state", "created_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True)
    user_id: Mapped[uuid.UUID] = _user_fk()
    session_id: Mapped[uuid.UUID]
    segment_id: Mapped[uuid.UUID]
    offset_ms: Mapped[int] = mapped_column(Integer)
    duration_ms: Mapped[int] = mapped_column(Integer)
    byte_size: Mapped[int] = mapped_column(BigInteger)
    sha256: Mapped[str] = mapped_column(String(64))
    codec: Mapped[str] = mapped_column(String(32))
    sample_rate_hz: Mapped[int] = mapped_column(Integer)
    channel_count: Mapped[int] = mapped_column(Integer)
    captured_at: Mapped[datetime]
    state: Mapped[str] = mapped_column(String(16), server_default="pending")
    object_key: Mapped[str] = mapped_column(String(200))
    created_at: Mapped[datetime] = _created()
    received_at: Mapped[datetime | None]
    # Why processing ended in `failed` (operator-visible, never content).
    failure_code: Mapped[str | None] = mapped_column(String(64))
    # The transcript revision in use. Kept here, not as a flag on the evidence rows, so
    # re-transcription never updates a transcript (ADR-0004: evidence is immutable).
    active_transcript_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("transcripts.id", ondelete="SET NULL", use_alter=True),
    )
    # Phone storage modes (ADR-0014): the key of the device that uploaded this clip. Its
    # transcript is sealed to exactly this key, never to another device of the same user.
    device_key_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("device_keys.id", ondelete="SET NULL", use_alter=True),
    )


class ProcessingJob(Base):
    """PostgreSQL-backed job queue: claim with SKIP LOCKED and a lease, bounded backoff, and a
    terminal `dead` state with an operator-visible reason. No Redis until measured (Gate G)."""

    __tablename__ = "processing_jobs"
    __table_args__ = (
        CheckConstraint(_in("state", JOB_STATES), name="state"),
        Index("ix_processing_jobs_claimable", "kind", "state", "run_after"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    kind: Mapped[str] = mapped_column(String(32))
    idempotency_key: Mapped[str] = mapped_column(String(200), unique=True)
    subject_id: Mapped[uuid.UUID]
    state: Mapped[str] = mapped_column(String(16), server_default="queued")
    attempts: Mapped[int] = mapped_column(Integer, server_default="0")
    max_attempts: Mapped[int] = mapped_column(Integer, server_default="5")
    run_after: Mapped[datetime] = mapped_column(server_default=func.now())
    lease_owner: Mapped[str | None] = mapped_column(String(64))
    lease_expires_at: Mapped[datetime | None]
    last_error_code: Mapped[str | None] = mapped_column(String(64))
    created_at: Mapped[datetime] = _created()
    updated_at: Mapped[datetime] = mapped_column(server_default=func.now(), onupdate=func.now())


class Transcript(Base):
    """Evidence (ADR-0004): inserted once, never updated. Re-transcription adds a revision;
    the upload's `active_transcript_id` says which one is in use."""

    __tablename__ = "transcripts"
    __table_args__ = (UniqueConstraint("upload_id", "revision"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    upload_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("audio_uploads.id", ondelete="CASCADE"),
    )
    revision: Mapped[int] = mapped_column(Integer)
    provider: Mapped[str] = mapped_column(String(40))
    model: Mapped[str] = mapped_column(String(80))
    language: Mapped[str | None] = mapped_column(String(16))
    created_at: Mapped[datetime] = _created()


class TranscriptSegment(Base):
    __tablename__ = "transcript_segments"
    __table_args__ = (CheckConstraint("start_ms >= 0 AND end_ms >= start_ms", name="span"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    transcript_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("transcripts.id", ondelete="CASCADE"),
        index=True,
    )
    # Milliseconds from the start of the upload's clip.
    start_ms: Mapped[int] = mapped_column(Integer)
    end_ms: Mapped[int] = mapped_column(Integer)
    text: Mapped[str] = mapped_column(Text)
    speaker: Mapped[str | None] = mapped_column(String(32))
    confidence: Mapped[float | None]


class DeviceKey(Base):
    """A phone's HPKE public key (X25519, ADR-0014). Transcripts for users who keep their
    memory on the phone are sealed to it, so the server can't read what it holds."""

    __tablename__ = "device_keys"
    __table_args__ = (
        Index(
            "uq_device_keys_one_active_per_token",
            "token_id",
            unique=True,
            postgresql_where=text("revoked_at IS NULL"),
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    token_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("access_tokens.id", ondelete="CASCADE"))
    public_key: Mapped[bytes] = mapped_column(LargeBinary(32))
    created_at: Mapped[datetime] = _created()
    revoked_at: Mapped[datetime | None]


class SealedDelivery(Base):
    """A transcript sealed to a device key, waiting for the phone to fetch and acknowledge it.
    Deleted on acknowledgement or after the 7-day hold (ADR-0014)."""

    __tablename__ = "sealed_deliveries"
    __table_args__ = (UniqueConstraint("upload_id", "device_key_id"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    upload_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("audio_uploads.id", ondelete="CASCADE"))
    device_key_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("device_keys.id", ondelete="CASCADE"),
        index=True,
    )
    ciphertext: Mapped[bytes] = mapped_column(LargeBinary)
    created_at: Mapped[datetime] = _created()
    expires_at: Mapped[datetime]


class MemoryItem(Base):
    """One extracted decision/action/commitment/idea/question (Phase 2, ADR-0019). Extraction
    only ever runs for `full_cloud` accounts: it's the only mode where the server holds a
    plaintext transcript to extract from (app/transcription.py). `memory_day` is currently the
    UTC calendar date of the session's first clip — a placeholder for the phone's real
    sleep-midpoint memory day (ADR-0012), which the upload protocol doesn't carry yet."""

    __tablename__ = "memory_items"
    __table_args__ = (
        CheckConstraint(_in("kind", MEMORY_ITEM_KINDS), name="kind"),
        CheckConstraint(_in("confidence", CONFIDENCE_LEVELS), name="confidence"),
        CheckConstraint(
            "kind != 'commitment' OR direction IS NOT NULL", name="commitment_has_direction"
        ),
        Index("ix_memory_items_user_id_memory_day", "user_id", "memory_day"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    session_id: Mapped[uuid.UUID]
    memory_day: Mapped[date]
    kind: Mapped[str] = mapped_column(String(16))
    text: Mapped[str] = mapped_column(String(280))
    confidence: Mapped[str] = mapped_column(String(16))
    owner: Mapped[str | None] = mapped_column(String(80))  # action items only
    counterpart: Mapped[str | None] = mapped_column(String(80))  # commitments only
    direction: Mapped[str | None] = mapped_column(String(16))  # commitments only
    due: Mapped[date | None]
    extraction_model: Mapped[str] = mapped_column(String(80))
    extraction_version: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = _created()
    # A user right (ADR-0004 amendment): "not a task" without deleting the evidence behind it.
    # Correcting or editing text isn't implemented yet — see TODO.md "Human correction".
    dismissed_at: Mapped[datetime | None]


class MemoryItemEvidence(Base):
    """Many-to-many: a memory item cites one or more transcript segments (ADR-0004, TODO.md
    "Memory items cite evidence many-to-many")."""

    __tablename__ = "memory_item_evidence"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    memory_item_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("memory_items.id", ondelete="CASCADE"),
        index=True,
    )
    transcript_segment_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("transcript_segments.id", ondelete="CASCADE"),
    )
    quote: Mapped[str | None] = mapped_column(String(280))


class DailyReportRow(Base):
    """The assembled report (app/reports/daily.py's `DailyReport` contract, stored as JSON) for
    one user's memory day. Regenerated in place whenever extraction produces new items for that
    day; `extraction_version` says which extraction pass produced it."""

    __tablename__ = "daily_reports"
    __table_args__ = (
        CheckConstraint(_in("status", REPORT_STATUSES), name="status"),
        UniqueConstraint("user_id", "memory_day"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = _user_fk()
    memory_day: Mapped[date]
    status: Mapped[str] = mapped_column(String(16))
    report: Mapped[dict[str, object]] = mapped_column(JSONB)
    extraction_version: Mapped[int] = mapped_column(Integer)
    generated_at: Mapped[datetime] = mapped_column(server_default=func.now(), onupdate=func.now())

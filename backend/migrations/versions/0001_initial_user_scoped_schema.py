"""initial user-scoped schema

Revision ID: 0001
Revises:
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column(
            "storage_mode", sa.String(length=16), server_default="phone_only", nullable=False
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "storage_mode IN ('phone_only', 'reports_cloud', 'full_cloud')",
            name=op.f("ck_users_storage_mode"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_users")),
    )
    op.create_table(
        "access_tokens",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("token_hash", sa.LargeBinary(length=32), nullable=False),
        sa.Column("kind", sa.String(length=8), nullable=False),
        sa.Column("scopes", sa.ARRAY(sa.Text()), nullable=False),
        sa.Column("name", sa.String(length=80), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("last_used_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("kind IN ('device', 'api')", name=op.f("ck_access_tokens_kind")),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_access_tokens_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_access_tokens")),
        sa.UniqueConstraint("token_hash", name=op.f("uq_access_tokens_token_hash")),
    )
    op.create_index(op.f("ix_access_tokens_user_id"), "access_tokens", ["user_id"], unique=False)
    op.create_table(
        "audio_uploads",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("session_id", sa.UUID(), nullable=False),
        sa.Column("segment_id", sa.UUID(), nullable=False),
        sa.Column("offset_ms", sa.Integer(), nullable=False),
        sa.Column("duration_ms", sa.Integer(), nullable=False),
        sa.Column("byte_size", sa.BigInteger(), nullable=False),
        sa.Column("sha256", sa.String(length=64), nullable=False),
        sa.Column("codec", sa.String(length=32), nullable=False),
        sa.Column("sample_rate_hz", sa.Integer(), nullable=False),
        sa.Column("channel_count", sa.Integer(), nullable=False),
        sa.Column("captured_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("state", sa.String(length=16), server_default="pending", nullable=False),
        sa.Column("object_key", sa.String(length=200), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("received_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("failure_code", sa.String(length=64), nullable=True),
        sa.Column("active_transcript_id", sa.UUID(), nullable=True),
        sa.CheckConstraint(
            "state IN ('pending', 'received', 'transcribed', 'failed')",
            name=op.f("ck_audio_uploads_state"),
        ),
        sa.CheckConstraint(
            "offset_ms >= 0 AND duration_ms > 0 AND byte_size > 0",
            name=op.f("ck_audio_uploads_sizes"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_audio_uploads_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_audio_uploads")),
        sa.UniqueConstraint(
            "user_id",
            "segment_id",
            "offset_ms",
            name=op.f("uq_audio_uploads_user_id_segment_id_offset_ms"),
        ),
    )
    op.create_index(op.f("ix_audio_uploads_user_id"), "audio_uploads", ["user_id"], unique=False)
    op.create_index(
        "ix_audio_uploads_user_id_created_at",
        "audio_uploads",
        ["user_id", "created_at"],
        unique=False,
    )
    op.create_table(
        "processing_jobs",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("kind", sa.String(length=32), nullable=False),
        sa.Column("idempotency_key", sa.String(length=200), nullable=False),
        sa.Column("subject_id", sa.UUID(), nullable=False),
        sa.Column("state", sa.String(length=16), server_default="queued", nullable=False),
        sa.Column("attempts", sa.Integer(), server_default="0", nullable=False),
        sa.Column("max_attempts", sa.Integer(), server_default="5", nullable=False),
        sa.Column(
            "run_after", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False
        ),
        sa.Column("lease_owner", sa.String(length=64), nullable=True),
        sa.Column("lease_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_error_code", sa.String(length=64), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "state IN ('queued', 'running', 'succeeded', 'dead')",
            name=op.f("ck_processing_jobs_state"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_processing_jobs_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_processing_jobs")),
        sa.UniqueConstraint("idempotency_key", name=op.f("uq_processing_jobs_idempotency_key")),
    )
    op.create_index(
        "ix_processing_jobs_claimable",
        "processing_jobs",
        ["kind", "state", "run_after"],
        unique=False,
    )
    op.create_index(
        op.f("ix_processing_jobs_user_id"), "processing_jobs", ["user_id"], unique=False
    )
    op.create_table(
        "transcripts",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("upload_id", sa.UUID(), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("provider", sa.String(length=40), nullable=False),
        sa.Column("model", sa.String(length=80), nullable=False),
        sa.Column("language", sa.String(length=16), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["upload_id"],
            ["audio_uploads.id"],
            name=op.f("fk_transcripts_upload_id_audio_uploads"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name=op.f("fk_transcripts_user_id_users"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_transcripts")),
        sa.UniqueConstraint(
            "upload_id", "revision", name=op.f("uq_transcripts_upload_id_revision")
        ),
    )
    op.create_index(op.f("ix_transcripts_user_id"), "transcripts", ["user_id"], unique=False)
    # Circular with transcripts.upload_id, so it is added once both tables exist.
    op.create_foreign_key(
        op.f("fk_audio_uploads_active_transcript_id_transcripts"),
        "audio_uploads",
        "transcripts",
        ["active_transcript_id"],
        ["id"],
        ondelete="SET NULL",
    )
    op.create_table(
        "transcript_segments",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("transcript_id", sa.UUID(), nullable=False),
        sa.Column("start_ms", sa.Integer(), nullable=False),
        sa.Column("end_ms", sa.Integer(), nullable=False),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("speaker", sa.String(length=32), nullable=True),
        sa.Column("confidence", sa.Double(), nullable=True),
        sa.CheckConstraint(
            "start_ms >= 0 AND end_ms >= start_ms", name=op.f("ck_transcript_segments_span")
        ),
        sa.ForeignKeyConstraint(
            ["transcript_id"],
            ["transcripts.id"],
            name=op.f("fk_transcript_segments_transcript_id_transcripts"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_transcript_segments_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_transcript_segments")),
    )
    op.create_index(
        op.f("ix_transcript_segments_transcript_id"),
        "transcript_segments",
        ["transcript_id"],
        unique=False,
    )
    op.create_index(
        op.f("ix_transcript_segments_user_id"), "transcript_segments", ["user_id"], unique=False
    )


def downgrade() -> None:
    op.drop_constraint(
        op.f("fk_audio_uploads_active_transcript_id_transcripts"),
        "audio_uploads",
        type_="foreignkey",
    )
    op.drop_index(op.f("ix_transcript_segments_user_id"), table_name="transcript_segments")
    op.drop_index(op.f("ix_transcript_segments_transcript_id"), table_name="transcript_segments")
    op.drop_table("transcript_segments")
    op.drop_index(op.f("ix_transcripts_user_id"), table_name="transcripts")
    op.drop_table("transcripts")
    op.drop_index(op.f("ix_processing_jobs_user_id"), table_name="processing_jobs")
    op.drop_index("ix_processing_jobs_claimable", table_name="processing_jobs")
    op.drop_table("processing_jobs")
    op.drop_index("ix_audio_uploads_user_id_created_at", table_name="audio_uploads")
    op.drop_index(op.f("ix_audio_uploads_user_id"), table_name="audio_uploads")
    op.drop_table("audio_uploads")
    op.drop_index(op.f("ix_access_tokens_user_id"), table_name="access_tokens")
    op.drop_table("access_tokens")
    op.drop_table("users")

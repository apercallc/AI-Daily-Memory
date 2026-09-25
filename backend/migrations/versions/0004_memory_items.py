"""structured extraction and daily reports (Phase 2)

Revision ID: 0004
Revises: 0003
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "memory_items",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("session_id", sa.UUID(), nullable=False),
        sa.Column("memory_day", sa.Date(), nullable=False),
        sa.Column("kind", sa.String(length=16), nullable=False),
        sa.Column("text", sa.String(length=280), nullable=False),
        sa.Column("confidence", sa.String(length=16), nullable=False),
        sa.Column("owner", sa.String(length=80), nullable=True),
        sa.Column("counterpart", sa.String(length=80), nullable=True),
        sa.Column("direction", sa.String(length=16), nullable=True),
        sa.Column("due", sa.Date(), nullable=True),
        sa.Column("extraction_model", sa.String(length=80), nullable=False),
        sa.Column("extraction_version", sa.Integer(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("dismissed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "kind IN ('decision', 'action', 'commitment', 'idea', 'question')",
            name=op.f("ck_memory_items_kind"),
        ),
        sa.CheckConstraint(
            "confidence IN ('confirmed', 'likely', 'possible')",
            name=op.f("ck_memory_items_confidence"),
        ),
        sa.CheckConstraint(
            "kind != 'commitment' OR direction IS NOT NULL",
            name=op.f("ck_memory_items_commitment_has_direction"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_memory_items_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_memory_items")),
    )
    op.create_index(op.f("ix_memory_items_user_id"), "memory_items", ["user_id"], unique=False)
    op.create_index(
        "ix_memory_items_user_id_memory_day",
        "memory_items",
        ["user_id", "memory_day"],
        unique=False,
    )

    op.create_table(
        "memory_item_evidence",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("memory_item_id", sa.UUID(), nullable=False),
        sa.Column("transcript_segment_id", sa.UUID(), nullable=False),
        sa.Column("quote", sa.String(length=280), nullable=True),
        sa.ForeignKeyConstraint(
            ["memory_item_id"],
            ["memory_items.id"],
            name=op.f("fk_memory_item_evidence_memory_item_id_memory_items"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["transcript_segment_id"],
            ["transcript_segments.id"],
            name=op.f("fk_memory_item_evidence_transcript_segment_id_transcript_segments"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_memory_item_evidence_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_memory_item_evidence")),
    )
    op.create_index(
        op.f("ix_memory_item_evidence_user_id"), "memory_item_evidence", ["user_id"], unique=False
    )
    op.create_index(
        op.f("ix_memory_item_evidence_memory_item_id"),
        "memory_item_evidence",
        ["memory_item_id"],
        unique=False,
    )

    op.create_table(
        "daily_reports",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("memory_day", sa.Date(), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("report", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("extraction_version", sa.Integer(), nullable=False),
        sa.Column(
            "generated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "status IN ('complete', 'partial')", name=op.f("ck_daily_reports_status")
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_daily_reports_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_daily_reports")),
        sa.UniqueConstraint(
            "user_id", "memory_day", name=op.f("uq_daily_reports_user_id_memory_day")
        ),
    )
    op.create_index(op.f("ix_daily_reports_user_id"), "daily_reports", ["user_id"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_daily_reports_user_id"), table_name="daily_reports")
    op.drop_table("daily_reports")
    op.drop_index(op.f("ix_memory_item_evidence_memory_item_id"), table_name="memory_item_evidence")
    op.drop_index(op.f("ix_memory_item_evidence_user_id"), table_name="memory_item_evidence")
    op.drop_table("memory_item_evidence")
    op.drop_index("ix_memory_items_user_id_memory_day", table_name="memory_items")
    op.drop_index(op.f("ix_memory_items_user_id"), table_name="memory_items")
    op.drop_table("memory_items")

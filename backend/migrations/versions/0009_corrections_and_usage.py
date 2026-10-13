"""memory item corrections and per-user usage counters

Revision ID: 0009
Revises: 0008
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0009"
down_revision: str | None = "0008"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "memory_item_corrections",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("memory_item_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("text", sa.String(length=280), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["memory_item_id"],
            ["memory_items.id"],
            name=op.f("fk_memory_item_corrections_memory_item_id_memory_items"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_memory_item_corrections_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_memory_item_corrections")),
        sa.UniqueConstraint(
            "memory_item_id", name=op.f("uq_memory_item_corrections_memory_item_id")
        ),
    )
    op.create_index(
        op.f("ix_memory_item_corrections_user_id"), "memory_item_corrections", ["user_id"]
    )
    op.create_table(
        "usage_counters",
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("kind", sa.String(length=16), nullable=False),
        sa.Column("count", sa.Integer(), server_default="0", nullable=False),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_usage_counters_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("user_id", "day", "kind", name=op.f("pk_usage_counters")),
    )


def downgrade() -> None:
    op.drop_table("usage_counters")
    op.drop_index(op.f("ix_memory_item_corrections_user_id"), table_name="memory_item_corrections")
    op.drop_table("memory_item_corrections")

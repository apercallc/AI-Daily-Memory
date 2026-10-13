"""email tokens, email verification, and plan columns

Revision ID: 0010
Revises: 0009
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0010"
down_revision: str | None = "0009"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("credentials", sa.Column("email_verified_at", sa.DateTime(timezone=True)))
    op.add_column(
        "users", sa.Column("plan", sa.String(length=16), server_default="free", nullable=False)
    )
    op.add_column("users", sa.Column("stripe_customer_id", sa.String(length=64)))
    op.add_column("users", sa.Column("plan_renews_at", sa.DateTime(timezone=True)))
    op.add_column(
        "users",
        sa.Column(
            "plan_cancels_at_period_end", sa.Boolean(), server_default="false", nullable=False
        ),
    )
    op.create_unique_constraint(
        op.f("uq_users_stripe_customer_id"), "users", ["stripe_customer_id"]
    )
    op.create_table(
        "email_tokens",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("purpose", sa.String(length=8), nullable=False),
        sa.Column("token_hash", sa.LargeBinary(length=32), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True)),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint("purpose IN ('reset', 'verify')", name=op.f("ck_email_tokens_purpose")),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_email_tokens_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_email_tokens")),
        sa.UniqueConstraint("token_hash", name=op.f("uq_email_tokens_token_hash")),
    )
    op.create_index(op.f("ix_email_tokens_user_id"), "email_tokens", ["user_id"])


def downgrade() -> None:
    op.drop_index(op.f("ix_email_tokens_user_id"), table_name="email_tokens")
    op.drop_table("email_tokens")
    op.drop_constraint(op.f("uq_users_stripe_customer_id"), "users", type_="unique")
    for column in ("plan_cancels_at_period_end", "plan_renews_at", "stripe_customer_id", "plan"):
        op.drop_column("users", column)
    op.drop_column("credentials", "email_verified_at")

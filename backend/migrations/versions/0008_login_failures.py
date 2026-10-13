"""per-source login failure tracking

Revision ID: 0008
Revises: 0007
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0008"
down_revision: str | None = "0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "login_failures",
        sa.Column("credential_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("source_hash", sa.String(length=64), nullable=False),
        sa.Column("failed_attempts", sa.Integer(), server_default="0", nullable=False),
        sa.Column("locked_until", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["credential_id"],
            ["credentials.id"],
            name=op.f("fk_login_failures_credential_id_credentials"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("credential_id", "source_hash", name=op.f("pk_login_failures")),
    )
    op.create_index("ix_login_failures_updated_at", "login_failures", ["updated_at"])


def downgrade() -> None:
    op.drop_index("ix_login_failures_updated_at", table_name="login_failures")
    op.drop_table("login_failures")

"""bound deleted-account object purge retries with a dead-letter state

Revision ID: 0015
Revises: 0014
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0015"
down_revision: str | None = "0014"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "pending_account_object_deletions",
        sa.Column("attempts", sa.Integer(), server_default="0", nullable=False),
    )
    op.add_column(
        "pending_account_object_deletions",
        sa.Column("last_error_code", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "pending_account_object_deletions",
        sa.Column("dead_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("pending_account_object_deletions", "dead_at")
    op.drop_column("pending_account_object_deletions", "last_error_code")
    op.drop_column("pending_account_object_deletions", "attempts")

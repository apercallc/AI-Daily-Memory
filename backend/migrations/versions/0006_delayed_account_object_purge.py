"""retain account object purge jobs after cascading user deletion

Revision ID: 0006
Revises: 0005
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "pending_account_object_deletions",
        sa.Column("account_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("purge_after", sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint(
            "account_id",
            name=op.f("pk_pending_account_object_deletions"),
        ),
    )
    op.create_index(
        "ix_pending_account_object_deletions_purge_after",
        "pending_account_object_deletions",
        ["purge_after"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index(
        "ix_pending_account_object_deletions_purge_after",
        table_name="pending_account_object_deletions",
    )
    op.drop_table("pending_account_object_deletions")

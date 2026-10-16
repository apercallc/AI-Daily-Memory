"""add format to account_exports for the JSONL export option

Revision ID: 0017
Revises: 0016
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0017"
down_revision: str | None = "0016"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "account_exports",
        sa.Column("format", sa.String(length=5), nullable=False, server_default="json"),
    )


def downgrade() -> None:
    op.drop_column("account_exports", "format")

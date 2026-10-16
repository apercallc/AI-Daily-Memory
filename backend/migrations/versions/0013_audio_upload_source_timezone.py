"""audio uploads carry the clip's own IANA timezone

Revision ID: 0013
Revises: 0012
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0013"
down_revision: str | None = "0012"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "audio_uploads", sa.Column("source_timezone", sa.String(length=64), nullable=True)
    )


def downgrade() -> None:
    op.drop_column("audio_uploads", "source_timezone")

"""retention sweep index

Revision ID: 0005
Revises: 0004
"""

from collections.abc import Sequence

from alembic import op

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # The retention sweep (app/retention.py) selects its candidates by state and age:
    # `state = 'pending' AND created_at < ...`, `state = 'received' AND ...`, and the
    # finished-metadata purge. Without this index every pass was a full-table scan.
    op.create_index(
        "ix_audio_uploads_state_created_at",
        "audio_uploads",
        ["state", "created_at"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index("ix_audio_uploads_state_created_at", table_name="audio_uploads")

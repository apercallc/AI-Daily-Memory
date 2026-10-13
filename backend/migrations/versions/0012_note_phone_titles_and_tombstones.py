"""phone-given note titles and sessions removed from the web

Revision ID: 0012
Revises: 0011
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0012"
down_revision: str | None = "0011"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("notes", sa.Column("phone_title", sa.String(length=120), nullable=True))
    op.create_table(
        "deleted_notes",
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_deleted_notes_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("user_id", "session_id", name=op.f("pk_deleted_notes")),
    )


def downgrade() -> None:
    op.drop_table("deleted_notes")
    op.drop_column("notes", "phone_title")

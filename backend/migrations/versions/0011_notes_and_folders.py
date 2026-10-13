"""notes and note folders

Revision ID: 0011
Revises: 0010
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0011"
down_revision: str | None = "0010"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "note_folders",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("name", sa.String(length=60), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "char_length(name) BETWEEN 1 AND 60", name=op.f("ck_note_folders_name_length")
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_note_folders_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_note_folders")),
    )
    op.create_index(op.f("ix_note_folders_user_id"), "note_folders", ["user_id"])
    op.create_index(
        "uq_note_folders_user_id_lower_name",
        "note_folders",
        ["user_id", sa.text("lower(name)")],
        unique=True,
    )
    op.create_table(
        "notes",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("memory_day", sa.Date(), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("auto_title", sa.String(length=120), nullable=False),
        sa.Column(
            "title_from_transcript", sa.Boolean(), server_default=sa.text("false"), nullable=False
        ),
        sa.Column("custom_title", sa.String(length=120), nullable=True),
        sa.Column("item_preview", sa.String(length=240), server_default="", nullable=False),
        sa.Column("lead", sa.String(length=240), server_default="", nullable=False),
        sa.Column("transcript_text", sa.Text(), server_default="", nullable=False),
        sa.Column("line_count", sa.Integer(), server_default="0", nullable=False),
        sa.Column("word_count", sa.Integer(), server_default="0", nullable=False),
        sa.Column("folder_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("starred", sa.Boolean(), server_default=sa.text("false"), nullable=False),
        sa.Column("archived_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["folder_id"],
            ["note_folders.id"],
            name=op.f("fk_notes_folder_id_note_folders"),
            ondelete="SET NULL",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name=op.f("fk_notes_user_id_users"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_notes")),
        sa.UniqueConstraint("user_id", "session_id", name=op.f("uq_notes_user_id_session_id")),
    )
    op.create_index(op.f("ix_notes_user_id"), "notes", ["user_id"])
    op.create_index("ix_notes_user_id_memory_day", "notes", ["user_id", "memory_day"])
    op.create_index("ix_notes_user_id_folder_id", "notes", ["user_id", "folder_id"])


def downgrade() -> None:
    op.drop_table("notes")
    op.drop_table("note_folders")

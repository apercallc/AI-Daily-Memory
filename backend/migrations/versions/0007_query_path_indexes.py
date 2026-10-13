"""indexes for the hot query paths

Revision ID: 0007
Revises: 0006
"""

from collections.abc import Sequence

from alembic import op

revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# (name, table, columns) -- each one backs a query that otherwise scans the table:
# - sealed_deliveries.expires_at: the retention sweep's expiry delete;
# - processing_jobs.subject_id: dead-job lookup per upload and per-subject job cleanup;
# - audio_uploads (user_id, session_id): session deletion and per-session clip counts;
# - audio_uploads (user_id, captured_at): building a user's day report;
# - memory_items (user_id, session_id): session deletion and per-session item replacement.
INDEXES: tuple[tuple[str, str, list[str]], ...] = (
    ("ix_sealed_deliveries_expires_at", "sealed_deliveries", ["expires_at"]),
    ("ix_processing_jobs_subject_id", "processing_jobs", ["subject_id"]),
    ("ix_audio_uploads_user_id_session_id", "audio_uploads", ["user_id", "session_id"]),
    ("ix_audio_uploads_user_id_captured_at", "audio_uploads", ["user_id", "captured_at"]),
    ("ix_memory_items_user_id_session_id", "memory_items", ["user_id", "session_id"]),
)


def upgrade() -> None:
    for name, table, columns in INDEXES:
        op.create_index(name, table, columns, unique=False)


def downgrade() -> None:
    for name, table, _ in reversed(INDEXES):
        op.drop_index(name, table_name=table)

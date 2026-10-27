"""append-only security audit trail

Revision ID: 0018
Revises: 0017
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0018"
down_revision: str | None = "0017"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "audit_events",
        sa.Column("id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column(
            "occurred_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column("event", sa.String(length=48), nullable=False),
        sa.Column("outcome", sa.String(length=8), nullable=False),
        sa.Column("actor_ref", sa.String(length=64), nullable=True),
        sa.Column("request_id", sa.String(length=64), nullable=True),
        sa.Column(
            "detail",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "outcome IN ('success', 'failure', 'denied')", name=op.f("ck_audit_events_outcome")
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_audit_events")),
    )
    op.create_index("ix_audit_events_occurred_at", "audit_events", ["occurred_at"])
    op.create_index(
        "ix_audit_events_actor_ref_occurred_at", "audit_events", ["actor_ref", "occurred_at"]
    )
    # The trail is only evidence if the application role cannot rewrite it.
    op.execute(
        """
        CREATE FUNCTION audit_events_reject_change() RETURNS trigger AS $$
        BEGIN
            RAISE EXCEPTION 'audit_events is append-only';
        END;
        $$ LANGUAGE plpgsql
        """
    )
    op.execute(
        """
        CREATE TRIGGER audit_events_append_only
        BEFORE UPDATE OR DELETE ON audit_events
        FOR EACH ROW EXECUTE FUNCTION audit_events_reject_change()
        """
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER audit_events_append_only ON audit_events")
    op.execute("DROP FUNCTION audit_events_reject_change()")
    op.drop_index("ix_audit_events_actor_ref_occurred_at", table_name="audit_events")
    op.drop_index("ix_audit_events_occurred_at", table_name="audit_events")
    op.drop_table("audit_events")

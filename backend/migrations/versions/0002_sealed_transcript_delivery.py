"""sealed transcript delivery

Revision ID: 0002
Revises: 0001
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # "delivered": sealed to the phone and acknowledged (ADR-0014). Autogenerate can't see
    # CHECK changes; tests/test_migrations.py compares them.
    op.drop_constraint(op.f("ck_audio_uploads_state"), "audio_uploads", type_="check")
    op.create_check_constraint(
        op.f("ck_audio_uploads_state"),
        "audio_uploads",
        "state IN ('pending', 'received', 'transcribed', 'delivered', 'failed')",
    )
    op.create_table(
        "device_keys",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("token_id", sa.UUID(), nullable=False),
        sa.Column("public_key", sa.LargeBinary(length=32), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(
            ["token_id"],
            ["access_tokens.id"],
            name=op.f("fk_device_keys_token_id_access_tokens"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name=op.f("fk_device_keys_user_id_users"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_device_keys")),
    )
    op.create_index(op.f("ix_device_keys_user_id"), "device_keys", ["user_id"], unique=False)
    op.create_index(
        "uq_device_keys_one_active_per_token",
        "device_keys",
        ["token_id"],
        unique=True,
        postgresql_where=sa.text("revoked_at IS NULL"),
    )
    op.create_table(
        "sealed_deliveries",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("user_id", sa.UUID(), nullable=False),
        sa.Column("upload_id", sa.UUID(), nullable=False),
        sa.Column("device_key_id", sa.UUID(), nullable=False),
        sa.Column("ciphertext", sa.LargeBinary(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(
            ["device_key_id"],
            ["device_keys.id"],
            name=op.f("fk_sealed_deliveries_device_key_id_device_keys"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["upload_id"],
            ["audio_uploads.id"],
            name=op.f("fk_sealed_deliveries_upload_id_audio_uploads"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.id"],
            name=op.f("fk_sealed_deliveries_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_sealed_deliveries")),
        sa.UniqueConstraint(
            "upload_id", "device_key_id", name=op.f("uq_sealed_deliveries_upload_id_device_key_id")
        ),
    )
    op.create_index(
        op.f("ix_sealed_deliveries_device_key_id"),
        "sealed_deliveries",
        ["device_key_id"],
        unique=False,
    )
    op.create_index(
        op.f("ix_sealed_deliveries_user_id"), "sealed_deliveries", ["user_id"], unique=False
    )


def downgrade() -> None:
    op.execute("UPDATE audio_uploads SET state = 'transcribed' WHERE state = 'delivered'")
    op.drop_constraint(op.f("ck_audio_uploads_state"), "audio_uploads", type_="check")
    op.create_check_constraint(
        op.f("ck_audio_uploads_state"),
        "audio_uploads",
        "state IN ('pending', 'received', 'transcribed', 'failed')",
    )
    op.drop_index(op.f("ix_sealed_deliveries_user_id"), table_name="sealed_deliveries")
    op.drop_index(op.f("ix_sealed_deliveries_device_key_id"), table_name="sealed_deliveries")
    op.drop_table("sealed_deliveries")
    op.drop_index(
        "uq_device_keys_one_active_per_token",
        table_name="device_keys",
        postgresql_where=sa.text("revoked_at IS NULL"),
    )
    op.drop_index(op.f("ix_device_keys_user_id"), table_name="device_keys")
    op.drop_table("device_keys")

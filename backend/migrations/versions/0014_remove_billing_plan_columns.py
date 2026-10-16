"""remove dormant Stripe billing and plan entitlement columns (ADR-0028)

Revision ID: 0014
Revises: 0013
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0014"
down_revision: str | None = "0013"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.drop_constraint(op.f("uq_users_stripe_customer_id"), "users", type_="unique")
    op.drop_column("users", "plan_cancels_at_period_end")
    op.drop_column("users", "plan_renews_at")
    op.drop_column("users", "stripe_customer_id")
    op.drop_column("users", "plan")


def downgrade() -> None:
    op.add_column(
        "users",
        sa.Column("plan", sa.String(length=16), server_default="free", nullable=False),
    )
    op.add_column("users", sa.Column("stripe_customer_id", sa.String(length=64)))
    op.add_column("users", sa.Column("plan_renews_at", sa.DateTime(timezone=True)))
    op.add_column(
        "users",
        sa.Column(
            "plan_cancels_at_period_end", sa.Boolean(), server_default="false", nullable=False
        ),
    )
    op.create_unique_constraint(
        op.f("uq_users_stripe_customer_id"), "users", ["stripe_customer_id"]
    )

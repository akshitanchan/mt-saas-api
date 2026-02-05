"""stripe webhook ledger

Revision ID: 0002_stripe_webhooks
Revises: 0001_init
Create Date: 2025-12-31
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0002_stripe_webhooks"
down_revision: Union[str, None] = "0001_init"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

def upgrade() -> None:
    op.create_table(
        "webhook_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("provider", sa.String(length=32), nullable=False),
        sa.Column("event_id", sa.String(length=255), nullable=False),
        sa.Column("event_type", sa.String(length=255), nullable=True),
        sa.Column("received_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("payload", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.UniqueConstraint("provider", "event_id", name="uq_webhook_events_provider_event_id"),
    )
    op.create_index(
        "ix_webhook_events_provider_event_id",
        "webhook_events",
        ["provider", "event_id"],
        unique=False,
    )
    op.add_column(
        "orgs",
        sa.Column("current_period_end", sa.DateTime(timezone=True), nullable=True),
    )

def downgrade() -> None:
    op.drop_index("ix_webhook_events_provider_event_id", table_name="webhook_events")
    op.drop_table("webhook_events")
    op.drop_column("orgs", "current_period_end")

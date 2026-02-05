"""webhook event status + error

Revision ID: 0003_webhook_event_status_error
Revises: 0002_stripe_webhooks
Create Date: 2025-12-31
"""
from __future__ import annotations

from typing import Sequence

import sqlalchemy as sa
from alembic import op

revision = "0003_webhook_event_status_error"
down_revision = "0002_stripe_webhooks"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

def upgrade() -> None:
    op.add_column(
        "webhook_events",
        sa.Column("status", sa.String(length=16), nullable=False, server_default="received"),
    )
    op.add_column(
        "webhook_events",
        sa.Column("error", sa.Text(), nullable=True),
    )

    # backfill: if it was processed before this migration, mark processed
    op.execute(
        "update webhook_events set status='processed' where processed_at is not null and status='received'"
    )

def downgrade() -> None:
    op.drop_column("webhook_events", "error")
    op.drop_column("webhook_events", "status")

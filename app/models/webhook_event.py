from datetime import datetime
from uuid import uuid4

import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base

class WebhookEvent(Base):
    __tablename__ = "webhook_events"

    id: Mapped[UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid4)

    provider: Mapped[str] = mapped_column(sa.String(50), nullable=False)
    event_id: Mapped[str] = mapped_column(sa.String(255), nullable=False)
    event_type: Mapped[str | None] = mapped_column(sa.String(255), nullable=True)

    received_at: Mapped[datetime] = mapped_column(
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.text("now()"),
    )
    processed_at: Mapped[datetime | None] = mapped_column(sa.DateTime(timezone=True), nullable=True)

    # phase c
    status: Mapped[str] = mapped_column(sa.String(16), nullable=False, server_default="received")
    error: Mapped[str | None] = mapped_column(sa.Text(), nullable=True)

    payload: Mapped[dict | None] = mapped_column(JSONB, nullable=True)

# unique(provider,event_id) is defined in migrations

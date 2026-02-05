from datetime import datetime
from uuid import uuid4

import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base

class Org(Base):
    __tablename__ = "orgs"

    id: Mapped[UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid4)

    created_at: Mapped[datetime] = mapped_column(
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.text("now()"),
    )

    name: Mapped[str] = mapped_column(sa.String(120), nullable=False)

    plan: Mapped[str] = mapped_column(
        sa.Enum("free", "pro", name="plan"),
        nullable=False,
        server_default="free",
    )

    subscription_status: Mapped[str] = mapped_column(
        sa.Enum(
            "none",
            "incomplete",
            "trialing",
            "active",
            "past_due",
            "canceled",
            "unpaid",
            name="subscription_status",
        ),
        nullable=False,
        server_default="none",
    )

    stripe_customer_id: Mapped[str | None] = mapped_column(sa.String(255), nullable=True)
    stripe_subscription_id: Mapped[str | None] = mapped_column(sa.String(255), nullable=True)

    current_period_end: Mapped[datetime | None] = mapped_column(
        sa.DateTime(timezone=True),
        nullable=True,
    )

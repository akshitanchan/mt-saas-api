"""init schema

Revision ID: 0001_init
Revises: 
Create Date: 2025-12-31
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0001_init"
down_revision = None
branch_labels = None
depends_on = None

def upgrade() -> None:
    # enums
    plan_create = postgresql.ENUM("free", "pro", name="plan")
    subscription_status_create = postgresql.ENUM(
        "none",
        "incomplete",
        "trialing",
        "active",
        "past_due",
        "canceled",
        "unpaid",
        name="subscription_status",
    )
    role_create = postgresql.ENUM("owner", "admin", "member", name="role")
    task_status_create = postgresql.ENUM("todo", "doing", "done", name="task_status")

    plan_create.create(op.get_bind(), checkfirst=True)
    subscription_status_create.create(op.get_bind(), checkfirst=True)
    role_create.create(op.get_bind(), checkfirst=True)
    task_status_create.create(op.get_bind(), checkfirst=True)

    plan = postgresql.ENUM("free", "pro", name="plan", create_type=False)
    subscription_status = postgresql.ENUM(
        "none",
        "incomplete",
        "trialing",
        "active",
        "past_due",
        "canceled",
        "unpaid",
        name="subscription_status",
        create_type=False,
    )
    role = postgresql.ENUM("owner", "admin", "member", name="role", create_type=False)
    task_status = postgresql.ENUM("todo", "doing", "done", name="task_status", create_type=False)

    op.create_table(
        "users",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("email", sa.String(length=320), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
    )
    op.create_index("ix_users_email", "users", ["email"], unique=True)

    op.create_table(
        "orgs",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("plan", plan, nullable=False, server_default="free"),
        sa.Column("subscription_status", subscription_status, nullable=False, server_default="none"),
        sa.Column("stripe_customer_id", sa.String(length=100), nullable=True),
        sa.Column("stripe_subscription_id", sa.String(length=100), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
    )

    op.create_table(
        "memberships",
        sa.Column("user_id", sa.dialects.postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id"), primary_key=True),
        sa.Column("org_id", sa.dialects.postgresql.UUID(as_uuid=True), sa.ForeignKey("orgs.id"), primary_key=True),
        sa.Column("role", role, nullable=False, server_default="member"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.UniqueConstraint("user_id", "org_id", name="uq_membership_user_org"),
    )
    op.create_index("ix_memberships_org_id", "memberships", ["org_id"])
    op.create_index("ix_memberships_user_id", "memberships", ["user_id"])

    op.create_table(
        "projects",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("org_id", sa.dialects.postgresql.UUID(as_uuid=True), sa.ForeignKey("orgs.id"), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
    )
    op.create_index("ix_projects_org_id", "projects", ["org_id"])

    op.create_table(
        "tasks",
        sa.Column("id", sa.dialects.postgresql.UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column("org_id", sa.dialects.postgresql.UUID(as_uuid=True), sa.ForeignKey("orgs.id"), nullable=False),
        sa.Column("project_id", sa.dialects.postgresql.UUID(as_uuid=True), sa.ForeignKey("projects.id"), nullable=False),
        sa.Column("title", sa.String(length=300), nullable=False),
        sa.Column("status", task_status, nullable=False, server_default="todo"),
        sa.Column("created_by", sa.dialects.postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("assigned_to", sa.dialects.postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id"), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
    )
    op.create_index("ix_tasks_org_id", "tasks", ["org_id"])
    op.create_index("ix_tasks_project_id", "tasks", ["project_id"])

    op.create_table(
        "auth_magic_links",
        sa.Column("token_hash", sa.String(length=64), primary_key=True, nullable=False),
        sa.Column("user_id", sa.dialects.postgresql.UUID(as_uuid=True), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
    )
    op.create_index("ix_auth_magic_links_user_id", "auth_magic_links", ["user_id"])

def downgrade() -> None:
    op.drop_index("ix_auth_magic_links_user_id", table_name="auth_magic_links")
    op.drop_table("auth_magic_links")

    op.drop_index("ix_tasks_project_id", table_name="tasks")
    op.drop_index("ix_tasks_org_id", table_name="tasks")
    op.drop_table("tasks")

    op.drop_index("ix_projects_org_id", table_name="projects")
    op.drop_table("projects")

    op.drop_index("ix_memberships_user_id", table_name="memberships")
    op.drop_index("ix_memberships_org_id", table_name="memberships")
    op.drop_table("memberships")

    op.drop_table("orgs")

    op.drop_index("ix_users_email", table_name="users")
    op.drop_table("users")

    postgresql.ENUM(name="task_status").drop(op.get_bind(), checkfirst=True)
    postgresql.ENUM(name="role").drop(op.get_bind(), checkfirst=True)
    postgresql.ENUM(name="subscription_status").drop(op.get_bind(), checkfirst=True)
    postgresql.ENUM(name="plan").drop(op.get_bind(), checkfirst=True)


-- mirrors alembic 0001_init, 0002_stripe_webhooks, 0003_webhook_event_status_error

create type plan as enum ('free', 'pro');
create type subscription_status as enum ('none', 'incomplete', 'trialing', 'active', 'past_due', 'canceled', 'unpaid');
create type role as enum ('owner', 'admin', 'member');
create type task_status as enum ('todo', 'doing', 'done');

create table users (
    id uuid primary key,
    email varchar(320) not null,
    name varchar(200),
    created_at timestamptz not null default now()
);
create unique index ix_users_email on users (email);

create table orgs (
    id uuid primary key,
    name varchar(200) not null,
    plan plan not null default 'free',
    subscription_status subscription_status not null default 'none',
    stripe_customer_id varchar(100),
    stripe_subscription_id varchar(100),
    created_at timestamptz not null default now(),
    current_period_end timestamptz
);

create table memberships (
    user_id uuid not null references users (id),
    org_id uuid not null references orgs (id),
    role role not null default 'member',
    created_at timestamptz not null default now(),
    primary key (user_id, org_id),
    constraint uq_membership_user_org unique (user_id, org_id)
);
create index ix_memberships_org_id on memberships (org_id);
create index ix_memberships_user_id on memberships (user_id);

create table projects (
    id uuid primary key,
    org_id uuid not null references orgs (id),
    name varchar(200) not null,
    created_at timestamptz not null default now()
);
create index ix_projects_org_id on projects (org_id);

create table tasks (
    id uuid primary key,
    org_id uuid not null references orgs (id),
    project_id uuid not null references projects (id),
    title varchar(300) not null,
    status task_status not null default 'todo',
    created_by uuid not null references users (id),
    assigned_to uuid references users (id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index ix_tasks_org_id on tasks (org_id);
create index ix_tasks_project_id on tasks (project_id);

create table auth_magic_links (
    token_hash varchar(64) primary key,
    user_id uuid not null references users (id),
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null default now()
);
create index ix_auth_magic_links_user_id on auth_magic_links (user_id);

create table webhook_events (
    id uuid primary key,
    provider varchar(32) not null,
    event_id varchar(255) not null,
    event_type varchar(255),
    received_at timestamptz not null default now(),
    processed_at timestamptz,
    payload jsonb,
    status varchar(16) not null default 'received',
    error text,
    constraint uq_webhook_events_provider_event_id unique (provider, event_id)
);
create index ix_webhook_events_provider_event_id on webhook_events (provider, event_id);

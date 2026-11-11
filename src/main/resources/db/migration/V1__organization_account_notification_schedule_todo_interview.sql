create table companies (
    id uuid primary key,
    name varchar(30) not null,
    size integer,
    industry varchar(100),
    plan_type varchar(10) not null,
    created_at timestamptz not null
);

create table departments (
    id uuid primary key,
    company_id uuid not null references companies(id),
    name varchar(100) not null,
    unique (company_id, name)
);

create table users (
    id uuid primary key,
    company_id uuid not null references companies(id),
    email varchar(255) not null unique,
    password_hash varchar(255),
    name varchar(100) not null,
    job_position varchar(100),
    phone varchar(30),
    role varchar(10) not null,
    department_id uuid references departments(id),
    status varchar(10) not null,
    deactivated_reason varchar(500),
    created_at timestamptz not null
);
create index idx_users_company on users(company_id, name);

create table invite_codes (
    id uuid primary key,
    company_id uuid not null references companies(id),
    code varchar(20) not null unique,
    role varchar(10) not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    used_by uuid,
    revoked_at timestamptz,
    created_by uuid not null,
    created_at timestamptz not null
);
create index idx_invite_codes_company on invite_codes(company_id, created_at);

create table mentor_assignments (
    newbie_id uuid primary key references users(id),
    company_id uuid not null references companies(id),
    mentor_id uuid not null references users(id),
    effective_at timestamptz not null
);
create index idx_mentor_assignments_mentor on mentor_assignments(mentor_id);

create table refresh_tokens (
    id uuid primary key,
    user_id uuid not null references users(id),
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz
);
create index idx_refresh_tokens_user on refresh_tokens(user_id);

create table notifications (
    id uuid primary key,
    company_id uuid not null,
    recipient_id uuid not null,
    category varchar(30) not null,
    mandatory boolean not null,
    title varchar(200) not null,
    body varchar(1000),
    link_type varchar(50),
    link_id uuid,
    read_at timestamptz,
    created_at timestamptz not null
);
create index idx_notifications_recipient on notifications(recipient_id, created_at desc);

create table notification_preferences (
    id uuid primary key,
    user_id uuid not null,
    category varchar(30) not null,
    enabled boolean not null,
    unique (user_id, category)
);

create table schedules (
    id uuid primary key,
    company_id uuid not null,
    owner_id uuid not null,
    title varchar(200) not null,
    start_at timestamptz not null,
    end_at timestamptz not null,
    visibility varchar(10) not null,
    memo varchar(2000),
    recurrence_rule varchar(500),
    holiday_policy varchar(50),
    version bigint not null,
    created_at timestamptz not null
);
create index idx_schedules_company on schedules(company_id, start_at);

create table todos (
    id uuid primary key,
    company_id uuid not null,
    owner_id uuid not null,
    title varchar(50) not null,
    due_date date,
    reminder_at timestamptz,
    memo varchar(2000),
    completed boolean not null,
    created_at timestamptz not null
);
create index idx_todos_owner on todos(owner_id, due_date);

create table interviews (
    id uuid primary key,
    company_id uuid not null,
    requester_id uuid not null,
    target_id uuid not null,
    preferred_at timestamptz not null,
    reason varchar(200) not null,
    status varchar(10) not null,
    confirmed_at timestamptz,
    reject_reason varchar(200),
    version bigint not null,
    created_at timestamptz not null
);
create index idx_interviews_company on interviews(company_id, preferred_at);
create index idx_interviews_target on interviews(target_id, status);

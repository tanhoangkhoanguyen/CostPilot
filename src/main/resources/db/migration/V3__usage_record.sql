-- The money ledger: one row per governed request. ACID + idempotent - the unique
-- constraint on idempotency_key is what makes replays safe.
-- Identity columns are free-form text until API-key auth (6.1) binds them to the
-- tenant/team/project tables; they arrive as headers today.

create table usage_record (
    id              uuid primary key default gen_random_uuid(),
    tenant_id       text,
    team_id         text,
    project_id      text,
    user_id         text,
    environment     text,
    provider        text not null,
    model           text not null,
    input_tokens    integer not null,
    output_tokens   integer not null,
    -- exact cost; rates are numeric(12,6) per 1k so 9 decimals hold full precision
    cost            numeric(18, 9) not null,
    idempotency_key text not null unique,
    created_at      timestamptz not null default now()
);

create index idx_usage_record_team_time on usage_record (team_id, created_at);
create index idx_usage_record_project_time on usage_record (project_id, created_at);

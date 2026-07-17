-- Core identity + pricing schema.
-- tenant -> team -> project is the governance hierarchy; api_key authenticates a team;
-- model_price is the pricing source used for cost attribution (versioning arrives in 2.3).

create table tenant (
    id          uuid primary key default gen_random_uuid(),
    name        text not null unique,
    created_at  timestamptz not null default now()
);

create table team (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenant (id),
    name        text not null,
    created_at  timestamptz not null default now(),
    unique (tenant_id, name)
);

create table project (
    id          uuid primary key default gen_random_uuid(),
    team_id     uuid not null references team (id),
    name        text not null,
    created_at  timestamptz not null default now(),
    unique (team_id, name)
);

create table api_key (
    id          uuid primary key default gen_random_uuid(),
    team_id     uuid not null references team (id),
    project_id  uuid references project (id),
    -- only the hash is ever stored (enforced from 6.1 onward)
    key_hash    text not null unique,
    name        text not null,
    created_at  timestamptz not null default now(),
    revoked_at  timestamptz
);

create table model_price (
    id                      uuid primary key default gen_random_uuid(),
    provider                text not null,
    model                   text not null,
    currency                text not null default 'USD',
    input_price_per_1k      numeric(12, 6) not null,
    output_price_per_1k     numeric(12, 6) not null,
    created_at              timestamptz not null default now(),
    unique (provider, model)
);

create index idx_team_tenant on team (tenant_id);
create index idx_project_team on project (team_id);
create index idx_api_key_team on api_key (team_id);

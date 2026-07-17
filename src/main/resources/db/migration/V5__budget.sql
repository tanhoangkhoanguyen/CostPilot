-- Budgets: the org-imposed dollar cap at a governance scope. Postgres is the
-- source of truth; Redis mirrors a live "remaining" counter per budget (3.1).
-- No period/window yet - remaining = limit - ledger spend; periods come later.

create table budget (
    id            uuid primary key default gen_random_uuid(),
    -- scope of enforcement: tenant | team | project | model
    scope_type    text not null check (scope_type in ('tenant', 'team', 'project', 'model')),
    -- the id/name the scope matches against usage_record columns
    scope_ref     text not null,
    limit_amount  numeric(18, 9) not null check (limit_amount >= 0),
    currency      text not null default 'USD',
    active        boolean not null default true,
    created_at    timestamptz not null default now(),
    unique (scope_type, scope_ref)
);

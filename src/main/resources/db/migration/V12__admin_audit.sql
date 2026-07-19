-- 9.1: who changed what governance config, when. Separate from audit_record (which
-- explains per-request spending decisions) - this is the admin control-plane trail:
-- budget/policy CRUD and approval decisions made through the admin API.
create table admin_audit (
    id           uuid primary key default gen_random_uuid(),
    actor        text not null,          -- the admin principal (tenant id) that acted
    action       text not null,          -- e.g. budget.upsert | budget.deactivate | policy.upsert
    target_type  text not null,          -- scope type or resource kind (team | project | tenant | model | policy)
    target_ref   text not null,          -- the scope ref / resource id acted on
    old_value    text,                   -- prior value (JSON/string), null on create
    new_value    text,                   -- new value (JSON/string), null on delete
    created_at   timestamptz not null default now()
);

create index idx_admin_audit_time on admin_audit (created_at);
create index idx_admin_audit_actor on admin_audit (actor, created_at);

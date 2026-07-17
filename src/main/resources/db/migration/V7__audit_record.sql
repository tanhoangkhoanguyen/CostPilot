-- 5.1 audit trail: explain every AI-spending decision. One row per governed request,
-- including DENY / REQUIRE_APPROVAL (which never forward and so have no usage_record).
-- This is the "explain everything" log; the money ledger (usage_record) stays pristine
-- and idempotent. No unique constraint on idempotency_key here - audit is append-only
-- history; "one audit row per forwarded request" is enforced in code via the ledger's
-- fresh-insert gate, not by the DB.

create table audit_record (
    id               uuid primary key default gen_random_uuid(),
    -- informational link to the money ledger row (no FK: audit is an independent
    -- append-only log, and the ledger may be pruned/wiped without touching history);
    -- null for denied / approval / unpriced requests that never produced a ledger row
    usage_record_id  uuid,
    tenant_id        text,
    team_id          text,
    project_id       text,
    user_id          text,
    environment      text,
    requested_model  text not null,                       -- what the client asked for
    executed_model   text,                                -- what actually ran; null when never forwarded
    decision         text not null
                     check (decision in ('allow', 'downgrade', 'deny', 'require_approval')),
    reason           text,                                -- 'policy' | 'budget' | policy rule reason
    matched_rule_id  uuid,                                -- policy rule that fired, when reason='policy'
    blocked_scope    text,                                -- budget scope that blocked, when reason='budget'
    finish_reason    text,                                -- 'stop' | 'budget_cutoff' | ...
    provider         text,
    input_tokens     integer,
    output_tokens    integer,
    cost             numeric(18, 9),                       -- null for denied / approval
    idempotency_key  text,
    created_at       timestamptz not null default now()
);

create index idx_audit_team_time    on audit_record (team_id, created_at);
create index idx_audit_project_time on audit_record (project_id, created_at);
create index idx_audit_decision     on audit_record (decision, created_at);

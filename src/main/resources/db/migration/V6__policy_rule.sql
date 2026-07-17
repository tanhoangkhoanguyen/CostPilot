-- Externalized policy: which models a team/project may use, decided at runtime.
-- A project rule overrides its team's rule. Rules live in the DB (change without
-- redeploy) and are hot-cached in Redis with explicit invalidation on change.

create table policy_rule (
    id              uuid primary key default gen_random_uuid(),
    scope_type      text not null check (scope_type in ('team', 'project')),
    scope_ref       text not null,
    -- comma-separated model patterns; '*' suffix wildcard, e.g. "gpt-4o-mini,claude-*"
    allowed_models  text not null,
    -- what happens when the requested model does NOT match: deny | downgrade | require_approval
    fallback_action text not null default 'deny'
                    check (fallback_action in ('deny', 'downgrade', 'require_approval')),
    -- target model when fallback_action = downgrade
    downgrade_to    text,
    active          boolean not null default true,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    unique (scope_type, scope_ref)
);

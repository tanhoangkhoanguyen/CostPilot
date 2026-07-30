-- 1.3 / #93: per-hit cache savings ledger. Cache hits never create a usage_record
-- (no provider call, $0 actual spend), but each hit still avoided a real would-be
-- cost. This table is the money-truth source for cache savings so /api/analytics/savings
-- can sum routing (usage_record.savings_nanos) + cache without double-counting, and
-- reconcile over a fixed time window the way RoutingSavingsIT does for routing.
create table cache_hit_log (
    id              uuid primary key default gen_random_uuid(),
    tenant_id       text,
    team_id         text,
    -- would-be provider cost of the avoided call, integer nanodollars
    savings_nanos   bigint not null check (savings_nanos >= 0),
    created_at      timestamptz not null default now()
);

create index idx_cache_hit_log_created on cache_hit_log (created_at);
create index idx_cache_hit_log_team_created on cache_hit_log (team_id, created_at);

-- 7.2: model capability tiers - the quality bar routing reasons about.
-- tier 1 = economy, 2 = standard, 3 = frontier. A request declares its minimum
-- acceptable tier (X-CostPilot-Min-Tier); the router picks the cheapest
-- policy-allowed model whose tier meets the bar. A model absent from this table
-- has unknown capability and is never a routing candidate.

create table model_capability (
    model      text primary key,
    tier       int not null check (tier between 1 and 3),
    updated_at timestamptz not null default now()
);

insert into model_capability (model, tier) values
    ('gpt-4o-mini',       1),
    ('gemini-2.5-flash',  1),
    ('claude-haiku-4-5',  2),
    ('gpt-4o',            3),
    ('claude-sonnet-4-5', 3),
    ('gemini-2.5-pro',    3);

-- routing is a first-class audited decision alongside allow/downgrade/deny
alter table audit_record drop constraint audit_record_decision_check;
alter table audit_record add constraint audit_record_decision_check
    check (decision in ('allow', 'downgrade', 'deny', 'require_approval', 'route'));

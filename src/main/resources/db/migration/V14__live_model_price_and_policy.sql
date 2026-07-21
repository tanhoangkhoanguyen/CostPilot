-- 11.3: price + governance for the live validation model so budget/cutoff math is real.
--
-- The roadmap's example (gemini-2.0-flash) was discontinued 2026-06-01, so validation
-- uses gemini-2.5-flash-lite: a current, low-cost Vertex model (cheapest = least real $
-- spent in the Stage 12 live run). List price confirmed from
-- https://ai.google.dev/gemini-api/docs/pricing - text input $0.10 / 1M tokens,
-- output $0.40 / 1M tokens (= per-1k below). The row lands in the versioned model_price
-- table (version 1, open effective_to) so historical ledger cost never mutates (2.3).
insert into model_price (provider, model, input_price_per_1k, output_price_per_1k) values
    ('gemini', 'gemini-2.5-flash-lite', 0.000100, 0.000400);

-- A dedicated validation team/project for the Stage 12 live run, separate from the demo
-- teams so validation traffic and budgets don't mix with platform/research.
insert into team (id, tenant_id, name)
values ('00000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000001', 'validation');

insert into project (id, team_id, name)
values ('00000000-0000-0000-0000-000000000023', '00000000-0000-0000-0000-000000000013', 'live-validation');

-- Policy: the validation team may use only the live model; any other model is denied.
-- scope_ref is the X-Team-ID the Stage 12 k6 run sends ('validation').
insert into policy_rule (scope_type, scope_ref, allowed_models, fallback_action)
values ('team', 'validation', 'gemini-2.5-flash-lite', 'deny');

-- 7.3: per-request routing savings, so accumulated savings reconcile against the
-- ledger over a fixed window (the same table that holds money-truth). Nullable:
-- null means "no routing happened" or "requested model was unpriced" (savings
-- unknown), which is distinct from a measured zero. Stored as exact integer
-- nanodollars to match the budget-counter convention (no float drift).
alter table usage_record
    add column savings_nanos bigint;

-- 5.3: OLAP sink for usage events. Lives OUTSIDE Flyway (Flyway owns Postgres only);
-- this runs on a fresh ClickHouse volume via /docker-entrypoint-initdb.d.
--
-- ReplacingMergeTree keyed on event_id makes ingest idempotent: a redelivered event
-- (same event_id) collapses to one row on merge. Merges are async, so money-exact reads
-- must dedup at query time (see 5.4) - this table only guarantees eventual single-row.
--
-- Dedup is per-partition; the partition derives from the event's own event_ts (fixed at
-- emit time), so a given event_id always lands in the same monthly partition. Never
-- partition by ingestion time, or duplicates could split across partitions and survive.
--
-- cost_nanos is Int64 integer nanodollars - identical representation to the Postgres
-- ledger, so reconciliation is exact with no float drift. USD is derived at query time.

CREATE DATABASE IF NOT EXISTS costpilot;

CREATE TABLE IF NOT EXISTS costpilot.usage_events
(
    event_id        UUID,
    tenant_id       String,
    team_id         String,
    project_id      String,
    user_id         String,
    environment     LowCardinality(String),
    provider        LowCardinality(String),
    original_model  LowCardinality(String),
    executed_model  LowCardinality(String),
    decision        LowCardinality(String),
    finish_reason   LowCardinality(String),
    input_tokens    UInt32,
    output_tokens   UInt32,
    cost_nanos      Int64,
    event_ts        DateTime64(3, 'UTC'),
    ingested_at     DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(event_ts)
ORDER BY (team_id, project_id, event_ts, event_id)
SETTINGS index_granularity = 8192;

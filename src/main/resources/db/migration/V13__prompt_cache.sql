-- Stage 10: semantic cache. A cost-optimization layer that returns a cached answer for
-- a semantically-similar prompt at $0 provider cost. pgvector stores the prompt
-- embedding; lookup is cosine similarity, scoped to tenant/team so tenants can never hit
-- each other's cache.
create extension if not exists vector;

-- 384-dim embeddings (the mock embedder's fixed dimension; a real embedder swaps in via
-- config and must match this dimension or the migration/insert will reject it).
create table prompt_cache (
    id              uuid primary key default gen_random_uuid(),
    -- isolation key: a lookup only ever considers rows with the same tenant + team
    tenant_id       text,
    team_id         text,
    -- the exact prompt text (for debugging/audit; matching is on the embedding, not this)
    prompt          text not null,
    embedding       vector(384) not null,
    -- the cached response + what it cost to produce originally, so a hit can record the
    -- would-be provider cost as savings (10.3)
    model           text not null,
    response        text not null,
    input_tokens    integer not null,
    output_tokens   integer not null,
    -- exact would-be provider cost of a hit, in integer nanodollars (matches the budget
    -- convention); accumulated as cache savings when this row is served
    cost_nanos      bigint not null,
    created_at      timestamptz not null default now()
);

-- The btree index scopes each lookup to one tenant/team; within that partition the NN
-- is an exact cosine scan. We deliberately do NOT add an approximate (ivfflat/hnsw)
-- vector index: for a cache, an approximate index can skip the true nearest neighbor and
-- cause a false MISS, which silently erodes the savings the cache exists to prove. Exact
-- scan within a per-tenant partition is correct and fast at this scale; an approximate
-- index is a future optimization to add only with a measured recall target.
create index idx_prompt_cache_tenant_team on prompt_cache (tenant_id, team_id);

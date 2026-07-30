-- 2.4 (#98): semantic-cache TTL + eviction. The prompt_cache.created_at column has
-- existed since V13 but was never read; it now becomes the TTL clock. Two access paths
-- filter on it: the lookup (never serve a past-TTL entry) and the eviction sweep
-- (delete past-TTL rows). Both are range scans on created_at, so add a supporting btree
-- index. Mirrors V15's created_at index on cache_hit_log.
create index idx_prompt_cache_created_at on prompt_cache (created_at);

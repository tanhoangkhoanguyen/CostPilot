-- 6.1: real API-key auth + per-team isolation.
--
-- is_admin distinguishes a tenant-admin key (reads across teams) from a normal team key
-- (force-scoped to its own team on the admin/analytics surfaces).
alter table api_key add column is_admin boolean not null default false;

-- A second team under the seed tenant so per-team isolation is demonstrable/testable
-- (team A = platform cannot read team B = research, and vice-versa).
insert into team (id, tenant_id, name)
values ('00000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000001', 'research');

insert into project (id, team_id, name)
values ('00000000-0000-0000-0000-000000000022', '00000000-0000-0000-0000-000000000012', 'analytics');

-- Seeded demo keys. Only the HMAC-SHA256 hash is stored - never the raw key. The hashes
-- below are computed with the dev pepper (costpilot.security.api-key-pepper default
-- 'costpilot-dev-pepper'); change that pepper and these stop resolving. The raw keys are
-- documented here + in the README for the <10-minute demo; they are NOT secrets in dev.
--
--   cp_demo_team_platform  -> team 'platform', project 'chatbot'   (normal team key)
--   cp_demo_team_research  -> team 'research',  project 'analytics' (normal team key)
--   cp_admin_root          -> team 'platform', is_admin = true      (tenant-admin key)
insert into api_key (id, team_id, project_id, key_hash, name, is_admin) values
    ('00000000-0000-0000-0000-000000000031',
     '00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000021',
     'eb13f17b7e87c6e1592ceefc8c953ce5d6026eaeef4dd862db5c8e4abb213ba7', 'demo-platform', false),
    ('00000000-0000-0000-0000-000000000032',
     '00000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000022',
     '2dfe9b0720e5d3a9058fadffb7a9e1fdf5bd4e6514f8f753443cdad84f733ecd', 'demo-research', false),
    ('00000000-0000-0000-0000-000000000033',
     '00000000-0000-0000-0000-000000000011', null,
     '2a7550c61e32662a96699304104f256f0f87de25255b498056b26af3f834dfcc', 'admin-root', true);

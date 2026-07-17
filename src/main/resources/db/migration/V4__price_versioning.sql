-- Price-table versioning: a price change closes the current row (effective_to)
-- and inserts a new version. History never mutates; ledger rows pin the exact
-- price row that applied at request time via usage_record.price_id.

alter table model_price
    add column version integer not null default 1,
    add column effective_from timestamptz not null default now(),
    add column effective_to timestamptz;

alter table model_price drop constraint model_price_provider_model_key;
alter table model_price add constraint uq_model_price_version unique (provider, model, version);
create index idx_model_price_lookup on model_price (provider, model, effective_from);

alter table usage_record
    add column price_id uuid references model_price (id);

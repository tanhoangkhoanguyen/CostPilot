-- Stage 8: human-in-the-loop for spend the org wants to gate. Two parts:
--
-- 1) a cost-estimate trigger on policy_rule: a request whose pre-flight MAX estimate
--    exceeds this threshold requires approval regardless of model. Null = no cost gate
--    (the existing allowed-model-set path still triggers require_approval on its own).
alter table policy_rule
    add column approval_threshold_nanos bigint;

-- 2) the parked-request store. A REQUIRE_APPROVAL request is persisted here (not
--    forwarded) and waits for an admin decision. Full request context is captured so
--    the request can be replayed verbatim on approval, and it survives a restart.
create table pending_approval (
    id                uuid primary key default gen_random_uuid(),
    -- attribution (mirrors LedgerContext) so the replay ledgers against the same scopes
    tenant_id         text,
    team_id           text,
    project_id        text,
    user_id           text,
    environment       text,
    idempotency_key   text not null,
    -- the request as submitted, enough to replay it byte-for-byte on approval
    requested_model   text not null,
    min_tier          integer,
    -- JSON string { messages:[{role,content}], maxTokens, stream }; serialized by the
    -- service (the codebase maps plain columns, not Hibernate jsonb types)
    request_payload   text not null,
    -- why it was parked + the pre-flight estimate shown to the approver
    estimate_nanos    bigint,
    reason            text,
    matched_rule_id   uuid,
    -- state machine: pending -> approved | rejected | expired
    state             text not null default 'pending'
                      check (state in ('pending', 'approved', 'rejected', 'expired')),
    -- the rendered response captured on approval as a JSON string (D3: approved
    -- requests run non-streaming; the caller retrieves it by the handle)
    stored_response   text,
    decided_by        text,
    decision_reason   text,
    created_at        timestamptz not null default now(),
    decided_at        timestamptz,
    -- documented TTL: pending rows past this are auto-rejected as expired
    expires_at        timestamptz not null
);

-- list-pending and the expiry sweep both scan by state; team filter for isolation.
create index idx_pending_approval_state on pending_approval (state, created_at);
create index idx_pending_approval_team on pending_approval (team_id, state);

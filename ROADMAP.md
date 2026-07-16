# CostPilot — Roadmap

## Identity

CostPilot is an **AI spending governance control plane** — a multi-provider LLM gateway (LiteLLM-inspired) whose identity is **runtime authorization and budget enforcement**, not observability.

Most AI cost tools answer *"How much did we spend?"* — after the fact.
CostPilot answers *"Are we allowed to spend this — right now, before the response even finishes?"*

**Headline claim this project must earn:**
> Enforce dollar budgets *before and during* a response — estimate cost pre-flight, meter tokens mid-stream, and cut off cleanly if a request would overspend — without slowing developers down.

Teams route AI requests through CostPilot instead of calling OpenAI / Anthropic / Gemini directly. The gateway decides, at runtime, **who** may use AI, **which models**, **how much budget**, and whether each request is **allowed, denied, downgraded, or escalated**.

## Why it is different (and not just LiteLLM)

- Identity is **enforcement**, not tracking. LiteLLM tells you what you spent; CostPilot stops you before you overspend.
- Provider abstraction is justified by **cost** (model choice = a cost lever) and **governance** (which team may use which model) — **never by failover**.
- The organization is the user; the developer is the thing being governed. Budgets are policy imposed by the org, not a limit a developer opts into.

## Architecture (core path)

```
Team app ──X-Team-ID/X-Project-ID──► CostPilot Gateway (Java 21 + Spring Boot 3, Virtual Threads)
                                          │
   ① Policy  ② Budget pre-flight  ③ Forward+Meter  ④ Cost ledger  ⑤ Emit event  ⑥ Audit
        │            │                    │              │             │            │
      Redis        Redis            Provider adapter   Postgres      Kafka       Postgres
   (hot policy) (live $ counter)  (OpenAI/Anthropic/Gemini)  (ledger)    │        (audit)
                                          │                             ▼
                                   Mock LLM upstream (default, $0)   ClickHouse ──► Dashboard
                                        ⇄ real provider (drop-in)    (OLAP, spend analytics)

OLTP path (Postgres) = money truth, on the hot path.  OLAP path (Kafka → ClickHouse) = reporting, off the hot path.
```

## Request lifecycle

```
1. Authenticate   — API key → tenant / team / project
2. Policy         — team→allowed models; decision ALLOW | DENY | DOWNGRADE | REQUIRE_APPROVAL
3. Budget         — pre-flight estimate vs remaining; block, or downgrade to cheaper allowed model
4. Forward+Meter  — proxy to provider, stream passthrough, meter tokens as they arrive
5. Cutoff         — if accrued cost crosses remaining budget mid-stream → terminate cleanly, record partial
6. Cost ledger    — tokens × versioned price → transactional, idempotent write (Postgres, source of truth)
7. Emit event     — append-only usage event → Kafka → ClickHouse (async, off the hot path)
8. Audit          — who / model / decision / reason / original-vs-executed / cost
```

## Tech stack (with reason)

| Layer | Choice | Why |
|---|---|---|
| Language | **Java 21** | LLM proxy is I/O-bound; **Virtual Threads** handle thousands of upstream-waiting connections without reactive complexity. Also the JVM/Spring ecosystem is the enterprise-infra hiring signal for 2026-2027. |
| Framework | **Spring Boot 3 (MVC + Virtual Threads)** | REST, security, WebClient for SSE passthrough; mature, battle-tested. |
| Upstream client | **Spring WebClient** | Streaming SSE passthrough so tokens can be metered live (no full buffering). |
| System of record | **PostgreSQL** | Money needs ACID — ledger, budgets, policy, audit are transactional. |
| Hot state | **Redis** | Atomic live budget counters (INCRBYFLOAT / Lua) and hot policy cache; sub-5ms decisions. |
| Migrations | **Flyway** | No manual schema changes; reproducible from clean DB. |
| Observability | **Micrometer + Prometheus + Grafana** | Latency, token usage, budget-rejection / downgrade / cutoff rates. |
| Event pipeline | **Kafka** | Append-only usage-event stream, off the hot path; decouples analytics from the governance write path. |
| OLAP store | **ClickHouse** | Spend analytics over millions of events at interactive speed; OLAP kept separate from the Postgres money ledger. |
| Testing | **Testcontainers** | Real Postgres + Redis (+ Kafka/ClickHouse) in tests; verify budget races, idempotency, cutoff, pipeline reconciliation deterministically. |
| Dev upstream | **Local mock LLM server** | Mimics each provider's API + SSE streaming. Default upstream ⇒ **$0 dev**, deterministic tests; real keys drop in via config for live demos only. |
| Packaging | **Docker + docker-compose** | Whole stack up with one command. |

## Scope guardrails

**This is NOT a reliability gateway.** Out of scope (a separate project owns these): circuit breakers, bulkheads, adaptive failover, provider health routing, traffic shifting, resilience benchmarking. Only basic **timeout / retry / error handling**. The one deliberate reliability touch is **fail-open budget enforcement** — cost control must never take down production traffic — and it is documented as such where it appears (Issue 3.2).

**Future work (listed, not built in core):** semantic cache (pgvector), cost-based routing across providers, Kubernetes / autoscaling. Deliberate boundaries, not omissions.

---

# Stages & Issues

Each stage ships a **working vertical increment**. Each issue is sized **1-3 days** and specifies Goal / What to do / Acceptance Criteria / Tech. Core governance flow is built before any optimization; nothing optional lands before the core lifecycle works.

---

## Stage 0 — Foundation

*Outcome: a skeleton that runs end-to-end and forwards to a free mock upstream.*

### Issue 0.1 — Project bootstrap
- **Goal:** A runnable Spring Boot app on Java 21 with Virtual Threads and local deps.
- **What to do:**
  - Init Gradle project, Java 21, Spring Boot 3 (web, actuator).
  - Enable Virtual Threads: `spring.threads.virtual.enabled=true`.
  - `docker-compose.yml` with Postgres + Redis; app config points at them.
  - Wire `/actuator/health`.
- **Acceptance Criteria:**
  - `./gradlew bootRun` starts the app; `/actuator/health` returns 200.
  - `docker compose up` brings Postgres + Redis healthy.
  - Confirm request threads are virtual (log/thread dump shows `VirtualThread`).
- **Tech/Technique:** Java 21, Spring Boot 3, Gradle, Project Loom Virtual Threads, docker-compose.

### Issue 0.2 — OpenAI-compatible contract + echo
- **Goal:** The public API surface exists and streams, before any real forwarding.
- **What to do:**
  - `POST /v1/chat/completions` accepting the OpenAI request schema (model, messages, stream, max_tokens).
  - Read `X-Team-ID` / `X-Project-ID` headers into a request context.
  - Return a hardcoded echo response in both non-streaming (JSON) and streaming (SSE) modes.
- **Acceptance Criteria:**
  - Invalid request body → 400 with a clear error; valid → 200.
  - `stream:false` returns one JSON; `stream:true` returns SSE chunks ending with `[DONE]`.
  - Contract test (MockMvc / WebTestClient) green for both modes.
- **Tech/Technique:** Spring MVC controller, DTO validation (Jakarta Validation), SSE (`text/event-stream`).

### Issue 0.3 — Flyway + core schema
- **Goal:** Reproducible relational foundation for identity + pricing.
- **What to do:**
  - Flyway migrations for: `tenant`, `team`, `project`, `api_key`, `model_price`.
  - Seed one tenant / team / project and a small price table.
- **Acceptance Criteria:**
  - Migrations run clean on an empty DB (verified in a Testcontainers Postgres).
  - Seed data loads via migration or a seed profile.
  - No manual DDL anywhere in the codebase.
- **Tech/Technique:** Flyway, PostgreSQL, JPA/JDBC entities.

### Issue 0.4 — Mock LLM upstream
- **Goal:** A free, deterministic upstream so dev + tests cost $0.
- **What to do:**
  - Local mock server (embedded module or WireMock) mimicking OpenAI/Anthropic/Gemini response shapes, including SSE token streaming and a usage block.
  - Config flag selects mock vs real endpoint.
- **Acceptance Criteria:**
  - Gateway forwards `/v1/chat/completions` to the mock and returns its response.
  - Streaming passthrough from mock works end-to-end.
  - Entire test suite hits the mock only — no network, no cost, deterministic.
- **Tech/Technique:** WireMock / embedded HTTP mock, Spring WebClient, SSE.

---

## Stage 1 — Multi-Provider Gateway

*Outcome: one internal request model forwarded to any of 3 providers; streaming real; real keys drop-in.*

### Issue 1.1 — Provider adapter abstraction
- **Goal:** Adding a provider means implementing one interface — nothing else.
- **What to do:**
  - Define `ProviderAdapter`: build upstream request, parse non-streaming response, parse stream chunks, extract usage (tokens).
  - Define a canonical internal request/response model that all adapters normalize to.
- **Acceptance Criteria:**
  - A new provider can be added by implementing `ProviderAdapter` only (no controller/service edits).
  - Unit tests per adapter run against the mock.
- **Tech/Technique:** Adapter pattern, interface-driven design, Spring DI.

### Issue 1.2 — Three adapters (OpenAI, Anthropic, Gemini)
- **Goal:** Real API shapes for all three, mock by default, real via config.
- **What to do:**
  - Implement OpenAI, Anthropic, Gemini adapters to their real request/response schemas.
  - Provider selection by model id / config mapping.
  - Base URL + API key per provider from env/config; default base URL = mock.
- **Acceptance Criteria:**
  - Each adapter maps a canonical prompt to the correct provider payload and back.
  - Switching a provider to its real endpoint is **env/config only — no code change**.
  - Adapter unit tests green against mock fixtures.
- **Tech/Technique:** Adapter implementations, externalized config (`@ConfigurationProperties`), WebClient per provider.

### Issue 1.3 — Streaming passthrough
- **Goal:** Stream tokens through without buffering the whole response (prerequisite for mid-stream metering).
- **What to do:**
  - WebClient consumes upstream SSE as a stream; gateway relays chunks to the client incrementally.
- **Acceptance Criteria:**
  - First token reaches the client before generation completes (verified with the mock's paced stream).
  - No whole-response buffering (assert memory/flow behavior).
  - Client disconnect cancels the upstream call.
- **Tech/Technique:** WebClient streaming (`bodyToFlux`), SSE relay, cancellation propagation.

---

## Stage 2 — Cost Attribution

*Outcome: every request is priced correctly and recorded transactionally. The money spine.*

### Issue 2.1 — Token → cost calculation
- **Goal:** Deterministic, per-provider correct cost from token usage.
- **What to do:**
  - Read usage (input/output tokens) from each adapter.
  - `cost = input_tokens × in_rate + output_tokens × out_rate`, rates from `model_price`.
- **Acceptance Criteria:**
  - Computed cost matches a hand-calculated fixture for each provider/model.
  - Uses `BigDecimal` (no floating-point money errors).
  - Price lookup selects the version active at request time (see 2.3).
- **Tech/Technique:** `BigDecimal`, price lookup service.

### Issue 2.2 — Transactional usage ledger
- **Goal:** Money writes are ACID and idempotent.
- **What to do:**
  - `usage_record`: tenant, team, project, model, user, environment, input/output tokens, cost, idempotency key, timestamp.
  - Write inside a DB transaction; unique constraint on idempotency key.
- **Acceptance Criteria:**
  - Replaying the same idempotency key does **not** double-count.
  - Concurrent writes remain correct (Testcontainers concurrency test).
  - Ledger sum reconciles with per-request costs.
- **Tech/Technique:** `@Transactional`, unique constraint, idempotency key, Testcontainers Postgres.

### Issue 2.3 — Price-table versioning
- **Goal:** Prices change without redeploy; history stays correct.
- **What to do:**
  - Version `model_price` (effective-from / effective-to or version column).
  - Records store the price version that applied at request time.
- **Acceptance Criteria:**
  - Changing a price creates a new version; existing records unchanged.
  - New requests use the new price; historical cost never mutates.
- **Tech/Technique:** Temporal/versioned table design, effective-dated lookup.

---

## Stage 3 — Budget Enforcement

*Outcome: the org can prevent overspend in real time. Differentiator, part 1.*

### Issue 3.1 — Budget model + Redis live counters
- **Goal:** Live remaining-budget that is atomic and fast.
- **What to do:**
  - Budgets in Postgres at team / project / tenant / model scope.
  - Mirror live `remaining` in Redis; update atomically on each charge (INCRBYFLOAT or Lua script).
- **Acceptance Criteria:**
  - Redis counter reconciles with the Postgres ledger.
  - Atomic under concurrent charges (no lost updates).
  - Counter rebuilds from ledger on cold start.
- **Tech/Technique:** Redis atomic ops / Lua, Postgres budgets, reconciliation job.

### Issue 3.2 — BudgetGuard on the hot path (hard + soft, fail-open)
- **Goal:** Block overspend in real time; warn before blocking; never take down traffic.
- **What to do:**
  - Pre-request guard: **hard limit** blocks over-cap requests; **soft limit** allows + warns at 80%.
  - **Fail-open** policy: if Redis/budget check is unavailable, allow the request and log it — cost control must not cause an outage. *(This is the single deliberate reliability touch; documented as intentional.)*
- **Acceptance Criteria:**
  - Over hard cap → blocked with 402/429 + machine-readable reason.
  - At ≥80% → served, with a warning header/event.
  - Guard decision adds **< ~5ms** on the hot path (measured).
  - **Concurrent flood cannot overspend** the cap (Testcontainers race test).
  - Redis down ⇒ fail-open, and the fail-open event is logged.
- **Tech/Technique:** Redis atomic decrement/reserve, hot-path filter, fail-open design, concurrency testing.

### Issue 3.3 — Policy engine (who may use what)
- **Goal:** Separate developer intent from organizational decision, at runtime.
- **What to do:**
  - Externalized rules: `team → allowed models`, `project → overrides`.
  - Runtime evaluation → decision `ALLOW | DENY | DOWNGRADE | REQUIRE_APPROVAL`.
  - Hot-cache policy in Redis.
- **Acceptance Criteria:**
  - Policy change takes effect **without redeploy**.
  - Every decision is logged with the specific rule that matched.
  - Denied model → 403 with reason; downgrade path feeds Stage 4.
- **Tech/Technique:** Externalized rules (DB-backed), Redis policy cache, decision enum.

---

## Stage 4 — WOW: Pre-flight Estimation + Mid-stream Metering

*Outcome: the headline. Enforce budgets before and during the response.*

### Issue 4.1 — Pre-flight cost estimation + auto-downgrade
- **Goal:** Prevent overspend without failing the developer.
- **What to do:**
  - Before forwarding, estimate max cost from input tokens + `max_tokens` × price.
  - If estimate exceeds remaining budget: **downgrade** to a cheaper policy-allowed model instead of hard-blocking.
- **Acceptance Criteria:**
  - Estimate is within tolerance of actual cost on fixtures.
  - An over-budget request is served on the downgraded model.
  - Audit records original vs executed model + reason.
- **Tech/Technique:** Token estimation (tokenizer/heuristic), price-aware model selection, policy integration.

### Issue 4.2 — Mid-stream token metering
- **Goal:** Know the running cost of an in-flight streamed response.
- **What to do:**
  - Accrue cost token-by-token as stream chunks arrive; maintain running spend for the request.
- **Acceptance Criteria:**
  - Running cost at stream end equals the final ledger cost (2.1).
  - Metering does not introduce full-buffering (streaming behavior preserved).
- **Tech/Technique:** Streaming reducer over the SSE flux, incremental cost accrual.

### Issue 4.3 — Mid-stream cutoff  ← headline claim
- **Goal:** Terminate a response the instant it would breach budget, cleanly.
- **What to do:**
  - When accrued cost crosses remaining budget mid-generation, cancel the upstream stream, flush partial output, record partial cost.
  - Emit a clean truncation signal to the client (finish_reason / event), not a broken socket.
- **Acceptance Criteria:**
  - Stream stops within N tokens of the limit (bounded overshoot, documented).
  - Partial usage + cost recorded accurately in the ledger.
  - Client receives a clean truncation signal; no dangling connection.
- **Tech/Technique:** Reactive stream cancellation, partial-usage accounting, graceful SSE termination.

---

## Stage 5 — Audit & Analytics Pipeline (Kafka → ClickHouse)

*Outcome: every decision is explainable, and spend analytics run through a real event pipeline — usage events flow Postgres ledger → Kafka → ClickHouse → dashboard. This is the primary analytics path, not a bolt-on: OLTP (Postgres, money truth) is decoupled from OLAP (ClickHouse, reporting) via an append-only event stream, off the governance hot path.*

### Issue 5.1 — Audit trail
- **Goal:** Explain every AI spending decision.
- **What to do:**
  - Record per request: who, model, decision, reason, original-vs-executed model, cost, timestamp.
  - Admin query endpoint (filter by team/project/decision/time).
- **Acceptance Criteria:**
  - Every request produces a queryable audit row.
  - A downgrade or cutoff is fully explained ("why this decision").
- **Tech/Technique:** Audit table, query endpoint, structured decision logging.

### Issue 5.2 — Emit usage events to Kafka
- **Goal:** Every governed request produces an append-only event, off the hot path.
- **What to do:**
  - Define a `usage_event` schema (tenant/team/project/model/user/env, tokens, cost, decision, original-vs-executed, event id, timestamp).
  - After the ledger write (2.2), publish the event to a Kafka topic asynchronously; a publish failure must **not** fail the request or the ledger.
- **Acceptance Criteria:**
  - Each request emits exactly one event; the Postgres ledger remains the transactional source of truth.
  - Publish is async — hot-path latency unchanged (measured before/after).
  - Kafka down ⇒ request still succeeds; undelivered events are buffered/retried or dead-lettered, and logged.
- **Tech/Technique:** Kafka producer, schema (Avro/JSON), async publish, transactional outbox or best-effort + DLQ.

### Issue 5.3 — Land events in ClickHouse
- **Goal:** Events durably stored in an OLAP table built for spend queries.
- **What to do:**
  - ClickHouse MergeTree table partitioned/ordered for time + team/project access patterns.
  - Consumer from Kafka → ClickHouse (Kafka engine table + materialized view, or a consumer service).
- **Acceptance Criteria:**
  - Events land within the pipeline's lag target (documented, e.g. < a few seconds).
  - Ingest is idempotent — replay/redelivery does not double-count (dedup on event id).
  - ClickHouse totals reconcile with the Postgres ledger for a fixed window.
- **Tech/Technique:** ClickHouse MergeTree, Kafka engine / materialized view or consumer, idempotent ingest.

### Issue 5.4 — OLAP-backed spend dashboard
- **Goal:** Scale-grade, reconciling spend visibility served from ClickHouse.
- **What to do:**
  - Aggregations: spend by team/project/model, budget utilization, top spenders, downgrade/cutoff counts, trends — as ClickHouse queries.
  - Read API + minimal UI (or Grafana) pointed at ClickHouse; Postgres stays the ledger.
- **Acceptance Criteria:**
  - Aggregations over millions of synthetic events return in interactive time (benchmark on seeded load; document p95).
  - Dashboard numbers reconcile exactly with `usage_record` for a fixed window.
  - README states the OLTP/OLAP split explicitly (Postgres = money truth, ClickHouse = reporting).
- **Tech/Technique:** ClickHouse OLAP queries, load generation for benchmarking, read API, Grafana or minimal frontend.

---

## Stage 6 — Production Hardening

*Outcome: reads as real infrastructure, not a demo.*

### Issue 6.1 — Security
- **Goal:** Real auth and isolation.
- **What to do:**
  - Per-team API-key auth; keys hashed at rest; Spring Security on admin endpoints.
- **Acceptance Criteria:**
  - Unauthenticated requests blocked.
  - Keys stored hashed, never plaintext.
  - Per-team data isolation enforced (team A cannot read team B).
- **Tech/Technique:** Spring Security, API-key auth, hashing (bcrypt/argon2).

### Issue 6.2 — Observability
- **Goal:** Operate the gateway from metrics.
- **What to do:**
  - Micrometer → Prometheus: request/provider latency, token usage, budget-rejection / downgrade / cutoff rates.
  - Grafana dashboards; structured logs.
- **Acceptance Criteria:**
  - Metrics scrapeable at `/actuator/prometheus`.
  - A budget rejection appears on a Grafana panel.
- **Tech/Technique:** Micrometer, Prometheus, Grafana.

### Issue 6.3 — Test depth + CI
- **Goal:** Trustworthy, reproducible correctness.
- **What to do:**
  - Testcontainers integration tests: budget races, policy evaluation, idempotency, mid-stream cutoff.
  - GitHub Actions CI; coverage gate.
- **Acceptance Criteria:**
  - CI green from a clean clone.
  - Race + cutoff tests deterministic (no flakes).
  - No test touches a real provider.
- **Tech/Technique:** Testcontainers, JUnit 5, GitHub Actions, coverage tooling.

### Issue 6.4 — Containerize + deploy manifest
- **Goal:** Anyone can run and demo it fast.
- **What to do:**
  - Docker image for the gateway; full-stack `docker-compose` (gateway + Postgres + Redis + Prometheus + Grafana + mock).
  - README: architecture, the headline claim, and live-demo steps (drop-in real key).
- **Acceptance Criteria:**
  - `docker compose up` runs the whole system.
  - A stranger can demo the headline flow in **< 10 minutes** from the README.
- **Tech/Technique:** Docker multi-stage build, docker-compose, documentation.

### Issue 6.5 — Load-test harness + published p99
- **Goal:** Turn the enforcement claims into measured numbers, not assertions.
- **What to do:**
  - Load-test harness (k6 / Gatling) that floods the gateway with concurrent traffic across teams.
  - Scenarios: guard-latency under load, overspend-under-flood, mid-stream cutoff accuracy at scale.
  - Publish results (p50/p95/p99, overspend count, cutoff overshoot) in the README.
- **Acceptance Criteria:**
  - Budget guard decision **p99 < target** (documented) under N concurrent RPS.
  - **Overspend = 0** across the entire flood run (reconciled against the cap).
  - Cutoff overshoot stays within the documented token bound at load.
  - Numbers are reproducible from a single command.
- **Tech/Technique:** k6 / Gatling, load scenarios, latency percentiles, reproducible benchmark.

---

## Stage 7 — Cost-Based Routing

*Outcome: the cost-optimization lever — route each request to the cheapest model that satisfies policy, treating model choice as a spend decision.*

### Issue 7.1 — Pre-call cost comparison across models
- **Goal:** Know, before forwarding, what each eligible model would cost for this request.
- **What to do:**
  - For the set of policy-allowed models, estimate request cost (input + expected output × each model's versioned price).
  - Produce a ranked cheapest-first candidate list.
- **Acceptance Criteria:**
  - Given a request + allowed set, the ranked cost list matches hand-computed fixtures.
  - Uses the versioned price active at request time (2.3).
- **Tech/Technique:** Cost estimation reuse (4.1), price lookup, candidate ranking.

### Issue 7.2 — Routing policy (cheapest-that-qualifies)
- **Goal:** Route to the cheapest model meeting a declared quality/capability bar.
- **What to do:**
  - Model capability tiers/tags (e.g. `min_tier`, required features) in policy.
  - Router picks cheapest candidate that satisfies the request's declared bar; records why.
- **Acceptance Criteria:**
  - A request with a low bar routes to a cheap model; a high-bar request is not downgraded below its bar.
  - Routing decision + reason recorded in audit (5.1) and event (5.2).
- **Tech/Technique:** Policy-driven routing, capability tiers, decision logging.

### Issue 7.3 — Routing savings measurement
- **Goal:** Prove routing saves money.
- **What to do:**
  - Track counterfactual: cost-if-default-model vs cost-actually-routed; accumulate savings.
- **Acceptance Criteria:**
  - Savings metric exposed and visible on the dashboard.
  - Savings reconcile with the ledger over a fixed window.
- **Tech/Technique:** Counterfactual cost accounting, metric emission.

---

## Stage 8 — REQUIRE_APPROVAL Workflow

*Outcome: the 4th policy decision becomes real — human-in-the-loop for spend the org wants to gate.*

### Issue 8.1 — Park requests pending approval
- **Goal:** Over-threshold requests wait for a human instead of being allowed or denied outright.
- **What to do:**
  - When policy returns `REQUIRE_APPROVAL` (e.g. estimated cost over a threshold), hold the request in a pending state; return a pending handle to the caller.
  - Persist pending request with full context (who/model/estimate/reason).
- **Acceptance Criteria:**
  - A triggering request is parked, not forwarded; caller gets a pending id.
  - Pending state survives restart (persisted).
- **Tech/Technique:** Pending-request store (Postgres), policy `REQUIRE_APPROVAL` path, async handle.

### Issue 8.2 — Approve / reject → resume or cancel
- **Goal:** A decision resumes or kills the parked request cleanly.
- **What to do:**
  - Approve endpoint → request forwarded, metered, ledgered as normal.
  - Reject endpoint → request cancelled, recorded with reason.
  - Timeout → auto-reject (documented TTL).
- **Acceptance Criteria:**
  - Approved request completes end-to-end and appears in ledger/audit.
  - Rejected/timed-out request never reaches a provider; recorded with reason.
- **Tech/Technique:** State machine (pending→approved/rejected/expired), TTL expiry, audit integration.

---

## Stage 9 — Admin API + CLI

*Outcome: the org-facing control surface — finance/platform set budgets and policy and act on approvals. This is "who holds the authority," made operable.*

### Issue 9.1 — Admin REST API (budgets & policy)
- **Goal:** Manage governance config without touching the DB by hand.
- **What to do:**
  - CRUD endpoints for budgets (team/project/tenant/model) and policy rules; secured (6.1).
  - Changes take effect at runtime (no redeploy), hot-cache invalidation.
- **Acceptance Criteria:**
  - Setting a budget/policy via API changes enforcement behavior immediately.
  - All admin actions are audited (who changed what, when).
- **Tech/Technique:** Spring MVC admin controllers, Spring Security, cache invalidation, audit.

### Issue 9.2 — Approvals API
- **Goal:** Act on `REQUIRE_APPROVAL` requests programmatically.
- **What to do:**
  - Endpoints to list pending, approve, reject (feeds Stage 8).
- **Acceptance Criteria:**
  - Pending list is accurate; approve/reject drive the Stage 8 state machine.
- **Tech/Technique:** REST over the pending-request store, RBAC.

### Issue 9.3 — CLI (`costpilot`)
- **Goal:** Ops-friendly control without a frontend.
- **What to do:**
  - CLI wrapping the admin + approvals API: `costpilot budget set`, `policy set`, `approvals ls/approve/reject`, `spend show`.
  - Config for endpoint + admin key.
- **Acceptance Criteria:**
  - Every admin/approval action is doable from the CLI.
  - CLI has help, non-zero exit on error, and is demoable in the README.
- **Tech/Technique:** Java CLI (Picocli) or thin script client, API auth.

---

## Stage 10 — Semantic Cache (Cost-Optimization)

*Outcome: a cost-optimization layer that returns cached answers for semantically-similar prompts and measures the dollars saved. Framed strictly as spend reduction — not an ML showcase.*

### Issue 10.1 — Embed + vector store
- **Goal:** Represent prompts for similarity lookup.
- **What to do:**
  - Embed incoming prompts; store vectors in pgvector keyed to tenant/team (isolation).
- **Acceptance Criteria:**
  - Prompts are embedded and stored; lookup returns nearest neighbors with scores.
  - Tenants cannot hit each other's cache.
- **Tech/Technique:** pgvector, embedding call, cosine similarity, tenant isolation.

### Issue 10.2 — Cache lookup + threshold (savings-first)
- **Goal:** Serve a cached answer when a prompt is close enough — and never serve a wrong one.
- **What to do:**
  - On request, look up similar prompts above a cosine threshold; on hit, return cached response at **\$0 provider cost**.
  - Conservative threshold; log hit/miss.
- **Acceptance Criteria:**
  - Similar prompt → cache hit, no provider call, cost recorded as saved.
  - Threshold tuned so false-hit rate stays under a documented bound (precision-over-recall).
- **Tech/Technique:** Similarity threshold, precision/recall tradeoff, cache-hit accounting.

### Issue 10.3 — Cache savings measurement
- **Goal:** Prove the cache reduces spend.
- **What to do:**
  - Accumulate \$ saved (would-be provider cost of cache hits); expose on dashboard.
- **Acceptance Criteria:**
  - Savings metric visible; reconciles with hit log over a fixed window.
- **Tech/Technique:** Savings accounting, metric emission, dashboard panel.

---

## Future Work (explicitly out of core)

Deliberate scope boundaries — each is a "future work" note, not a gap:

- **Kubernetes / autoscaling** — horizontal scale + HPA (scale/reliability, off-thesis for now).

Intentionally deferred so the cost-governance story stays sharp.

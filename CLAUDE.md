# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

CostPilot is an **AI-spending governance control plane**: a multi-provider LLM gateway (LiteLLM-inspired) whose identity is **runtime authorization and budget enforcement**, not observability. Clients speak the OpenAI API to CostPilot instead of calling OpenAI/Anthropic/Gemini directly; CostPilot decides — per request, at runtime — who may spend, on which model, and how much, and can downgrade or cut off a request mid-stream to stay within budget.

`ROADMAP.md` is the authoritative plan: 11 stages (0–10), 36 issues, each tagged in code comments (e.g. `// 4.3 headline`, `(3.2)`). Issues map 1:1 to GitHub issues #1–#36 with `stage-N` labels. When touching a feature, find its stage/issue in ROADMAP.md first — acceptance criteria live there.

**Scope guardrail:** this is NOT a reliability gateway. Do not add circuit breakers, failover, health-based or latency-based routing. The *only* deliberate reliability stance is **fail-open budget enforcement** (see `BudgetGuard`), documented as intentional. Cost-based routing (Stage 7) routes by price/policy, never by health/latency.

## Commands

```bash
./gradlew build            # compile + run all tests
./gradlew test             # all tests (unit + Testcontainers integration)
./gradlew bootRun          # run the app on :8080 (needs Postgres+Redis, see below)

# run a single test class / method
./gradlew test --tests 'com.costpilot.budget.MidStreamCutoffIT'
./gradlew test --tests 'com.costpilot.cost.CostCalculatorTest.*'

docker compose up -d       # Postgres + Redis for bootRun (tests don't need this)
```

Tests do **not** require `docker compose up` — they spin their own Postgres + Redis via Testcontainers (`TestcontainersConfiguration`, wired with `@ServiceConnection`). A working Docker daemon is required. `bootRun` *does* need the compose stack (or env-var overrides: `COSTPILOT_DB_*`, `COSTPILOT_REDIS_*`).

**Test naming is a hard convention:** `*Test` = pure unit tests (no containers); `*IT` = Testcontainers integration tests. Match the suffix to whether the test needs a real DB/Redis.

## Architecture

### Request lifecycle (all orchestrated in `api/ChatCompletionsController`)
The controller is the whole governance pipeline; read it first. Order matters:

1. **Normalize** — OpenAI-shaped `ChatCompletionRequest` → `CanonicalChatRequest` (`core/model`). Everything internal speaks canonical; only the edges speak provider/OpenAI dialects.
2. **Policy** (`policy/PolicyService`, 3.3) — `ALLOW | DENY | DOWNGRADE | REQUIRE_APPROVAL`. DENY/REQUIRE_APPROVAL throw (→ 403 via `GlobalExceptionHandler`); DOWNGRADE swaps the executed model before budget.
3. **Budget reserve** (`budget/BudgetGuard.reserve`, 3.2) — atomically reserves the request's *estimated max* cost against every governed scope. A hard-limit block triggers **pre-flight auto-downgrade** (4.1) to cheaper policy-allowed models (`budget/DowngradeService`); only if nothing fits does a 402 escape. Soft limit (≤20% remaining) serves + sets `X-CostPilot-Budget-Warning`.
4. **Forward** (`upstream/ForwardingService`) — non-streaming returns a `Mono`; streaming returns a `Flux<CanonicalStreamChunk>` relayed to the client via `SseEmitter`.
5. **Meter + cutoff** (streaming only, `cost/StreamCostMeter`, 4.2/4.3) — accrues cost per chunk; when accrued cost crosses the request's allowance, `takeUntil` emits the crossing chunk then cancels the upstream, appending a clean `budget_cutoff` finish + `[DONE]`. Bounded overshoot = 1 chunk.
6. **Ledger** (`cost/UsageLedgerService`, 2.2) — transactional, idempotent write keyed by `Idempotency-Key` header (or a generated UUID). Billing happens in `doFinally` for streams, so tokens are billed even when the client disconnects.
7. **Release** — reservations are returned after the request settles; the ledger charge is the real deduction.

### Provider abstraction (the single extension point)
Adding a provider = implement `provider/ProviderAdapter` as a Spring bean. Nothing else changes: `ProviderRegistry` discovers all adapters via DI and selects one by (a) explicit `costpilot.upstream.model-providers` config, then (b) model-id prefix convention (`claude*`→anthropic, `gemini*`→gemini, else openai). Adapters translate canonical ↔ provider wire format for both request, non-streaming response, and per-SSE-event parsing.

### Mock upstream = $0 dev (default)
`mockllm/Mock*Controller` are embedded controllers mounted at `/mock/{providerId}` that mimic each provider's real API + SSE streaming. `costpilot.upstream.mode` (default `mock`) makes `ForwardingService` target the app's own live port instead of real endpoints. Switching to a real provider is **env/config only, never a code change** (`COSTPILOT_UPSTREAM_MODE=real` + per-provider base-url/api-key). Tests and daily dev cost nothing.

### Money representation (critical correctness invariant)
Cost is stored and computed as **integer nanodollars** (`BigDecimal` scale 9). Redis counters use plain `DECRBY`/`INCRBY` — exact integer math, atomic, **no `INCRBYFLOAT` float drift**. Use `BudgetService.toNanos`/`fromNanos` at the boundary; never do floating-point money math. `BudgetScope` enum = TENANT/TEAM/PROJECT/MODEL; a request is charged against every scope that has an active budget.

### Postgres is truth; Redis is a fast mirror
- **Flyway owns the schema** (`db/migration/V*.sql`); Hibernate is `ddl-auto: validate` — it only checks entities match. Schema changes = new migration, never entity-driven DDL.
- Redis budget counters (`budget:remaining:*`, `budget:limit:*`, negative cache `budget:none:*`) are **rebuildable** from `limit - ledger-spend` at any time (`BudgetService.rebuild`, `SETNX` so a concurrent writer is never clobbered). This handles cold start and drift repair.
- The budget hot path must stay ~<5ms, so `BudgetGuard` keeps a short-lived in-memory price cache; the *reservation* may use a briefly-stale rate, but the *ledger* always charges the exact effective-dated price (`cost/PriceVersioningService`, 2.3).

### MVC + Virtual Threads, WebClient only for upstream
The server is Spring **MVC** with Virtual Threads enabled (I/O-bound proxy workload). WebFlux is on the classpath **solely** for `WebClient` (SSE-capable upstream client) — do not turn this into a reactive web server. Reactive types (`Mono`/`Flux`) appear only in the upstream-forwarding path and are bridged to `SseEmitter`/blocking at the controller.

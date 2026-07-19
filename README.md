# CostPilot

An AI cost-governance gateway. Your apps call CostPilot instead of OpenAI / Anthropic / Gemini directly, and CostPilot decides - at runtime - who may use AI, which models, and how much budget, then enforces it.

> **The headline claim:** Enforce dollar budgets *before and during* a response - estimate cost pre-flight, meter tokens mid-stream, and cut off cleanly if a request would overspend - without slowing developers down (warm budget decision < 5 ms).

## Architecture

```
                        +---------------------------------------------+
  your app ---------->  |                CostPilot gateway            |
  POST /v1/chat/...     |                                             |
  Bearer cp_...         |  auth -> policy -> pre-flight estimate      |
                        |  -> budget reserve (Redis, atomic Lua)      |
                        |  -> forward to provider (SSE passthrough)   |
                        |  -> meter tokens mid-stream, cutoff on      |
                        |     breach -> exact-price ledger (Postgres) |
                        +----+----------+----------+---------+-------+
                             |          |          |         |
                        Postgres      Redis      Kafka   Prometheus
                        (ledger,    (live       (usage    (metrics)
                         budgets,    counters)   events)     |
                         audit)                    |      Grafana
                                               ClickHouse (dashboard)
                                               (spend analytics)
```

- **Postgres** - source of truth: usage ledger (idempotent writes), budgets, policies, audit trail, versioned prices.
- **Redis** - live remaining-budget counters; reservations are atomic Lua, fail-open by design.
- **Kafka -> ClickHouse** - async usage events off the hot path, OLAP spend analytics with Postgres reconciliation.
- **Prometheus + Grafana** - governance metrics scraped from `/actuator/prometheus`, auto-provisioned dashboard.
- **Embedded mock LLM** - default upstream, so the demo below costs $0 and touches no real provider.

## 10-minute demo

Prereqs: Docker. Nothing else.

```bash
git clone https://github.com/tanhoangkhoanguyen/CostPilot.git
cd CostPilot
docker compose up --build -d
# wait for the gateway to report healthy (~2-3 min first time: image build + stack boot)
docker compose ps gateway
```

Seeded demo API keys (dev only - hashes live in the DB, these raw values are public on purpose):

| key | scope |
|-----|-------|
| `cp_demo_team_platform` | team `platform` |
| `cp_demo_team_research` | team `research` |
| `cp_admin_root` | tenant admin (may impersonate teams via `X-Team-ID`) |

**1. A normal request flows through and gets billed** (mock upstream, $0):

```bash
curl -s http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer cp_demo_team_platform" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"max_tokens":64}'
```

**2. The headline: a streaming response gets cut off mid-generation the moment it would overspend.** Give team `research` a budget that passes the pre-flight estimate (which assumes a default-length answer) but cannot cover the long generation that actually happens - the exact under-estimate scenario mid-stream cutoff exists for:

```bash
# budget for team "research" (scope_ref matches the team name the gateway stamps on usage)
docker compose exec postgres psql -U costpilot -d costpilot -c \
  "insert into budget (scope_type, scope_ref, limit_amount) values ('team','research', 0.0013);"

# the mock upstream echoes the prompt back token by token, so a ~2000-word prompt
# forces a ~2000-token generation; no max_tokens -> the estimate assumes far less
PROMPT=$(printf 'lorem %.0s' $(seq 1 2000))
curl -sN http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer cp_demo_team_research" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"$PROMPT\"}],\"stream\":true}" \
  | tail -5
```

The stream delivers real chunks for a few seconds, then ends with a clean truncation event - `"finish_reason":"budget_cutoff"` followed by `[DONE]`, not a broken socket. Only the tokens actually delivered are billed (spend is bounded by the budget to within one token).

**3. Hard block, machine-readable.** Once the budget is exhausted, the next request is refused up front:

```bash
curl -si http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer cp_demo_team_research" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"max_tokens":256}'
# -> HTTP 402, {"error":{"type":"budget_exceeded","code":"team",...}}
```

**4. Watch it on the dashboard.** Open Grafana at <http://localhost:3000> (anonymous viewer enabled; admin/admin to edit) - the CostPilot governance dashboard shows requests, spend, and the budget rejections you just caused. Raw metrics: <http://localhost:9090> (Prometheus) or `curl localhost:8080/actuator/prometheus`.

**5. Spend analytics** (ClickHouse-backed, reconciled against the Postgres ledger):

```bash
curl -s "http://localhost:8080/api/analytics/spend" -H "Authorization: Bearer cp_admin_root"
curl -s "http://localhost:8080/api/analytics/reconcile" -H "Authorization: Bearer cp_admin_root"
```

## Semantic cache (optional cost optimization)

An opt-in spend-reduction layer: when an incoming prompt is semantically close to one already answered, CostPilot serves the cached response at **\$0 provider cost** and records the would-be cost as savings. Off by default; enable with:

```bash
COSTPILOT_CACHE_ENABLED=true docker compose up -d
```

- **How it decides:** prompts are embedded by a deterministic local embedder (\$0, no network - dev and tests cost nothing) and stored in **pgvector**, keyed by tenant/team. A lookup takes the nearest neighbor **within the same tenant and team** - tenants can never hit each other's cache. The `Embedder` interface is the single swap point for a real embedding provider.
- **Precision over recall:** a hit requires cosine similarity ≥ **0.97** (`COSTPILOT_CACHE_SIMILARITY_THRESHOLD`). The threshold is deliberately conservative so the false-hit rate stays low - the cache would rather forward a borderline prompt than serve a wrong answer. A hit sets `X-CostPilot-Cache: hit`.
- **Savings:** every hit accrues `costpilot.cache.savings_nanos`; the Grafana dashboard shows cache savings, hit ratio, and hit/miss rate, and the figure reconciles against the hit log.

Streaming requests bypass the cache (a cached answer is a complete response).

## Going live with real providers

Switching upstreams is env-only, never a code change:

```bash
COSTPILOT_UPSTREAM_MODE=real \
COSTPILOT_UPSTREAM_OPENAI_API_KEY=sk-... \
COSTPILOT_UPSTREAM_ANTHROPIC_API_KEY=sk-ant-... \
docker compose up -d
```

Point your OpenAI-compatible SDK at `http://localhost:8080/v1` with a CostPilot key as the bearer token. For any real deploy also override `COSTPILOT_API_KEY_PEPPER` and mint fresh keys via `POST /admin/keys`.

## Admin CLI (`costpilot`)

Finance/platform run the control plane without a frontend - set budgets and policy, and act on approvals - via the `costpilot` CLI (a standalone Picocli app that talks to the gateway's admin API). Build it, then point it at the gateway with an admin key:

```bash
./gradlew :cli:installDist
export COSTPILOT_ENDPOINT=http://localhost:8080
export COSTPILOT_ADMIN_KEY=cp_admin_root      # dev key; use a minted key in prod

CLI=cli/build/install/costpilot/bin/costpilot

# governance config - takes effect at runtime, no redeploy
$CLI budget set --scope team --ref research --limit 25.00
$CLI policy set --scope-type team --scope-ref research \
      --allowed "gpt-4o-mini,claude-*" --fallback require_approval
$CLI budget ls
$CLI policy ls

# human-in-the-loop approvals (Stage 8)
$CLI approvals ls
$CLI approvals approve <pending-id>
$CLI approvals reject  <pending-id> --reason "over quarter budget"

# spend (grouped by team | project | model)
$CLI spend show --group-by team
```

Every command has `--help`, exits non-zero on error, and reads the endpoint + admin key from `--endpoint`/`--admin-key` flags or the `COSTPILOT_ENDPOINT`/`COSTPILOT_ADMIN_KEY` env vars.

## Load-test numbers (k6, reproducible)

One command runs the whole benchmark - stack up, budgets seeded, three k6 scenarios, then the claims are verified straight from the Postgres ledger:

```bash
bash loadtest/run.sh
```

Scenarios: 130s warm soak at 30 req/s, then **100 req/s sustained for 30s** across 10 governed teams (guard latency), then **300 requests flooding 10 teams with tiny caps** (overspend), then **10 concurrent long streams** against cutoff-sized caps (cutoff accuracy).

Measured on a dev laptop (Windows 11, Docker Desktop, whole stack + k6 on one machine), latest run:

| claim | target | measured |
|-------|--------|----------|
| budget-guard decision p50 / p95 / p99 at 100 req/s | p99 < 5 ms | 2.03 ms / 3.08 ms / **4.65 ms** |
| teams overspending their cap under flood | 0 | **0 of 30** (156 served, 144 blocked with 402) |
| worst mid-stream cutoff overshoot | < 1 output token | **0.33 tokens** |
| functional checks (clean cutoff signal, valid statuses) | 100% | **100%** (7213 of 7213) |

End-to-end request p99 during the guard-latency window was ~500 ms on this shared machine; that number swings with host load and is only sanity-bounded (< 3 s) by the harness - the enforcement targets above are the stable, published claims. Guard quantiles are read back from Prometheus at the measurement window, so micrometer's decaying summary can't dilute them with cold-start samples.

## Development

- Build + full test suite (Testcontainers - needs Docker): `./gradlew build`
- 134 tests, all against the embedded mock upstream; JaCoCo gate: line >= 80%, branch >= 60%
- CI: GitHub Actions runs the same build on every push/PR
- Roadmap and design decisions: [ROADMAP.md](ROADMAP.md)

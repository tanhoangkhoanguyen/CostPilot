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

## Going live with real providers

Switching upstreams is env-only, never a code change:

```bash
COSTPILOT_UPSTREAM_MODE=real \
COSTPILOT_UPSTREAM_OPENAI_API_KEY=sk-... \
COSTPILOT_UPSTREAM_ANTHROPIC_API_KEY=sk-ant-... \
docker compose up -d
```

Point your OpenAI-compatible SDK at `http://localhost:8080/v1` with a CostPilot key as the bearer token. For any real deploy also override `COSTPILOT_API_KEY_PEPPER` and mint fresh keys via `POST /admin/keys`.

## Development

- Build + full test suite (Testcontainers - needs Docker): `./gradlew build`
- 134 tests, all against the embedded mock upstream; JaCoCo gate: line >= 80%, branch >= 60%
- CI: GitHub Actions runs the same build on every push/PR
- Roadmap and design decisions: [ROADMAP.md](ROADMAP.md)

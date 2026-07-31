<div align="center">

# CostPilot documentation

Everything you need to run it, drive it, and point it at a real provider.

[← Back to the project](../README.md) · [Benchmarks](BENCHMARK.md)

</div>

---

## Contents

| | page | what is in it |
|---|---|---|
| 1 | [Run it](#1-run-it) | one command, what comes up, seeded keys |
| 2 | [The ten minute demo](#2-the-ten-minute-demo) | normal request, mid stream cutoff, hard block, dashboard, analytics |
| 3 | [Admin CLI](#3-admin-cli) | budgets, policy, approvals, spend |
| 4 | [Python SDK](#4-python-sdk) | governance aware client |
| 5 | [Semantic cache](#5-semantic-cache) | optional, serves close prompts for free |
| 6 | [Going live](#6-going-live-with-real-providers) | OpenAI, Anthropic, Gemini on Vertex |
| 7 | [Response headers and errors](#7-response-headers-and-errors) | what the gateway tells your client |
| 8 | [Load test](#8-load-test) | reproducing the numbers |
| 9 | [Development](#9-development) | build, test, coverage gate |

---

## 1. Run it

The only prerequisite is Docker. The default upstream is a mock provider embedded in the app, so nothing below touches a real provider or costs a cent.

```bash
git clone https://github.com/tanhoangkhoanguyen/CostPilot.git
cd CostPilot
docker compose up --build -d

# first boot takes 2 to 3 minutes: image build plus the whole stack
docker compose ps gateway
```

What comes up:

| service | port | role |
|---|---|---|
| gateway | 8080 | the governance control plane and the OpenAI compatible API |
| Postgres + pgvector | 5432 | source of truth: ledger, budgets, policy, audit, prices |
| Redis | 6379 | live remaining budget counters |
| Kafka | 9092 | usage events, published after a request settles |
| ClickHouse | 8123 | spend analytics, fed from Kafka |
| Prometheus | 9090 | governance metrics |
| Grafana | 3000 | auto provisioned dashboard, anonymous viewer |

Seeded demo keys. These are dev only, the database stores hashes, and the raw values are public here on purpose.

| key | scope |
|---|---|
| `cp_demo_team_platform` | team `platform` |
| `cp_demo_team_research` | team `research` |
| `cp_admin_root` | tenant admin, may act for any team via `X-Team-ID` |

For any real deployment, override `COSTPILOT_API_KEY_PEPPER` and mint fresh keys with `POST /admin/keys`.

---

## 2. The ten minute demo

### A normal request flows through and gets billed

```bash
curl -s http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer cp_demo_team_platform" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"max_tokens":64}'
```

### The headline: a stream is cut off the moment it would overspend

Give team `research` a budget that clears the pre flight estimate, which assumes a normal length answer, but cannot cover the very long generation that actually happens. This is exactly the under estimate case that mid stream cutoff exists for.

```bash
# scope_ref matches the team name the gateway stamps on usage
docker compose exec postgres psql -U costpilot -d costpilot -c \
  "insert into budget (scope_type, scope_ref, limit_amount) values ('team','research', 0.0013);"

# the mock upstream echoes the prompt back token by token, so a 2000 word prompt
# forces a 2000 token generation, and with no max_tokens the estimate assumes far less
PROMPT=$(printf 'lorem %.0s' $(seq 1 2000))
curl -sN http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer cp_demo_team_research" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"$PROMPT\"}],\"stream\":true}" \
  | tail -5
```

Real chunks arrive for a few seconds, then the stream ends with a clean truncation: `"finish_reason":"budget_cutoff"` followed by `[DONE]`. Not a dropped socket. Only the tokens actually delivered are billed, and the overshoot is bounded to one streamed chunk.

### Once the budget is gone, the next request never leaves

```bash
curl -si http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer cp_demo_team_research" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"max_tokens":256}'
# HTTP 402  {"error":{"type":"budget_exceeded","code":"team",...}}
```

### Watch it happen

Open Grafana at <http://localhost:3000>. Anonymous viewing is on, use `admin` / `admin` if you want to edit. The governance dashboard shows requests, spend, and the budget rejections you just caused.

Raw metrics: <http://localhost:9090>, or `curl localhost:8080/actuator/prometheus`.

### Spend analytics

Served from ClickHouse and reconciled against the Postgres ledger.

```bash
curl -s "http://localhost:8080/api/analytics/spend"     -H "Authorization: Bearer cp_admin_root"
curl -s "http://localhost:8080/api/analytics/reconcile" -H "Authorization: Bearer cp_admin_root"
```

Available under `/api/analytics/`: `spend`, `trends`, `top-spenders`, `decisions`, `budget-utilization`, `savings`, `reconcile`.

---

## 3. Admin CLI

Finance and platform people run the control plane without a frontend. `costpilot` is a standalone Picocli app that talks to the gateway over HTTP only, so it has no dependency on the server.

```bash
./gradlew :cli:installDist
export COSTPILOT_ENDPOINT=http://localhost:8080
export COSTPILOT_ADMIN_KEY=cp_admin_root      # dev key, mint a real one for prod

CLI=cli/build/install/costpilot/bin/costpilot
```

Governance config. Every write takes effect on the next request, with no redeploy, because the writes invalidate the relevant caches.

```bash
$CLI budget set --scope team --ref research --limit 25.00
$CLI policy set --scope-type team --scope-ref research \
      --allowed "gpt-4o-mini,claude-*" --fallback require_approval
$CLI budget ls
$CLI policy ls
```

Human in the loop approvals.

```bash
$CLI approvals ls
$CLI approvals approve <pending-id>
$CLI approvals reject  <pending-id> --reason "over quarter budget"
```

Spend, grouped by `team`, `project` or `model`.

```bash
$CLI spend show --group-by team
```

Every command has `--help`, exits non zero on failure, and reads the endpoint and key from flags or from `COSTPILOT_ENDPOINT` and `COSTPILOT_ADMIN_KEY`.

---

## 4. Python SDK

You can point a plain OpenAI SDK at the gateway and parse headers yourself, or use the client that surfaces the governance verdict as typed data.

```bash
pip install costpilot
```

```python
from costpilot import CostPilot, BudgetExceededError

cp = CostPilot(base_url="http://localhost:8080/v1", api_key="cp_...", team="research")

r = cp.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "hi"}],
)

print(r.content)
print(r.governance.cache_hit, r.governance.budget_warning)
```

It exposes cache hits, budget warnings, model routing and downgrades, mid stream `budget_cutoff`, and typed errors such as `BudgetExceededError.scope`, `PolicyDeniedError.rule_id` and `ApprovalRequiredError`. Sync and async, one dependency (`httpx`).

Source, quickstart and streaming examples: [`sdk/python/`](../sdk/python/). Its tests run against a mocked transport, so they need no gateway and cost nothing.

---

## 5. Semantic cache

Off by default. It is a cost optimisation you opt into, not part of the governance guarantee.

```bash
COSTPILOT_CACHE_ENABLED=true docker compose up -d
```

When an incoming prompt is close enough to one already answered, CostPilot serves the stored response at zero provider cost and books the would be cost as savings.

- **How it decides.** Prompts are embedded by a deterministic local embedder, which means dev and tests make no network call and cost nothing, and stored in pgvector keyed by tenant and team. A lookup takes the nearest neighbour **inside the same tenant and team**, so tenants can never read each other's cache. The `Embedder` interface is the single place to swap in a real embedding provider.
- **Precision over recall.** A hit needs cosine similarity of at least **0.97** (`COSTPILOT_CACHE_SIMILARITY_THRESHOLD`). The threshold is deliberately strict: the cache would rather forward a borderline prompt than return a wrong answer. A hit sets `X-CostPilot-Cache: hit`.
- **Savings.** Every hit accrues `costpilot.cache.savings_nanos`. Grafana shows savings, hit ratio and hit/miss rate, and the figure reconciles against the hit log.
- **Lifetime is bounded.** An entry older than the TTL (`COSTPILOT_CACHE_TTL`, default `PT24H`) is never served — the lookup excludes past-TTL rows — and a scheduled sweep (`COSTPILOT_CACHE_EVICTION_INTERVAL_MS`, default 60s) deletes them so the table stays bounded. A semantic cache with no expiry would serve unboundedly stale answers as "free" and grow without limit; every other cache in the system already has a TTL, and now this one does too. Evictions are counted at `costpilot.cache.evictions` and the live entry count is the `costpilot.cache.size` gauge.

Streaming requests skip the cache, because a cached answer is a complete response.

---

## 6. Going live with real providers

Switching upstreams is configuration, never a code change.

```bash
COSTPILOT_UPSTREAM_MODE=real \
COSTPILOT_UPSTREAM_OPENAI_API_KEY=sk-... \
COSTPILOT_UPSTREAM_ANTHROPIC_API_KEY=sk-ant-... \
docker compose up -d
```

### Reproducible deploy profile, Gemini on Vertex AI

For a repeatable bring up where the credential is injected at runtime and never baked into the image, use the overlay plus a `.env`.

```bash
cp .env.example .env                        # project, pepper, credentials path
cp /path/to/service-account.json secrets/adc.json && chmod 644 secrets/adc.json
docker compose -f docker-compose.yml -f docker-compose.real.yml up --build -d
```

The overlay flips `COSTPILOT_UPSTREAM_MODE=real`, sets the Vertex flavor, project and location, mounts the service account JSON read only at `/var/secrets/adc.json` for ADC bearer auth, and overrides the dev pepper. The rest of the stack is inherited unchanged, so health, the ledger, Redis counters and `/actuator/prometheus` all populate against live traffic.

`.env` and `secrets/*.json` are gitignored, so no credential reaches git or the image.

### Pointing an app at it

Set `base_url` to `http://localhost:8080/v1` and use a CostPilot key as the bearer token. The provider is chosen by the model id, or by explicit config in `costpilot.upstream.model-providers`.

| model id starts with | goes to |
|---|---|
| `claude` | Anthropic |
| `gemini` | Gemini or Vertex |
| anything else | OpenAI |

---

## 7. Response headers and errors

What the gateway tells your client about the decision it made.

| header | meaning |
|---|---|
| `X-CostPilot-Cache: hit` | served from the semantic cache, zero provider cost |
| `X-CostPilot-Model-Routed` | cost routing picked a different model, with the reason |
| `X-CostPilot-Model-Downgraded` | policy or budget pressure swapped the model, with the reason |
| `X-CostPilot-Budget-Warning` | soft limit, 20% or less remaining in some scope |

Request headers you can send:

| header | effect |
|---|---|
| `Authorization: Bearer cp_...` | required, this is your identity |
| `X-Team-ID` / `X-Project-ID` | honoured only for admin keys, ignored for team keys |
| `X-User-ID` / `X-Environment` | attribution only, no authority |
| `Idempotency-Key` | makes a retry replay safe in the ledger |
| `X-CostPilot-Min-Tier` | the quality bar cost routing must not go below |

Terminal outcomes:

| status | body | when |
|---|---|---|
| `200` | `chat.completion`, or SSE ending in `[DONE]` | normal |
| `402` | `{"error":{"type":"budget_exceeded","code":"<scope>"}}` | out of budget and nothing cheaper fits |
| `403` | `{"error":{"type":"policy_denied",...}}` | policy said no |
| `202` | `{"id":...,"state":"pending","expires_at":...}` | held for human approval, not forwarded |

---

## 8. Load test

One command runs the whole benchmark: stack up, budgets seeded, three k6 scenarios, then the claims are verified straight from the Postgres ledger.

```bash
bash loadtest/run.sh
```

Cost-savings benchmark (routing + semantic cache, also $0 mock):

```bash
bash loadtest/run-savings.sh
```

The scenarios are a 130 second warm soak at 30 req/s, then 100 req/s sustained for 30 seconds across 10 governed teams to measure guard latency, then 300 requests flooding 10 teams with tiny caps to test for overspend, then 10 concurrent long streams against cutoff sized caps to measure cutoff accuracy.

Guard quantiles are read from Prometheus at the measurement window, so the decaying summary cannot dilute them with cold start samples. Full results, the live Gemini run, and caveats: [BENCHMARK.md](BENCHMARK.md).

---

## 9. Development

```bash
./gradlew build     # compile, full test suite, coverage gate
./gradlew test      # tests only
./gradlew bootRun   # run on :8080, needs Postgres and Redis
```

- Tests do **not** need `docker compose up`. They start their own Postgres, Redis, Kafka and ClickHouse through Testcontainers. A working Docker daemon is required.
- Naming is a hard rule: `*Test` is a pure unit test with no containers, `*IT` is an integration test that needs them.
- 170+ tests run against the embedded mock upstream. Coverage gate: line 80%, branch 60%.
- CI runs the same build on every push and pull request.
- The build has two modules: the gateway at the root, and `cli/`, which is standalone and deliberately excluded from the coverage gate and the Docker image.

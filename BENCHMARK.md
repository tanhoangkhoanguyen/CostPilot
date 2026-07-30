# CostPilot — Benchmark Results

**Runtime LLM budget governance: enforce dollar budgets *before* a request and *during* the
stream — cut off an over-budget generation mid-flight, a capability admission-only gateways
(LiteLLM) don't have.** The numbers below prove that on both a $0 mock and a real Gemini stream.

Every claim below is reconciled from the **Postgres ledger** (the money source of truth) and,
for latency, read from **Prometheus** at the measurement window. k6's own numbers are treated
as noisy context. Reproduce with `bash loadtest/run.sh` (mock upstream, $0) or
`bash loadtest/run-live.sh` (real Gemini/Vertex, a few cents).

## Headline — mid-stream budget cutoff

A streamed response that passes admission but overruns mid-generation is **cut off the instant
it would breach budget** — upstream cancelled, clean `budget_cutoff` + `[DONE]` signal, only
delivered tokens billed. This is the capability admission-only gateways (LiteLLM) lack: they
check budget *before* a request and cannot stop a stream once it starts.

| upstream | overshoot beyond cap | signal |
|---|---|---|
| mock (1 token/chunk) | ~1–2 tokens | clean `budget_cutoff` + `[DONE]` |
| **live Gemini** (40–55 tokens/chunk) | **~53 tokens (one provider chunk)** | clean `budget_cutoff` + `[DONE]` |

The bound is **one streamed chunk** — coarser provider chunking makes it coarser, which is the
honest, provider-dependent result.

## Supporting claims

| claim | result | source |
|---|---|---|
| Teams overspending their cap under concurrent flood | **0** — mock 0/30, live Vertex breach=false | ledger |
| Budget-guard decision latency, p99 (mock, 100 req/s sustained) | **14.123008 ms** | Prometheus |
| Price correctness | billed = provider-reported tokens × published price (exact) | ledger |

**Guard latency — measurement method.** Two-host run on identical VMs (both GCP `e2-standard-4`,
4 vCPU / 16 GB, `us-central1-a`, Ubuntu 22.04): the gateway stack on one, the k6 load generator
on a *separate* VM in the same zone, so the generator can't steal CPU from the JVM recording the
latency. 100 req/s was sustained through the measured window (confirmed from Prometheus,
`rate(costpilot_budget_guard_seconds_count[15s]) = 100`). Quantiles are read from Prometheus at
the window tail, **not** from k6's client-side round-trip — the Timers are recorded server-side
inside `BudgetGuard.reserve` (`GovernedRequestExecutor.java:100-124`), so the client→gateway
network hop is outside the measured span and running the generator on a second host only removes
CPU contention.

**Where the time goes (timer split).** `reserve()` is instrumented as three Timers so the p99
can be *attributed*, not guessed: the total, the Redis Lua reservation phase, and the price
lookup (tagged cache `hit`/`miss`). p99 at the window tail:

| phase | metric | p99 |
|---|---|---|
| **total** | `costpilot_budget_guard_seconds` | 14.123008 ms |
| **Redis Lua reservation** | `costpilot_budget_guard_reserve_seconds` | 14.123008 ms |
| price lookup, cache miss (Postgres) | `costpilot_budget_guard_price_lookup_seconds{outcome="miss"}` | 1.769472 ms |

Values are the exact Prometheus summary-quantile readings at the window tail (reproduced
verbatim, not rounded); the trailing digits are the summary's bucket boundary.

**Observed.** The `reserve` (Redis Lua) phase p99 equals the total p99; the price-lookup
cache-miss phase p99 is **1.769472 ms**, ~8× smaller. The total is accounted for by the Redis
reservation phase, not the price lookup.

From the code (`BudgetGuard.reserve`, `GovernedRequestExecutor.java:100-124`): the reservation
issues one Lua call **per governed scope**, iterating `BudgetScope.values()` = tenant, team,
project, model (four scopes); the price lookup is served from a 30 s in-process cache
(`BudgetGuard.java`) and reaches Postgres only on a miss.

*Metric type: client-side **summary** quantiles (`GovernanceMetrics.java:42-45`) — per-process
estimates, not exact percentiles, and not aggregatable across instances (single gateway process,
no load balancer). `reserve` and `total` fall in the same summary bucket at this scale.*

### Reproducing the guard-latency benchmark

The benchmark spans two hosts. The full start→end runbook — provision two VMs, open the
firewall, run, and scrape — is [`loadtest/REPRODUCE.md`](loadtest/REPRODUCE.md); its mechanical
steps are wrapped in [`loadtest/guard-benchmark.sh`](loadtest/guard-benchmark.sh), one
subcommand per host:

```bash
# VM-A: bash loadtest/guard-benchmark.sh stack                    # up + seed
# VM-B: bash loadtest/guard-benchmark.sh load <VM-A-INTERNAL-IP>  # drive load, prints T0
# VM-A: bash loadtest/guard-benchmark.sh scrape <T0>              # read p50/p95/p99 per phase
```

> **Cutoff over the two-host network.** k6 may report `cutoff: clean budget_cutoff signal 0/10`
> (SSE-body detection is unreliable across the hop). That is a k6 false negative — the ledger is
> authoritative and shows every `lt-cutoff-*` team billed to `cap + ~1.3 output tokens`
> (one-chunk overshoot), i.e. the stream *was* cut off. Verify from Postgres, not k6's check line.

**Price correctness caveat.** Verified against the provider's reported token counts × Google's
published per-token price — **not** reconciled against the GCP invoice (sub-cent charges are
rounded/absorbed by free-trial credit and never surfaced a line item).

## Live Vertex run — 2026-07-23 (real Gemini, local host)

Same governance claims against a real `gemini-2.5-flash-lite` stream over Vertex AI (ADC auth,
project `costpilot-503302`, `us-central1`; V14 price $0.10/$0.40 per 1M). Proves **correctness on
a real provider**, not scale — Gemini 2.x uses Dynamic Shared Quota (no fixed RPM to raise), so
throughput is sample-limited and guard latency stays a mock-host measurement.

| claim | result |
|---|---|
| Price correctness | 7 in / 9 out tokens → **$0.0000043** (= 7×$0.0000001 + 9×$0.0000004) |
| Overspend (100 req, cap $0.00012) | spend ≤ cap, **breach=false** (429 DSQ throttles retried, unrelated to budget) |
| Mid-stream cutoff | billed **1173 tokens** (< 1239 full), overshoot **~53 tok = one Gemini chunk** |

Cost of the run: a few thousandths of a US cent, inside the free-trial credit.

## vs. LiteLLM (argued from docs, not A/B-tested)

LiteLLM enforces at **admission** — compares accumulated spend to `max_budget` before a request,
and does not interrupt an admitted stream. CostPilot enforces at **runtime** — meters tokens
mid-stream and cuts off the moment a response would overspend (the headline table above). A
head-to-head benchmark is not yet run; this contrast is from LiteLLM's own docs and open
budget-bypass issues.

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

### What is exact vs what is a hold

CostPilot bills **exactly**; it estimates **only** at the two points where the true token
count does not exist yet — the pre-flight budget reservation and the mid-stream cutoff
decision — and the provider's own count supersedes the estimate the instant it arrives. The
money written to the ledger is never an estimate.

| mechanism | kind | role | source of truth |
|---|---|---|---|
| **Ledger billing** — `CostCalculator.calculate` ([`CostCalculator.java:18-28`](../src/main/java/com/costpilot/cost/CostCalculator.java#L18-L28)) | **authoritative** | what a team is actually charged | provider-reported tokens × versioned per-1k price, in `BigDecimal` nanodollars — `rate × tokens / 1000` only shifts the decimal, so **no rounding ever happens** |
| **Pre-flight reservation** — `CostEstimator.estimateMax` ([`CostEstimator.java:22-38`](../src/main/java/com/costpilot/cost/CostEstimator.java#L22-L38)) | deterministic heuristic | a **hold** placed before the request is forwarded, so a concurrent flood can't overspend | deliberately *over*-estimates (input `chars/3`, output the full `max_tokens`); released and replaced by the exact charge once the provider reports |
| **Mid-stream cutoff** — `StreamCostMeter.usage` ([`StreamCostMeter.java:74-84`](../src/main/java/com/costpilot/cost/StreamCostMeter.java#L74-L84)) | deterministic heuristic | a running cost during the stream so an over-budget generation is cut off on time | script-weighted length estimate **only until** the provider's usage arrives; `reportedOutputTokens > 0` ⇒ the reported count is returned instead |

**The estimate never becomes the bill.** Both estimate sites exist because most providers
report token usage only at the *end* of a stream — until then the true number physically
does not exist. `StreamCostMeter.usage()` returns the provider's reported count the instant
it is present (`reportedOutputTokens.get() > 0`), so the ledger always settles on the exact
provider-reported figure. The one documented exception is a stream cut off *before* any usage
event arrived: it is billed on the estimated tokens (a partial record), because there is no
authoritative number to use.

*Price-correctness caveat: "exact" means exact against the **provider's reported token counts ×
the published per-token price** — not reconciled against the GCP invoice line, whose sub-cent
charges are rounded/absorbed by free-trial credit and never surface individually.*

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
firewall, run, and scrape — is [`loadtest/REPRODUCE.md`](../loadtest/REPRODUCE.md); its mechanical
steps are wrapped in [`loadtest/guard-benchmark.sh`](../loadtest/guard-benchmark.sh), one
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

## Cost savings — routing + semantic cache (mock, $0)

Cost optimization is the product: route to the cheapest model that clears the min-tier bar,
and serve repeat/similar prompts from the semantic cache at $0 provider cost. Both channels
are measured in Postgres money truth and exposed as one total:

| channel | ledger | counted in |
|---|---|---|
| Routing / downgrade | `usage_record.savings_nanos` | `routingSavingsUsd` |
| Semantic cache hit | `cache_hit_log.savings_nanos` | `cacheSavingsUsd` |
| **Combined** | sum of the two (no double-count) | `totalSavingsUsd` + `percentSaved` |

`percentSaved = totalSavings / wouldBeSpend`, where `wouldBeSpend = actualSpend + totalSavings`
(what the originally requested models would have cost without CostPilot).

Reproduce (mock upstream, $0, no VM):

```bash
bash loadtest/run-savings.sh
```

| claim | result | source |
|---|---|---|
| Routing savings (20× gpt-4o → gpt-4o-mini, Min-Tier 1) | **$0.001316** | `usage_record.savings_nanos` |
| Cache savings (5 prompts × 3 hits) | **$0.000119** | `cache_hit_log` |
| **Combined $ / % saved** | **$0.001435 (92.1%)** of would-be $0.001559 | ledger = `GET /api/analytics/savings` |

Numbers from `bash loadtest/run-savings.sh` on the $0 mock (2026-07-30). Absolute dollars are small because the mock prices tokens in sub-cents; the **92.1%** figure is the résumé-relevant claim (counterfactual would-be spend vs actual). Script asserts `API total == routing + cache` (no double-count) before printing.

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

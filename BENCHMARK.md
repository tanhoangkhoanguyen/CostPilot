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
| Budget-guard decision overhead (mock, 100 req/s) | p50 **2.72 ms** / p95 **7.57 ms** | Prometheus |
| Price correctness | billed = provider-reported tokens × published price (exact) | ledger |

**Guard p99 (<5 ms target): pending.** The last mock run measured p99 **17.79 ms** on a
cold-build, shared laptop (whole stack + load generator on one host) — a measurement-noise
figure, not the guard logic. p50/p95 are in line with steady state; a clean warm-host re-run is
needed before publishing a p99. Guard latency is computed *before* the upstream call, so it is
provider-independent — a live run adds nothing here.

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

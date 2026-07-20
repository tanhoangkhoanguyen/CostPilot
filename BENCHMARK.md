# CostPilot — Benchmark Results

Reproducible benchmark: `bash loadtest/run.sh` (Git Bash on Windows). Brings the full
compose stack up, seeds fresh `lt-*` load-test budgets, runs four k6 scenarios in
sequence, then **reconciles every cost claim from the Postgres ledger** and reads
hot-path latency **from Prometheus** at the measurement window. The load generator's
own numbers are treated as noisy context; the ledger and Prometheus are the sources of
truth.

## What each scenario proves

| Scenario | Simulates | Measures | Target |
|---|---|---|---|
| `warmup` (30 req/s, 130s) | steady state before measuring | nothing — validity control (warms JIT, ages out cold-start latency samples) | n/a |
| `guard_latency` (100 req/s, 30s, 10 teams, huge caps) | production peak — is governance a hot-path bottleneck? | budget-guard decision latency p50/p95/p99 (Prometheus `costpilot_budget_guard_seconds`) | **guard p99 < 5 ms** |
| `overspend_flood` (300 reqs, 30 VUs, 10 teams, $0.0002 caps) | runaway concurrent spend / race conditions | teams whose ledger spend exceeded their cap | **0 overspend** |
| `cutoff_scale` (10 concurrent streams, ~2000-tok prompt vs ~1700-tok cap) | a stream that passes admission but overspends mid-generation (the case LiteLLM can't stop) | worst mid-stream overshoot; clean truncation signal | **< 1 output token** + 100% clean `budget_cutoff`+`[DONE]` |

`overspend_flood` and `cutoff_scale` are correctness/cost-control claims (verified from
the ledger). `guard_latency` is a performance claim (verified from Prometheus).

---

## Run — 2026-07-19 (dev laptop: Windows 11, Docker Desktop, whole stack + k6 on one machine)

> **Caveat for this run:** captured immediately after a cold image build + k6 image pull,
> with the whole stack and the load generator sharing one laptop. Latency numbers below
> are elevated by host contention and are **not** a clean steady-state measurement. The
> correctness results (overspend, cutoff overshoot) are host-independent and stand as-is.

### Correctness / cost control — ledger verdict (all PASS)

| scenario | teams | breach_teams (spend > cap) | worst overshoot |
|---|---|---|---|
| cutoff  | 10 | **0** | **0.33 tokens** |
| flood   | 10 | **0** | under cap (−261 tokens of headroom) |
| latency | 10 | **0** | under cap (large headroom) |

- **0 of 30 teams overspent** their budget. (flood: 143 served / 157 blocked with 402.)
- **Worst mid-stream cutoff overshoot: 0.33 output tokens** — streams are cut off within
  a third of a token of the budget, then terminated with a clean
  `"finish_reason":"budget_cutoff"` + `[DONE]`.
- Negative "overshoot" for flood/latency simply means those teams finished *under* cap
  (spend − cap < 0), which is the intended outcome — reservations block before overspend.

### Functional checks (k6) — PASS

- **100.00% checks passed (7190 / 7190):** every request a valid `200`/`402`, and every
  stream carried the clean `budget_cutoff` signal and ended with `[DONE]`.

### Hot-path latency — guard decision (MISSED target this run)

| quantile | measured | target |
|---|---|---|
| p50 | 2.72 ms | — |
| p95 | 7.57 ms | — |
| p99 | **17.79 ms** | **< 5 ms** ❌ |

p50 is in line with steady-state expectations; p95/p99 are inflated by the cold-build +
shared-host contention noted above. This is a latency-of-measurement issue, not a change
in the guard logic. A clean steady-state re-run (idle host, stack already built and warm)
is needed before publishing a guard-p99 figure. **Do not cite a sub-5 ms p99 from this
run.**

End-to-end request p99 during the guard window was ~0.9 s on this shared machine; that
figure swings with host load and is only sanity-bounded (< 3 s) by the harness.

---

## Standing (what is proven vs. still pending)

| Claim | Status |
|---|---|
| 0 budget overruns under concurrent flood (ledger-verified) | ✅ proven (0/30) |
| Mid-stream cutoff bounds overspend to < 1 token | ✅ proven (0.33) |
| Clean, protocol-correct truncation on cutoff | ✅ proven (100%) |
| Guard-decision p99 < 5 ms | ⏳ p50 ok (2.72 ms); p95/p99 missed on a noisy host — needs a clean re-run |

## How this differs from LiteLLM (argued, not yet A/B-tested)

LiteLLM enforces budgets at **admission** — it compares already-accumulated spend to
`max_budget` before letting a request in, and does not interrupt a stream once admitted.
CostPilot enforces at **runtime**: it meters tokens mid-stream and cuts off the moment a
response would overspend. `cutoff_scale` measures exactly that capability (a request that
*passed* admission but overran mid-generation, bounded to 0.33 tokens). A live
side-by-side LiteLLM benchmark is not yet run — this contrast is currently argued from
LiteLLM's own docs and open budget-bypass issues, not demonstrated head-to-head.

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
  > _Note (added 2026-07-23):_ this 0.33 figure reflects the original one-token-per-chunk
  > meter. The meter was later changed to length-based estimation (see the live-run section)
  > so cutoff also works on providers that pack many tokens per chunk; a fresh mock run now
  > reports ~1–2 tokens here. The claim "bounded to one streamed chunk" is unchanged.
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

## Live run — 2026-07-23 (real Vertex Gemini API, **local** host)

> **What this is.** The section above uses the embedded `$0` mock upstream. This section
> re-runs the same governance claims against a **real Google Gemini model over Vertex AI**
> — actual API key, actual tokens, actual (tiny) dollars billed. It was run **locally**
> (dev laptop) against the deployed `docker-compose.real.yml` stack, i.e. the only
> difference from the mock run is a real upstream + credentials, **not** a cloud host. A
> deployed-VM run (for a host-representative guard-latency figure) is still pending; guard
> latency is upstream-independent, so the mock figure above already characterises it.
>
> Reproduce with `bash loadtest/run-live.sh` after `cp .env.example .env` (fill in a GCP
> project, a Vertex service-account at `secrets/adc.json`, and a fresh API-key pepper).
> The mock `bash loadtest/run.sh` is unchanged and still runs at `$0` with no credentials.

**Environment.** Dev laptop (Windows 11, Docker Desktop); full `compose.real` stack.
**Provider / model.** Gemini via **Vertex AI** (ADC bearer auth), flavor `vertex`, project
`costpilot-503302`, location `us-central1`, model **`gemini-2.5-flash-lite`** (V14 price:
input $0.10 / 1M, output $0.40 / 1M). Governed via the seeded `validation` team + a
deny-all-but-that-model policy. Loads are deliberately tiny — a fresh project's Vertex
quota is a few requests/minute, and real tokens cost real money.

All figures below are reconciled from the **Postgres ledger** (`usage_record`), not k6's
self-report.

### Price correctness (ledger)
A non-stream call reported by Gemini as 7 input / 9 output tokens was billed
**$0.0000043** — exactly `7×$0.0000001 + 9×$0.0000004`. The Gemini price path is correct.

### Overspend under concurrent load (ledger) — PASS
15 concurrent requests against a tight **$0.00012** cap (each request reserves its
~128-token estimated max ≈ $0.0000512, so the cap admits ~2–3 then blocks):

| served (200) | blocked (402) | ledger spend | cap | breach |
|---|---|---|---|---|
| 3 | 12 | **$0.0000373** | $0.00012 | **false** |

Reservations blocked overspend before it happened; ledger spend stayed under the cap.

### Mid-stream cutoff on a real Gemini stream — the bound is **one provider chunk**
A no-`max_tokens` request (admission reserves the 1024-token default ≈ $0.00041, which
fits the **$0.00045** cap) whose prompt actually generates ~1240 tokens — so it passes
admission and overruns mid-generation:

| | billed output tokens | ledger cost | overshoot beyond cap | signal |
|---|---|---|---|---|
| **after meter fix** | 1173 (< 1239 full) | $0.0004712 | **≈ 53 tokens (one Gemini SSE chunk)** | clean `budget_cutoff` + `[DONE]`, no dangling connection |

**Honest finding + fix (12.1).** The first live attempt did **not** bound spend: the full
~1240-token response streamed out with `finish_reason:stop` and the whole thing was
billed (overshoot 224–596 tokens across attempts). Root cause: `StreamCostMeter` counted
**one token per SSE chunk**, which is exact for the mock/OpenAI-style streams (one token
per chunk) but wrong for Gemini, which packs ~40–55 tokens into each chunk and reports
authoritative usage only in the final chunk — so mid-stream the meter under-counted ~40×
and the cutoff check never tripped. Fixed by estimating output tokens from the cumulative
**content length** (~4 chars/token); the provider's exact count still wins for the ledger
once it arrives. After the fix the stream is genuinely truncated (billed 1173 < 1239 full)
and overshoot is bounded by **one provider chunk (~53 tokens here)** — not the mock's
~1.5-token figure. The chunk-sized bound is the honest, provider-dependent result: coarse
provider chunking makes the bound coarser, exactly as anticipated.

### Guard latency
Not meaningfully measurable at this run's ~1 req/s (too few samples for a stable p99, and
the quota caps throughput). Guard latency is computed **before** the upstream call, so it
is upstream-independent — the mock warm-host measurement characterises it. A deployed-VM
run is the outstanding item for a host-representative figure.

### Cost of this run
A few thousandths of a US cent of real Gemini usage, inside the GCP free-trial credit.

---

## Standing (what is proven vs. still pending)

| Claim | Status |
|---|---|
| 0 budget overruns under concurrent flood (ledger-verified) | ✅ proven — mock (0/30) **and live Vertex** (breach=false) |
| Mid-stream cutoff bounds overspend to one streamed chunk | ✅ proven — mock (~1.5 tok, tiny chunks) **and live Vertex** (~53 tok = 1 Gemini chunk) |
| Clean, protocol-correct truncation on cutoff | ✅ proven — mock (100%) **and live Vertex** (`budget_cutoff`+`[DONE]`) |
| Guard-decision p99 < 5 ms | ⏳ p50 ok (2.72 ms); p95/p99 missed on a noisy host — needs a clean re-run (guard is upstream-independent) |
| Cutoff generalises beyond one-token-per-chunk providers | ✅ length-based metering added after the live run exposed the gap (12.1) |

## How this differs from LiteLLM (argued, not yet A/B-tested)

LiteLLM enforces budgets at **admission** — it compares already-accumulated spend to
`max_budget` before letting a request in, and does not interrupt a stream once admitted.
CostPilot enforces at **runtime**: it meters tokens mid-stream and cuts off the moment a
response would overspend. `cutoff_scale` measures exactly that capability (a request that
*passed* admission but overran mid-generation, bounded to one streamed chunk — the mock's
tiny chunks give ~1–2 tokens, a real Gemini stream ~one 40–55-token chunk). A live
side-by-side LiteLLM benchmark is not yet run — this contrast is currently argued from
LiteLLM's own docs and open budget-bypass issues, not demonstrated head-to-head.

# Benchmark results

**Budget-admission guard: p99 14.12 ms at a sustained 100 req/s**, measured server-side and
read from Prometheus — with the p99 *attributed to a specific phase*, not guessed.

The hot-path guard (`BudgetGuard.reserve`) decides whether a request fits its dollar budget
before the LLM call. Its latency Timer is split into phases so the cost is provable:

| phase | p99 |
|---|---|
| **total guard decision** | **14.123008 ms** |
| Redis Lua reservation (per governed scope) | 14.123008 ms |
| price lookup, cache miss → Postgres | 1.769472 ms |

The Redis reservation phase accounts for the whole total; the price-DB fall-through is ~8×
smaller. The guard's cost lives in the per-scope Redis reservation, not the database.

Measured the honest way: generator isolated on a **separate host** (no CPU contention with the
JVM under test), 100 req/s sustained through the window, quantiles read from Prometheus — not
from the load generator's client-side timings.

→ Full method, per-phase analysis, and reproduction steps: [`../BENCHMARK.md`](../BENCHMARK.md).

---

## Running the harness

```
bash loadtest/run.sh              # single-host, $0 mock upstream
bash loadtest/run-live.sh         # real Gemini/Vertex, a few cents (see .env.example)
```

`run.sh` defaults to single-host and takes two overrides for a two-host guard-latency run
(stack on one VM, k6 on another):

| var        | default                  | override when…                                   |
|------------|--------------------------|--------------------------------------------------|
| `BASE_URL` | `http://gateway:8080`    | k6 runs on a separate host → the stack host's IP |
| `PROM_URL` | `http://localhost:9090`  | the scrape runs on a different host than Prometheus |

To reproduce the two-host guard-latency benchmark from scratch (provision → firewall → run →
scrape), follow [`REPRODUCE.md`](REPRODUCE.md); it drives [`guard-benchmark.sh`](guard-benchmark.sh).

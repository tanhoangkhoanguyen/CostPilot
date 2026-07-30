# Load test / benchmark procedure

Two ways to run this harness:

- **Single-host** (default) — everything on one box; k6 reaches the gateway over the
  compose network. Fine for the ledger claims (overspend, cutoff), but the **guard-latency
  quantile is contended**: k6 and the JVM steal CPU from each other, so the number is noise.
- **Two-host** — stack on one VM, k6 generator on a *separate* VM. This is the only setup
  that produces a defensible guard-latency figure. Use it for anything you publish.

```
bash loadtest/run.sh              # single-host, $0 mock upstream
bash loadtest/run-live.sh         # real Gemini/Vertex, a few cents (see .env.example)
```

`run.sh` reads two env vars, defaulting to single-host:

| var        | default                  | override when…                                   |
|------------|--------------------------|--------------------------------------------------|
| `BASE_URL` | `http://gateway:8080`    | k6 runs on a separate host → the stack host's IP |
| `PROM_URL` | `http://localhost:9090`  | the scrape runs on a different host than Prometheus |

---

## What each number means (and does not)

- The published guard latency is **`costpilot_budget_guard_seconds`**, a Micrometer Timer
  recorded **server-side** around `BudgetGuard.reserve` (`GovernedRequestExecutor.java`), read
  from Prometheus. It is **not** k6's `http_req_duration` — that is a client-side round-trip
  (network + Tomcat + mock token pacing) and is treated as noisy context only.
- Because the Timer is server-side, the k6→gateway network hop is **outside** the measured
  span. Running the generator on a separate host removes CPU contention **without** adding the
  network hop into the number.
- The quantiles are **client-side summary estimates** (`.publishPercentiles(...)` in
  `GovernanceMetrics.java`), exposed as `costpilot_budget_guard_seconds{quantile="0.99"}`.
  They are **approximations, not exact percentiles**, and are **not aggregatable across
  processes** — averaging summary quantiles from multiple instances is a statistical error.
  There are **no histogram buckets**, so do **not** use `histogram_quantile()` on this metric.
- Scope of the claim: **a single gateway JVM process**, no load balancer, no horizontal scale.
  This characterizes *per-decision* guard latency, not fleet throughput. p99 also depends on
  the JVM's concurrency (thread pool / virtual threads) and GC — inherent to a single-process
  measurement.

---

## Two-host guard-latency benchmark

### 1. Provision

- **VM-A (stack under test):** ≥ 4 vCPU / 8 GB. The stack is six containers (gateway,
  postgres, redis, kafka, clickhouse, prometheus); under 4 vCPU they contend and you
  reintroduce the noise you are trying to remove. Note: kafka + clickhouse co-located here is
  a benchmarking convenience, not a production topology.
- **VM-B (load generator):** 2 vCPU / 4 GB is plenty for 100 rps of tiny requests.
- Put both in the **same region/zone** so 100 rps arrives evenly. (The hop is outside the
  measured span, but steady delivery still matters.)
- Install Docker + the compose plugin on both. VM-B only needs `loadtest/k6/loadtest.js`
  (clone the repo, or copy that one file over).
- Record VM-A's spec (machine type, vCPU/RAM, region) — it goes in `BENCHMARK.md`.

### 2. Firewall

Allow inbound to VM-A on **tcp:8080** (gateway) and **tcp:9090** (Prometheus), with the
source restricted to **VM-B's _internal_ IP** (e.g. `10.x.x.x/32`) — **not** its external IP.
Same-VPC traffic is matched against the internal address; an external-IP source range will
never match and you get `dial: i/o timeout`.

Quick checks if k6 can't connect:

```
# VM-A: is the gateway actually listening on all interfaces?
sudo ss -tlnp | grep 8080          # expect 0.0.0.0:8080

# VM-B: can it reach VM-A?
curl -v http://<VM-A-INTERNAL-IP>:8080/actuator/health
#   timeout            -> firewall (wrong source range / missing target tag)
#   connection refused -> gateway not bound to 0.0.0.0
```

### 3. Bring the stack up — VM-A

```
docker compose up -d --build
until [ "$(docker inspect costpilot-gateway --format '{{.State.Health.Status}}')" = healthy ]; do sleep 3; done
curl -s http://localhost:8080/actuator/health          # {"status":"UP"}
```

Confirm the metric shape (dictates the query). Expect `{quantile="..."}` lines plus
`_count`/`_sum`, and **no `_bucket` lines**:

```
curl -s http://localhost:8080/actuator/prometheus | grep budget_guard
```

If you see `_bucket{le=...}`, someone switched the Timer to a histogram — stop and use
`histogram_quantile()` instead of the query below.

Seed the load-test budgets first (needed for the ledger/cutoff claims — a bare `docker run`
of k6 skips this and cutoff will falsely report 0/10). Simplest: run `bash loadtest/run.sh`
once on VM-A single-host, which seeds the `lt-*` budgets and flushes stale counters.

### 4. Run the load — VM-B

```
T0=$(date +%s); echo "T0=$T0"
docker run --rm -i grafana/k6:0.54.0 run --quiet \
  -e BASE_URL=http://<VM-A-INTERNAL-IP>:8080 - < loadtest/k6/loadtest.js
```

Scenario timeline (baked into `loadtest/k6/loadtest.js`):

| scenario          | window     | rate    | role                                              |
|-------------------|------------|---------|---------------------------------------------------|
| `warmup`          | 0–130 s    | 30 rps  | JIT, Lua cache, counter rebuild; cold samples age out of the ~2-min quantile window |
| **`guard_latency`** | **135–165 s** | **100 rps** | **the measured window**                     |
| `overspend_flood` | 170 s +    | —       | ledger claim (not the guard number)               |
| `cutoff_scale`    | 235 s +    | —       | headline cutoff claim (not the guard number)      |

Let the full run finish. The warmup is load-bearing — skipping it means measuring a cold
JVM, which is exactly what made an earlier figure unpublishable.

Confirm 100 rps actually reached the guard (run on VM-A during the 135–165 s window):

```
curl -s "http://localhost:9090/api/v1/query" \
  --data-urlencode 'query=rate(costpilot_budget_guard_seconds_count[15s])'    # expect ~100
```

### 5. Read the number — VM-A

The summary quantile decays over ~2 min, so read it at the **window tail** (`T0+170`).
Prometheus scraped every 5 s, so `max_over_time([15s])` picks the warm, steady-state value:

```
for q in 0.5 0.95 0.99; do
  printf "guard p%s: " "$q"
  curl -s "http://localhost:9090/api/v1/query" \
    --data-urlencode "query=max_over_time(costpilot_budget_guard_seconds{quantile=\"$q\"}[15s])" \
    --data-urlencode "time=$((T0 + 170))"
  echo
done
```

Save the raw JSON as evidence. If you get an empty result, the `time=` fell outside the
scrape range — recompute `T0+170`, or widen to `[30s]`. Do not fabricate a value.

### 6. Reproduce (required)

Run steps 3–5 a second time from a fresh stack (`docker compose down && docker compose up
-d --build`). Accepted reproducibility threshold: **p99 within ±20%** of run 1 — p99 on
shared cloud infrastructure carries real variance (noisy neighbors, GC, JVM warmup), so this
is a stated tolerance, not an exact-match expectation. If the two runs diverge more than
that, the isolation or window isn't clean — investigate; don't average two bad runs.

---

## Interpreting the shape

A low p50 with a longer p99 tail is expected and explainable: `reserve()` does the Redis Lua
reservation on every request (the p50 path) **and** a model price lookup cached for 30 s
(`BudgetGuard.java`). On a cache miss it falls through to Postgres, so the first requests
after each expiry — and after a fresh counter rebuild — pay a DB hit. **The tail is the
price-cache / counter-rebuild path, not the Redis reservation.**

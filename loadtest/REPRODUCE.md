# Reproducing the guard-latency benchmark (start → end)

This walks from nothing to a published p99: provision two VMs, open the firewall, run the load,
and read the number from Prometheus. It spans **two machines** on purpose — the load generator
must not share CPU with the gateway JVM, or the latency it records is contention noise.

- **VM-A** — the gateway stack under test.
- **VM-B** — the k6 load generator only.

The mechanical steps are wrapped in [`guard-benchmark.sh`](guard-benchmark.sh) (one subcommand
per host); this guide explains the setup around it and what each step is for. Commands assume a
GCP-style layout, but nothing here is cloud-specific beyond the firewall UI.

---

## 1. Provision the two VMs

| VM | role | size | why |
|----|------|------|-----|
| **VM-A** | gateway stack | **≥ 4 vCPU**, Ubuntu 22.04 | six containers (gateway, postgres, redis, kafka, clickhouse, prometheus); fewer cores and they contend, reintroducing the noise you're removing |
| **VM-B** | k6 generator | same as VM-A | driving 100 rps of tiny requests is light, so VM-B is over-provisioned here — matching VM-A's spec just keeps it simple and rules out the generator as a bottleneck |

> The published figures in `BENCHMARK.md` were measured with **both VMs = GCP `e2-standard-4`
> (4 vCPU / 16 GB), `us-central1-a`, Ubuntu 22.04** (identical spec, different name). Record your
> own VM-A spec next to any number you publish — the guard latency is a single-process
> measurement and the host matters.

- Put both in the **same region/zone** so 100 rps arrives evenly.
- Install Docker + the compose plugin on **both**:
  ```bash
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER" && newgrp docker
  docker compose version        # confirm v2+
  ```
- Clone the repo on **VM-A**. On **VM-B** you only need `loadtest/k6/loadtest.js` (clone, or copy
  that one file over).
- Note both **internal** IPs (e.g. `10.128.0.12` = VM-A, `10.128.0.13` = VM-B) and VM-A's exact
  spec (machine type, vCPU/RAM, region) — the spec is worth recording alongside the result.

---

## 2. Open the firewall (VM-A ← VM-B)

Allow inbound to **VM-A** on **tcp:8080** (gateway) and **tcp:9090** (Prometheus), with the
source restricted to **VM-B's _internal_ IP** as a `/32`.

> **The one gotcha that wastes an afternoon:** use VM-B's **internal** IP, not its external one.
> Same-VPC traffic is matched against the internal address; an external-IP source range never
> matches and every request fails with `dial: i/o timeout`.

GCP example (adjust for your provider):

```
Direction:   Ingress
Action:      Allow
Targets:     target tag  ->  costpilot-gateway     (tag must be on VM-A)
Source IPv4: 10.128.0.13/32                          (VM-B INTERNAL ip)
Protocols:   tcp:8080, tcp:9090
```

Verify connectivity before going further:

```bash
# VM-A: is the gateway listening on all interfaces?
sudo ss -tlnp | grep 8080                       # expect 0.0.0.0:8080

# VM-B: can it reach VM-A?
curl -v http://<VM-A-INTERNAL-IP>:8080/actuator/health
#   {"status":"UP"}    -> good
#   timeout            -> firewall: wrong source range, or target tag missing on VM-A
#   connection refused -> gateway not bound to 0.0.0.0 (see below)
```

If you get `connection refused`, the stack isn't up yet — do step 3 first, then re-test.

---

## 3. Bring the stack up + seed budgets — on VM-A

```bash
bash loadtest/guard-benchmark.sh stack
```

This brings the compose stack up, waits until the gateway is healthy, seeds the `lt-*`
load-test budgets (via `run.sh`), and confirms the three guard Timers are exported.

Doing it by hand instead:

```bash
docker compose up -d --build
until [ "$(docker inspect costpilot-gateway --format '{{.State.Health.Status}}')" = healthy ]; do sleep 3; done
curl -s http://localhost:8080/actuator/health          # {"status":"UP"}
bash loadtest/run.sh                                    # seeds lt-* budgets, flushes counters
```

> **Seed the budgets — don't skip `run.sh`.** A bare `docker run` of k6 skips seeding, so the
> `lt-cutoff-*` teams have no cap and the cutoff scenario falsely reports `0/10`. The
> single-host guard number `run.sh` prints here is *contended* — ignore it; this step exists to
> seed state, not to measure.

Confirm the metric shape (it dictates the query):

```bash
curl -s http://localhost:8080/actuator/prometheus | grep budget_guard
```

Expect `{quantile="..."}` lines plus `_count`/`_sum`, and **no `_bucket` lines**. If you see
`_bucket{le=...}`, someone switched the Timer to a histogram — then use `histogram_quantile()`
instead of the direct `{quantile}` query in step 5.

---

## 4. Drive the load — on VM-B

```bash
bash loadtest/guard-benchmark.sh load <VM-A-INTERNAL-IP>
```

It records **T0** (the start epoch — you need it in step 5), then drives the k6 timeline against
VM-A. Doing it by hand:

```bash
T0=$(date +%s); echo "T0=$T0"
docker run --rm -i grafana/k6:0.54.0 run --quiet \
  -e BASE_URL=http://<VM-A-INTERNAL-IP>:8080 - < loadtest/k6/loadtest.js
```

The timeline is baked into `loadtest/k6/loadtest.js`:

| scenario          | window        | rate    | role                                   |
|-------------------|---------------|---------|----------------------------------------|
| `warmup`          | 0–130 s       | 30 rps  | JIT, Lua cache, counter rebuild; cold samples age out of the ~2-min quantile window |
| **`guard_latency`** | **135–165 s** | **100 rps** | **the measured window**            |
| `overspend_flood` | 170 s +       | —       | ledger claim (not the guard number)    |
| `cutoff_scale`    | 235 s +       | —       | cutoff claim (not the guard number)    |

Let the full run finish (~5 min). The warm-up is load-bearing — skipping it measures a cold JVM.

> **`cutoff: clean budget_cutoff signal 0/10` is a false negative here.** Over the two-host
> network, k6's SSE-body detection is unreliable. The ledger is authoritative: every
> `lt-cutoff-*` team is billed to `cap + ~1.3 output tokens` (one-chunk overshoot), i.e. the
> stream *was* cut off. Verify from Postgres, not from k6's check line.

Optional sanity check while the window is live (on VM-A, ~T0+150):

```bash
curl -s http://localhost:9090/api/v1/query \
  --data-urlencode 'query=rate(costpilot_budget_guard_seconds_count[15s])'    # expect ~100
```

---

## 5. Read the number — on VM-A

```bash
bash loadtest/guard-benchmark.sh scrape <T0>
```

Using the `T0` printed in step 4, this reads p50/p95/p99 for each phase Timer at the window tail
(**T0+170 s**) and the rps sanity check. Doing it by hand:

```bash
AT=$((T0 + 170))
for mq in \
  'costpilot_budget_guard_seconds{quantile="0.99"}' \
  'costpilot_budget_guard_reserve_seconds{quantile="0.99"}' \
  'costpilot_budget_guard_price_lookup_seconds{outcome="miss",quantile="0.99"}'; do
  printf '%s = ' "$mq"
  curl -s http://localhost:9090/api/v1/query \
    --data-urlencode "query=max_over_time(${mq}[15s])" \
    --data-urlencode "time=$AT" | sed -E 's/.*,"([^"]+)".*/\1 s/'
done
```

> **Read at T0+170, not later.** These are client-side **summary** quantiles that decay ~2 min
> after the window. `max_over_time([15s])` at `time=T0+170` picks the warm, steady-state value;
> query the raw metric too late and it reads `0`. If you get `0` or empty, re-check `T0` — don't
> fabricate a value; re-run instead.

---

## 6. Reproduce (required before publishing)

Run steps 3–5 a second time from a fresh stack:

```bash
docker compose down && docker compose up -d --build      # on VM-A
# then step 3 (seed) → step 4 (VM-B, new T0) → step 5 (scrape)
```

Accepted tolerance: **total p99 within ±20%** across runs — client-side summary quantiles on
shared cloud infra carry real variance (noisy neighbors, GC, JVM warmup). If two runs diverge
more than that, the isolation or window isn't clean; investigate rather than averaging two bad
runs.

The measured figures and what they show are in [`../BENCHMARK.md`](../BENCHMARK.md).

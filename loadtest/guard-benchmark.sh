#!/usr/bin/env bash
# Mechanical wrapper for the two-host guard-latency benchmark.
# Full narrative: loadtest/REPRODUCE.md  ·  published numbers: docs/BENCHMARK.md
#
#   # VM-A
#   bash loadtest/guard-benchmark.sh stack
#   # VM-B
#   bash loadtest/guard-benchmark.sh load <VM-A-INTERNAL-IP>
#   # VM-A (use T0 printed by load)
#   bash loadtest/guard-benchmark.sh scrape <T0>
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
	cat <<'EOF'
Usage:
  bash loadtest/guard-benchmark.sh stack
  bash loadtest/guard-benchmark.sh load <gateway-host>
  bash loadtest/guard-benchmark.sh scrape <T0-epoch-seconds>
EOF
	exit 1
}

cmd="${1:-}"
case "$cmd" in
	stack)
		echo "== bringing the stack up"
		docker compose up -d --build
		for i in $(seq 1 60); do
			s=$(docker inspect costpilot-gateway --format '{{.State.Health.Status}}' 2>/dev/null || echo starting)
			[ "$s" = "healthy" ] && break
			sleep 5
		done
		[ "$s" = "healthy" ] || { echo "gateway never became healthy"; exit 1; }
		curl -sf http://localhost:8080/actuator/health >/dev/null
		echo "== seeding lt-* budgets via run.sh (ignore contended single-host guard print)"
		bash loadtest/run.sh || true
		echo "== confirming guard Timers are exported"
		curl -sf http://localhost:8080/actuator/prometheus | grep -q 'costpilot_budget_guard_seconds' \
			|| { echo "budget_guard metrics missing from /actuator/prometheus"; exit 1; }
		echo "OK: stack up + seeded. Drive load from VM-B next."
		;;
	load)
		host="${2:-}"
		[ -n "$host" ] || usage
		T0=$(date +%s)
		echo "T0=$T0"
		echo "== driving k6 against http://${host}:8080 (warmup -> guard_latency -> flood -> cutoff)"
		docker run --rm -i grafana/k6:0.54.0 run --quiet \
			-e "BASE_URL=http://${host}:8080" - < loadtest/k6/loadtest.js \
			|| echo "(k6 exit non-zero — ledger remains authoritative for cutoff; scrape with T0=$T0)"
		echo "T0=$T0"
		echo "Next on VM-A: bash loadtest/guard-benchmark.sh scrape $T0"
		;;
	scrape)
		T0="${2:-}"
		[[ "$T0" =~ ^[0-9]+$ ]] || usage
		PROM_URL="${PROM_URL:-http://localhost:9090}"
		AT=$((T0 + 170))
		echo "== scraping Prometheus at time=$AT (T0+170)"
		echo "-- sustained rps sanity (expect ~100 during the guard window) --"
		curl -s "${PROM_URL}/api/v1/query" \
			--data-urlencode 'query=rate(costpilot_budget_guard_seconds_count[15s])' \
			--data-urlencode "time=$AT" \
			| sed -E 's/.*"value":\[[0-9.]+,"([^"]+)".*/rate=\1/'
		echo "-- phase p50 / p95 / p99 --"
		scrape_q() {
			local query="$1" label="$2"
			local v
			v=$(curl -s "${PROM_URL}/api/v1/query" \
				--data-urlencode "query=${query}" \
				--data-urlencode "time=$AT" \
				| sed -E 's/.*"value":\[[0-9.]+,"([^"]+)".*/\1/')
			echo "${label}=${v}s"
		}
		for q in 0.5 0.95 0.99; do
			scrape_q "max_over_time(costpilot_budget_guard_seconds{quantile=\"${q}\"}[15s])" \
				"total p${q#0.}"
			scrape_q "max_over_time(costpilot_budget_guard_reserve_seconds{quantile=\"${q}\"}[15s])" \
				"reserve p${q#0.}"
			scrape_q "max_over_time(costpilot_budget_guard_price_lookup_seconds{outcome=\"miss\",quantile=\"${q}\"}[15s])" \
				"price_miss p${q#0.}"
		done
		;;
	*)
		usage
		;;
esac

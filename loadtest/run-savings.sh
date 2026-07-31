#!/usr/bin/env bash
# 1.3 (#93): reproducible $0-mock cost-savings benchmark.
#   bash loadtest/run-savings.sh
# Enables the semantic cache, drives downgrade-eligible + cache-hit traffic, then
# reconciles routing (usage_record.savings_nanos) + cache (cache_hit_log) against
# GET /api/analytics/savings and prints $ saved / % saved for docs/BENCHMARK.md.
set -euo pipefail
cd "$(dirname "$0")/.."

PSQL="docker compose exec -T postgres psql -U costpilot -d costpilot"
K6_IMAGE="grafana/k6:0.54.0"

echo "== bringing the stack up (cache ON for savings scenario)"
COSTPILOT_CACHE_ENABLED=true docker compose up -d --build --force-recreate gateway
for i in $(seq 1 60); do
	s=$(docker inspect costpilot-gateway --format '{{.State.Health.Status}}' 2>/dev/null || echo starting)
	[ "$s" = "healthy" ] && break
	sleep 5
done
[ "$s" = "healthy" ] || { echo "gateway never became healthy"; exit 1; }

# confirm Flyway applied V15 (cache_hit_log) — recreate may race health before migrate finishes
for i in $(seq 1 30); do
	if $PSQL -Atc "select to_regclass('public.cache_hit_log')" | grep -q cache_hit_log; then
		break
	fi
	sleep 2
done
$PSQL -Atc "select to_regclass('public.cache_hit_log')" | grep -q cache_hit_log \
	|| { echo "cache_hit_log missing — Flyway V15 did not apply"; exit 1; }

echo "== resetting savings load-test state (lt-savings-* only)"
$PSQL -q <<'SQL'
delete from usage_record where team_id like 'lt-savings-%';
delete from cache_hit_log where team_id like 'lt-savings-%';
delete from prompt_cache where team_id like 'lt-savings-%';
delete from budget where scope_ref like 'lt-savings-%';
insert into budget (scope_type, scope_ref, limit_amount)
select 'team', 'lt-savings-route-' || n, 1000 from generate_series(0, 4) n union all
select 'team', 'lt-savings-cache-' || n, 1000 from generate_series(0, 4) n;
SQL
docker compose exec -T redis redis-cli flushdb > /dev/null

echo "== running k6 savings scenarios (routing + cache)"
K6_EXIT=0
docker run --rm -i --quiet --network costpilot_default -e BASE_URL=http://gateway:8080 \
	"$K6_IMAGE" run --quiet - < loadtest/k6/savings.js || K6_EXIT=$?

echo
echo "== ledger + cache_hit_log reconciliation (money truth)"
$PSQL <<'SQL'
\x off
select
  coalesce((select sum(savings_nanos) from usage_record where team_id like 'lt-savings-%'), 0) as routing_nanos,
  coalesce((select sum(savings_nanos) from cache_hit_log where team_id like 'lt-savings-%'), 0) as cache_nanos,
  coalesce((select sum(savings_nanos) from usage_record where team_id like 'lt-savings-%'), 0)
    + coalesce((select sum(savings_nanos) from cache_hit_log where team_id like 'lt-savings-%'), 0) as total_savings_nanos,
  coalesce((select sum(cost) from usage_record where team_id like 'lt-savings-%'), 0) as actual_spend_usd;
SQL

echo
echo "== /api/analytics/savings (unified $ and %)"
FROM=$(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-1H +%Y-%m-%dT%H:%M:%SZ)
TO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
API_JSON=$(curl -s -H "Authorization: Bearer cp_admin_root" \
	"http://localhost:8080/api/analytics/savings?from=${FROM}&to=${TO}")
echo "$API_JSON"

LEDGER=$($PSQL -At -F ',' -c "
select
  coalesce((select sum(savings_nanos) from usage_record where team_id like 'lt-savings-%'),0),
  coalesce((select sum(savings_nanos) from cache_hit_log where team_id like 'lt-savings-%'),0)
")
ROUTING_N=${LEDGER%%,*}
CACHE_N=${LEDGER##*,}
TOTAL_N=$((ROUTING_N + CACHE_N))

# nanodollars -> 9-decimal USD string (matches AnalyticsQueryService.usd)
usd9() {
	awk -v n="$1" 'BEGIN { printf "%.9f", n / 1e9 }'
}
API_ROUTE=$(echo "$API_JSON" | sed -n 's/.*"routingSavingsUsd":"\([^"]*\)".*/\1/p')
API_CACHE=$(echo "$API_JSON" | sed -n 's/.*"cacheSavingsUsd":"\([^"]*\)".*/\1/p')
API_TOTAL=$(echo "$API_JSON" | sed -n 's/.*"totalSavingsUsd":"\([^"]*\)".*/\1/p')
API_WOULD=$(echo "$API_JSON" | sed -n 's/.*"wouldBeSpendUsd":"\([^"]*\)".*/\1/p')
API_PCT=$(echo "$API_JSON" | sed -n 's/.*"percentSaved":\([0-9.]*\).*/\1/p')

EXP_ROUTE=$(usd9 "$ROUTING_N")
EXP_CACHE=$(usd9 "$CACHE_N")
EXP_TOTAL=$(usd9 "$TOTAL_N")

[ "$ROUTING_N" -gt 0 ] || { echo "FAIL: expected positive routing savings"; exit 1; }
[ "$CACHE_N" -gt 0 ] || { echo "FAIL: expected positive cache savings"; exit 1; }
[ "$API_ROUTE" = "$EXP_ROUTE" ] || { echo "FAIL: routing API=$API_ROUTE ledger=$EXP_ROUTE"; exit 1; }
[ "$API_CACHE" = "$EXP_CACHE" ] || { echo "FAIL: cache API=$API_CACHE ledger=$EXP_CACHE"; exit 1; }
[ "$API_TOTAL" = "$EXP_TOTAL" ] || { echo "FAIL: total API=$API_TOTAL ledger=$EXP_TOTAL"; exit 1; }

echo "OK reconcile: routing=\$$API_ROUTE cache=\$$API_CACHE total=\$$API_TOTAL (${API_PCT}% saved)"
echo "BENCHMARK: \$$API_TOTAL saved (${API_PCT}% of would-be \$$API_WOULD)"

exit $K6_EXIT

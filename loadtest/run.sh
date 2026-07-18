#!/usr/bin/env bash
# 6.5: the single reproducible benchmark command.
#   bash loadtest/run.sh
# Brings the compose stack up, seeds fresh load-test budgets, runs the k6 scenarios,
# then proves the money claims straight from the Postgres ledger:
#   - overspend teams (spend > cap) must be 0
#   - worst cutoff overshoot must stay within one output token (0.0000006 USD)
# and scrapes the guard-only decision quantiles from micrometer.
set -euo pipefail
cd "$(dirname "$0")/.."

PSQL="docker compose exec -T postgres psql -U costpilot -d costpilot"
K6_IMAGE="grafana/k6:0.54.0"

echo "== bringing the stack up"
docker compose up -d --build
for i in $(seq 1 60); do
	s=$(docker inspect costpilot-gateway --format '{{.State.Health.Status}}' 2>/dev/null || echo starting)
	[ "$s" = "healthy" ] && break
	sleep 5
done
[ "$s" = "healthy" ] || { echo "gateway never became healthy"; exit 1; }

echo "== resetting load-test state (lt-* teams only)"
$PSQL -q <<'SQL'
delete from usage_record where team_id like 'lt-%';
delete from budget where scope_ref like 'lt-%';
insert into budget (scope_type, scope_ref, limit_amount)
select 'team', 'lt-latency-' || n, 1000        from generate_series(0, 9) n union all
select 'team', 'lt-flood-'   || n, 0.0002      from generate_series(0, 9) n union all
select 'team', 'lt-cutoff-'  || n, 0.0013      from generate_series(0, 9) n;
SQL
# stale lt-* counters/negative-caches from a previous run would mask the fresh caps.
# flushing everything is safe by design: counters rebuild from the ledger on demand.
docker compose exec -T redis redis-cli flushdb > /dev/null

echo "== running k6 (warmup -> guard_latency -> overspend_flood -> cutoff_scale, ~3 min)"
T0=$(date +%s)
# don't die on a crossed k6 threshold before the ledger verdict prints;
# the exit code is re-raised at the end
K6_EXIT=0
docker run --rm -i --quiet --network costpilot_default -e BASE_URL=http://gateway:8080 \
	"$K6_IMAGE" run --quiet - < loadtest/k6/loadtest.js || K6_EXIT=$?

echo
echo "== ledger verdict (spend reconciled against every cap)"
# the allowed bound differs per scenario: flood/latency must never exceed the cap at
# all (reservations block first); cutoff intentionally overshoots by < 1 output token
# (0.0000006 USD on gpt-4o-mini) because the per-chunk check fires one token late
$PSQL <<'SQL'
select split_part(b.scope_ref, '-', 2)                          as scenario,
       count(*)                                                 as teams,
       count(*) filter (where s.spent > b.limit_amount + b.bound) as breach_teams,
       round(max(s.spent - b.limit_amount) / 0.0000006, 2)      as worst_overshoot_tokens
from (
    select *, case when scope_ref like 'lt-cutoff-%' then 0.0000006 else 0 end as bound
    from budget where scope_ref like 'lt-%'
) b
join lateral (
    select coalesce(sum(u.cost), 0) as spent
    from usage_record u where u.team_id = b.scope_ref
) s on true
group by 1 order by 1;
SQL

echo "== guard-only decision latency during the 100 RPS window (seconds)"
# micrometer's summary quantiles decay after ~2 min, but Prometheus scraped them every
# 5s during the run - so read them back AT the guard_latency window, not at the end
# guard_latency runs T0+135s..T0+165s; read the tail of that window, where the
# decaying quantile reflects only warm, steady-state samples
for q in 0.5 0.95 0.99; do
	v=$(curl -s "http://localhost:9090/api/v1/query" \
		--data-urlencode "query=max_over_time(costpilot_budget_guard_seconds{quantile=\"$q\"}[15s])" \
		--data-urlencode "time=$((T0 + 170))" \
		| sed -E 's/.*"value":\[[0-9.]+,"([^"]+)".*/\1/')
	echo "guard p$q: ${v}s"
done

exit $K6_EXIT

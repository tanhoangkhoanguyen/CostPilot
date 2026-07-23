#!/usr/bin/env bash
# Stage 12: the LIVE-provider benchmark command (real Gemini/Vertex, real dollars).
#   bash loadtest/run-live.sh
# The live analogue of run.sh. Brings up the REAL stack (compose.real overlay), bootstraps
# an admin key that works under the deploy's overridden pepper, then runs the same three
# governance claims against a real Gemini stream and proves them from the Postgres ledger
# and Prometheus:
#   - overspend flood: ledger spend must never exceed the cap (reservations block first)
#   - mid-stream cutoff: worst overshoot within a documented token bound + clean signal
#   - guard latency:   costpilot_budget_guard_seconds p50/p95/p99 (deployment, not Gemini)
#
# Prereqs (see .env.example): secrets/adc.json present (SA role Vertex AI User, chmod 644),
# and .env filled with COSTPILOT_UPSTREAM_GEMINI_PROJECT / _LOCATION and a fresh
# COSTPILOT_API_KEY_PEPPER. This run costs real (tiny) money — verified at the end.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose.yml -f docker-compose.real.yml"
PSQL="$COMPOSE exec -T postgres psql -U costpilot -d costpilot"
K6_IMAGE="grafana/k6:0.54.0"

# gemini-2.5-flash-lite output price is $0.40 / 1M tokens = $0.0000004 per output token
# (V14). The mock run.sh divides overshoot by gpt-4o-mini's 0.0000006 — the live run MUST
# use the Gemini figure or worst_overshoot_tokens is wrong.
OUT_TOK_USD=0.0000004
# per-phase tight caps (USD), sized against gemini-2.5-flash-lite's real behaviour here:
#   flood  — $0.00012 admits ~2-3 of the concurrent requests (each reserves ~128 out
#            tokens = ~$0.0000512) then blocks the rest with 402; proves spend <= cap.
#   cutoff — the no-max_tokens request reserves the 1024-token DEFAULT estimate
#            (~$0.00041); this prompt actually generates ~1240 tokens. A cap of $0.00045
#            sits ABOVE the estimate (so admission passes) but BELOW the full generation
#            (so it cuts off mid-stream). Too-low a cap (e.g. 0.00015) is denied at
#            admission and never streams - there'd be nothing to cut off.
FLOOD_CAP=0.00012
CUTOFF_CAP=0.00045
GUARD_CAP=1000

# .env carries the pepper (needed to reproduce the key hash) + provider config; compose
# already auto-loads it for the containers, we source it here for the psql hmac() call.
[ -f .env ] || { echo ".env missing — cp .env.example .env and fill it in"; exit 1; }
set -a; . ./.env; set +a
: "${COSTPILOT_API_KEY_PEPPER:?set COSTPILOT_API_KEY_PEPPER in .env}"

echo "== bringing the REAL stack up (Gemini/Vertex upstream)"
$COMPOSE up -d --build
for i in $(seq 1 60); do
	s=$(docker inspect costpilot-gateway --format '{{.State.Health.Status}}' 2>/dev/null || echo starting)
	[ "$s" = "healthy" ] && break
	sleep 5
done
[ "$s" = "healthy" ] || { echo "gateway never became healthy"; exit 1; }

# --- bootstrap an admin key that resolves under the deploy's real pepper ------------------
# POST /admin/keys needs ROLE_ADMIN, but the real deploy's overridden pepper invalidates the
# seeded cp_admin_root hash — there is no HTTP path to the first key. So insert one directly,
# computing the exact hash the gateway expects: hex HMAC-SHA256(rawkey, pepper) — identical
# to ApiKeyHasher. pgcrypto's hmac() gives the same bytes; encode(...,'hex') the same string.
ADMIN_KEY="cp_live_bootstrap_$(date +%s)"
echo "== bootstrapping live admin key directly in Postgres"
$PSQL -v ON_ERROR_STOP=1 -q <<SQL
create extension if not exists pgcrypto;
delete from api_key where name = 'live-admin';
insert into api_key (team_id, project_id, key_hash, name, is_admin)
values ('00000000-0000-0000-0000-000000000013', null,
        encode(hmac('${ADMIN_KEY}', '${COSTPILOT_API_KEY_PEPPER}', 'sha256'), 'hex'),
        'live-admin', true);
SQL

# reset any budget/counter/ledger state for the validation team from a previous run
reset_state() {
	local cap="$1"
	$PSQL -v ON_ERROR_STOP=1 -q <<SQL
delete from usage_record where team_id = 'validation';
delete from budget where scope_type = 'team' and scope_ref = 'validation';
insert into budget (scope_type, scope_ref, limit_amount) values ('team', 'validation', ${cap});
SQL
	# stale counters/negative-caches would mask the fresh cap; safe to flush — Redis budget
	# counters rebuild from (limit - ledger spend) on demand.
	$COMPOSE exec -T redis redis-cli flushdb > /dev/null
}

run_phase() {  # <phase> <cap>
	local phase="$1" cap="$2"
	echo "== phase '${phase}' (cap \$${cap})"
	reset_state "$cap"
	docker run --rm -i --quiet --network costpilot_default \
		-e BASE_URL=http://gateway:8080 -e "ADMIN_KEY=${ADMIN_KEY}" -e "PHASE=${phase}" \
		"$K6_IMAGE" run --quiet - < loadtest/k6/live.js || echo "  (k6 threshold crossed — ledger verdict below is authoritative)"
}

verdict() {  # <label> <cap> — reconcile this phase's ledger spend against its own cap
	local label="$1" cap="$2"
	echo "-- ledger verdict: ${label}"
	$PSQL <<SQL
select 'validation'                                         as team,
       count(*)                                             as ledger_rows,
       coalesce(sum(cost), 0)                               as spent_usd,
       ${cap}                                               as cap_usd,
       (coalesce(sum(cost), 0) > ${cap})                    as breach,
       round((coalesce(sum(cost), 0) - ${cap}) / ${OUT_TOK_USD}, 2) as overshoot_tokens
from usage_record where team_id = 'validation';
SQL
}

# --- guard latency (deployment hot path) --------------------------------------------------
# guard_latency runs at warmup(40s)+guard(30s) = the measured window is T0+45s..+75s.
# micrometer quantiles decay after ~2min but Prometheus scrapes every 5s, so read them
# back AT the window tail (same method as run.sh). Widen to [30s] since the low request
# rate (1 rps, quota-limited) puts fewer samples in each 5s scrape.
GUARD_T0=$(date +%s)
run_phase guard "$GUARD_CAP"
echo "== guard-only decision latency during the measured window (seconds)"
for q in 0.5 0.95 0.99; do
	v=$(curl -s "http://localhost:9090/api/v1/query" \
		--data-urlencode "query=max_over_time(costpilot_budget_guard_seconds{quantile=\"$q\"}[30s])" \
		--data-urlencode "time=$((GUARD_T0 + 78))" \
		| sed -E 's/.*"value":\[[0-9.]+,"([^"]+)".*/\1/')
	echo "guard p$q: ${v}s"
done

# --- overspend flood ----------------------------------------------------------------------
run_phase flood "$FLOOD_CAP"
verdict "overspend flood (breach must be false)" "$FLOOD_CAP"

# --- mid-stream cutoff --------------------------------------------------------------------
run_phase cutoff "$CUTOFF_CAP"
verdict "mid-stream cutoff (overshoot within a documented bound)" "$CUTOFF_CAP"

echo
# NOTE: each phase resets usage_record for a clean per-cap verdict, so this shows only the
# LAST phase's ledger rows — not the whole run. The per-phase spent_usd figures above are
# each tiny; the real $ bill is their sum plus the guard phase's ~1500 tiny completions.
echo "== last-phase ledger rows still present (per-phase spend is reset for clean verdicts)"
$PSQL -c "select count(*) as calls, coalesce(sum(cost),0) as total_usd from usage_record where team_id = 'validation';"

// 6.5: the load-test harness. Three scenarios, run in sequence so they don't
// pollute each other's numbers:
//   guard_latency   - sustained 100 req/s across 10 governed teams; p99 threshold
//   overspend_flood - 300 requests hammer 10 teams with tiny caps; the post-run SQL
//                     in run.sh proves total spend never exceeded any cap
//   cutoff_scale    - 10 concurrent long streams against cutoff-sized caps; run.sh
//                     proves every team's overshoot stays within one token
// Requests go through the admin key + X-Team-ID (same impersonation the ITs use),
// upstream is the embedded mock - the whole run costs $0 and touches no provider.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://gateway:8080';
const ADMIN = 'cp_admin_root';
const TEAMS = 10;

export const options = {
	summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
	scenarios: {
		// long soak: JIT + counter rebuilds + caches all happen here, AND the cold-start
		// samples age out of micrometer's ~2-min decaying quantile window before the
		// measured scenario starts - so guard_latency reports true steady state
		warmup: {
			executor: 'constant-arrival-rate',
			rate: 30, timeUnit: '1s', duration: '130s',
			preAllocatedVUs: 30, maxVUs: 60,
			exec: 'latency',
		},
		guard_latency: {
			executor: 'constant-arrival-rate',
			rate: 100, timeUnit: '1s', duration: '30s',
			startTime: '135s',
			preAllocatedVUs: 60, maxVUs: 200,
			exec: 'latency',
		},
		overspend_flood: {
			executor: 'shared-iterations',
			vus: 30, iterations: 300,
			startTime: '170s', maxDuration: '60s',
			exec: 'flood',
		},
		cutoff_scale: {
			executor: 'per-vu-iterations',
			vus: 10, iterations: 1,
			startTime: '235s', maxDuration: '120s',
			exec: 'cutoff',
		},
	},
	thresholds: {
		// e2e request time is reported for context but only sanity-bounded here: it
		// swings wildly on shared dev hardware. The DOCUMENTED latency target is the
		// guard-only decision p99 < 5ms, which run.sh reads from micrometer/prometheus.
		'http_req_duration{scenario:guard_latency}': ['p(99)<3000'],
		'http_req_duration{scenario:overspend_flood}': ['p(99)<3000'],
		'checks': ['rate>0.99'],
	},
};

const served = new Counter('flood_served');
const blocked = new Counter('flood_blocked_402');

function post(team, body, params = {}) {
	return http.post(`${BASE}/v1/chat/completions`, JSON.stringify(body), {
		headers: {
			'Content-Type': 'application/json',
			'Authorization': `Bearer ${ADMIN}`,
			'X-Team-ID': team,
		},
		...params,
	});
}

export function latency() {
	const team = `lt-latency-${exec.scenario.iterationInTest % TEAMS}`;
	const r = post(team, {
		model: 'gpt-4o-mini',
		messages: [{ role: 'user', content: 'ping' }],
		max_tokens: 16,
	});
	check(r, { 'latency: 200': (x) => x.status === 200 });
}

export function flood() {
	const team = `lt-flood-${exec.scenario.iterationInTest % TEAMS}`;
	const r = post(team, {
		model: 'gpt-4o-mini',
		messages: [{ role: 'user', content: 'hello costpilot' }],
		max_tokens: 128,
	});
	if (r.status === 200) served.add(1);
	if (r.status === 402) blocked.add(1);
	// under a flood both outcomes are legitimate; anything else is a failure
	check(r, { 'flood: 200 or 402': (x) => x.status === 200 || x.status === 402 });
}

export function cutoff() {
	// scenario-scoped iteration counter: 0..9 exactly once each. NOT vu.idInTest -
	// k6 reuses VUs across scenarios, so those ids collide mod 10 and two streams
	// would hit the same team (the second one gets an unplanned 402)
	const team = `lt-cutoff-${exec.scenario.iterationInTest % TEAMS}`;
	// ~2000-token echo vs a cap sized for ~1700: forces a mid-stream cutoff
	const prompt = 'lorem '.repeat(2000).trim();
	const r = post(team, {
		model: 'gpt-4o-mini',
		messages: [{ role: 'user', content: prompt }],
		stream: true,
	}, { timeout: '110s' });
	check(r, {
		'cutoff: 200': (x) => x.status === 200,
		'cutoff: clean budget_cutoff signal': (x) =>
			typeof x.body === 'string' && x.body.includes('"finish_reason":"budget_cutoff"'),
		'cutoff: terminated with [DONE]': (x) =>
			typeof x.body === 'string' && x.body.trim().endsWith('[DONE]'),
	});
}

// Stage 12: the LIVE-provider load harness — the same three governance claims as
// loadtest.js, but every request goes to a real Gemini (Vertex) stream instead of the
// embedded $0 mock. Run via loadtest/run-live.sh (never the mock run.sh).
//
// Differences from the mock harness (loadtest.js), all forced by "this costs real money
// and talks to a real provider":
//   - Model is gemini-2.5-flash-lite (the cheapest current Vertex model; seeded in V14),
//     NOT gpt-4o-mini. The 'validation' team's policy (V14) DENIES every other model.
//   - Auth key is NOT the seeded cp_admin_root: the real deploy overrides the pepper, so
//     that hash no longer resolves. run-live.sh bootstraps a fresh admin key straight into
//     Postgres and passes the raw value in via __ENV.ADMIN_KEY.
//   - One governed team ('validation') is reused across all three scenarios. They are
//     staggered by startTime and run-live.sh RE-SEEDS the 'validation' budget cap between
//     each scenario (so one scenario's spend never poisons the next one's tight cap).
//   - Loads are deliberately small — real tokens = real dollars. The ledger/Prometheus,
//     not k6's own numbers, remain the sources of truth (reconciled in run-live.sh).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://gateway:8080';
// raw admin key minted by run-live.sh under the deploy's real pepper (no default: a
// missing key must fail loudly rather than silently hit the invalidated seed key)
const ADMIN = __ENV.ADMIN_KEY;
const TEAM = 'validation';
const MODEL = 'gemini-2.5-flash-lite';

// One 'validation' team is reused across all three claims, so they must NOT share a
// budget cap. run-live.sh invokes k6 ONCE PER PHASE, re-seeding the cap between runs, and
// selects the phase via __ENV.PHASE (guard | flood | cutoff). Only that phase's scenarios
// are included. guard always drags its warmup soak along so micrometer quantiles are warm.
const PHASE = __ENV.PHASE || 'guard';

const ALL = {
	// deployment hot-path test. Guard latency is upstream-INDEPENDENT (pure Redis/price
	// math before the upstream call), so this validates the deployed host, not Gemini.
	// The warmup soak warms JIT + counters and ages cold-start samples out of micrometer's
	// ~2-min decaying quantile window before guard_latency measures.
	guard: {
		warmup: {
			executor: 'constant-arrival-rate',
			rate: 20, timeUnit: '1s', duration: '90s',
			preAllocatedVUs: 20, maxVUs: 50,
			exec: 'latency',
		},
		guard_latency: {
			executor: 'constant-arrival-rate',
			rate: 50, timeUnit: '1s', duration: '20s',
			startTime: '95s',
			preAllocatedVUs: 40, maxVUs: 120,
			exec: 'latency',
		},
	},
	// concurrent flood against a tight cap → most requests must be blocked at reservation
	// (402) before any spend; run-live.sh proves ledger spend ≤ cap.
	flood: {
		overspend_flood: {
			executor: 'shared-iterations',
			vus: 20, iterations: 90, maxDuration: '60s',
			exec: 'flood',
		},
	},
	// concurrent long streams that pass admission but overrun mid-generation → forces a
	// mid-stream budget_cutoff. Gemini emits multi-token chunks, so the ledger overshoot
	// is bounded by ~one chunk's tokens (documented honestly in BENCHMARK.md), not the
	// mock's 0.33.
	cutoff: {
		cutoff_scale: {
			executor: 'per-vu-iterations',
			vus: 5, iterations: 1, maxDuration: '120s',
			exec: 'cutoff',
		},
	},
};

export const options = {
	summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
	scenarios: ALL[PHASE],
	thresholds: {
		// e2e time is context only and swings with provider latency; the DOCUMENTED target
		// is guard-only p99 < 5ms, read from Prometheus by run-live.sh. Bound generously
		// because a real Gemini round-trip is far slower than the mock.
		'http_req_duration{scenario:guard_latency}': ['p(99)<10000'],
		'checks': ['rate>0.99'],
	},
};

const served = new Counter('flood_served');
const blocked = new Counter('flood_blocked_402');

function post(body, params = {}) {
	return http.post(`${BASE}/v1/chat/completions`, JSON.stringify(body), {
		headers: {
			'Content-Type': 'application/json',
			'Authorization': `Bearer ${ADMIN}`,
			'X-Team-ID': TEAM,
		},
		...params,
	});
}

export function latency() {
	const r = post({
		model: MODEL,
		messages: [{ role: 'user', content: 'ping' }],
		max_tokens: 16,
	});
	check(r, { 'latency: 200': (x) => x.status === 200 });
}

export function flood() {
	const r = post({
		model: MODEL,
		messages: [{ role: 'user', content: 'hello costpilot' }],
		max_tokens: 128,
	});
	if (r.status === 200) served.add(1);
	if (r.status === 402) blocked.add(1);
	// under a flood both outcomes are legitimate; anything else is a failure
	check(r, { 'flood: 200 or 402': (x) => x.status === 200 || x.status === 402 });
}

export function cutoff() {
	// ask for a genuinely long generation; max_tokens sits well above what the tight cap
	// can afford, so the stream is admitted (its per-request estimate fits) and then
	// overruns mid-flight → mid-stream cutoff. Unlike the mock's echo prompt, we can't
	// predict Gemini's exact token count, which is the point: the cap does the bounding.
	const r = post({
		model: MODEL,
		messages: [{ role: 'user', content: 'Write a detailed 1000-word story about a lighthouse keeper.' }],
		max_tokens: 800,
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

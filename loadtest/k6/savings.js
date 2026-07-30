// 1.3 (#93): $0-mock cost-savings scenario — routing downgrades + semantic cache hits.
// Seeds lt-savings-* traffic, then run-savings.sh reconciles ledger + cache_hit_log
// against GET /api/analytics/savings (and prints $ / % saved for BENCHMARK.md).
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://gateway:8080';
const ADMIN = 'cp_admin_root';
const TEAMS = 5;
const ROUTING_ITERS = 20;
const CACHE_PROMPTS = 5;
const CACHE_HITS_PER_PROMPT = 3;

export const options = {
	scenarios: {
		// gpt-4o requested + Min-Tier 1 -> routed to gpt-4o-mini; each row gets savings_nanos
		routing_savings: {
			executor: 'shared-iterations',
			vus: 5,
			iterations: ROUTING_ITERS,
			maxDuration: '60s',
			exec: 'routing',
		},
		// first pass stores; subsequent identical prompts hit cache (cache_hit_log rows)
		cache_miss_then_hits: {
			executor: 'shared-iterations',
			vus: 1,
			iterations: CACHE_PROMPTS * (1 + CACHE_HITS_PER_PROMPT),
			startTime: '5s',
			maxDuration: '90s',
			exec: 'cacheTraffic',
		},
	},
	thresholds: {
		checks: ['rate>0.95'],
	},
};

function headers(team, extra = {}) {
	return {
		'Content-Type': 'application/json',
		Authorization: `Bearer ${ADMIN}`,
		'X-Team-ID': team,
		...extra,
	};
}

export function routing() {
	const team = `lt-savings-route-${exec.scenario.iterationInTest % TEAMS}`;
	const r = http.post(
		`${BASE}/v1/chat/completions`,
		JSON.stringify({
			model: 'gpt-4o',
			messages: [{ role: 'user', content: `savings routing ping ${exec.scenario.iterationInTest}` }],
			max_tokens: 32,
			stream: false,
		}),
		{
			headers: headers(team, { 'X-CostPilot-Min-Tier': '1' }),
		},
	);
	check(r, { 'routing: 200': (x) => x.status === 200 });
}

export function cacheTraffic() {
	// iteration 0..N: for each prompt, 1 miss + CACHE_HITS_PER_PROMPT hits
	const cycle = 1 + CACHE_HITS_PER_PROMPT;
	const promptIdx = Math.floor(exec.scenario.iterationInTest / cycle) % CACHE_PROMPTS;
	const team = `lt-savings-cache-${promptIdx % TEAMS}`;
	const prompt = `costpilot savings cache prompt number ${promptIdx} for reproducible hit`;
	const r = http.post(
		`${BASE}/v1/chat/completions`,
		JSON.stringify({
			model: 'gpt-4o-mini',
			messages: [{ role: 'user', content: prompt }],
			max_tokens: 32,
			stream: false,
		}),
		{ headers: headers(team) },
	);
	const isFirstInCycle = exec.scenario.iterationInTest % cycle === 0;
	check(r, {
		'cache: 200': (x) => x.status === 200,
		'cache: miss then hit header': (x) => {
			// k6 canonicalizes MIME headers: X-CostPilot-Cache -> X-Costpilot-Cache
			const h = x.headers['X-Costpilot-Cache']
				|| x.headers['X-CostPilot-Cache']
				|| x.headers['x-costpilot-cache'];
			return isFirstInCycle ? !h : h === 'hit';
		},
	});
	// tiny pause so store commits before the next identical prompt in the same VU
	if (isFirstInCycle) sleep(0.15);
}

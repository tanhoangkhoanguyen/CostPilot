<div align="center">

# CostPilot

<a href="https://github.com/tanhoangkhoanguyen/CostPilot">
  <img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=600&size=21&pause=1200&color=F97316&center=true&vCenter=true&width=820&height=46&lines=Nobody+knew+who+was+spending+the+money.;The+bill+only+showed+up+at+the+end+of+the+month.;By+then+the+money+was+already+gone.;So+I+put+a+gate+in+front+of+the+models." alt="Nobody knew who was spending the money. The bill only showed up at the end of the month. By then the money was already gone. So I put a gate in front of the models." />
</a>

**An AI spending gateway that says no before the money is gone.**

Your apps call CostPilot instead of OpenAI, Anthropic or Gemini.
CostPilot decides, per request, at runtime: who may spend, on which model, and how much.

<p>
<img alt="Java 21" src="https://img.shields.io/badge/Java-21-e76f00?style=flat-square&logo=openjdk&logoColor=white">
<img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white">
<img alt="Postgres" src="https://img.shields.io/badge/Postgres-pgvector-4169E1?style=flat-square&logo=postgresql&logoColor=white">
<img alt="Redis" src="https://img.shields.io/badge/Redis-counters-FF4438?style=flat-square&logo=redis&logoColor=white">
<img alt="Kafka" src="https://img.shields.io/badge/Kafka-%E2%86%92%20ClickHouse-231F20?style=flat-square&logo=apachekafka&logoColor=white">
<br>
<a href="https://github.com/tanhoangkhoanguyen/CostPilot/actions"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/tanhoangkhoanguyen/CostPilot/ci.yml?style=flat-square&label=build"></a>
<img alt="Tests" src="https://img.shields.io/badge/tests-170%2B-16a34a?style=flat-square">
<img alt="Guard latency" src="https://img.shields.io/badge/budget%20decision-p50%202.7ms-0ea5e9?style=flat-square">
<img alt="Demo cost" src="https://img.shields.io/badge/demo%20cost-%240-7c3aed?style=flat-square">
</p>

<a href="docs/README.md"><b>Setup and usage docs</b></a> · <a href="ROADMAP.md">Roadmap</a> · <a href="BENCHMARK.md">Benchmarks</a>

</div>

---

## What we kept seeing

Every team that starts building on top of a model provider goes through the same three months.

Month one is fun. Somebody drops an API key into a config file, ships a feature, and it works. Month two, three more services want the same key, so they get it. A batch job starts running nightly. Someone tries the bigger model because the small one was a little dull, and it turns out the bigger model is fifteen times the price. Nobody notices, because nothing on the screen changes.

Month three, the invoice arrives.

And this is the part that always struck me: at that moment, nobody in the room can answer basic questions. Which team spent it. Which feature. Was it the nightly job or the demo someone left running over the weekend. The provider dashboard shows one number for one key, and that key belongs to everybody. So the meeting ends the way those meetings always end, with somebody saying we should be more careful, and nothing actually changing.

The tools we reached for did not help either. They were all dashboards. They told you, beautifully and in colour, what you had already spent. But a dashboard is a rear view mirror. It cannot stop anything. By the time a chart goes red, the money is spent, and no chart has ever refunded a dollar.

So the problem was never that we could not see the spending. It was that seeing was all we could do.

## What I did about it

I moved the decision to the moment it actually matters: before the request goes out.

CostPilot sits between your app and the model providers and speaks the OpenAI API, so from your side it is a one line change to `base_url`. But every request that passes through gets asked a few questions first. Who are you. Are you allowed to use this model. Is there budget left. Can we answer this from a cache instead. Is there a cheaper model that still meets the bar you asked for.

If the answer is no, the request never leaves the building. You get a `402` with a machine readable reason, not a surprise at the end of the month.

And because a streaming answer can quietly run much longer than anyone estimated, the meter keeps running while the tokens come back. When the spend crosses the line mid answer, the stream is cut cleanly, with a proper `finish_reason` and a `[DONE]`, and only the tokens actually delivered get billed. The overshoot is bounded to a single chunk.

None of this is worth much if it makes engineers hate you, so the whole governance decision has to disappear into the noise. It does: the budget check lands at 2.7 ms at the median under 100 requests per second.

## One request, end to end

Ten steps. Everything the request touches lights up as it goes.

<p align="center">
  <img src="docs/diagram.gif" alt="A live request moving through the CostPilot pipeline: auth, normalize, policy, cache, route, budget, forward, meter, ledger, settle" width="100%">
</p>

| | step | what it decides |
|---|---|---|
| 1 | **Auth** | who you are, from a hashed key, never from a header you sent |
| 2 | **Normalize** | your OpenAI shaped request becomes one internal shape |
| 3 | **Policy** | allow, deny, quietly downgrade, or hold for a human |
| 4 | **Cache** | close enough to something already answered, serve it for free |
| 5 | **Route** | cheapest model that still clears the bar you asked for |
| 6 | **Budget** | reserve the worst case cost across every scope that governs you |
| 7 | **Forward** | out to OpenAI, Anthropic, Gemini, or the built in mock |
| 8 | **Meter** | count the money as it streams, cut off cleanly on breach |
| 9 | **Ledger** | write the real charge, once, even if you hang up |
| 10 | **Settle** | release, publish, and leave an audit row explaining the verdict |

Money is stored as whole nanodollars, never floats, so nothing drifts. Postgres is the truth. Redis is a fast copy that can be rebuilt from the ledger at any time.

## What it is not

It is not a reliability gateway. There are no circuit breakers, no failover, no routing by latency or health. That is a different product and doing both badly helps nobody.

There is exactly one deliberate reliability decision in here: if the budget store is unreachable, the request goes through. A billing system should never be the reason your product is down. That is a choice, it is written down, and it is tested.

## Does it hold up

Everything below comes out of one command, `bash loadtest/run.sh`, and the claims are read back from the Postgres ledger afterwards rather than from the load tool.

| claim | target | measured |
|---|---|---|
| budget decision overhead at 100 req/s | single digit ms | **p50 2.72 ms** / p95 7.57 ms |
| teams that overspent their cap under flood | 0 | **0 of 30** (156 served, 144 blocked) |
| worst mid stream cutoff overshoot | one chunk | **about 1.5 tokens** |
| clean cutoff signal and valid statuses | 100% | **100%** |

Measured on a laptop running the whole stack and the load generator at once. Full method and caveats live in [BENCHMARK.md](BENCHMARK.md).

## Try it

Docker is the only thing you need, and the demo costs nothing because the default upstream is a mock provider that lives inside the app.

```bash
git clone https://github.com/tanhoangkhoanguyen/CostPilot.git
cd CostPilot
docker compose up --build -d
```

Then walk through the ten minute demo, the CLI, the Python SDK, and going live with real providers here:

### → [Setup and usage docs](docs/README.md)

---

<div align="center">
<sub>Built because a dashboard has never stopped a single dollar from leaving.</sub>
</div>

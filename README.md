<div align="center">

<img src="docs/Avatar_Costpilot.png" width="170" alt="CostPilot mascot: a small robot in a pilot cap holding a magnifying glass over a spending chart">

# CostPilot

*Nobody knew who was spending the money.*<br>
*The bill only showed up at the end of the month, and by then it was gone.*

**An AI spending gateway that says no before the money is gone.**

<p>
<img height="56" alt="Java 21" src="https://cdn.simpleicons.org/openjdk/5382A1">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img height="56" alt="Spring Boot" src="https://cdn.simpleicons.org/springboot/6DB33F">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img height="56" alt="Postgres + pgvector" src="https://cdn.simpleicons.org/postgresql/5D8FD6">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img height="56" alt="Redis" src="https://cdn.simpleicons.org/redis/FF5449">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img height="56" alt="Kafka" src="https://cdn.simpleicons.org/apachekafka/8F98A6">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
<img height="56" alt="ClickHouse" src="https://cdn.simpleicons.org/clickhouse/EFB100">
</p>

<a href="docs/README.md"><b>Setup and usage docs</b></a> · <a href="ROADMAP.md">Roadmap</a>

</div>

---

## The problem

<img src="docs/Scene1.png" alt="A developer happily drops a glowing API key into a treasure chest while fireworks play on the screen behind him">

**Month one.** Somebody drops an API key into a config file, ships a feature, and it works. Everyone is delighted.

<img src="docs/Scene2.png" alt="The same API key has spread to a nightly robot, a salesperson and another developer, while a monster in the corner eats a pile of cash">

**Month two.** That key is now in three more services, a nightly job and a client demo. Someone swaps in the bigger model because the small one felt dull. It costs fifteen times more. Nothing on the screen changes, so nobody notices.

<img src="docs/Scene3.png" alt="A meeting room with an enormous invoice unrolling across the table while everyone points at each other">

**Month three.** The invoice arrives, and nobody in the room can say which team spent it, or on what. One key, one number, everybody's fingerprints.

<img src="docs/Scene4.png" alt="A driver watching a spiking cost chart in the rear view mirror while the road ahead ends at a cliff">

Every tool we reached for was a dashboard. A dashboard is a rear view mirror. By the time a chart turns red, the money is already behind you.

> **No chart has ever refunded a dollar.**
> Seeing the spending was never the problem. Seeing was all we could do.

## The approach

Move the decision to where it matters: before the request leaves.

<img src="docs/Scene5.png" alt="The CostPilot mascot flying between a Your App cloud and a Model Provider cloud, holding the connection, next to an editor showing a changed base_url">

**One line on your side.** CostPilot sits between your app and the providers and speaks the OpenAI API, so all you change is `base_url`.

<img src="docs/Scene6.png" alt="The mascot at a checkpoint desk questioning each request, then holding a stop sign that reads HTTP 402 budget exceeded">

**Every request gets questioned first.** Who are you. May you use this model. Can a cache answer this instead. Is there budget left. If the answer is no, the request never leaves, and you get a `402` with a reason your code can read.

<img src="docs/Scene7.png" alt="A stream of tokens flowing until a limit gauge redlines, then the mascot snips the stream cleanly and the rest of the budget stays unspent">

**The meter keeps running while it streams.** An answer can run far longer than anyone estimated, so when the spend crosses the line the stream is cut clean, with a real `finish_reason` and a `[DONE]`. You pay for what arrived. The overshoot is one chunk.

<img src="docs/Scene8.png" alt="A calm office, feet on the desk, a screen reading budget status safe, and the mascot giving a thumbs up">

And it has to be invisible, or engineers will route around it. The whole check lands at 2.7 ms median under 100 requests per second.

## Architecture

Ten steps. Everything the request touches lights up as it goes.

<p align="center">
  <img src="docs/diagram.gif" alt="A live request moving through the CostPilot pipeline: auth, normalize, policy, cache, route, budget, forward, meter, ledger, settle" width="100%">
</p>

| # | step | decides |
|:---:|---|---|
| 1 | **Auth** | who you are, from a hashed key, never from a header you sent |
| 2 | **Normalize** | your OpenAI shaped request becomes one internal shape |
| 3 | **Policy** | allow, deny, quietly downgrade, or hold for a human |
| 4 | **Cache** | close enough to something already answered, serve it for free |
| 5 | **Route** | cheapest model that still clears the bar you asked for |
| 6 | **Budget** | reserve a *conservative hold* (heuristic max cost) across every scope that governs you — not the bill |
| 7 | **Forward** | out to OpenAI, Anthropic, Gemini, or the built in mock |
| 8 | **Meter** | estimate running cost to cut off mid-stream; provider token count wins the instant it arrives |
| 9 | **Ledger** | write the **exact** charge from provider-reported tokens × published price, once, even if you hang up |
| 10 | **Settle** | release the hold, publish, and leave an audit row explaining the verdict |

Exact billing vs the two heuristic sites (reserve, cutoff): [BENCHMARK.md — Money correctness](BENCHMARK.md#money-correctness--exact-billing-vs-heuristics).

## Quickstart

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
<img src="docs/Avatar_Costpilot.png" width="80" alt="">
<br>
<sub>Built because a dashboard has never stopped a single dollar from leaving.</sub>
</div>

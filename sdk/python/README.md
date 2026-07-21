# costpilot (Python SDK)

A governance-first Python client for the [CostPilot](https://github.com/tanhoangkhoanguyen/CostPilot) AI-spending gateway.

CostPilot speaks the OpenAI API, so you *can* point any OpenAI client at it. This SDK adds what a raw client can't: the gateway's **runtime governance verdict as typed data** - cache hits, budget warnings, model routing/downgrades, mid-stream budget cut-offs, and typed exceptions for budget / policy / approval outcomes.

```bash
pip install costpilot
```

## Quickstart

```python
from costpilot import CostPilot

cp = CostPilot(
    base_url="http://localhost:8080/v1",   # or env COSTPILOT_BASE_URL
    api_key="cp_...",                       # or env COSTPILOT_API_KEY
    team="research",                        # optional default attribution
)

r = cp.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "explain nanodollars in one line"}],
    max_tokens=256,
)

print(r.content)                    # first choice's text
print(r.usage.total_tokens)

# governance verdict, as typed attributes (read from response headers)
print(r.governance.cache_hit)       # bool  - served from semantic cache at $0
print(r.governance.budget_warning)  # str | None - soft-limit heads-up
print(r.governance.model_routed)    # str | None - cost-based route swap
print(r.governance.model_downgraded)# str | None - policy/budget downgrade
```

## Streaming, with budget cut-off detection

CostPilot can cancel a stream mid-flight to stay within budget. The SDK surfaces that as `budget_cutoff` after the loop, and stops cleanly on `[DONE]`.

```python
with cp.chat.completions.stream(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "write a long essay"}],
) as stream:
    for chunk in stream:
        print(chunk.delta or "", end="", flush=True)

    if stream.budget_cutoff:
        print("\n[truncated by CostPilot to stay within budget]")
    print(stream.governance.budget_warning)
```

## Typed governance errors

The gateway's decisions come back as exceptions you can branch on - no JSON digging:

```python
from costpilot import (
    BudgetExceededError, PolicyDeniedError, ApprovalRequiredError, AuthError, APIError,
)

try:
    cp.chat.completions.create(model="gpt-4o", messages=[...])
except BudgetExceededError as e:
    print("out of budget on scope:", e.scope)        # 402, e.scope = team/project/...
except PolicyDeniedError as e:
    print("blocked by policy rule:", e.rule_id)       # 403
except ApprovalRequiredError as e:
    print("parked for approval:", e.pending_id, e.expires_at)  # 202, not forwarded
except AuthError:
    print("bad or missing cp_ key")                   # 401
except APIError as e:
    print("gateway error", e.status_code, e.type)     # any other non-2xx
```

## Attribution, idempotency, routing floor

Set `team` / `project` once on the client, or override per call. Every call also
takes an `idempotency_key` (safe retries - the ledger charges once) and a
`min_tier` routing floor.

```python
cp.chat.completions.create(
    model="gpt-4o-mini",
    messages=[...],
    team="ops",                 # -> X-Team-ID
    project="q3-forecast",      # -> X-Project-ID
    idempotency_key="job-42",   # -> Idempotency-Key
    min_tier=2,                 # -> X-CostPilot-Min-Tier
)
```

## Async

`AsyncCostPilot` mirrors the sync surface with `await` / `async for`:

```python
import asyncio
from costpilot import AsyncCostPilot

async def main():
    async with AsyncCostPilot(api_key="cp_...") as cp:
        r = await cp.chat.completions.create(
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": "hi"}],
        )
        print(r.content)

        async with cp.chat.completions.stream(
            model="gpt-4o-mini", messages=[{"role": "user", "content": "stream please"}],
        ) as stream:
            async for chunk in stream:
                print(chunk.delta or "", end="")

asyncio.run(main())
```

## Configuration

| Argument   | Env var                | Default                        |
|------------|------------------------|--------------------------------|
| `base_url` | `COSTPILOT_BASE_URL`   | `http://localhost:8080/v1`     |
| `api_key`  | `COSTPILOT_API_KEY`    | required                       |
| `team`     | -                      | none                           |
| `project`  | -                      | none                           |
| `timeout`  | -                      | `60.0` seconds                 |

The only dependency is [`httpx`](https://www.python-httpx.org/). Requires Python 3.9+.

## License

MIT

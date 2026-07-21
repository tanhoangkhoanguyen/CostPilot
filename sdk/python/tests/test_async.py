"""Async path mirrors the sync surface: create, stream, cutoff, typed errors."""

from __future__ import annotations

import httpx
import pytest

from costpilot import BudgetExceededError
from _helpers import COMPLETION, async_client, chunk, json_handler, sse_bytes


async def test_async_create_parses_and_maps_governance():
    cp = async_client(json_handler(COMPLETION, headers={"X-CostPilot-Cache": "hit"}))
    r = await cp.chat.completions.create(
        model="gpt-4o-mini", messages=[{"role": "user", "content": "hi"}]
    )
    assert r.content == "hello there"
    assert r.usage.total_tokens == 7
    assert r.governance.cache_hit is True
    await cp.aclose()


async def test_async_context_manager_and_attribution():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["headers"] = request.headers
        return httpx.Response(200, json=COMPLETION)

    async with async_client(handler, team="research") as cp:
        await cp.chat.completions.create(
            model="gpt-4o-mini", messages=[{"role": "user", "content": "hi"}], project="p2"
        )
    assert seen["headers"]["x-team-id"] == "research"
    assert seen["headers"]["x-project-id"] == "p2"


async def test_async_stream_deltas_and_cutoff():
    chunks = [chunk("a"), chunk("b"), chunk("", finish_reason="budget_cutoff")]

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=sse_bytes(chunks))

    out = []
    async with async_client(handler) as cp:
        async with cp.chat.completions.stream(model="m", messages=[]) as s:
            async for c in s:
                if c.delta:
                    out.append(c.delta)
            assert s.budget_cutoff is True
    assert "".join(out) == "ab"


async def test_async_stream_error_raises_typed():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            402, json={"error": {"message": "no budget", "type": "budget_exceeded", "code": "project"}}
        )

    async with async_client(handler) as cp:
        with pytest.raises(BudgetExceededError) as ei:
            async with cp.chat.completions.stream(model="m", messages=[]) as s:
                async for _ in s:
                    pass
        assert ei.value.scope == "project"

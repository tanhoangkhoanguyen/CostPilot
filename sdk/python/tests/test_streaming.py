"""Streaming path: deltas, clean [DONE] stop, and budget_cutoff surfacing."""

from __future__ import annotations

import httpx
import pytest

from costpilot import BudgetExceededError
from _helpers import chunk, sse_bytes, sync_client


def _stream_handler(chunks, *, status=200, headers=None):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, content=sse_bytes(chunks), headers=headers or {})

    return handler


def test_stream_yields_deltas_and_stops_at_done():
    chunks = [chunk("Hel"), chunk("lo"), chunk("!", finish_reason="stop")]
    cp = sync_client(_stream_handler(chunks))
    out = []
    with cp.chat.completions.stream(model="m", messages=[{"role": "user", "content": "hi"}]) as s:
        for c in s:
            if c.delta:
                out.append(c.delta)
        assert s.budget_cutoff is False
    assert "".join(out) == "Hello!"


def test_stream_surfaces_budget_cutoff():
    # deltas, then the crossing chunk carries finish_reason budget_cutoff, then [DONE]
    chunks = [chunk("some "), chunk("partial"), chunk("", finish_reason="budget_cutoff")]
    cp = sync_client(_stream_handler(chunks, headers={"X-CostPilot-Budget-Warning": "cut at cap"}))
    seen = []
    with cp.chat.completions.stream(model="m", messages=[{"role": "user", "content": "hi"}]) as s:
        for c in s:
            if c.delta:
                seen.append(c.delta)
        assert s.budget_cutoff is True
        assert s.governance.budget_warning == "cut at cap"
    assert "".join(seen) == "some partial"


def test_stream_stops_iterating_after_done_sentinel():
    # anything the server sends after [DONE] must not be yielded
    body = sse_bytes([chunk("a")], done=True) + b'data: {"choices":[{"delta":{"content":"LEAK"}}]}\n\n'

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=body)

    cp = sync_client(handler)
    collected = []
    with cp.chat.completions.stream(model="m", messages=[]) as s:
        for c in s:
            collected.append(c.delta)
    assert collected == ["a"]


def test_stream_error_before_body_raises_typed():
    # a 402 arriving before the stream opens must raise, not yield an empty stream
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(402, json={"error": {"message": "no budget", "type": "budget_exceeded", "code": "team"}})

    cp = sync_client(handler)
    with pytest.raises(BudgetExceededError) as ei:
        with cp.chat.completions.stream(model="m", messages=[]) as s:
            list(s)
    assert ei.value.scope == "team"


def test_stream_sets_stream_true_in_body():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        import json

        seen["body"] = json.loads(request.content)
        seen["accept"] = request.headers.get("accept")
        return httpx.Response(200, content=sse_bytes([chunk("x", finish_reason="stop")]))

    cp = sync_client(handler)
    with cp.chat.completions.stream(model="m", messages=[{"role": "user", "content": "hi"}]) as s:
        list(s)
    assert seen["body"]["stream"] is True
    assert seen["accept"] == "text/event-stream"

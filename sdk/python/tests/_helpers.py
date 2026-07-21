"""Shared test helpers: build clients over an httpx.MockTransport (no network, $0)."""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

import httpx

from costpilot import AsyncCostPilot, CostPilot

BASE = "http://gw.test/v1"
KEY = "cp_test_key"

# a representative non-streaming completion body
COMPLETION: Dict[str, Any] = {
    "id": "chatcmpl-abc",
    "object": "chat.completion",
    "model": "gpt-4o-mini",
    "choices": [
        {
            "index": 0,
            "message": {"role": "assistant", "content": "hello there"},
            "finish_reason": "stop",
        }
    ],
    "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7},
}


def sync_client(handler, **kw) -> CostPilot:
    return CostPilot(base_url=BASE, api_key=KEY, transport=httpx.MockTransport(handler), **kw)


def async_client(handler, **kw) -> AsyncCostPilot:
    return AsyncCostPilot(base_url=BASE, api_key=KEY, transport=httpx.MockTransport(handler), **kw)


def json_handler(body: Any, *, status: int = 200, headers: Optional[Dict[str, str]] = None):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=body, headers=headers or {})

    return handler


def sse_bytes(chunks: List[Dict[str, Any]], *, done: bool = True) -> bytes:
    parts: List[str] = []
    for c in chunks:
        parts.append("data: " + json.dumps(c))
        parts.append("")  # blank line separates SSE events
    if done:
        parts.append("data: [DONE]")
        parts.append("")
    return ("\n".join(parts) + "\n").encode()


def chunk(
    content: Optional[str] = None,
    finish_reason: Optional[str] = None,
    *,
    id: str = "chatcmpl-stream",
    model: str = "gpt-4o-mini",
) -> Dict[str, Any]:
    delta: Dict[str, Any] = {}
    if content is not None:
        delta["content"] = content
    return {
        "id": id,
        "object": "chat.completion.chunk",
        "model": model,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish_reason}],
    }

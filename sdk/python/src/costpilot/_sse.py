"""A minimal Server-Sent Events reader for the streaming chat path.

Only what the gateway actually emits: ``data:`` lines carrying a JSON chunk, a
terminal ``data: [DONE]`` sentinel, and blank separators. Comment lines (``:``)
and other SSE fields are ignored. We deliberately don't pull in a full SSE
library - this keeps the dependency surface to just httpx.
"""

from __future__ import annotations

import json
from typing import Any, AsyncIterator, Dict, Iterable, Iterator, Optional

_DATA_PREFIX = "data:"
_DONE = "[DONE]"


def _payload(line: str) -> Optional[str]:
    """Return the data payload of an SSE line, or None if it isn't a data line."""
    if not line or not line.startswith(_DATA_PREFIX):
        return None
    # tolerate both "data: {..}" and "data:{..}"
    return line[len(_DATA_PREFIX):].lstrip()


def iter_sse_data(lines: Iterable[str]) -> Iterator[Dict[str, Any]]:
    """Yield decoded JSON objects from SSE ``data:`` lines until ``[DONE]``."""
    for line in lines:
        data = _payload(line)
        if data is None:
            continue
        if data == _DONE:
            return
        try:
            obj = json.loads(data)
        except json.JSONDecodeError:
            # a malformed keep-alive or partial line: skip rather than crash the stream
            continue
        if isinstance(obj, dict):
            yield obj


async def aiter_sse_data(lines: AsyncIterator[str]) -> AsyncIterator[Dict[str, Any]]:
    """Async twin of :func:`iter_sse_data`."""
    async for line in lines:
        data = _payload(line)
        if data is None:
            continue
        if data == _DONE:
            return
        try:
            obj = json.loads(data)
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict):
            yield obj

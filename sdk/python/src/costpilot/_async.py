"""Asynchronous CostPilot client - mirrors the sync surface with await/async for."""

from __future__ import annotations

from typing import Any, List, Mapping, Optional

import httpx

from . import _core
from ._models import ChatCompletion, Governance, StreamChunk
from ._sse import aiter_sse_data

_BUDGET_CUTOFF = "budget_cutoff"


class AsyncStreamResponse:
    """Async context manager yielded by ``chat.completions.stream(...)``.

    ``async with`` it, then ``async for`` the deltas. ``budget_cutoff`` and
    ``governance`` carry the same meaning as the sync :class:`StreamResponse`.
    """

    def __init__(self, http: httpx.AsyncClient, url: str, body: dict, headers: dict) -> None:
        self._http = http
        self._url = url
        self._body = body
        self._headers = headers
        self._cm: Any = None
        self._resp: Optional[httpx.Response] = None
        self.governance: Governance = Governance()
        self.budget_cutoff: bool = False

    async def __aenter__(self) -> "AsyncStreamResponse":
        self._cm = self._http.stream("POST", self._url, json=self._body, headers=self._headers)
        self._resp = await self._cm.__aenter__()
        resp = self._resp
        if resp.status_code == 202:
            await resp.aread()
            await self._close()
            raise _core.approval_from_body(resp.text)
        if not resp.is_success:
            await resp.aread()
            text = resp.text
            await self._close()
            _core.raise_for_status(resp.status_code, text)
        self.governance = Governance.from_headers(resp.headers)
        return self

    async def __aiter__(self):
        assert self._resp is not None, "stream not opened; use `async with client...stream(...) as s:`"
        async for payload in aiter_sse_data(self._resp.aiter_lines()):
            chunk = StreamChunk.from_dict(payload)
            if chunk.finish_reason == _BUDGET_CUTOFF:
                self.budget_cutoff = True
            yield chunk

    async def __aexit__(self, *exc: Any) -> None:
        await self._close()

    async def _close(self) -> None:
        if self._cm is not None:
            try:
                await self._cm.__aexit__(None, None, None)
            finally:
                self._cm = None


class _AsyncCompletions:
    def __init__(self, client: "AsyncCostPilot") -> None:
        self._client = client

    async def create(
        self,
        *,
        model: str,
        messages: List[Mapping[str, Any]],
        max_tokens: Optional[int] = None,
        team: Optional[str] = None,
        project: Optional[str] = None,
        idempotency_key: Optional[str] = None,
        min_tier: Optional[int] = None,
        extra_body: Optional[Mapping[str, Any]] = None,
        **params: Any,
    ) -> ChatCompletion:
        headers = self._client._headers(
            team=team, project=project, idempotency_key=idempotency_key,
            min_tier=min_tier, stream=False,
        )
        body = _core.build_body(
            model=model, messages=messages, max_tokens=max_tokens,
            stream=False, extra_body=extra_body, params=params,
        )
        resp = await self._client._http.post(self._client._chat_url, json=body, headers=headers)
        if resp.status_code == 202:
            raise _core.approval_from_body(resp.text)
        if not resp.is_success:
            _core.raise_for_status(resp.status_code, resp.text)
        return ChatCompletion.from_response(resp.json(), resp.headers)

    def stream(
        self,
        *,
        model: str,
        messages: List[Mapping[str, Any]],
        max_tokens: Optional[int] = None,
        team: Optional[str] = None,
        project: Optional[str] = None,
        idempotency_key: Optional[str] = None,
        min_tier: Optional[int] = None,
        extra_body: Optional[Mapping[str, Any]] = None,
        **params: Any,
    ) -> AsyncStreamResponse:
        headers = self._client._headers(
            team=team, project=project, idempotency_key=idempotency_key,
            min_tier=min_tier, stream=True,
        )
        body = _core.build_body(
            model=model, messages=messages, max_tokens=max_tokens,
            stream=True, extra_body=extra_body, params=params,
        )
        return AsyncStreamResponse(self._client._http, self._client._chat_url, body, headers)


class _AsyncChat:
    def __init__(self, client: "AsyncCostPilot") -> None:
        self.completions = _AsyncCompletions(client)


class AsyncCostPilot:
    """Async twin of :class:`CostPilot`.

    >>> async with AsyncCostPilot(api_key="cp_...") as cp:
    ...     r = await cp.chat.completions.create(model="gpt-4o-mini",
    ...         messages=[{"role": "user", "content": "hi"}])
    """

    def __init__(
        self,
        *,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        team: Optional[str] = None,
        project: Optional[str] = None,
        timeout: float = _core.DEFAULT_TIMEOUT,
        transport: Optional[httpx.AsyncBaseTransport] = None,
        http_client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        self._base_url = _core.resolve_base_url(base_url)
        self._api_key = _core.resolve_api_key(api_key)
        self._chat_url = f"{self._base_url}/chat/completions"
        self._default_team = team
        self._default_project = project
        self._owns_http = http_client is None
        self._http = http_client or httpx.AsyncClient(timeout=timeout, transport=transport)
        self.chat = _AsyncChat(self)

    def _headers(self, **kwargs: Any) -> dict:
        return _core.build_headers(
            api_key=self._api_key,
            default_team=self._default_team,
            default_project=self._default_project,
            **kwargs,
        )

    async def aclose(self) -> None:
        if self._owns_http:
            await self._http.aclose()

    async def __aenter__(self) -> "AsyncCostPilot":
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.aclose()

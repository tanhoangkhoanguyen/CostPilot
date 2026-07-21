"""Synchronous CostPilot client."""

from __future__ import annotations

from typing import Any, List, Mapping, Optional

import httpx

from . import _core
from ._models import ChatCompletion, Governance, StreamChunk
from ._sse import iter_sse_data

_BUDGET_CUTOFF = "budget_cutoff"


class StreamResponse:
    """Context manager yielded by ``chat.completions.stream(...)``.

    Iterate it for :class:`~costpilot._models.StreamChunk` deltas. After the loop,
    ``budget_cutoff`` tells you whether the gateway truncated the stream to stay
    within budget, and ``governance`` carries the response-header verdict.
    """

    def __init__(self, http: httpx.Client, url: str, body: dict, headers: dict) -> None:
        self._http = http
        self._url = url
        self._body = body
        self._headers = headers
        self._cm: Any = None
        self._resp: Optional[httpx.Response] = None
        self.governance: Governance = Governance()
        self.budget_cutoff: bool = False

    def __enter__(self) -> "StreamResponse":
        self._cm = self._http.stream("POST", self._url, json=self._body, headers=self._headers)
        self._resp = self._cm.__enter__()
        resp = self._resp
        if resp.status_code == 202:
            resp.read()
            self._close()
            raise _core.approval_from_body(resp.text)
        if not resp.is_success:
            resp.read()
            text = resp.text
            self._close()
            _core.raise_for_status(resp.status_code, text)
        self.governance = Governance.from_headers(resp.headers)
        return self

    def __iter__(self):
        assert self._resp is not None, "stream not opened; use `with client...stream(...) as s:`"
        for payload in iter_sse_data(self._resp.iter_lines()):
            chunk = StreamChunk.from_dict(payload)
            if chunk.finish_reason == _BUDGET_CUTOFF:
                self.budget_cutoff = True
            yield chunk

    def __exit__(self, *exc: Any) -> None:
        self._close()

    def _close(self) -> None:
        if self._cm is not None:
            try:
                self._cm.__exit__(None, None, None)
            finally:
                self._cm = None


class _Completions:
    def __init__(self, client: "CostPilot") -> None:
        self._client = client

    def create(
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
        """Send a non-streaming chat completion and return the parsed result.

        Raises :class:`BudgetExceededError`, :class:`PolicyDeniedError`,
        :class:`AuthError`, :class:`ApprovalRequiredError`, or :class:`APIError`
        on the corresponding governance / transport outcome.
        """
        headers = self._client._headers(
            team=team, project=project, idempotency_key=idempotency_key,
            min_tier=min_tier, stream=False,
        )
        body = _core.build_body(
            model=model, messages=messages, max_tokens=max_tokens,
            stream=False, extra_body=extra_body, params=params,
        )
        resp = self._client._http.post(self._client._chat_url, json=body, headers=headers)
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
    ) -> StreamResponse:
        """Open a streaming chat completion. Use as a context manager."""
        headers = self._client._headers(
            team=team, project=project, idempotency_key=idempotency_key,
            min_tier=min_tier, stream=True,
        )
        body = _core.build_body(
            model=model, messages=messages, max_tokens=max_tokens,
            stream=True, extra_body=extra_body, params=params,
        )
        return StreamResponse(self._client._http, self._client._chat_url, body, headers)


class _Chat:
    def __init__(self, client: "CostPilot") -> None:
        self.completions = _Completions(client)


class CostPilot:
    """Governance-aware client for a CostPilot gateway.

    >>> cp = CostPilot(base_url="http://localhost:8080/v1", api_key="cp_...")
    >>> r = cp.chat.completions.create(model="gpt-4o-mini",
    ...     messages=[{"role": "user", "content": "hi"}])
    >>> r.content, r.governance.cache_hit
    """

    def __init__(
        self,
        *,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        team: Optional[str] = None,
        project: Optional[str] = None,
        timeout: float = _core.DEFAULT_TIMEOUT,
        transport: Optional[httpx.BaseTransport] = None,
        http_client: Optional[httpx.Client] = None,
    ) -> None:
        self._base_url = _core.resolve_base_url(base_url)
        self._api_key = _core.resolve_api_key(api_key)
        self._chat_url = f"{self._base_url}/chat/completions"
        self._default_team = team
        self._default_project = project
        self._owns_http = http_client is None
        # full URLs are built by hand, so no httpx base_url (its RFC-3986 join would
        # drop the /v1 path segment).
        self._http = http_client or httpx.Client(timeout=timeout, transport=transport)
        self.chat = _Chat(self)

    def _headers(self, **kwargs: Any) -> dict:
        return _core.build_headers(
            api_key=self._api_key,
            default_team=self._default_team,
            default_project=self._default_project,
            **kwargs,
        )

    def close(self) -> None:
        if self._owns_http:
            self._http.close()

    def __enter__(self) -> "CostPilot":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

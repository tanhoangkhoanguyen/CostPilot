"""Non-streaming sync path: parsing, governance headers, attribution, approval."""

from __future__ import annotations

import json

import httpx
import pytest

from costpilot import ApprovalRequiredError, CostPilotConfigError, CostPilot
from _helpers import BASE, COMPLETION, KEY, json_handler, sync_client


def test_create_parses_completion_and_governance():
    headers = {
        "X-CostPilot-Cache": "hit",
        "X-CostPilot-Budget-Warning": "team research at 12% remaining",
        "X-CostPilot-Model-Routed": "gpt-4o -> gpt-4o-mini; reason=price",
        "X-CostPilot-Model-Downgraded": "gpt-4o -> gpt-4o-mini; reason=policy",
    }
    cp = sync_client(json_handler(COMPLETION, headers=headers))
    r = cp.chat.completions.create(model="gpt-4o-mini", messages=[{"role": "user", "content": "hi"}])

    assert r.content == "hello there"
    assert r.choices[0].finish_reason == "stop"
    assert r.usage.total_tokens == 7
    assert r.governance.cache_hit is True
    assert "12% remaining" in r.governance.budget_warning
    assert "price" in r.governance.model_routed
    assert "policy" in r.governance.model_downgraded
    # raw preserves the untouched body
    assert r.raw["id"] == "chatcmpl-abc"


def test_no_governance_headers_defaults_to_false_none():
    cp = sync_client(json_handler(COMPLETION))
    r = cp.chat.completions.create(model="gpt-4o-mini", messages=[{"role": "user", "content": "hi"}])
    assert r.governance.cache_hit is False
    assert r.governance.budget_warning is None
    assert r.governance.model_routed is None


def test_request_shape_auth_body_and_url():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["method"] = request.method
        seen["headers"] = request.headers
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=COMPLETION)

    cp = sync_client(handler)
    cp.chat.completions.create(
        model="gpt-4o-mini",
        messages=[{"role": "user", "content": "hi"}],
        max_tokens=128,
        temperature=0.2,
    )

    # full /v1 path preserved (no base_url join surprise)
    assert seen["url"] == f"{BASE}/chat/completions"
    assert seen["method"] == "POST"
    assert seen["headers"]["authorization"] == f"Bearer {KEY}"
    assert seen["headers"]["content-type"] == "application/json"
    body = seen["body"]
    assert body["model"] == "gpt-4o-mini"
    assert body["messages"] == [{"role": "user", "content": "hi"}]
    assert body["max_tokens"] == 128
    assert body["temperature"] == 0.2
    assert body["stream"] is False


def test_default_and_per_call_attribution():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["headers"] = request.headers
        return httpx.Response(200, json=COMPLETION)

    cp = sync_client(handler, team="research", project="default-proj")
    cp.chat.completions.create(
        model="gpt-4o-mini",
        messages=[{"role": "user", "content": "hi"}],
        project="override-proj",       # per-call beats client default
        idempotency_key="job-42",
        min_tier=2,
    )
    h = seen["headers"]
    assert h["x-team-id"] == "research"           # client default
    assert h["x-project-id"] == "override-proj"   # per-call override
    assert h["idempotency-key"] == "job-42"
    assert h["x-costpilot-min-tier"] == "2"


def test_approval_202_raises_with_pending_details():
    parked = {
        "id": "pending-123",
        "object": "approval.pending",
        "state": "PENDING",
        "model": "gpt-4o",
        "reason": "estimated cost over approval threshold",
        "expires_at": "2026-07-22T00:00:00Z",
    }
    cp = sync_client(json_handler(parked, status=202))
    with pytest.raises(ApprovalRequiredError) as ei:
        cp.chat.completions.create(model="gpt-4o", messages=[{"role": "user", "content": "hi"}])
    err = ei.value
    assert err.pending_id == "pending-123"
    assert err.state == "PENDING"
    assert err.expires_at == "2026-07-22T00:00:00Z"


def test_missing_api_key_raises_config_error(monkeypatch):
    monkeypatch.delenv("COSTPILOT_API_KEY", raising=False)
    with pytest.raises(CostPilotConfigError):
        CostPilot(base_url=BASE, api_key=None)


def test_env_config_fallback(monkeypatch):
    monkeypatch.setenv("COSTPILOT_API_KEY", "cp_from_env")
    monkeypatch.setenv("COSTPILOT_BASE_URL", "http://env-host:9000/v1/")

    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers["authorization"]
        return httpx.Response(200, json=COMPLETION)

    cp = CostPilot(transport=httpx.MockTransport(handler))
    cp.chat.completions.create(model="gpt-4o-mini", messages=[{"role": "user", "content": "hi"}])
    # trailing slash trimmed, path preserved
    assert seen["url"] == "http://env-host:9000/v1/chat/completions"
    assert seen["auth"] == "Bearer cp_from_env"

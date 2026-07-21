"""Error-envelope mapping: each status -> its typed exception with governance context."""

from __future__ import annotations

import pytest

from costpilot import (
    APIError,
    AuthError,
    BudgetExceededError,
    PolicyDeniedError,
)
from _helpers import json_handler, sync_client


def _err(message, type_, code=None):
    body = {"error": {"message": message, "type": type_}}
    if code is not None:
        body["error"]["code"] = code
    return body


def test_401_unauthorized():
    cp = sync_client(json_handler(_err("bad key", "unauthorized"), status=401))
    with pytest.raises(AuthError) as ei:
        cp.chat.completions.create(model="m", messages=[])
    assert ei.value.status_code == 401
    assert "bad key" in ei.value.message


def test_402_budget_exceeded_exposes_scope():
    cp = sync_client(json_handler(_err("team budget exhausted", "budget_exceeded", "team"), status=402))
    with pytest.raises(BudgetExceededError) as ei:
        cp.chat.completions.create(model="m", messages=[])
    assert ei.value.status_code == 402
    assert ei.value.scope == "team"
    assert ei.value.code == "team"


def test_403_policy_denied_exposes_rule_id():
    cp = sync_client(json_handler(_err("model not allowed", "policy_denied", "rule-9"), status=403))
    with pytest.raises(PolicyDeniedError) as ei:
        cp.chat.completions.create(model="m", messages=[])
    assert ei.value.status_code == 403
    assert ei.value.rule_id == "rule-9"


def test_400_and_500_fall_back_to_apierror():
    cp = sync_client(json_handler(_err("bad body", "invalid_request_error"), status=400))
    with pytest.raises(APIError) as ei:
        cp.chat.completions.create(model="m", messages=[])
    assert ei.value.status_code == 400
    assert ei.value.type == "invalid_request_error"
    # a more specific subclass must NOT swallow this
    assert not isinstance(ei.value, (AuthError, BudgetExceededError, PolicyDeniedError))

    cp2 = sync_client(json_handler({"oops": True}, status=500))
    with pytest.raises(APIError) as ei2:
        cp2.chat.completions.create(model="m", messages=[])
    assert ei2.value.status_code == 500


def test_non_json_error_body_still_raises_with_text():
    import httpx

    def handler(request):
        return httpx.Response(502, text="upstream boom")

    cp = sync_client(handler)
    with pytest.raises(APIError) as ei:
        cp.chat.completions.create(model="m", messages=[])
    assert ei.value.status_code == 502
    assert "boom" in str(ei.value)

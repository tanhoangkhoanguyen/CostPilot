"""Pure helpers shared by the sync and async clients.

Everything here is transport-agnostic: config resolution, header/body assembly,
and turning a non-2xx response into the right typed exception. Keeping it out of
the client classes means the sync and async paths can't drift apart.
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict, List, Mapping, Optional

from ._version import __version__
from .errors import (
    APIError,
    ApprovalRequiredError,
    AuthError,
    BudgetExceededError,
    CostPilotConfigError,
    PolicyDeniedError,
)

DEFAULT_BASE_URL = "http://localhost:8080/v1"
DEFAULT_TIMEOUT = 60.0


def resolve_base_url(base_url: Optional[str]) -> str:
    url = base_url or os.environ.get("COSTPILOT_BASE_URL") or DEFAULT_BASE_URL
    return url.rstrip("/")


def resolve_api_key(api_key: Optional[str]) -> str:
    key = api_key or os.environ.get("COSTPILOT_API_KEY")
    if not key:
        raise CostPilotConfigError(
            "no API key: pass api_key=... or set COSTPILOT_API_KEY "
            "(a CostPilot cp_ key, not the upstream provider key)"
        )
    return key


def build_headers(
    *,
    api_key: str,
    default_team: Optional[str],
    default_project: Optional[str],
    team: Optional[str] = None,
    project: Optional[str] = None,
    idempotency_key: Optional[str] = None,
    min_tier: Optional[int] = None,
    stream: bool = False,
) -> Dict[str, str]:
    """Assemble request headers, per-call values overriding client defaults."""
    headers: Dict[str, str] = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "Accept": "text/event-stream" if stream else "application/json",
        "User-Agent": f"costpilot-python/{__version__}",
    }
    eff_team = team if team is not None else default_team
    eff_project = project if project is not None else default_project
    if eff_team is not None:
        headers["X-Team-ID"] = eff_team
    if eff_project is not None:
        headers["X-Project-ID"] = eff_project
    if idempotency_key is not None:
        headers["Idempotency-Key"] = idempotency_key
    if min_tier is not None:
        headers["X-CostPilot-Min-Tier"] = str(min_tier)
    return headers


# control kwargs consumed by the SDK as headers, never forwarded in the JSON body
_CONTROL_KEYS = frozenset(
    {"team", "project", "idempotency_key", "min_tier", "extra_body"}
)

# sampling params we pass through explicitly when present (documentation value);
# anything else the caller supplies still rides along via **params.
_KNOWN_BODY_KEYS = (
    "temperature",
    "top_p",
    "stop",
    "presence_penalty",
    "frequency_penalty",
    "n",
    "seed",
    "response_format",
    "tools",
    "tool_choice",
    "user",
)


def build_body(
    *,
    model: str,
    messages: List[Mapping[str, Any]],
    max_tokens: Optional[int],
    stream: bool,
    extra_body: Optional[Mapping[str, Any]],
    params: Mapping[str, Any],
) -> Dict[str, Any]:
    """Assemble the OpenAI-shaped chat completions payload."""
    body: Dict[str, Any] = {"model": model, "messages": list(messages), "stream": stream}
    if max_tokens is not None:
        body["max_tokens"] = max_tokens
    for key, value in params.items():
        if key in _CONTROL_KEYS:
            continue
        if value is not None:
            body[key] = value
    if extra_body:
        body.update(extra_body)
    return body


def _parse_envelope(status_code: int, body_text: str) -> Dict[str, Optional[str]]:
    """Best-effort extraction of {message, type, code} from an error response."""
    message = body_text.strip() or f"HTTP {status_code}"
    err_type: Optional[str] = None
    code: Optional[str] = None
    try:
        parsed = json.loads(body_text)
        if isinstance(parsed, dict):
            err = parsed.get("error")
            if isinstance(err, dict):
                message = err.get("message") or message
                err_type = err.get("type")
                code = err.get("code")
    except (json.JSONDecodeError, ValueError):
        pass
    return {"message": message, "type": err_type, "code": code}


def raise_for_status(status_code: int, body_text: str) -> None:
    """Map a non-2xx response to the right typed exception.

    401 -> AuthError, 402 -> BudgetExceededError(scope), 403 ->
    PolicyDeniedError(rule_id), everything else -> APIError.
    """
    env = _parse_envelope(status_code, body_text)
    message = env["message"] or f"HTTP {status_code}"
    kwargs = dict(
        status_code=status_code,
        type=env["type"],
        code=env["code"],
        response_body=body_text,
    )
    if status_code == 401:
        raise AuthError(message, **kwargs)
    if status_code == 402:
        raise BudgetExceededError(message, **kwargs)
    if status_code == 403:
        raise PolicyDeniedError(message, **kwargs)
    raise APIError(message, **kwargs)


def approval_from_body(body_text: str) -> ApprovalRequiredError:
    """Build the parked-for-approval signal from a 202 body."""
    data: Dict[str, Any] = {}
    try:
        parsed = json.loads(body_text)
        if isinstance(parsed, dict):
            data = parsed
    except (json.JSONDecodeError, ValueError):
        pass
    reason = data.get("reason") or "request parked for human approval"
    return ApprovalRequiredError(
        f"approval required: {reason}",
        pending_id=data.get("id"),
        state=data.get("state"),
        model=data.get("model"),
        reason=data.get("reason"),
        expires_at=data.get("expires_at"),
        response_body=body_text,
    )

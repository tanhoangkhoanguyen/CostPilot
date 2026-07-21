"""Typed exceptions mapping CostPilot's governance responses.

CostPilot answers with an OpenAI-style error envelope: ``{"error": {"message",
"type", "code"}}``. The gateway overloads ``code`` with governance context - the
budget *scope* on a 402, the matched policy *rule id* on a 403 - so each status
gets its own exception that surfaces that context as a named attribute instead of
forcing callers to re-parse JSON.
"""

from __future__ import annotations

from typing import Any, Optional


class CostPilotError(Exception):
    """Base class for every error this SDK raises."""


class CostPilotConfigError(CostPilotError):
    """Client was constructed without a usable base_url / api_key."""


class APIError(CostPilotError):
    """A non-2xx response that isn't a more specific governance verdict.

    ``type`` and ``code`` come straight from the error envelope; ``status_code``
    is the HTTP status. Governance subclasses (401/402/403) refine this.
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: int,
        type: Optional[str] = None,
        code: Optional[str] = None,
        response_body: Any = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.type = type
        self.code = code
        self.response_body = response_body

    def __str__(self) -> str:  # pragma: no cover - trivial
        parts = [f"HTTP {self.status_code}"]
        if self.type:
            parts.append(self.type)
        parts.append(self.message)
        return " ".join(parts)


class AuthError(APIError):
    """401 - missing, unknown, or revoked ``cp_`` key."""


class BudgetExceededError(APIError):
    """402 - a governed budget blocked the request before it was forwarded.

    ``scope`` is the budget scope that ran out (tenant / team / project / model);
    it mirrors the envelope ``code``.
    """

    @property
    def scope(self) -> Optional[str]:
        return self.code


class PolicyDeniedError(APIError):
    """403 - policy denied the model/request.

    ``rule_id`` is the policy rule that matched (envelope ``code``).
    """

    @property
    def rule_id(self) -> Optional[str]:
        return self.code


class ApprovalRequiredError(CostPilotError):
    """202 - the request was parked for human approval, not forwarded.

    Not a transport failure: the gateway accepted the request but a policy needs
    a human decision first. The completion never arrives on this call; poll the
    admin approvals API with ``pending_id`` and retry once approved.
    """

    def __init__(
        self,
        message: str,
        *,
        pending_id: Optional[str] = None,
        state: Optional[str] = None,
        model: Optional[str] = None,
        reason: Optional[str] = None,
        expires_at: Optional[str] = None,
        response_body: Any = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.pending_id = pending_id
        self.state = state
        self.model = model
        self.reason = reason
        self.expires_at = expires_at
        self.response_body = response_body

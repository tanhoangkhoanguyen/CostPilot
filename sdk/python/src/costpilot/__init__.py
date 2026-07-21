"""CostPilot - a governance-first Python client for the CostPilot gateway.

CostPilot speaks the OpenAI API, so you *can* point any OpenAI client at it. This
SDK adds what a raw client can't: the gateway's runtime governance verdict as
typed data - cache hits, budget warnings, model routing/downgrades, mid-stream
budget cut-offs, and typed exceptions for budget/policy/approval outcomes.

    from costpilot import CostPilot

    cp = CostPilot(base_url="http://localhost:8080/v1", api_key="cp_...")
    r = cp.chat.completions.create(
        model="gpt-4o-mini",
        messages=[{"role": "user", "content": "hello"}],
    )
    print(r.content)
    if r.governance.budget_warning:
        print("heads up:", r.governance.budget_warning)
"""

from __future__ import annotations

from ._async import AsyncCostPilot, AsyncStreamResponse
from ._client import CostPilot, StreamResponse
from ._models import ChatCompletion, Choice, Governance, Message, StreamChunk, Usage
from ._version import __version__
from .errors import (
    APIError,
    ApprovalRequiredError,
    AuthError,
    BudgetExceededError,
    CostPilotConfigError,
    CostPilotError,
    PolicyDeniedError,
)

__all__ = [
    "CostPilot",
    "AsyncCostPilot",
    "StreamResponse",
    "AsyncStreamResponse",
    "ChatCompletion",
    "Choice",
    "Message",
    "Usage",
    "Governance",
    "StreamChunk",
    "CostPilotError",
    "CostPilotConfigError",
    "APIError",
    "AuthError",
    "BudgetExceededError",
    "PolicyDeniedError",
    "ApprovalRequiredError",
    "__version__",
]

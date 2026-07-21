"""Parsed response types.

Deliberately tolerant: the gateway speaks the OpenAI dialect, but the SDK never
assumes every field is present (mock upstream, provider variance, cut-off
streams). Unknown fields are preserved on ``raw`` so nothing is lost.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


# CostPilot response headers that carry governance decisions.
_H_CACHE = "X-CostPilot-Cache"
_H_BUDGET_WARNING = "X-CostPilot-Budget-Warning"
_H_MODEL_ROUTED = "X-CostPilot-Model-Routed"
_H_MODEL_DOWNGRADED = "X-CostPilot-Model-Downgraded"


@dataclass
class Governance:
    """The governance verdict for one request, read from response headers.

    This is the whole reason the SDK exists: the gateway may serve from cache at
    $0, warn that a budget is nearly spent, or quietly route/downgrade the model.
    Each of those is a header; here they're typed attributes.
    """

    cache_hit: bool = False
    budget_warning: Optional[str] = None
    model_routed: Optional[str] = None
    model_downgraded: Optional[str] = None
    headers: Dict[str, str] = field(default_factory=dict)

    @classmethod
    def from_headers(cls, headers: Mapping[str, str]) -> "Governance":
        # httpx.Headers is case-insensitive; fall back to a plain dict lookup too.
        def get(name: str) -> Optional[str]:
            try:
                return headers.get(name)
            except Exception:  # pragma: no cover - defensive
                return None

        cp = {k: v for k, v in headers.items() if k.lower().startswith("x-costpilot")}
        return cls(
            cache_hit=(get(_H_CACHE) or "").lower() == "hit",
            budget_warning=get(_H_BUDGET_WARNING),
            model_routed=get(_H_MODEL_ROUTED),
            model_downgraded=get(_H_MODEL_DOWNGRADED),
            headers=cp,
        )


@dataclass
class Message:
    role: Optional[str] = None
    content: Optional[str] = None

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "Message":
        return cls(role=d.get("role"), content=d.get("content"))


@dataclass
class Choice:
    index: Optional[int] = None
    message: Optional[Message] = None
    finish_reason: Optional[str] = None

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "Choice":
        msg = d.get("message")
        return cls(
            index=d.get("index"),
            message=Message.from_dict(msg) if isinstance(msg, Mapping) else None,
            finish_reason=d.get("finish_reason"),
        )


@dataclass
class Usage:
    prompt_tokens: Optional[int] = None
    completion_tokens: Optional[int] = None
    total_tokens: Optional[int] = None

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "Usage":
        return cls(
            prompt_tokens=d.get("prompt_tokens"),
            completion_tokens=d.get("completion_tokens"),
            total_tokens=d.get("total_tokens"),
        )


@dataclass
class ChatCompletion:
    id: Optional[str] = None
    model: Optional[str] = None
    choices: List[Choice] = field(default_factory=list)
    usage: Optional[Usage] = None
    governance: Governance = field(default_factory=Governance)
    raw: Dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_response(cls, body: Mapping[str, Any], headers: Mapping[str, str]) -> "ChatCompletion":
        choices = [Choice.from_dict(c) for c in body.get("choices", []) if isinstance(c, Mapping)]
        usage = body.get("usage")
        return cls(
            id=body.get("id"),
            model=body.get("model"),
            choices=choices,
            usage=Usage.from_dict(usage) if isinstance(usage, Mapping) else None,
            governance=Governance.from_headers(headers),
            raw=dict(body),
        )

    @property
    def content(self) -> Optional[str]:
        """Convenience: text of the first choice's message, or None."""
        if self.choices and self.choices[0].message:
            return self.choices[0].message.content
        return None


@dataclass
class StreamChunk:
    """One SSE delta from a streaming completion."""

    id: Optional[str] = None
    model: Optional[str] = None
    delta: Optional[str] = None
    finish_reason: Optional[str] = None
    raw: Dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> "StreamChunk":
        delta_text: Optional[str] = None
        finish: Optional[str] = None
        choices = d.get("choices")
        if isinstance(choices, list) and choices:
            first = choices[0]
            if isinstance(first, Mapping):
                delta_obj = first.get("delta")
                if isinstance(delta_obj, Mapping):
                    delta_text = delta_obj.get("content")
                finish = first.get("finish_reason")
        return cls(
            id=d.get("id"),
            model=d.get("model"),
            delta=delta_text,
            finish_reason=finish,
            raw=dict(d),
        )

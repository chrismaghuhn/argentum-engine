"""Small model-facing shape predicates shared by the derived reader."""

from __future__ import annotations

from typing import Any


def require_model_input(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("model input must be an object")
    expected = {"decisionContext", "observation", "domain"}
    if set(value) != expected:
        raise ValueError("model input has an unsupported wrapper shape")
    return value

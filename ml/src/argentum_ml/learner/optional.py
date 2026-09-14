"""Lazy optional dependency loading for local learner tooling."""

from __future__ import annotations

from importlib import import_module
from types import ModuleType


class LearnerToolingError(RuntimeError):
    """Base error for optional learner tooling."""


class LearnerToolingUnavailable(LearnerToolingError):
    """Raised when an optional learner package cannot be imported."""


def require_optional_module(import_name: str, package_name: str) -> ModuleType:
    """Load one learner dependency or fail without a replacement implementation."""
    try:
        return import_module(import_name)
    except (ImportError, ModuleNotFoundError) as exc:
        raise LearnerToolingUnavailable(
            f"TOOLING_UNAVAILABLE: install argentum-ml[learner] for {package_name}"
        ) from exc

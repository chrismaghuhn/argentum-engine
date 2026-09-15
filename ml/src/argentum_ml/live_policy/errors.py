"""Typed fail-closed errors for the local live-policy runtime."""

from __future__ import annotations


class LivePolicyRuntimeError(RuntimeError):
    """Base error; callers must close/fail the ML seat rather than substitute an action."""

    def __init__(self, message: str, *, code: str = "LIVE_POLICY_RUNTIME_FAILURE") -> None:
        super().__init__(message)
        self.code = code


class LivePolicyProtocolError(LivePolicyRuntimeError, ValueError):
    """Malformed, version-incompatible, or semantically mismatched wire data."""


class LivePolicyCheckpointError(LivePolicyRuntimeError, ValueError):
    """A checkpoint, manifest, weight artifact, or model profile is not authoritative."""


class LivePolicyStartupError(LivePolicyRuntimeError):
    """The worker could not establish a healthy checkpoint-backed runtime."""


class LivePolicyInferenceError(LivePolicyRuntimeError):
    """The provider or ordinal-selection step failed closed."""


class LivePolicyTimeoutError(LivePolicyRuntimeError):
    """The worker did not answer within the bounded inference deadline."""


class LivePolicyWorkerCrashedError(LivePolicyRuntimeError):
    """The worker exited or closed its output before returning a valid response."""

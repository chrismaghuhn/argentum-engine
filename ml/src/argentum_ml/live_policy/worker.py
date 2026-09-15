"""Entrypoint for the long-lived local C1_07B policy worker."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

from .contracts import (
    LivePolicyRequestEnvelopeV1,
    require_shutdown_envelope,
)
from .engine import LivePolicyDecisionEngine
from .errors import LivePolicyRuntimeError
from .profile import C1_07B_POLICY_PROFILE
from .protocol import FramedProtocolError, read_frame, write_frame
from .provider import C1_06LiveScoreProvider


def run_worker(checkpoint_dir: Path | str) -> int:
    profile = C1_07B_POLICY_PROFILE
    stdout = sys.stdout.buffer
    stdin = sys.stdin.buffer
    try:
        provider = C1_06LiveScoreProvider.from_checkpoint(profile, checkpoint_dir)
        engine = LivePolicyDecisionEngine(provider, profile)
    except LivePolicyRuntimeError as exc:
        _send_error(profile, stdout, exc, phase="startup")
        return 1
    except Exception as exc:
        _send_error(
            profile,
            stdout,
            LivePolicyRuntimeError(
                "worker startup failed closed",
                code="WORKER_STARTUP_FAILURE",
            ),
            phase="startup",
        )
        del exc
        return 1

    try:
        write_frame(stdout, profile.health_envelope())
    except FramedProtocolError:
        return 2

    while True:
        try:
            frame = read_frame(stdin)
        except FramedProtocolError as exc:
            _send_error(profile, stdout, exc, phase="protocol")
            return 2
        if frame is None:
            return 0
        try:
            message_type = frame.get("messageType")
            if message_type == "REQUEST":
                envelope = LivePolicyRequestEnvelopeV1.from_dict(frame, profile)
                response = engine.decide(envelope.request)
                write_frame(
                    stdout,
                    profile.response_envelope(
                        request_id=envelope.request.request_id,
                        response=response.to_dict(),
                        scored_candidate_count=envelope.request.present_candidate_count,
                    ),
                )
            elif message_type == "SHUTDOWN":
                require_shutdown_envelope(frame, profile)
                write_frame(stdout, profile.shutdown_ack_envelope())
                return 0
            else:
                raise LivePolicyRuntimeError(
                    "worker received an unsupported message type",
                    code="PROTOCOL_MESSAGE_INVALID",
                )
        except LivePolicyRuntimeError as exc:
            _send_error(
                profile,
                stdout,
                exc,
                phase="inference" if message_type == "REQUEST" else "protocol",
                request_id=_safe_request_id(frame),
            )
        except Exception:
            _send_error(
                profile,
                stdout,
                LivePolicyRuntimeError(
                    "worker operation failed closed",
                    code="WORKER_OPERATION_FAILURE",
                ),
                phase="inference" if message_type == "REQUEST" else "protocol",
                request_id=_safe_request_id(frame),
            )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Argentum C1_07B local policy worker")
    parser.add_argument("--checkpoint-dir", required=True)
    args = parser.parse_args(argv)
    return run_worker(Path(args.checkpoint_dir))


def _send_error(
    profile: Any,
    stdout: Any,
    error: Exception,
    *,
    phase: str,
    request_id: str | None = None,
) -> None:
    code = getattr(error, "code", "WORKER_FAILURE")
    message = str(error) or "worker failure"
    try:
        write_frame(
            stdout,
            profile.error_envelope(
                code=code,
                phase=phase,
                message=message,
                request_id=request_id,
            ),
        )
    except FramedProtocolError:
        pass


def _safe_request_id(frame: dict[str, Any]) -> str | None:
    value = frame.get("requestId")
    return value if isinstance(value, str) and value else None


if __name__ == "__main__":
    raise SystemExit(main())

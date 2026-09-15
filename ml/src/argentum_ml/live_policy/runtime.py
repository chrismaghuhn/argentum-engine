"""Parent-side lifecycle and correlation adapter for the local Python worker."""

from __future__ import annotations

import queue
import subprocess
import sys
import threading
from pathlib import Path
from typing import Any, Sequence

from .contracts import (
    LivePolicyDecisionRequestV1,
    LivePolicyDecisionResponseV1,
    LivePolicyResponseEnvelopeV1,
    build_decision_envelope,
    parse_error_envelope,
    require_health_envelope,
    require_shutdown_ack_envelope,
)
from .errors import (
    LivePolicyProtocolError,
    LivePolicyRuntimeError,
    LivePolicyStartupError,
    LivePolicyTimeoutError,
    LivePolicyWorkerCrashedError,
)
from .profile import C1_07B_POLICY_PROFILE, C1_07BPolicyProfile
from .protocol import FramedProtocolError, read_frame, write_frame


WORKER_MODULE = "argentum_ml.live_policy.worker"


class LocalPythonPolicyRuntime:
    """One serialized, checkpoint-backed local worker with bounded lifecycle operations."""

    def __init__(
        self,
        process: subprocess.Popen[bytes],
        profile: C1_07BPolicyProfile,
        *,
        startup_timeout_seconds: float,
        inference_timeout_seconds: float,
    ) -> None:
        self._process = process
        self._profile = profile
        self._startup_timeout_seconds = startup_timeout_seconds
        self._inference_timeout_seconds = inference_timeout_seconds
        self._closed = False
        self._lock = threading.RLock()
        self._events: queue.Queue[tuple[str, Any]] = queue.Queue()
        if process.stdin is None or process.stdout is None:
            raise LivePolicyStartupError("worker did not expose binary stdin/stdout", code="WORKER_STDIO_INVALID")
        self._stdin = process.stdin
        self._stdout = process.stdout
        self._reader = threading.Thread(target=self._read_loop, name="argentum-live-policy-reader", daemon=True)
        self._reader.start()

    @classmethod
    def start(
        cls,
        checkpoint_dir: Path | str,
        *,
        profile: C1_07BPolicyProfile | None = None,
        startup_timeout_seconds: float = 30.0,
        inference_timeout_seconds: float = 5.0,
        _worker_command: Sequence[str] | None = None,
    ) -> "LocalPythonPolicyRuntime":
        selected_profile = profile or C1_07B_POLICY_PROFILE
        if not isinstance(selected_profile, C1_07BPolicyProfile):
            raise LivePolicyStartupError("runtime requires the server-owned C1_07B profile", code="PROFILE_INVALID")
        if startup_timeout_seconds <= 0 or inference_timeout_seconds <= 0:
            raise LivePolicyStartupError("runtime timeouts must be positive", code="RUNTIME_TIMEOUT_INVALID")
        command = list(_worker_command) if _worker_command is not None else [
            sys.executable,
            "-m",
            WORKER_MODULE,
            "--checkpoint-dir",
            str(checkpoint_dir),
        ]
        if not command or any(not isinstance(item, str) or not item for item in command):
            raise LivePolicyStartupError("worker command is invalid", code="WORKER_COMMAND_INVALID")
        try:
            process = subprocess.Popen(
                command,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                bufsize=0,
            )
        except OSError as exc:
            raise LivePolicyStartupError("local Python worker could not be started", code="WORKER_START_FAILURE") from exc
        runtime: LocalPythonPolicyRuntime | None = None
        try:
            runtime = cls(
                process,
                selected_profile,
                startup_timeout_seconds=startup_timeout_seconds,
                inference_timeout_seconds=inference_timeout_seconds,
            )
            runtime._await_health()
            return runtime
        except LivePolicyRuntimeError:
            if runtime is not None:
                runtime._abort()
            else:
                _terminate_process(process)
            raise
        except Exception as exc:
            if runtime is not None:
                runtime._abort()
            else:
                _terminate_process(process)
            raise LivePolicyStartupError("worker health handshake failed", code="HEALTH_HANDSHAKE_INVALID") from exc

    def decide(
        self,
        request: LivePolicyDecisionRequestV1 | dict[str, Any],
    ) -> LivePolicyDecisionResponseV1:
        with self._lock:
            self._require_open()
            if self._process.poll() is not None:
                raise LivePolicyWorkerCrashedError("local Python worker exited before inference", code="WORKER_CRASH")
            parsed_request = (
                request
                if isinstance(request, LivePolicyDecisionRequestV1)
                else LivePolicyDecisionRequestV1.from_dict(request)
            )
            try:
                write_frame(self._stdin, build_decision_envelope(self._profile, parsed_request))
            except FramedProtocolError as exc:
                self._abort()
                raise LivePolicyWorkerCrashedError("worker request write failed", code="WORKER_WRITE_FAILURE") from exc
            event, value = self._next_event(self._inference_timeout_seconds)
            if event == "eof":
                self._abort()
                raise LivePolicyWorkerCrashedError("local Python worker closed its output", code="WORKER_CRASH")
            if event == "error":
                self._abort()
                if isinstance(value, FramedProtocolError):
                    raise LivePolicyProtocolError("worker returned an invalid frame", code="FRAME_PROTOCOL_FAILURE") from value
                raise LivePolicyWorkerCrashedError("local Python worker reader failed", code="WORKER_READ_FAILURE") from value
            if value.get("messageType") == "ERROR":
                try:
                    code, error_request_id = parse_error_envelope(value, self._profile)
                except LivePolicyProtocolError:
                    self._abort()
                    raise
                if error_request_id is not None and error_request_id != parsed_request.request_id:
                    self._abort()
                    raise LivePolicyProtocolError("worker error response has the wrong requestId", code="REQUEST_ID_MISMATCH")
                raise LivePolicyRuntimeError("worker rejected the live inference request", code=code)
            try:
                return LivePolicyResponseEnvelopeV1.from_dict(value, self._profile, parsed_request).response
            except LivePolicyProtocolError:
                self._abort()
                raise
            except Exception as exc:
                self._abort()
                raise LivePolicyProtocolError("worker response envelope is invalid", code="RESPONSE_INVALID") from exc

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            if self._process.poll() is None:
                try:
                    write_frame(self._stdin, self._profile.shutdown_envelope())
                    event, value = self._next_event(2.0)
                    if event == "frame":
                        require_shutdown_ack_envelope(value, self._profile)
                except (FramedProtocolError, LivePolicyRuntimeError):
                    pass
                finally:
                    _terminate_process(self._process)
            try:
                self._stdin.close()
            except OSError:
                pass
            try:
                self._stdout.close()
            except OSError:
                pass
            self._reader.join(timeout=1.0)

    def __enter__(self) -> "LocalPythonPolicyRuntime":
        return self

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        self.close()

    def _await_health(self) -> None:
        event, value = self._next_event(self._startup_timeout_seconds)
        if event == "eof":
            raise LivePolicyStartupError("worker exited before health handshake", code="WORKER_STARTUP_FAILURE")
        if event == "error":
            raise LivePolicyStartupError("worker health frame could not be read", code="HEALTH_HANDSHAKE_INVALID") from value
        if value.get("messageType") == "ERROR":
            code, _ = parse_error_envelope(value, self._profile)
            raise LivePolicyStartupError("worker startup was rejected", code=code)
        try:
            require_health_envelope(value, self._profile)
        except LivePolicyProtocolError as exc:
            raise LivePolicyStartupError("worker health handshake was invalid", code="HEALTH_HANDSHAKE_INVALID") from exc

    def _read_loop(self) -> None:
        try:
            while True:
                frame = read_frame(self._stdout)
                if frame is None:
                    self._events.put(("eof", None))
                    return
                self._events.put(("frame", frame))
        except Exception as exc:
            self._events.put(("error", exc))

    def _next_event(self, timeout: float) -> tuple[str, Any]:
        try:
            event, value = self._events.get(timeout=timeout)
        except queue.Empty as exc:
            self._abort()
            raise LivePolicyTimeoutError(
                "local Python worker exceeded the inference timeout",
                code="WORKER_TIMEOUT",
            ) from exc
        return event, value

    def _require_open(self) -> None:
        if self._closed:
            raise LivePolicyWorkerCrashedError("local Python worker is closed", code="WORKER_CLOSED")

    def _abort(self) -> None:
        _terminate_process(self._process)
        self._closed = True
        try:
            self._stdin.close()
        except OSError:
            pass
        try:
            self._stdout.close()
        except OSError:
            pass
        self._reader.join(timeout=1.0)


def _terminate_process(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        process.terminate()
        process.wait(timeout=1.0)
    except (OSError, subprocess.TimeoutExpired):
        try:
            process.kill()
            process.wait(timeout=1.0)
        except (OSError, subprocess.TimeoutExpired):
            pass

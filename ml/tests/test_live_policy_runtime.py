from __future__ import annotations

import io
import json
import math
import os
import shutil
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from typing import Any, Sequence


from argentum_ml.contracts.identities import POLICY_TIE_RNG_IDENTITY
from argentum_ml.checkpoint import ArgentumCheckpointManifestV1
from argentum_ml.live_policy import (
    C1_07B_POLICY_PROFILE,
    C1_06LiveScoreProvider,
    FramedProtocolError,
    LivePolicyDecisionEngine,
    LivePolicyDecisionRequestV1,
    LivePolicyCheckpointError,
    LivePolicyInferenceError,
    LivePolicyProtocolError,
    LivePolicyResponseEnvelopeV1,
    LivePolicyRequestEnvelopeV1,
    LivePolicyStartupError,
    LivePolicyTimeoutError,
    LivePolicyWorkerCrashedError,
    LocalPythonPolicyRuntime,
    decode_frame,
    encode_frame,
    read_frame,
    write_frame,
)
from argentum_ml.selection.policy_tie_rng import PolicyTieRngStateV1


ACCEPTED_CHECKPOINT_DIR = Path(
    os.environ.get(
        "ARGENTUM_C1_06_CHECKPOINT_DIR",
        Path(tempfile.gettempdir())
        / "argentum-c1-06-gpu-smoke-943338abbaf47f289cfe606acd50caf0a2b15ef5",
    )
)


def _request(
    *,
    discriminators: dict[str, str] | None = None,
    cursor: int = 0,
) -> LivePolicyDecisionRequestV1:
    candidates = [
        {"kind": "candidate", "affordable": True, "slot": "a"},
        {"kind": "candidate", "affordable": True, "slot": "b"},
    ]
    return LivePolicyDecisionRequestV1.from_dict(
        {
            "version": 1,
            "schemaIdentity": "argentum-ml-live-policy-decision-request@v1",
            "requestId": "request-1",
            "observationDigest": "a" * 64,
            "candidateDomainDigest": {
                "version": 1,
                "schemaIdentity": "argentum-gym-candidate-domain-digest@v1",
                "value": "b" * 64,
            },
            "modelInput": {
                "decisionContext": {"domainKind": "ACTION_CANDIDATES"},
                "observation": {"players": [], "zones": [], "stack": []},
                "domain": {"kind": "ACTION_CANDIDATES", "candidates": candidates},
            },
            "candidateFeatureViews": candidates,
            "selectionBindingChannel": {
                "version": 1,
                "schemaIdentity": "argentum-ml-live-selection-address@v1",
                "sourceBindingOrdinals": [0, 1],
                "presentMask": [True, True],
                "executableSupportMask": [True, True],
                "semanticTieDiscriminators": discriminators or {},
            },
            "policyRngState": {
                "streamKeyHex": "0" * 64,
                "cursor": cursor,
            },
        }
    )


class _StubProvider:
    checkpoint_id = C1_07B_POLICY_PROFILE.expected_checkpoint_id
    numeric_profile_class = C1_07B_POLICY_PROFILE.expected_numeric_profile_class

    def __init__(self, scores: Sequence[float]) -> None:
        self.scores = tuple(scores)

    def score(self, model_input: Any, candidates: Sequence[Any]) -> Sequence[float]:
        return self.scores


def _test_worker_command(mode: str) -> list[str]:
    script = textwrap.dedent(
        f"""
        import sys
        import time
        from argentum_ml.live_policy.protocol import read_frame, write_frame
        from argentum_ml.live_policy.profile import C1_07B_POLICY_PROFILE

        stdin = sys.stdin.buffer
        stdout = sys.stdout.buffer
        write_frame(stdout, C1_07B_POLICY_PROFILE.health_envelope())
        frame = read_frame(stdin)
        if {mode!r} == "crash":
            raise SystemExit(7)
        if {mode!r} == "timeout":
            time.sleep(60)
        request = frame["request"]
        request_id = frame["requestId"]
        if {mode!r} == "wrong-request-id":
            request_id = "wrong-request-id"
        response = {{
            "version": 1,
            "schemaIdentity": "argentum-ml-live-policy-decision-response@v1",
            "requestId": frame["requestId"],
            "selectedSourceBindingOrdinal": 0,
            "policyRngState": request["policyRngState"],
            "rngCursorBefore": request["policyRngState"]["cursor"],
            "rngCursorAfter": request["policyRngState"]["cursor"],
            "rngDrawCount": 0,
        }}
        output = C1_07B_POLICY_PROFILE.response_envelope(
            request_id=request_id,
            response=response,
            scored_candidate_count=2,
        )
        write_frame(stdout, output)
        shutdown = read_frame(stdin)
        if shutdown and shutdown.get("messageType") == "SHUTDOWN":
            write_frame(stdout, C1_07B_POLICY_PROFILE.shutdown_ack_envelope())
        """
    )
    return [sys.executable, "-c", script]


class LivePolicyRuntimeContractTests(unittest.TestCase):
    def test_reference_profile_binds_accepted_c1_06_checkpoint_and_wire_identities(self) -> None:
        profile = C1_07B_POLICY_PROFILE
        self.assertEqual(
            profile.profile_identity,
            "argentum-mtg-ml-akiri-vs-engine-chevill@v1",
        )
        self.assertEqual(
            profile.expected_checkpoint_id,
            "f4b191d99734af66a5643c988ce3c2f2198a2957be24e2a717287006a43124c5",
        )
        self.assertEqual(
            profile.expected_model_architecture_identity,
            "argentum-ml-c1-06-feed-forward-candidate-scorer@v1",
        )
        self.assertEqual(
            profile.expected_numeric_profile_class,
            "C1_REFERENCE_NUMERIC_PROFILE",
        )
        envelope = profile.health_envelope()
        self.assertEqual(envelope["messageType"], "HEALTH")
        self.assertEqual(envelope["selectionAddressContractIdentity"], "argentum-ml-live-selection-address@v1")
        self.assertEqual(envelope["policyRngContractIdentity"], POLICY_TIE_RNG_IDENTITY)

    def test_request_round_trip_is_inner_semantic_payload_without_binding_digest(self) -> None:
        request = _request()
        encoded = request.to_dict()
        self.assertNotIn("bindingDigest", encoded)
        self.assertNotIn("exactSourceBinding", str(encoded))
        self.assertEqual(
            LivePolicyDecisionRequestV1.from_dict(encoded),
            request,
        )

    def test_request_owns_a_copy_of_validated_model_input(self) -> None:
        encoded = _request().to_dict()
        request = LivePolicyDecisionRequestV1.from_dict(encoded)
        encoded["modelInput"]["observation"]["mutatedAfterValidation"] = True
        self.assertNotIn("mutatedAfterValidation", request.model_input["observation"])

    def test_request_constructor_is_parser_only(self) -> None:
        with self.assertRaisesRegex(TypeError, "must be parsed from a versioned payload"):
            LivePolicyDecisionRequestV1()

    def test_request_rejects_binding_digest_and_raw_exact_binding(self) -> None:
        encoded = _request().to_dict()
        encoded["bindingDigest"] = "c" * 64
        with self.assertRaises(LivePolicyProtocolError):
            LivePolicyDecisionRequestV1.from_dict(encoded)

        encoded = _request().to_dict()
        encoded["candidateFeatureViews"][0]["binding"] = {"action": "raw"}
        with self.assertRaises(LivePolicyProtocolError):
            LivePolicyDecisionRequestV1.from_dict(encoded)

    def test_request_rejects_unknown_protocol_version(self) -> None:
        encoded = C1_07B_POLICY_PROFILE.health_envelope()
        encoded["protocolVersion"] = 2
        with self.assertRaises(LivePolicyProtocolError):
            from argentum_ml.live_policy import require_health_envelope

            require_health_envelope(encoded, C1_07B_POLICY_PROFILE)

        request_envelope = {
            "protocolVersion": 2,
            "messageType": "REQUEST",
            **C1_07B_POLICY_PROFILE.envelope_fields(),
            "requestId": "request-1",
            "request": _request().to_dict(),
        }
        with self.assertRaises(LivePolicyProtocolError):
            LivePolicyRequestEnvelopeV1.from_dict(request_envelope, C1_07B_POLICY_PROFILE)

    def test_request_rejects_wrong_checkpoint_contract_or_numeric_profile(self) -> None:
        for field in (
            "checkpointId",
            "inferenceContractIdentity",
            "selectionContractIdentity",
            "numericProfileClass",
        ):
            envelope = {
                "protocolVersion": 1,
                "messageType": "REQUEST",
                **C1_07B_POLICY_PROFILE.envelope_fields(),
                "requestId": "request-1",
                "request": _request().to_dict(),
            }
            envelope[field] = "wrong@v1"
            with self.assertRaises(LivePolicyProtocolError):
                LivePolicyRequestEnvelopeV1.from_dict(envelope, C1_07B_POLICY_PROFILE)

    def test_framing_round_trip_and_partial_frame_rejection(self) -> None:
        payload = {"messageType": "HEALTH", "status": "READY"}
        framed = encode_frame(payload)
        self.assertEqual(decode_frame(framed), payload)
        stream = io.BytesIO(framed)
        self.assertEqual(read_frame(stream), payload)
        with self.assertRaises(FramedProtocolError):
            read_frame(io.BytesIO(framed[:-1]))
        with self.assertRaises(FramedProtocolError):
            read_frame(io.BytesIO(framed[:2]))

        output = io.BytesIO()
        write_frame(output, payload)
        self.assertEqual(decode_frame(output.getvalue()), payload)

    def test_ordinal_engine_preserves_unique_semantic_and_rng_tie_contracts(self) -> None:
        engine = LivePolicyDecisionEngine(_StubProvider([2.0, 1.0]), C1_07B_POLICY_PROFILE)
        unique = engine.decide(_request())
        self.assertEqual(unique.selected_source_binding_ordinal, 0)
        self.assertEqual(unique.rng_cursor_before, 0)
        self.assertEqual(unique.rng_cursor_after, 0)

        semantic = LivePolicyDecisionEngine(_StubProvider([1.0, 1.0]), C1_07B_POLICY_PROFILE).decide(
            _request(discriminators={"0": '{"semantic":"a"}', "1": '{"semantic":"b"}'})
        )
        self.assertEqual(semantic.selected_source_binding_ordinal, 0)
        self.assertEqual(semantic.rng_cursor_after, 0)

        rng = LivePolicyDecisionEngine(_StubProvider([1.0, 1.0]), C1_07B_POLICY_PROFILE).decide(
            _request()
        )
        self.assertIn(rng.selected_source_binding_ordinal, {0, 1})
        self.assertEqual(rng.rng_cursor_before, 0)
        self.assertEqual(rng.rng_cursor_after - rng.rng_cursor_before, rng.rng_draw_count)

    def test_response_rejects_policy_rng_stream_mismatch(self) -> None:
        request = _request()
        response = C1_07B_POLICY_PROFILE.response_envelope(
            request_id=request.request_id,
            response={
                "version": 1,
                "schemaIdentity": "argentum-ml-live-policy-decision-response@v1",
                "requestId": request.request_id,
                "selectedSourceBindingOrdinal": 0,
                "policyRngState": {"streamKeyHex": "1" * 64, "cursor": 0},
                "rngCursorBefore": 0,
                "rngCursorAfter": 0,
                "rngDrawCount": 0,
            },
            scored_candidate_count=2,
        )
        with self.assertRaises(LivePolicyProtocolError):
            LivePolicyResponseEnvelopeV1.from_dict(response, C1_07B_POLICY_PROFILE, request)

    def test_ordinal_engine_rejects_rng_cursor_exhaustion(self) -> None:
        from argentum_ml.selection.policy_tie_rng import UINT64_MAX

        with self.assertRaises(LivePolicyInferenceError):
            LivePolicyDecisionEngine(_StubProvider([1.0, 1.0]), C1_07B_POLICY_PROFILE).decide(
                _request(cursor=UINT64_MAX)
            )

    def test_engine_rejects_wrong_count_and_nonfinite_scores(self) -> None:
        with self.assertRaises(LivePolicyProtocolError):
            LivePolicyDecisionEngine(_StubProvider([1.0]), C1_07B_POLICY_PROFILE).decide(_request())
        with self.assertRaises(LivePolicyProtocolError):
            LivePolicyDecisionEngine(_StubProvider([math.nan, 1.0]), C1_07B_POLICY_PROFILE).decide(
                _request()
            )

    def test_runtime_process_rejects_wrong_request_id(self) -> None:
        runtime = LocalPythonPolicyRuntime.start(
            Path("unused-test-artifact"),
            _worker_command=_test_worker_command("wrong-request-id"),
        )
        try:
            with self.assertRaises(LivePolicyProtocolError):
                runtime.decide(_request())
        finally:
            runtime.close()

    def test_runtime_worker_crash_fails_closed(self) -> None:
        runtime = LocalPythonPolicyRuntime.start(
            Path("unused-test-artifact"),
            _worker_command=_test_worker_command("crash"),
        )
        with self.assertRaises(LivePolicyWorkerCrashedError):
            runtime.decide(_request())
        runtime.close()

    def test_runtime_timeout_fails_closed(self) -> None:
        runtime = LocalPythonPolicyRuntime.start(
            Path("unused-test-artifact"),
            inference_timeout_seconds=0.1,
            _worker_command=_test_worker_command("timeout"),
        )
        with self.assertRaises(LivePolicyTimeoutError):
            runtime.decide(_request())
        runtime.close()

    @unittest.skipUnless(
        ACCEPTED_CHECKPOINT_DIR.is_dir(),
        "accepted C1_06 checkpoint artifact is not provisioned on this host",
    )
    def test_profile_rejects_wrong_checkpoint_and_weight_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copyfile(ACCEPTED_CHECKPOINT_DIR / "manifest.json", root / "manifest.json")
            shutil.copyfile(ACCEPTED_CHECKPOINT_DIR / "weights.safetensors", root / "weights.safetensors")
            manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
            manifest["checkpointId"] = "0" * 64
            (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(LivePolicyCheckpointError):
                C1_07B_POLICY_PROFILE.validate_checkpoint_artifact(root)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copyfile(ACCEPTED_CHECKPOINT_DIR / "manifest.json", root / "manifest.json")
            corrupted = (ACCEPTED_CHECKPOINT_DIR / "weights.safetensors").read_bytes() + b"corrupt"
            (root / "weights.safetensors").write_bytes(corrupted)
            with self.assertRaises(LivePolicyCheckpointError):
                C1_07B_POLICY_PROFILE.validate_checkpoint_artifact(root)

    @unittest.skipUnless(
        ACCEPTED_CHECKPOINT_DIR.is_dir(),
        "accepted C1_06 checkpoint artifact is not provisioned on this host",
    )
    def test_profile_rejects_architecture_and_config_drift(self) -> None:
        manifest = ArgentumCheckpointManifestV1.from_path(ACCEPTED_CHECKPOINT_DIR / "manifest.json")
        source = manifest.to_dict()
        for key, value in (
            ("modelArchitectureIdentity", "wrong-architecture@v1"),
            ("modelConfigDigest", "1" * 64),
        ):
            changed = dict(source)
            changed[key] = value
            changed["checkpointId"] = manifest.recompute_checkpoint_id(changed)
            changed_manifest = ArgentumCheckpointManifestV1.from_dict(changed)
            with self.assertRaises(LivePolicyCheckpointError):
                C1_07B_POLICY_PROFILE.require_manifest_identity(changed_manifest)

    @unittest.skipUnless(
        ACCEPTED_CHECKPOINT_DIR.is_dir(),
        "accepted C1_06 checkpoint artifact is not provisioned on this host",
    )
    def test_worker_startup_rejects_wrong_checkpoint_before_health(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = json.loads((ACCEPTED_CHECKPOINT_DIR / "manifest.json").read_text(encoding="utf-8"))
            manifest["checkpointId"] = "0" * 64
            (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            shutil.copyfile(ACCEPTED_CHECKPOINT_DIR / "weights.safetensors", root / "weights.safetensors")
            with self.assertRaises(LivePolicyStartupError):
                LocalPythonPolicyRuntime.start(root, startup_timeout_seconds=5.0)

    @unittest.skipUnless(
        ACCEPTED_CHECKPOINT_DIR.is_dir(),
        "accepted C1_06 checkpoint artifact is not provisioned on this host",
    )
    def test_runtime_starts_with_accepted_checkpoint_and_round_trips(self) -> None:
        try:
            import torch
        except ImportError:
            self.skipTest("PyTorch is not installed")
        if not torch.cuda.is_available():
            self.skipTest("CUDA is unavailable on this host")
        with LocalPythonPolicyRuntime.start(ACCEPTED_CHECKPOINT_DIR) as runtime:
            response = runtime.decide(_request())
        self.assertIn(response.selected_source_binding_ordinal, {0, 1})
        self.assertEqual(response.request_id, "request-1")


class C1_06LiveScoreProviderTests(unittest.TestCase):
    def test_cuda_gate_rejects_unavailable_runtime_without_cpu_fallback(self) -> None:
        class FakeCuda:
            def is_available(self) -> bool:
                return False

            def device_count(self) -> int:
                return 0

        class FakeTorch:
            cuda = FakeCuda()

            def device(self, name: str) -> str:
                self.fail(f"CPU fallback or device construction should not happen: {name}")

        with self.assertRaises(LivePolicyCheckpointError):
            C1_06LiveScoreProvider.require_cuda(FakeTorch())


if __name__ == "__main__":
    unittest.main()

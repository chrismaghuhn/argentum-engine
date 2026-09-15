from __future__ import annotations

import io
import inspect
import json
import math
import os
import shutil
import sys
import tempfile
import textwrap
import unittest
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Sequence
from unittest.mock import patch


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
        {
            "kind": "candidate",
            "affordable": True,
            "targetEntityAliases": [],
            "manaCost": None,
            "hasXCost": False,
            "maxAffordableX": None,
            "minTargets": 0,
            "maxTargets": 0,
            "validSacrificeTargetsAliases": [],
            "sacrificeCount": 0,
            "sacrificeMinCount": 0,
            "sacrificeMaxCount": 0,
            "requiresDamageDistribution": False,
            "isManaAbility": False,
            "requiresStructuredAction": False,
            "requiredPayloadFields": [],
            "actionSemantics": {"type": "PlayLand"},
            "isDecisionOption": False,
        },
        {
            "kind": "candidate",
            "affordable": True,
            "targetEntityAliases": [],
            "manaCost": None,
            "hasXCost": False,
            "maxAffordableX": None,
            "minTargets": 0,
            "maxTargets": 0,
            "validSacrificeTargetsAliases": [],
            "sacrificeCount": 0,
            "sacrificeMinCount": 0,
            "sacrificeMaxCount": 0,
            "requiresDamageDistribution": False,
            "isManaAbility": False,
            "requiresStructuredAction": False,
            "requiredPayloadFields": [],
            "actionSemantics": {"type": "PlayLand"},
            "isDecisionOption": False,
        },
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
                "decisionContext": {
                    "domainKind": "ACTION_CANDIDATES",
                    "turnNumber": 1,
                    "phase": "PRECOMBAT_MAIN",
                    "step": "PRECOMBAT_MAIN",
                    "agentToActRole": "SELF",
                    "activePlayerRole": "SELF",
                    "priorityPlayerRole": "SELF",
                },
                "observation": {
                    "turnNumber": 1,
                    "phase": "PRECOMBAT_MAIN",
                    "step": "PRECOMBAT_MAIN",
                    "players": [],
                    "zones": [],
                    "stack": [],
                },
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
        if {mode!r} == "crash-before-request":
            raise SystemExit(8)
        frame = read_frame(stdin)
        if {mode!r} == "crash":
            raise SystemExit(7)
        if {mode!r} == "timeout":
            time.sleep(60)
        request = frame["request"]
        if {mode!r} == "inference-error-no-request-id":
            write_frame(stdout, C1_07B_POLICY_PROFILE.error_envelope(
                code="SCORE_PROVIDER_FAILURE",
                phase="inference",
                message="score provider failed",
            ))
            raise SystemExit(1)
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


@contextmanager
def _patched_test_worker(mode: str):
    import argentum_ml.live_policy.runtime as runtime_module

    real_popen = runtime_module.subprocess.Popen

    def start_test_worker(command: Sequence[str], **kwargs: Any):
        expected = [
            sys.executable,
            "-m",
            runtime_module.WORKER_MODULE,
            "--checkpoint-dir",
            "unused-test-artifact",
        ]
        if list(command) != expected:
            raise AssertionError(f"production worker command drifted: {list(command)!r}")
        return real_popen(_test_worker_command(mode), **kwargs)

    with patch.object(runtime_module.subprocess, "Popen", start_test_worker):
        yield


class LivePolicyRuntimeContractTests(unittest.TestCase):
    def test_runtime_start_has_no_caller_worker_override(self) -> None:
        self.assertNotIn("_worker_command", inspect.signature(LocalPythonPolicyRuntime.start).parameters)

    def test_runtime_start_uses_only_the_fixed_worker_command(self) -> None:
        with _patched_test_worker("round-trip"):
            runtime = LocalPythonPolicyRuntime.start(Path("unused-test-artifact"))
        try:
            response = runtime.decide(_request())
            self.assertEqual(response.selected_source_binding_ordinal, 0)
        finally:
            runtime.close()

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

    def test_request_nested_model_data_is_immutable(self) -> None:
        request = _request()
        with self.assertRaises(TypeError):
            request.model_input["observation"]["mutated"] = True
        with self.assertRaises(TypeError):
            request.candidate_feature_views[0]["mutated"] = True
        with self.assertRaises(TypeError):
            request.candidate_domain_digest["value"] = "c" * 64

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

        for field, value in (
            ("id", "raw-id"),
            ("manaAbilityKey", "raw-ability"),
            ("target", "raw-target"),
            ("editableBy", "raw-player"),
            ("selectedExactSourceBinding", {"action": "raw"}),
        ):
            encoded = _request().to_dict()
            encoded["candidateFeatureViews"][0][field] = value
            encoded["modelInput"]["domain"]["candidates"][0][field] = value
            with self.assertRaises(LivePolicyProtocolError):
                LivePolicyDecisionRequestV1.from_dict(encoded)

    def test_request_rejects_an_alias_not_declared_by_the_observation(self) -> None:
        encoded = _request().to_dict()
        encoded["candidateFeatureViews"][0]["sourceAlias"] = "entity-999"
        encoded["modelInput"]["domain"]["candidates"][0]["sourceAlias"] = "entity-999"
        with self.assertRaises(LivePolicyProtocolError):
            LivePolicyDecisionRequestV1.from_dict(encoded)

        encoded = _request().to_dict()
        fabricated_target = {"type": "Permanent", "entityAlias": "entity-999"}
        action_semantics = {"type": "CastSpell", "targetsAliases": [fabricated_target]}
        encoded["candidateFeatureViews"][0]["actionSemantics"] = action_semantics
        encoded["modelInput"]["domain"]["candidates"][0]["actionSemantics"] = action_semantics
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
        with _patched_test_worker("wrong-request-id"):
            runtime = LocalPythonPolicyRuntime.start(Path("unused-test-artifact"))
        try:
            with self.assertRaises(LivePolicyProtocolError):
                runtime.decide(_request())
        finally:
            runtime.close()

    def test_runtime_worker_crash_fails_closed(self) -> None:
        with _patched_test_worker("crash"):
            runtime = LocalPythonPolicyRuntime.start(Path("unused-test-artifact"))
        with self.assertRaises(LivePolicyWorkerCrashedError):
            runtime.decide(_request())
        self.assertTrue(runtime._closed)
        self.assertIsNotNone(runtime._process.poll())
        runtime.close()

    def test_runtime_pre_inference_crash_closes_before_request_write(self) -> None:
        with _patched_test_worker("crash-before-request"):
            runtime = LocalPythonPolicyRuntime.start(Path("unused-test-artifact"))
        runtime._process.wait(timeout=2.0)
        with self.assertRaises(LivePolicyWorkerCrashedError):
            runtime.decide(_request())
        self.assertTrue(runtime._closed)
        runtime.close()

    def test_inference_error_requires_request_id(self) -> None:
        with _patched_test_worker("inference-error-no-request-id"):
            runtime = LocalPythonPolicyRuntime.start(Path("unused-test-artifact"))
        with self.assertRaises(LivePolicyProtocolError):
            runtime.decide(_request())
        self.assertTrue(runtime._closed)
        runtime.close()

    def test_runtime_timeout_fails_closed(self) -> None:
        with _patched_test_worker("timeout"):
            runtime = LocalPythonPolicyRuntime.start(
                Path("unused-test-artifact"),
                inference_timeout_seconds=0.1,
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

    @unittest.skipUnless(
        ACCEPTED_CHECKPOINT_DIR.is_dir(),
        "accepted C1_06 checkpoint artifact is not provisioned on this host",
    )
    def test_provider_loads_the_verified_snapshot_after_path_replacement(self) -> None:
        try:
            import torch
        except ImportError:
            self.skipTest("PyTorch is not installed")
        if not torch.cuda.is_available():
            self.skipTest("CUDA is unavailable on this host")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copyfile(ACCEPTED_CHECKPOINT_DIR / "manifest.json", root / "manifest.json")
            shutil.copyfile(ACCEPTED_CHECKPOINT_DIR / "weights.safetensors", root / "weights.safetensors")
            original_validate = C1_07B_POLICY_PROFILE.validate_checkpoint_artifact

            def validate_then_replace(profile: Any, checkpoint_dir: Path | str):
                artifact = original_validate(checkpoint_dir)
                Path(checkpoint_dir, "manifest.json").write_bytes(b"replacement-manifest")
                Path(checkpoint_dir, "weights.safetensors").write_bytes(b"replacement-weights")
                return artifact

            with patch.object(
                type(C1_07B_POLICY_PROFILE),
                "validate_checkpoint_artifact",
                validate_then_replace,
            ):
                provider = C1_06LiveScoreProvider.from_checkpoint(
                    C1_07B_POLICY_PROFILE,
                    root,
                )
            self.assertEqual(provider.checkpoint_id, C1_07B_POLICY_PROFILE.expected_checkpoint_id)


class C1_06LiveScoreProviderTests(unittest.TestCase):
    def test_reference_numeric_profile_is_configured_and_verified(self) -> None:
        profile = C1_07B_POLICY_PROFILE.numeric_execution_profile

        class FakeDevice:
            type = "cuda"
            index = 0

            def __str__(self) -> str:
                return "cuda:0"

        class FakeCuda:
            def __init__(self) -> None:
                self._available = True
                self._device_count = 1

            def is_available(self) -> bool:
                return self._available

            def device_count(self) -> int:
                return self._device_count

            def get_device_capability(self, index: int) -> tuple[int, int]:
                return (8, 9)

        class FakeBackends:
            class cuda:
                class matmul:
                    allow_tf32 = True

            class cudnn:
                allow_tf32 = True

        class FakeVersion:
            cuda = "12.0"

        class FakeTorch:
            __version__ = "2.14.0+cu130"
            cuda = FakeCuda()
            backends = FakeBackends()
            version = FakeVersion()
            deterministic = False
            autocast = True
            precision = "high"
            default_dtype = "torch.float32"

            @staticmethod
            def device(name: str) -> FakeDevice:
                return FakeDevice()

            @classmethod
            def use_deterministic_algorithms(cls, enabled: bool) -> None:
                cls.deterministic = enabled

            @classmethod
            def are_deterministic_algorithms_enabled(cls) -> bool:
                return cls.deterministic

            @classmethod
            def set_autocast_enabled(cls, device_type: str, enabled: bool) -> None:
                cls.autocast = enabled

            @classmethod
            def is_autocast_enabled(cls, device_type: str) -> bool:
                return cls.autocast

            @classmethod
            def set_float32_matmul_precision(cls, value: str) -> None:
                cls.precision = value

            @classmethod
            def get_float32_matmul_precision(cls) -> str:
                return cls.precision

            @classmethod
            def get_default_dtype(cls) -> str:
                return cls.default_dtype

        FakeTorch.version.cuda = "13.0"
        device = FakeTorch.device("cuda:0")
        profile.configure_and_validate(FakeTorch, device)
        self.assertTrue(FakeTorch.deterministic)
        self.assertFalse(FakeTorch.autocast)
        self.assertEqual(FakeTorch.precision, "highest")
        self.assertFalse(FakeTorch.backends.cuda.matmul.allow_tf32)
        self.assertTrue(FakeTorch.backends.cudnn.allow_tf32)

    def test_reference_numeric_profile_rejects_wrong_framework_runtime(self) -> None:
        profile = C1_07B_POLICY_PROFILE.numeric_execution_profile

        class WrongTorch:
            __version__ = "2.14.0+cpu"

        with self.assertRaises(ValueError):
            profile.configure_and_validate(WrongTorch, object())

    def test_provider_rejects_raw_model_surface_before_model_access(self) -> None:
        provider = object.__new__(C1_06LiveScoreProvider)
        payload = _request().to_dict()
        payload["modelInput"]["domain"]["candidates"][0]["id"] = "raw-id"
        payload["candidateFeatureViews"][0]["id"] = "raw-id"
        with self.assertRaises(LivePolicyInferenceError):
            provider.score(
                payload["modelInput"],
                payload["candidateFeatureViews"],
            )

    def test_provider_normalizes_non_string_model_keys_to_typed_failure(self) -> None:
        provider = object.__new__(C1_06LiveScoreProvider)
        payload = _request().to_dict()
        payload["modelInput"]["observation"][1] = "malformed"
        with self.assertRaises(LivePolicyInferenceError):
            provider.score(
                payload["modelInput"],
                payload["candidateFeatureViews"],
            )

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

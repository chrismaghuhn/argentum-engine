import math
import unittest
from pathlib import Path

from argentum_ml.checkpoint import (
    ArgentumCheckpointManifestV1,
    NumericExecutionProfileIdentity,
)
from argentum_ml.contracts.identities import (
    NUMERIC_PROFILE_CONTRACT_IDENTITY,
    POLICY_TIE_RNG_IDENTITY,
    SELECTION_V2_IDENTITY,
)
from argentum_ml.data.variable_batch import CandidateFeature, VariableDomainItem
from argentum_ml.inference import (
    C1_00_STRUCTURED_INFERENCE_TOTALITY,
    InferenceContext,
    InferenceError,
    InferenceRuntime,
    SourceSelectionBindings,
)
from argentum_ml.selection import PolicyTieRngStateV1


def _rng() -> PolicyTieRngStateV1:
    return PolicyTieRngStateV1.from_policy_seed(
        4259905,
        0,
        policy_rng_identity=POLICY_TIE_RNG_IDENTITY,
    )


def _context() -> InferenceContext:
    manifest = ArgentumCheckpointManifestV1.from_path(
        Path(__file__).parent / "fixtures" / "checkpoint_manifest_v1.json"
    )
    return InferenceContext.from_checkpoint(
        manifest,
        NumericExecutionProfileIdentity(
            NUMERIC_PROFILE_CONTRACT_IDENTITY,
            "C1_REFERENCE_NUMERIC_PROFILE",
        ),
    )


def _flat_item() -> VariableDomainItem:
    candidates = tuple(
        CandidateFeature(
            feature_view={"kind": f"candidate-{ordinal}"},
            source_binding_ordinal=ordinal,
            present=True,
            executable_support=ordinal != 2,
        )
        for ordinal in range(3)
    )
    return VariableDomainItem(
        model_input={
            "decisionContext": {"domainKind": "ACTION_CANDIDATES"},
            "observation": {"phase": "MAIN"},
            "domain": {
                "kind": "ACTION_CANDIDATES",
                "candidates": [candidate.feature_view for candidate in candidates],
            },
        },
        candidates=candidates,
        structured_domain=None,
        target_binding_ordinal=0,
    )


def _source_bindings() -> SourceSelectionBindings:
    channel = {
        "completeLegalDomain": {
            "kind": "ACTION_CANDIDATES",
            "candidates": [
                {
                    "kind": f"candidate-{ordinal}",
                    "requiredPayloadFields": [],
                    "actionSemantics": {"type": "PassPriority"},
                }
                for ordinal in range(3)
            ],
        },
        "sourceBindingOrdinals": [0, 1, 2],
        "semanticTieDiscriminators": {
            "0": '{"semantic":"a"}',
            "1": '{"semantic":"b"}',
            "2": '{"semantic":"c"}',
        },
        "entityAliasBindings": [
            {"alias": "entity-0", "sourceEntityId": "raw-a"},
            {"alias": "entity-1", "sourceEntityId": "raw-b"},
        ],
    }
    return SourceSelectionBindings.from_derived_binding_channel(channel)


class _RecordingProvider:
    def __init__(self, scores: list[float]) -> None:
        self.scores = scores
        self.model_input = None
        self.candidates = None

    def score(self, model_input, candidates):
        self.model_input = model_input
        self.candidates = candidates
        return self.scores


class _FailIfCalledProvider:
    def score(self, model_input, candidates):
        raise AssertionError("structured inference must fail before provider access")


class InferenceRuntimeTests(unittest.TestCase):
    def test_scores_every_present_candidate_and_returns_exact_binding(self) -> None:
        provider = _RecordingProvider([0.2, 0.9, 99.0])
        result = InferenceRuntime(_context()).select(
            _flat_item(),
            _source_bindings(),
            provider,
            _rng(),
        )

        self.assertEqual(len(provider.candidates), 3)
        self.assertEqual([view["kind"] for view in provider.candidates], ["candidate-0", "candidate-1", "candidate-2"])
        self.assertEqual(
            result.exact_source_binding.exact_action["candidate"]["kind"],
            "candidate-1",
        )
        self.assertEqual(result.audit_source_binding_ordinal, 1)
        self.assertEqual(result.rng_draw_count, 0)
        with self.assertRaises(TypeError):
            provider.model_input["domain"] = {}
        with self.assertRaises(TypeError):
            provider.candidates[0]["kind"] = "mutated"

    def test_provider_arguments_have_no_target_or_provenance_channels(self) -> None:
        provider = _RecordingProvider([0.1, 0.2, 0.3])
        InferenceRuntime(_context()).select(_flat_item(), _source_bindings(), provider, _rng())

        forbidden = {
            "target", "provenance", "sourceReference", "binding", "gameState", "rawAction",
            "actionId", "decisionId", "abilityId", "envId", "policySeed", "outcome",
        }

        def keys(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    yield key
                    yield from keys(child)
            elif isinstance(value, (list, tuple)):
                for child in value:
                    yield from keys(child)

        self.assertTrue(forbidden.isdisjoint(set(keys(provider.model_input))))
        self.assertTrue(forbidden.isdisjoint(set(keys(provider.candidates))))

    def test_unaffordable_candidate_can_score_but_cannot_be_selected(self) -> None:
        provider = _RecordingProvider([0.1, 0.2, 100.0])
        result = InferenceRuntime(_context()).select(_flat_item(), _source_bindings(), provider, _rng())
        self.assertEqual(
            result.exact_source_binding.exact_action["candidate"]["kind"],
            "candidate-1",
        )

    def test_missing_or_extra_scores_fail_closed(self) -> None:
        for scores in ([0.1, 0.2], [0.1, 0.2, 0.3, 0.4]):
            with self.subTest(scores=scores):
                with self.assertRaises(InferenceError):
                    InferenceRuntime(_context()).select(
                        _flat_item(),
                        _source_bindings(),
                        _RecordingProvider(scores),
                        _rng(),
                    )

    def test_non_finite_score_fails_before_selection(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with self.subTest(value=value):
                with self.assertRaises(InferenceError):
                    InferenceRuntime(_context()).select(
                        _flat_item(),
                        _source_bindings(),
                        _RecordingProvider([0.1, value, 0.3]),
                        _rng(),
                    )

    def test_structured_inference_fails_closed_before_provider_or_rng(self) -> None:
        structured = {"type": "targets", "version": 2, "requirements": []}
        item = VariableDomainItem(
            model_input={
                "decisionContext": {},
                "observation": {},
                "domain": {
                    "kind": "STRUCTURED_DECISION",
                    "structuredType": structured,
                },
            },
            candidates=(),
            structured_domain=structured,
            target_binding_ordinal=None,
        )
        rng = _rng()
        with self.assertRaises(InferenceError):
            InferenceRuntime(_context()).select(
                item,
                _source_bindings(),
                _FailIfCalledProvider(),
                rng,
            )
        self.assertEqual(C1_00_STRUCTURED_INFERENCE_TOTALITY, "NO")
        self.assertEqual(rng.cursor, 0)

    def test_context_requires_numeric_profile_and_selection_pair(self) -> None:
        with self.assertRaises(TypeError):
            InferenceContext("not-a-profile", SELECTION_V2_IDENTITY, POLICY_TIE_RNG_IDENTITY)
        with self.assertRaises(InferenceError):
            InferenceRuntime(None)
        with self.assertRaises(InferenceError):
            InferenceContext.from_checkpoint(
                ArgentumCheckpointManifestV1.from_path(
                    Path(__file__).parent / "fixtures" / "checkpoint_manifest_v1.json"
                ),
                NumericExecutionProfileIdentity(
                    NUMERIC_PROFILE_CONTRACT_IDENTITY,
                    "OTHER_PROFILE",
                ),
            )

    def test_source_binding_is_derived_from_the_authoritative_candidate(self) -> None:
        channel = {
            "completeLegalDomain": {
                "kind": "ACTION_CANDIDATES",
                "candidates": [
                    {"kind": "candidate-a", "requiredPayloadFields": []},
                    {"kind": "candidate-b", "requiredPayloadFields": []},
                ],
            },
            "sourceBindingOrdinals": [0, 1],
            "semanticTieDiscriminators": {},
            "entityAliasBindings": [],
        }
        bindings = SourceSelectionBindings.from_derived_binding_channel(channel)
        self.assertEqual(
            bindings.exact_binding_for(0).exact_action["candidate"]["kind"],
            "candidate-a",
        )
        self.assertEqual(
            bindings.exact_binding_for(1).exact_action["candidate"]["kind"],
            "candidate-b",
        )

    def test_action_candidates_requiring_payload_fail_closed_before_provider(self) -> None:
        channel = {
            "completeLegalDomain": {
                "kind": "ACTION_CANDIDATES",
                "candidates": [{"kind": "targeted", "requiredPayloadFields": ["targets"]}],
            },
            "sourceBindingOrdinals": [0],
            "semanticTieDiscriminators": {},
            "entityAliasBindings": [],
        }
        with self.assertRaises(InferenceError):
            SourceSelectionBindings.from_derived_binding_channel(channel)

    def test_source_binding_ordinals_cannot_reorder_authoritative_candidates(self) -> None:
        channel = {
            "completeLegalDomain": {
                "kind": "ACTION_CANDIDATES",
                "candidates": [
                    {"kind": "candidate-a", "requiredPayloadFields": []},
                    {"kind": "candidate-b", "requiredPayloadFields": []},
                ],
            },
            "sourceBindingOrdinals": [1, 0],
            "semanticTieDiscriminators": {},
            "entityAliasBindings": [],
        }
        with self.assertRaises(InferenceError):
            SourceSelectionBindings.from_derived_binding_channel(channel)


if __name__ == "__main__":
    unittest.main()

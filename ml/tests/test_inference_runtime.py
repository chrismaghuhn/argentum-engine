import math
import copy
import tempfile
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
    InferenceRequest,
    InferenceRuntime,
    SourceSelectionBindings,
)
from argentum_ml.selection import PolicyTieRngStateV1
from argentum_ml.data.derived_reader import DerivedArtifactReader
from tests.test_derived_reader import _artifact, _sample, _structured_sample


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


def _item_from_validated_sample(sample, *, prefix: str = "candidate-") -> VariableDomainItem:
    model_input = sample.sample["input"]
    features = model_input["domain"]["candidates"]
    source_candidates = sample.sample["binding"]["completeLegalDomain"]["candidates"]
    candidates = tuple(
        CandidateFeature(
            feature_view=feature,
            source_binding_ordinal=ordinal,
            present=True,
            executable_support=source_candidates[ordinal]["affordable"],
        )
        for ordinal, feature in enumerate(features)
    )
    return VariableDomainItem(
        model_input=model_input,
        candidates=candidates,
        structured_domain=None,
        target_binding_ordinal=0,
    )


def _validated_sample(
    root: Path,
    *,
    prefix: str = "candidate-",
    unaffordable_ordinal: int | None = None,
    source_kind_override: str | None = None,
):
    sample = _sample()
    source_candidates = []
    model_candidates = []
    base_source_candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
    base_model_candidate = sample["input"]["domain"]["candidates"][0]
    for ordinal in range(3):
        source_candidate = copy.deepcopy(base_source_candidate)
        source_candidate["kind"] = (
            source_kind_override
            if ordinal == 0 and source_kind_override is not None
            else f"{prefix}{ordinal}"
        )
        source_candidate["affordable"] = ordinal != unaffordable_ordinal
        source_candidates.append(source_candidate)
        model_candidate = copy.deepcopy(base_model_candidate)
        model_candidate["kind"] = f"{prefix}{ordinal}"
        model_candidate["affordable"] = ordinal != unaffordable_ordinal
        model_candidates.append(model_candidate)
    sample["binding"]["completeLegalDomain"]["candidates"] = source_candidates
    sample["binding"]["sourceBindingOrdinals"] = [0, 1, 2]
    sample["binding"]["semanticTieDiscriminators"] = {
        "0": '{"semantic":"a"}',
        "1": '{"semantic":"b"}',
        "2": '{"semantic":"c"}',
    }
    sample["input"]["domain"]["candidates"] = model_candidates
    chosen = {"candidate": source_candidates[0], "choicePayload": {}, "type": "chosen-action"}
    sample["binding"]["selectedExactSourceBinding"] = chosen
    sample["target"] = {"chosenSemanticAction": chosen, "chosenSemanticResponse": None}
    artifact = _artifact(root, sample=sample)
    reader = DerivedArtifactReader.open(artifact)
    stream = reader.iter_validated_samples_for_inference()
    validated = next(stream)
    stream.close()
    return validated


def _request(
    root: Path,
    *,
    prefix: str = "candidate-",
    unaffordable_ordinal: int | None = None,
) -> InferenceRequest:
    validated = _validated_sample(
        root,
        prefix=prefix,
        unaffordable_ordinal=unaffordable_ordinal,
    )
    return InferenceRequest.from_validated_sample(validated, _item_from_validated_sample(validated))


def _structured_request(root: Path) -> InferenceRequest:
    sample = _structured_sample()
    artifact = _artifact(root, sample=sample)
    reader = DerivedArtifactReader.open(artifact)
    stream = reader.iter_validated_samples_for_inference()
    validated = next(stream)
    stream.close()
    structured_type = validated.sample["input"]["domain"]["structuredType"]
    item = VariableDomainItem(
        model_input=validated.sample["input"],
        candidates=(),
        structured_domain=structured_type,
        target_binding_ordinal=None,
    )
    return InferenceRequest.from_validated_sample(validated, item)


def _item_with_mask(
    sample,
    ordinal: int,
    *,
    present: bool,
    executable_support: bool,
) -> VariableDomainItem:
    base = _item_from_validated_sample(sample)
    candidates = list(base.candidates)
    original = candidates[ordinal]
    candidates[ordinal] = CandidateFeature(
        feature_view=original.feature_view,
        source_binding_ordinal=original.source_binding_ordinal,
        present=present,
        executable_support=executable_support,
    )
    return VariableDomainItem(
        model_input=base.model_input,
        candidates=tuple(candidates),
        structured_domain=None,
        target_binding_ordinal=1 if ordinal == 0 and not present else 0,
    )


class _RecordingProvider:
    def __init__(self, scores: list[float]) -> None:
        self.scores = scores
        self.checkpoint_id = _context().checkpoint_id
        self.numeric_profile_class = "C1_REFERENCE_NUMERIC_PROFILE"
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
        provider = _RecordingProvider([0.2, 0.9, 0.3])
        with tempfile.TemporaryDirectory() as directory:
            result = InferenceRuntime(_context()).select(
                _request(Path(directory)),
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
        with tempfile.TemporaryDirectory() as directory:
            InferenceRuntime(_context()).select(_request(Path(directory)), provider, _rng())

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
        with tempfile.TemporaryDirectory() as directory:
            result = InferenceRuntime(_context()).select(
                _request(Path(directory), unaffordable_ordinal=2),
                provider,
                _rng(),
            )
        self.assertEqual(
            result.exact_source_binding.exact_action["candidate"]["kind"],
            "candidate-1",
        )

    def test_missing_or_extra_scores_fail_closed(self) -> None:
        for scores in ([0.1, 0.2], [0.1, 0.2, 0.3, 0.4]):
            with self.subTest(scores=scores):
                with tempfile.TemporaryDirectory() as directory:
                    with self.assertRaises(InferenceError):
                        InferenceRuntime(_context()).select(
                            _request(Path(directory)),
                            _RecordingProvider(scores),
                            _rng(),
                        )

    def test_non_finite_score_fails_before_selection(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with self.subTest(value=value):
                with tempfile.TemporaryDirectory() as directory:
                    with self.assertRaises(InferenceError):
                        InferenceRuntime(_context()).select(
                            _request(Path(directory)),
                            _RecordingProvider([0.1, value, 0.3]),
                            _rng(),
                        )

    def test_provider_checkpoint_and_numeric_profile_must_match_context(self) -> None:
        provider = _RecordingProvider([0.1, 0.2, 0.3])
        provider.checkpoint_id = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(InferenceError):
                InferenceRuntime(_context()).select(
                    _request(Path(directory)),
                    provider,
                    _rng(),
                )
        provider = _RecordingProvider([0.1, 0.2, 0.3])
        provider.numeric_profile_class = "OTHER_PROFILE"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(InferenceError):
                InferenceRuntime(_context()).select(
                    _request(Path(directory)),
                    provider,
                    _rng(),
                )

    def test_presence_and_executable_masks_must_match_source_authority(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            validated = _validated_sample(Path(directory))
            with self.assertRaises(InferenceError):
                InferenceRequest.from_validated_sample(
                    validated,
                    _item_with_mask(validated, 0, present=False, executable_support=False),
                )
            with self.assertRaises(InferenceError):
                InferenceRequest.from_validated_sample(
                    validated,
                    _item_with_mask(validated, 0, present=True, executable_support=False),
                )
        with tempfile.TemporaryDirectory() as directory:
            validated = _validated_sample(Path(directory), unaffordable_ordinal=2)
            with self.assertRaises(InferenceError):
                InferenceRequest.from_validated_sample(
                    validated,
                    _item_with_mask(validated, 2, present=True, executable_support=True),
                )

    def test_reader_sample_and_transport_are_coupled(self) -> None:
        with tempfile.TemporaryDirectory() as first_directory, tempfile.TemporaryDirectory() as second_directory:
            first = _validated_sample(Path(first_directory), prefix="first-")
            second = _validated_sample(Path(second_directory), prefix="second-")
            item_from_second = _item_from_validated_sample(second)
            with self.assertRaises(InferenceError):
                InferenceRequest.from_validated_sample(first, item_from_second)

    def test_source_candidate_direct_fields_bind_to_model_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            validated = _validated_sample(Path(directory), source_kind_override="source-only-kind")
            with self.assertRaises(InferenceError):
                InferenceRequest.from_validated_sample(
                    validated,
                    _item_from_validated_sample(validated),
                )

    def test_transport_candidate_ordinal_bijection_is_exact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            validated = _validated_sample(Path(directory))
            base = _item_from_validated_sample(validated)
            swapped = VariableDomainItem(
                model_input=base.model_input,
                candidates=(
                    CandidateFeature(base.candidates[0].feature_view, 1, True, True),
                    CandidateFeature(base.candidates[1].feature_view, 0, True, True),
                    CandidateFeature(base.candidates[2].feature_view, 2, True, True),
                ),
                structured_domain=None,
                target_binding_ordinal=0,
            )
            with self.assertRaises(InferenceError):
                InferenceRequest.from_validated_sample(validated, swapped)

    def test_structured_inference_fails_closed_before_provider_or_rng(self) -> None:
        rng = _rng()
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(InferenceError):
                InferenceRuntime(_context()).select(
                    _structured_request(Path(directory)),
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

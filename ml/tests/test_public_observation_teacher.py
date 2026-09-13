import copy
import math
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from argentum_ml.contracts.canonical_json import canonical_json
from argentum_ml.contracts.identities import POLICY_TIE_RNG_IDENTITY
from argentum_ml.contracts.tie_discriminator import SemanticTieDiscriminator
from argentum_ml.data.derived_reader import DerivedArtifactReader
from argentum_ml.data.variable_batch import CandidateFeature, VariableDomainItem
from argentum_ml.inference import InferenceRequest
from argentum_ml.selection.policy_tie_rng import PolicyTieRngStateV1
from argentum_ml.teacher import (
    GenericPublicObservationScorer,
    GenericScoringConfigurationV1,
    NoLabelReason,
    NoLabelTeacherResultV1,
    PublicObservationTeacherConfigV1,
    PublicObservationTeacherRequestV1,
    PublicObservationTeacherV1,
    TeacherConfigError,
    TeacherInputError,
)
from tests.test_derived_reader import _artifact, _sample, _structured_sample


SOURCE_COMMIT = "777a1308b40821748f0a1513ff9c9da811781f27"


def _discriminator(value: dict) -> SemanticTieDiscriminator:
    return SemanticTieDiscriminator.from_json(
        canonical_json(value),
        forbidden_raw_values={"raw-0", "raw-1", "raw-7", "raw-9"},
    )


def _flat_request(
    feature_views: list[dict],
    *,
    ordinals: list[int] | None = None,
    executable: list[bool] | None = None,
    raw_targets: list[str] | None = None,
    discriminators: list[SemanticTieDiscriminator | None] | None = None,
    response_domain: bool = False,
) -> PublicObservationTeacherRequestV1:
    ordinals = ordinals or list(range(len(feature_views)))
    if ordinals != list(range(len(feature_views))):
        raise ValueError("C1_00 flat source ordinals are producer-authorized sequential ordinals")
    executable = executable or [True] * len(feature_views)
    raw_targets = raw_targets or [f"raw-{ordinal}" for ordinal in ordinals]
    discriminators = discriminators or [None] * len(feature_views)
    sample = _sample()
    source_candidates = []
    model_candidates = []
    known_aliases = {
        binding["alias"] for binding in sample["binding"]["entityAliasBindings"]
    }
    base_source_candidate = sample["binding"]["completeLegalDomain"]["candidates"][0]
    base_model_candidate = sample["input"]["domain"]["candidates"][0]
    for ordinal, (feature_view, raw_target, is_executable) in enumerate(
        zip(feature_views, raw_targets, executable)
    ):
        source_candidate = copy.deepcopy(base_source_candidate)
        model_candidate = copy.deepcopy(base_model_candidate)
        source_candidate["kind"] = feature_view["kind"]
        model_candidate["kind"] = feature_view["kind"]
        source_candidate["affordable"] = is_executable
        model_candidate["affordable"] = is_executable
        normalized = dict(feature_view)
        if "targetAliases" in normalized:
            normalized["targetEntityAliases"] = normalized.pop("targetAliases")
        model_candidate.update(copy.deepcopy(normalized))
        source_candidate["targetEntityIds"] = [raw_target]
        source_candidate["requiredPayloadFields"] = []
        if response_domain:
            response = {"type": "OptionChosenResponse", "optionIndex": ordinal}
            source_candidate["actionSemantics"] = response
            model_candidate["actionSemantics"] = response
        source_candidates.append(source_candidate)
        model_candidates.append(model_candidate)
        for alias in model_candidate.get("targetEntityAliases", []):
            if alias not in known_aliases:
                sample["binding"]["entityAliasBindings"].append(
                    {"alias": alias, "sourceEntityId": raw_target}
                )
                known_aliases.add(alias)

    domain_kind = "FOLDED_DECISION_OPTIONS" if response_domain else "ACTION_CANDIDATES"
    source_domain = sample["binding"]["completeLegalDomain"]
    source_domain["kind"] = domain_kind
    sample["input"]["domain"]["kind"] = domain_kind
    sample["input"]["decisionContext"]["domainKind"] = domain_kind
    if response_domain:
        shape = {
            "availableColors": [],
            "budget": None,
            "maxSelections": 1,
            "minSelections": 1,
            "numericMax": None,
            "numericMin": None,
            "totalToDistribute": None,
        }
        source_domain["decisionKind"] = "CHOOSE_OPTION"
        source_domain["shape"] = shape
        sample["input"]["domain"]["decisionKind"] = "CHOOSE_OPTION"
        sample["input"]["domain"]["shape"] = shape
    source_domain["candidates"] = source_candidates
    sample["input"]["domain"]["candidates"] = model_candidates
    sample["binding"]["sourceBindingOrdinals"] = list(range(len(feature_views)))
    sample["binding"]["semanticTieDiscriminators"] = {
        str(index): discriminator.canonical_value
        for index, discriminator in enumerate(discriminators)
        if discriminator is not None
    }
    if response_domain:
        chosen = {"response": source_candidates[0]["actionSemantics"], "type": "chosen-response"}
        sample["target"] = {"chosenSemanticAction": None, "chosenSemanticResponse": chosen}
    else:
        chosen = {"candidate": source_candidates[0], "choicePayload": {}, "type": "chosen-action"}
        sample["target"] = {"chosenSemanticAction": chosen, "chosenSemanticResponse": None}
    sample["binding"]["selectedExactSourceBinding"] = chosen

    with tempfile.TemporaryDirectory() as directory:
        artifact = _artifact(Path(directory), sample=sample)
        reader = DerivedArtifactReader.open(artifact)
        stream = reader.iter_validated_samples_for_inference()
        validated = next(stream)
        stream.close()
        features = validated.sample["input"]["domain"]["candidates"]
        source = validated.sample["binding"]["completeLegalDomain"]["candidates"]
        candidates = tuple(
            CandidateFeature(
                feature_view=feature,
                source_binding_ordinal=index,
                present=True,
                executable_support=source[index]["affordable"],
            )
            for index, feature in enumerate(features)
        )
        item = VariableDomainItem(
            model_input=validated.sample["input"],
            candidates=candidates,
            structured_domain=None,
            target_binding_ordinal=0,
        )
        inference_request = InferenceRequest.from_validated_sample(validated, item)
    return PublicObservationTeacherRequestV1.from_inference_request(inference_request)


def _structured_request(structured_type: str, version: int) -> PublicObservationTeacherRequestV1:
    sample = _structured_sample()
    structured_domain = sample["binding"]["completeLegalDomain"]["structuredDomain"]
    structured_domain["type"] = structured_type
    structured_domain["version"] = version
    sample["input"]["domain"]["structuredType"]["type"] = structured_type
    sample["input"]["domain"]["structuredType"]["version"] = version
    with tempfile.TemporaryDirectory() as directory:
        artifact = _artifact(Path(directory), sample=sample)
        reader = DerivedArtifactReader.open(artifact)
        stream = reader.iter_validated_samples_for_inference()
        validated = next(stream)
        stream.close()
        item = VariableDomainItem(
            model_input=validated.sample["input"],
            candidates=(),
            structured_domain=validated.sample["input"]["domain"]["structuredType"],
            target_binding_ordinal=None,
        )
        inference_request = InferenceRequest.from_validated_sample(validated, item)
    return PublicObservationTeacherRequestV1.from_inference_request(inference_request)


def _teacher() -> PublicObservationTeacherV1:
    return PublicObservationTeacherV1(
        PublicObservationTeacherConfigV1.reference(),
        SOURCE_COMMIT,
    )


def _rng(seed: int = 17, seat: int = 0) -> PolicyTieRngStateV1:
    return PolicyTieRngStateV1.from_policy_seed(
        seed,
        seat,
        policy_rng_identity=POLICY_TIE_RNG_IDENTITY,
    )


def _score_by_feature(request: PublicObservationTeacherRequestV1, scores: tuple[float, ...]) -> dict[str, float]:
    return {
        canonical_json(candidate.feature_view): score
        for candidate, score in zip(request.item.candidates, scores)
    }


def _mutable_json(value):
    if isinstance(value, dict):
        return {key: _mutable_json(child) for key, child in value.items()}
    if isinstance(value, (list, tuple)):
        return [_mutable_json(child) for child in value]
    return value


class _RecordingScorer:
    def __init__(self, scores: list[float] | None = None) -> None:
        self.scores = scores
        self.model_input = None
        self.candidate_features = None

    def score(self, model_input, candidate_features):
        self.model_input = model_input
        self.candidate_features = tuple(candidate_features)
        if self.scores is not None:
            return self.scores
        return [0.0] * len(self.candidate_features)


class PublicObservationTeacherContractTests(unittest.TestCase):
    def test_reference_config_has_immutable_digest_and_exact_contract_pair(self) -> None:
        config = PublicObservationTeacherConfigV1.reference()

        self.assertEqual(config.selection_contract_identity, "argentum-ml-policy-selection@v2")
        self.assertEqual(config.policy_rng_contract_identity, POLICY_TIE_RNG_IDENTITY)
        self.assertEqual(len(config.digest), 64)
        self.assertEqual(config.digest, PublicObservationTeacherConfigV1.from_dict(config.to_dict()).digest)

    def test_unknown_config_version_and_fields_are_rejected(self) -> None:
        config = PublicObservationTeacherConfigV1.reference().to_dict()
        config["version"] = 2
        with self.assertRaises(TeacherConfigError):
            PublicObservationTeacherConfigV1.from_dict(config)

        config = PublicObservationTeacherConfigV1.reference().to_dict()
        config["futureField"] = True
        with self.assertRaises(TeacherConfigError):
            PublicObservationTeacherConfigV1.from_dict(config)

    def test_request_rejects_candidate_truncation_before_scoring(self) -> None:
        request = _flat_request([
            {"kind": "PlayLand", "targetAliases": ["entity-0"]},
            {"kind": "PassPriority", "targetAliases": ["entity-1"]},
        ])

        with self.assertRaises(TeacherInputError):
            PublicObservationTeacherRequestV1.from_inference_request(
                request.inference_request,
                permutation=(0,),
            )

    def test_runtime_identity_exposes_no_materializer_identity(self) -> None:
        identity = _teacher().identity

        self.assertEqual(identity.source_commit, SOURCE_COMMIT)
        self.assertEqual(identity.teacher_configuration_identity_or_digest, _teacher().config.digest)
        self.assertIsNone(identity.label_materializer_identity)

    def test_direct_config_sequences_are_defensively_immutable(self) -> None:
        scoring = GenericScoringConfigurationV1(
            default_score=0.0,
            kind_scores=[("PlayLand", 1.0)],
        )
        config = PublicObservationTeacherConfigV1(
            version=1,
            schema_identity="argentum-ml-public-observation-teacher-config@v1",
            teacher_policy_identity="argentum-ml-public-observation-bootstrap-teacher@v1",
            selection_contract_identity="argentum-ml-policy-selection@v2",
            policy_rng_contract_identity=POLICY_TIE_RNG_IDENTITY,
            scorer_identity="argentum-ml-public-observation-generic-kind-scorer@v1",
            scoring_configuration=scoring,
            supported_decision_families=["ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS"],
            unsupported_decision_policy="NO_LABEL",
            structured_decision_policy_identity="argentum-ml-structured-no-label@v1",
        )

        with self.assertRaises((TypeError, AttributeError)):
            scoring.kind_scores.append(("PassPriority", -1.0))
        with self.assertRaises((TypeError, AttributeError)):
            config.supported_decision_families.append("OTHER")


class PublicObservationTeacherScoringTests(unittest.TestCase):
    def test_runtime_id_and_alias_rename_preserves_score_vector(self) -> None:
        original = _flat_request([
            {"kind": "Same", "targetAliases": ["entity-0"]},
            {"kind": "Same", "targetAliases": ["entity-1"]},
        ], raw_targets=["raw-0", "raw-1"])
        renamed = _flat_request([
            {"kind": "Same", "targetAliases": ["entity-0"]},
            {"kind": "Same", "targetAliases": ["entity-1"]},
        ], raw_targets=["raw-7", "raw-9"])

        teacher = _teacher()
        self.assertEqual(teacher.score_vector(original), teacher.score_vector(renamed))

    def test_candidate_permutation_preserves_semantic_score_map(self) -> None:
        request = _flat_request([
            {"kind": "PlayLand", "targetAliases": ["entity-0"]},
            {"kind": "PassPriority", "targetAliases": ["entity-1"]},
        ])
        permuted = PublicObservationTeacherRequestV1.from_inference_request(
            request.inference_request,
            permutation=(1, 0),
        )

        teacher = _teacher()
        self.assertEqual(
            _score_by_feature(request, teacher.score_vector(request)),
            _score_by_feature(permuted, teacher.score_vector(permuted)),
        )

    def test_source_binding_ordinal_is_not_read_as_a_score_feature(self) -> None:
        features = [
            {"kind": "PlayLand", "targetAliases": ["entity-0"]},
            {"kind": "PassPriority", "targetAliases": ["entity-1"]},
        ]
        original = _flat_request(features)
        permuted = PublicObservationTeacherRequestV1.from_inference_request(
            original.inference_request,
            permutation=(1, 0),
        )

        self.assertEqual(
            _score_by_feature(original, _teacher().score_vector(original)),
            _score_by_feature(permuted, _teacher().score_vector(permuted)),
        )

    def test_recorded_target_mutation_does_not_change_teacher_scores(self) -> None:
        features = [
            {"kind": "PlayLand", "targetAliases": ["entity-0"]},
            {"kind": "PassPriority", "targetAliases": ["entity-1"]},
        ]
        original = _flat_request(features, raw_targets=["raw-0", "raw-1"])
        recorded_target_changed = _flat_request(features, raw_targets=["raw-7", "raw-9"])

        self.assertEqual(
            _teacher().score_vector(original),
            _teacher().score_vector(recorded_target_changed),
        )

    def test_scorer_receives_only_public_policy_channels(self) -> None:
        scorer = _RecordingScorer()
        request = _flat_request([
            {"kind": "PlayLand", "targetAliases": ["entity-0"]},
            {"kind": "PassPriority", "targetAliases": ["entity-1"]},
        ])
        with patch.object(GenericPublicObservationScorer, "score", new=scorer.score):
            _teacher().score_vector(request)

        forbidden = {
            "targetEntityIds",
            "sourceEntityId",
            "sourceBindingOrdinal",
            "rowIndex",
            "candidateIndex",
            "binding",
            "provenance",
            "outcome",
            "winnerId",
        }

        def keys(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    yield key
                    yield from keys(child)
            elif isinstance(value, (list, tuple)):
                for child in value:
                    yield from keys(child)

        self.assertIsNotNone(scorer.model_input)
        self.assertIsNotNone(scorer.candidate_features)
        self.assertTrue(forbidden.isdisjoint(set(keys(scorer.model_input))))
        self.assertTrue(forbidden.isdisjoint(set(keys(scorer.candidate_features))))

    def test_every_real_candidate_receives_one_finite_score(self) -> None:
        scorer = _RecordingScorer()
        request = _flat_request(
            [
                {"kind": "PlayLand"},
                {"kind": "PassPriority"},
                {"kind": "UnknownGenericAction"},
            ],
            executable=[True, False, True],
        )

        with patch.object(GenericPublicObservationScorer, "score", new=scorer.score):
            scores = _teacher().score_vector(request)

        self.assertEqual(len(scores), 3)
        self.assertTrue(all(math.isfinite(score) for score in scores))
        self.assertEqual(len(scorer.candidate_features), 3)


class PublicObservationTeacherSelectionTests(unittest.TestCase):
    def test_unique_maximum_selects_without_rng_draw_and_returns_exact_binding(self) -> None:
        request = _flat_request([
            {"kind": "PlayLand"},
            {"kind": "PassPriority"},
        ])
        initial = _rng()

        result = _teacher().select(request, initial)

        self.assertNotIsInstance(result, NoLabelTeacherResultV1)
        self.assertEqual(result.source_binding_ordinal, 0)
        self.assertEqual(result.rng_draw_count, 0)
        self.assertEqual(result.cursor_before, initial.cursor)
        self.assertEqual(result.cursor_after, initial.cursor)
        self.assertEqual(
            result.exact_source_binding,
            request.source_bindings.exact_binding_for(0),
        )

    def test_unresolved_exact_tie_uses_policy_tie_rng_and_is_permutation_safe(self) -> None:
        request = _flat_request([
            {"kind": "Same", "targetAliases": ["entity-0"]},
            {"kind": "Same", "targetAliases": ["entity-1"]},
        ])
        permuted = PublicObservationTeacherRequestV1.from_inference_request(
            request.inference_request,
            permutation=(1, 0),
        )
        initial = _rng(seed=31, seat=1)

        first = _teacher().select(request, initial)
        second = _teacher().select(permuted, initial)

        self.assertEqual(first.source_binding_ordinal, second.source_binding_ordinal)
        self.assertGreaterEqual(first.rng_draw_count, 1)
        self.assertEqual(first.cursor_after, second.cursor_after)

    def test_semantic_discriminator_resolves_tie_without_rng(self) -> None:
        request = _flat_request(
            [{"kind": "Same"}, {"kind": "Same"}],
            discriminators=[
                _discriminator({"preference": "low"}),
                _discriminator({"preference": "high"}),
            ],
        )
        initial = _rng()

        result = _teacher().select(request, initial)

        self.assertEqual(result.rng_draw_count, 0)
        self.assertEqual(result.cursor_after, initial.cursor)
        self.assertEqual(result.source_binding_ordinal, 1)

    def test_same_input_config_and_rng_reproduce_scores_selection_and_cursor(self) -> None:
        request = _flat_request([
            {"kind": "Same", "targetAliases": ["entity-0"]},
            {"kind": "Same", "targetAliases": ["entity-1"]},
        ])
        teacher = _teacher()
        first_rng = _rng(seed=42, seat=0)
        second_rng = _rng(seed=42, seat=0)

        first_scores = teacher.score_vector(request)
        first = teacher.select(request, first_rng)
        second_scores = teacher.score_vector(request)
        second = teacher.select(request, second_rng)

        self.assertEqual(first_scores, second_scores)
        self.assertEqual(first.source_binding_ordinal, second.source_binding_ordinal)
        self.assertEqual(first.cursor_after, second.cursor_after)

    def test_folded_decision_returns_exact_response_binding(self) -> None:
        request = _flat_request(
            [{"kind": "OptionA"}, {"kind": "OptionB"}],
            response_domain=True,
        )

        result = _teacher().select(request, _rng())

        self.assertIsNotNone(result.exact_source_binding.exact_response)
        self.assertIsNone(result.exact_source_binding.exact_action)


class PublicObservationTeacherLeakageTests(unittest.TestCase):
    def test_c1_target_feature_vocabulary_remains_scoreable(self) -> None:
        request = _flat_request([{"kind": "Same"}])
        result = _teacher().select(request, _rng())

        self.assertNotIsInstance(result, NoLabelTeacherResultV1)

    def test_non_finite_scorer_output_fails_closed(self) -> None:
        request = _flat_request([{"kind": "Same"}, {"kind": "Same"}])
        with patch.object(GenericPublicObservationScorer, "score", return_value=[math.nan, 0.0]):
            result = _teacher().select(request, _rng())

        self.assertIsInstance(result, NoLabelTeacherResultV1)
        self.assertEqual(result.reason, NoLabelReason.NON_FINITE_SCORE)

    def test_empty_executable_domain_returns_no_label_without_substitution(self) -> None:
        with self.assertRaises(Exception):
            _flat_request(
                [{"kind": "PlayLand"}, {"kind": "PassPriority"}],
                executable=[False, False],
            )

    def test_structured_domains_are_explicit_no_label_and_do_not_score_or_draw(self) -> None:
        for structured_type, version in (("targets", 2),):
            scorer = _RecordingScorer()
            initial = _rng()
            with patch.object(GenericPublicObservationScorer, "score", new=scorer.score):
                result = _teacher().select(
                    _structured_request(structured_type, version),
                    initial,
                )

            self.assertIsInstance(result, NoLabelTeacherResultV1)
            self.assertEqual(result.reason, NoLabelReason.STRUCTURED_DOMAIN_NOT_SCOREABLE)
            self.assertIsNone(scorer.model_input)
            self.assertEqual(result.rng_state, initial)

    def test_unknown_structured_domain_version_is_rejected_by_c1_authority(self) -> None:
        with self.assertRaises(Exception):
            _structured_request("targets", 99)


if __name__ == "__main__":
    unittest.main()

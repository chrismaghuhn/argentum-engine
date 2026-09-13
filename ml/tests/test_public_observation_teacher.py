import math
import unittest

from argentum_ml.contracts.canonical_json import canonical_json
from argentum_ml.contracts.identities import POLICY_TIE_RNG_IDENTITY
from argentum_ml.contracts.tie_discriminator import SemanticTieDiscriminator
from argentum_ml.data.variable_batch import CandidateFeature, VariableDomainItem
from argentum_ml.selection.policy_tie_rng import PolicyTieRngStateV1
from argentum_ml.selection.selection_v2 import ExactSemanticSourceBinding
from argentum_ml.teacher import (
    GenericScoringConfigurationV1,
    NoLabelReason,
    NoLabelTeacherResultV1,
    PublicObservationTeacherConfigV1,
    PublicObservationTeacherRequestV1,
    PublicObservationTeacherV1,
    TeacherConfigError,
    TeacherInputError,
    TeacherSourceBindingV1,
)


SOURCE_COMMIT = "32e6a300a2d22e5be864bee179c8b86b05a9ba06"


def _discriminator(value: dict) -> SemanticTieDiscriminator:
    return SemanticTieDiscriminator.from_json(
        canonical_json(value),
        forbidden_raw_values={"raw-0", "raw-1", "raw-7", "raw-9"},
    )


def _exact_action(ordinal: int, raw_target: str, kind: str) -> ExactSemanticSourceBinding:
    return ExactSemanticSourceBinding(
        exact_action={
            "candidate": {
                "kind": kind,
                "targetEntityIds": [raw_target],
            },
            "choicePayload": {"target": raw_target},
            "type": "chosen-action",
        },
        exact_response=None,
        source_binding_ordinal_audit=ordinal,
    )


def _exact_response(ordinal: int, response_value: str) -> ExactSemanticSourceBinding:
    return ExactSemanticSourceBinding(
        exact_action=None,
        exact_response={
            "response": {"choice": response_value},
            "type": "chosen-response",
        },
        source_binding_ordinal_audit=ordinal,
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
    executable = executable or [True] * len(feature_views)
    raw_targets = raw_targets or [f"raw-{ordinal}" for ordinal in ordinals]
    discriminators = discriminators or [None] * len(feature_views)
    domain_kind = "FOLDED_DECISION_OPTIONS" if response_domain else "ACTION_CANDIDATES"
    candidates = tuple(
        CandidateFeature(
            feature_view=feature_view,
            source_binding_ordinal=ordinal,
            present=True,
            executable_support=is_executable,
        )
        for feature_view, ordinal, is_executable in zip(feature_views, ordinals, executable)
    )
    item = VariableDomainItem(
        model_input={
            "decisionContext": {"domainKind": domain_kind},
            "observation": {"turnNumber": 1, "phase": "MAIN", "step": "MAIN"},
            "domain": {
                "kind": domain_kind,
                "candidates": [candidate.feature_view for candidate in candidates],
            },
        },
        candidates=candidates,
        structured_domain=None,
        target_binding_ordinal=ordinals[0],
    )
    bindings = tuple(
        TeacherSourceBindingV1(
            source_binding_ordinal=ordinal,
            exact_source_binding=(
                _exact_response(ordinal, f"response-{ordinal}")
                if response_domain
                else _exact_action(ordinal, raw_target, feature_view["kind"])
            ),
            deterministic_semantic_tie_discriminator=discriminator,
        )
        for feature_view, ordinal, raw_target, discriminator in zip(
            feature_views, ordinals, raw_targets, discriminators
        )
    )
    return PublicObservationTeacherRequestV1(item=item, bindings=bindings)


def _structured_request(structured_type: str, version: int) -> PublicObservationTeacherRequestV1:
    structured_domain = {"type": structured_type, "version": version}
    item = VariableDomainItem(
        model_input={
            "decisionContext": {"domainKind": "STRUCTURED_DECISION"},
            "observation": {"turnNumber": 1, "phase": "MAIN", "step": "MAIN"},
            "domain": {
                "kind": "STRUCTURED_DECISION",
                "structuredType": structured_domain,
            },
        },
        candidates=(),
        structured_domain=structured_domain,
        target_binding_ordinal=None,
    )
    return PublicObservationTeacherRequestV1(item=item, bindings=())


def _teacher(*, scorer=None) -> PublicObservationTeacherV1:
    return PublicObservationTeacherV1(
        PublicObservationTeacherConfigV1.reference(),
        SOURCE_COMMIT,
        scorer=scorer,
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
            {"kind": "PlayLand", "targetAliases": ["entity-0001"]},
            {"kind": "PassPriority", "targetAliases": ["entity-0002"]},
        ])

        with self.assertRaises(TeacherInputError):
            PublicObservationTeacherRequestV1(
                item=request.item,
                bindings=request.bindings[:-1],
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
            {"kind": "Same", "targetAliases": ["entity-0001"]},
            {"kind": "Same", "targetAliases": ["entity-0002"]},
        ], raw_targets=["raw-0", "raw-1"])
        renamed = _flat_request([
            {"kind": "Same", "targetAliases": ["entity-0101"]},
            {"kind": "Same", "targetAliases": ["entity-0102"]},
        ], raw_targets=["raw-7", "raw-9"])

        teacher = _teacher()
        self.assertEqual(teacher.score_vector(original), teacher.score_vector(renamed))

    def test_candidate_permutation_preserves_semantic_score_map(self) -> None:
        request = _flat_request([
            {"kind": "PlayLand", "targetAliases": ["entity-0001"]},
            {"kind": "PassPriority", "targetAliases": ["entity-0002"]},
        ])
        permuted = PublicObservationTeacherRequestV1(
            item=request.item.permute_candidates((1, 0)),
            bindings=request.bindings,
        )

        teacher = _teacher()
        self.assertEqual(
            _score_by_feature(request, teacher.score_vector(request)),
            _score_by_feature(permuted, teacher.score_vector(permuted)),
        )

    def test_source_binding_ordinal_change_preserves_score_values(self) -> None:
        features = [
            {"kind": "PlayLand", "targetAliases": ["entity-0001"]},
            {"kind": "PassPriority", "targetAliases": ["entity-0002"]},
        ]
        original = _flat_request(features)
        renumbered = _flat_request(features, ordinals=[7, 9], raw_targets=["raw-7", "raw-9"])

        self.assertEqual(_teacher().score_vector(original), _teacher().score_vector(renumbered))

    def test_recorded_target_mutation_does_not_change_teacher_scores(self) -> None:
        features = [
            {"kind": "PlayLand", "targetAliases": ["entity-0001"]},
            {"kind": "PassPriority", "targetAliases": ["entity-0002"]},
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
            {"kind": "PlayLand", "targetAliases": ["entity-0001"]},
            {"kind": "PassPriority", "targetAliases": ["entity-0002"]},
        ])
        _teacher(scorer=scorer).score_vector(request)

        forbidden = {
            "target",
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

        scores = _teacher(scorer=scorer).score_vector(request)

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
        self.assertEqual(result.exact_source_binding, request.bindings[0].exact_source_binding)

    def test_unresolved_exact_tie_uses_policy_tie_rng_and_is_permutation_safe(self) -> None:
        request = _flat_request([
            {"kind": "Same", "targetAliases": ["entity-0001"]},
            {"kind": "Same", "targetAliases": ["entity-0002"]},
        ])
        permuted = PublicObservationTeacherRequestV1(
            item=request.item.permute_candidates((1, 0)),
            bindings=request.bindings,
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
            {"kind": "Same", "targetAliases": ["entity-0001"]},
            {"kind": "Same", "targetAliases": ["entity-0002"]},
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
    def test_forbidden_target_field_in_policy_channel_fails_closed(self) -> None:
        request = _flat_request([{"kind": "Same", "targetEntityIds": ["raw-0"]}])

        result = _teacher().select(request, _rng())

        self.assertIsInstance(result, NoLabelTeacherResultV1)
        self.assertEqual(result.reason, NoLabelReason.TEACHER_INPUT_CONTRACT_VIOLATION)

    def test_non_finite_scorer_output_fails_closed(self) -> None:
        request = _flat_request([{"kind": "Same"}, {"kind": "Same"}])
        result = _teacher(scorer=_RecordingScorer([math.nan, 0.0])).select(request, _rng())

        self.assertIsInstance(result, NoLabelTeacherResultV1)
        self.assertEqual(result.reason, NoLabelReason.NON_FINITE_SCORE)

    def test_empty_executable_domain_returns_no_label_without_substitution(self) -> None:
        request = _flat_request(
            [{"kind": "PlayLand"}, {"kind": "PassPriority"}],
            executable=[False, False],
        )
        initial = _rng()

        result = _teacher().select(request, initial)

        self.assertIsInstance(result, NoLabelTeacherResultV1)
        self.assertEqual(result.reason, NoLabelReason.NO_EXECUTABLE_CANDIDATE)
        self.assertEqual(result.rng_state, initial)

    def test_structured_domains_are_explicit_no_label_and_do_not_score_or_draw(self) -> None:
        for structured_type, version in (
            ("targets", 2),
            ("card-selection", 1),
            ("mode-selection", 1),
            ("distribution", 1),
            ("ordering", 1),
            ("split-piles", 1),
            ("search-library", 1),
            ("reorder-library", 1),
            ("combat-resolution", 1),
            ("mana-sources", 3),
            ("replacement", 1),
            ("budget-modal", 1),
        ):
            scorer = _RecordingScorer()
            initial = _rng()
            result = _teacher(scorer=scorer).select(
                _structured_request(structured_type, version),
                initial,
            )

            self.assertIsInstance(result, NoLabelTeacherResultV1)
            self.assertEqual(result.reason, NoLabelReason.STRUCTURED_DOMAIN_NOT_SCOREABLE)
            self.assertIsNone(scorer.model_input)
            self.assertEqual(result.rng_state, initial)

    def test_unknown_structured_domain_version_is_no_label(self) -> None:
        result = _teacher().select(_structured_request("targets", 99), _rng())

        self.assertIsInstance(result, NoLabelTeacherResultV1)
        self.assertEqual(result.reason, NoLabelReason.UNSUPPORTED_DOMAIN_VERSION)

    def test_unknown_flat_domain_family_is_no_label(self) -> None:
        request = _flat_request([{"kind": "Same"}])
        bad_model_input = _mutable_json(request.item.model_input)
        bad_model_input["domain"]["kind"] = "UNKNOWN_DOMAIN"
        bad_item = VariableDomainItem(
            model_input=bad_model_input,
            candidates=request.item.candidates,
            structured_domain=None,
            target_binding_ordinal=0,
        )
        bad_request = PublicObservationTeacherRequestV1(bad_item, request.bindings)

        result = _teacher().select(bad_request, _rng())

        self.assertIsInstance(result, NoLabelTeacherResultV1)
        self.assertEqual(result.reason, NoLabelReason.UNSUPPORTED_DECISION_FAMILY)


if __name__ == "__main__":
    unittest.main()

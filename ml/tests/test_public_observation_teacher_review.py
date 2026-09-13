import unittest

from argentum_ml.teacher import (
    GenericPublicObservationScorer,
    PublicObservationTeacherConfigV1,
    PublicObservationTeacherRequestV1,
    PublicObservationTeacherV1,
    TeacherInputError,
)
from tests.test_public_observation_teacher import (
    SOURCE_COMMIT,
    _flat_request,
    _teacher,
)


class PublicObservationTeacherReviewRegressionTests(unittest.TestCase):
    def test_production_constructor_rejects_unbound_scorer_substitution(self) -> None:
        class DifferentScorer:
            def score(self, model_input, candidate_features):
                return [999.0] * len(candidate_features)

        with self.assertRaises(TypeError):
            PublicObservationTeacherV1(
                PublicObservationTeacherConfigV1.reference(),
                SOURCE_COMMIT,
                scorer=DifferentScorer(),
            )

    def test_teacher_request_cannot_be_constructed_from_caller_bindings(self) -> None:
        request = _flat_request([{"kind": "Same"}])

        with self.assertRaises(TypeError):
            PublicObservationTeacherRequestV1(request.item, request.source_bindings)

    def test_teacher_contract_identity_is_distinct_from_policy_identity(self) -> None:
        identity = _teacher().identity

        self.assertEqual(identity.teacher_contract_identity, "argentum-ml-teacher-bootstrap@v1")
        self.assertEqual(
            identity.teacher_policy_identity,
            "argentum-ml-public-observation-bootstrap-teacher@v1",
        )

    def test_c1_model_facing_target_feature_is_not_rejected_as_a_raw_channel(self) -> None:
        feature = {"kind": "Same", "target": {"semantic": "public-semantic-value"}}
        scorer = GenericPublicObservationScorer(
            PublicObservationTeacherConfigV1.reference().scoring_configuration
        )
        try:
            scores = scorer.score(
                {"domain": {"candidates": [feature]}},
                [feature],
            )
        except TeacherInputError as exc:
            self.fail(f"C1 model-facing feature vocabulary was rejected: {exc}")

        self.assertEqual(len(scores), 1)


if __name__ == "__main__":
    unittest.main()

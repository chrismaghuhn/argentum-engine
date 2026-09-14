import copy
import tempfile
import unittest
from pathlib import Path

from argentum_ml.contracts.canonical_json import canonical_bytes
from argentum_ml.data.derived_reader import DerivedArtifactReader
from argentum_ml.data.label_materializer import (
    LabelMaterializerError,
    materialize_selected_label,
    materialize_artifact,
)
from argentum_ml.data.label_artifact import LabelArtifactReader
from argentum_ml.teacher.execution import (
    TeacherExecutionBindingV1,
    TeacherExecutionError,
    teacher_seat_index,
)
from argentum_ml.teacher.request_factory import teacher_request_from_validated_sample
from argentum_ml.teacher import PublicObservationTeacherConfigV1, PublicObservationTeacherV1
from argentum_ml.selection.policy_tie_rng import PolicyTieRngStateV1

from tests.test_derived_reader import _artifact, _sample, _sha


def _source_fixture(*, candidate_count: int = 1, folded: bool = False) -> dict:
    sample = _sample()
    sample["provenance"] = {
        "environmentIdentity": {
            "roster": [
                {"playerId": "player-0", "seatIndex": 0},
                {"playerId": "player-1", "seatIndex": 1},
            ]
        }
    }
    source_domain = sample["binding"]["completeLegalDomain"]
    model_domain = sample["input"]["domain"]
    source_candidates = []
    model_candidates = []
    for ordinal in range(candidate_count):
        source_candidate = copy.deepcopy(source_domain["candidates"][0])
        model_candidate = copy.deepcopy(model_domain["candidates"][0])
        if folded:
            source_candidate["actionSemantics"] = {
                "optionIndex": ordinal,
                "type": "OptionChosenResponse",
            }
            source_candidate["kind"] = "DECISION"
            source_candidate["isDecisionOption"] = True
            model_candidate["actionSemantics"] = {
                "optionIndex": ordinal,
                "type": "OptionChosenResponse",
            }
            model_candidate["kind"] = "DECISION"
            model_candidate["isDecisionOption"] = True
        else:
            source_candidate["actionSemantics"] = {
                "playerId": f"player-{ordinal}",
                "type": "PassPriority",
            }
            model_candidate["actionSemantics"] = {
                "actorRole": "SELF" if ordinal == 0 else "OPPONENT",
                "type": "PassPriority",
            }
        source_candidate["affordable"] = True
        model_candidate["affordable"] = True
        source_candidates.append(source_candidate)
        model_candidates.append(model_candidate)
    source_domain["candidates"] = source_candidates
    source_domain["kind"] = "FOLDED_DECISION_OPTIONS" if folded else "ACTION_CANDIDATES"
    source_domain["decisionKind"] = "CHOOSE_OPTION" if folded else None
    source_domain["shape"] = (
        {
            "availableColors": [],
            "budget": None,
            "maxSelections": 1,
            "minSelections": 1,
            "numericMax": None,
            "numericMin": None,
            "totalToDistribute": None,
        }
        if folded
        else None
    )
    model_domain["candidates"] = model_candidates
    model_domain["kind"] = source_domain["kind"]
    if folded:
        model_domain["decisionKind"] = "CHOOSE_OPTION"
        model_domain["shape"] = source_domain["shape"]
    else:
        model_domain.pop("decisionKind", None)
        model_domain.pop("shape", None)
    selected_source = (
        {
            "response": source_candidates[0]["actionSemantics"],
            "type": "chosen-response",
        }
        if folded
        else {
            "candidate": source_candidates[0],
            "choicePayload": {},
            "type": "chosen-action",
        }
    )
    sample["binding"]["selectedExactSourceBinding"] = selected_source
    sample["binding"]["sourceBindingOrdinals"] = list(range(candidate_count))
    sample["binding"]["semanticTieDiscriminators"] = {}
    sample["target"] = {
        "chosenSemanticAction": None if folded else selected_source,
        "chosenSemanticResponse": selected_source if folded else None,
    }
    sample["input"]["decisionContext"]["domainKind"] = source_domain["kind"]
    sample["sourceReference"]["perspectivePlayerId"] = "player-0"
    return sample


def _validated_sample(sample: dict):
    with tempfile.TemporaryDirectory() as directory:
        root = _artifact(Path(directory), sample=sample)
        reader = DerivedArtifactReader.open(root)
        return next(reader.iter_validated_samples_for_inference())


def _teacher_request_and_result(sample: dict, *, folded: bool = False):
    validated = _validated_sample(sample)
    request = teacher_request_from_validated_sample(validated)
    teacher = PublicObservationTeacherV1(
        PublicObservationTeacherConfigV1.reference(),
        "a" * 40,
    )
    rng = PolicyTieRngStateV1.from_policy_seed(
        0,
        0,
        policy_rng_identity="argentum-ml-policy-tie-rng@v1",
    )
    result = teacher.select(request, rng)
    return validated, request, result


class LabelMaterializerTests(unittest.TestCase):
    def test_small_artifact_materialization_writes_label_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_root = root / "source"
            output_root = root / "labels"
            source_root.mkdir()
            source = _source_fixture()
            _artifact(source_root, sample=source)
            teacher = PublicObservationTeacherV1(
                PublicObservationTeacherConfigV1.reference(),
                "a" * 40,
            )
            manifest = materialize_artifact(
                source_root,
                output_root,
                teacher=teacher,
                execution=TeacherExecutionBindingV1.reference(),
                materializer_implementation_identity={
                    "implementation": "argentum-ml-label-materializer@v1",
                    "sourceCommit": "e" * 40,
                },
                materializer_config_digest=_sha("f"),
                expected_source_artifact_id=None,
            )
            reader = LabelArtifactReader.open(output_root)
            self.assertEqual(manifest["labelCount"], 1)
            self.assertEqual(len(tuple(reader.iter_labels())), 1)

    def test_action_target_is_exact_teacher_binding_not_feature_view(self) -> None:
        validated, request, result = _teacher_request_and_result(_source_fixture())
        row = materialize_selected_label(
            validated,
            request,
            result,
            execution=TeacherExecutionBindingV1.reference(),
            teacher_config_digest=PublicObservationTeacherConfigV1.reference().digest,
        )
        self.assertEqual(
            row["target"]["chosenSemanticAction"],
            result.exact_source_binding.exact_action,
        )
        self.assertNotEqual(
            row["target"]["chosenSemanticAction"],
            request.item.candidates[result.source_binding_ordinal].feature_view,
        )

    def test_folded_target_is_exact_teacher_response_binding(self) -> None:
        validated, request, result = _teacher_request_and_result(
            _source_fixture(folded=True),
            folded=True,
        )
        row = materialize_selected_label(
            validated,
            request,
            result,
            execution=TeacherExecutionBindingV1.reference(),
            teacher_config_digest=PublicObservationTeacherConfigV1.reference().digest,
        )
        self.assertEqual(
            row["target"]["chosenSemanticResponse"],
            result.exact_source_binding.exact_response,
        )

    def test_multiple_executable_candidates_still_accept_one_semantic_match(self) -> None:
        validated, request, result = _teacher_request_and_result(
            _source_fixture(candidate_count=2),
        )
        row = materialize_selected_label(
            validated,
            request,
            result,
            execution=TeacherExecutionBindingV1.reference(),
            teacher_config_digest=PublicObservationTeacherConfigV1.reference().digest,
        )
        self.assertEqual(
            row["binding"]["sourceBindingOrdinal"],
            result.source_binding_ordinal,
        )

    def test_missing_or_ambiguous_perspective_roster_seat_rejects(self) -> None:
        sample = _source_fixture()
        sample["provenance"]["environmentIdentity"]["roster"][0]["playerId"] = "player-0"
        sample["provenance"]["environmentIdentity"]["roster"].append(
            {"playerId": "player-0", "seatIndex": 2}
        )
        with self.assertRaises(TeacherExecutionError):
            teacher_seat_index(sample)

    def test_teacher_seat_comes_from_exact_roster_player_match(self) -> None:
        sample = _source_fixture()
        sample["sourceReference"]["perspectivePlayerId"] = "player-1"
        self.assertEqual(teacher_seat_index(sample), 1)

    def test_structured_result_is_counted_as_no_label(self) -> None:
        with self.assertRaises(LabelMaterializerError):
            materialize_selected_label(
                _validated_sample(_source_fixture()),
                None,
                None,
                execution=TeacherExecutionBindingV1.reference(),
                teacher_config_digest=PublicObservationTeacherConfigV1.reference().digest,
            )

    def test_wrong_execution_provenance_rejects(self) -> None:
        execution = TeacherExecutionBindingV1.reference()
        mutated = type(execution)(
            teacher_policy_tie_schedule_identity=execution.teacher_policy_tie_schedule_identity,
            teacher_policy_tie_seed=1,
            initial_policy_tie_cursor=execution.initial_policy_tie_cursor,
            teacher_tie_state_scope=execution.teacher_tie_state_scope,
            legacy_a9_policy_seed_reused=execution.legacy_a9_policy_seed_reused,
            teacher_admission_purpose_identity=execution.teacher_admission_purpose_identity,
            teacher_admission_result=execution.teacher_admission_result,
            teacher_admission_plan_identity=execution.teacher_admission_plan_identity,
            teacher_admission_plan_digest=execution.teacher_admission_plan_digest,
        )
        with self.assertRaises(TeacherExecutionError):
            mutated.validate()
        self.assertEqual(mutated.teacher_policy_tie_seed, 1)


if __name__ == "__main__":
    unittest.main()

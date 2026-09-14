import copy
import dataclasses
import tempfile
import unittest
from pathlib import Path

from argentum_ml.characterization.c1_03 import folded_selection_is_teacher_owned
from argentum_ml.contracts.canonical_json import canonical_json
from argentum_ml.contracts.identities import POLICY_TIE_RNG_IDENTITY
from argentum_ml.data.derived_reader import DerivedArtifactReader
from argentum_ml.data.variable_batch import CandidateFeature, VariableDomainItem
from argentum_ml.inference import InferenceRequest
from argentum_ml.selection import ExactSemanticSourceBinding, PolicyTieRngStateV1
from argentum_ml.teacher import (
    NoLabelTeacherResultV1,
    PublicObservationTeacherConfigV1,
    PublicObservationTeacherRequestV1,
    PublicObservationTeacherV1,
    SelectedTeacherResultV1,
)
from tests.test_derived_reader import _artifact, _sample


SOURCE_COMMIT = "0" * 40


def _rng() -> PolicyTieRngStateV1:
    return PolicyTieRngStateV1.from_policy_seed(
        17,
        0,
        policy_rng_identity=POLICY_TIE_RNG_IDENTITY,
    )


def _teacher() -> PublicObservationTeacherV1:
    return PublicObservationTeacherV1(
        PublicObservationTeacherConfigV1.reference(),
        SOURCE_COMMIT,
    )


def _folded_request(
    source_response: dict,
    model_response: dict,
    *,
    source_entity_id: str | None = None,
) -> tuple[PublicObservationTeacherRequestV1, dict[str, str]]:
    sample = _sample()
    alias_to_source: dict[str, str] = {}
    if source_entity_id is not None:
        alias_to_source = {"entity-2": source_entity_id}
        sample["binding"]["entityAliasBindings"].append(
            {"alias": "entity-2", "sourceEntityId": source_entity_id}
        )

    shape = {
        "availableColors": [],
        "budget": None,
        "maxSelections": 1,
        "minSelections": 1,
        "numericMax": None,
        "numericMin": None,
        "totalToDistribute": None,
    }
    source_candidate = copy.deepcopy(
        sample["binding"]["completeLegalDomain"]["candidates"][0]
    )
    source_candidate.update(
        {
            "actionSemantics": source_response,
            "isDecisionOption": True,
            "kind": "DECISION",
            "requiredPayloadFields": [],
        }
    )
    model_candidate = copy.deepcopy(sample["input"]["domain"]["candidates"][0])
    model_candidate.update(
        {
            "actionSemantics": model_response,
            "isDecisionOption": True,
            "kind": "DECISION",
            "requiredPayloadFields": [],
        }
    )

    decision_kind = (
        "SELECT_CARDS"
        if source_response["type"] == "CardsSelectedResponse"
        else "CHOOSE_OPTION"
    )
    source_domain = sample["binding"]["completeLegalDomain"]
    source_domain.update(
        {
            "candidates": [source_candidate],
            "decisionKind": decision_kind,
            "kind": "FOLDED_DECISION_OPTIONS",
            "shape": shape,
        }
    )
    model_domain = sample["input"]["domain"]
    model_domain.update(
        {
            "candidates": [model_candidate],
            "decisionKind": decision_kind,
            "kind": "FOLDED_DECISION_OPTIONS",
            "shape": shape,
        }
    )
    sample["input"]["decisionContext"]["domainKind"] = "FOLDED_DECISION_OPTIONS"
    chosen = {"response": source_response, "type": "chosen-response"}
    sample["binding"]["selectedExactSourceBinding"] = chosen
    sample["binding"]["sourceBindingOrdinals"] = [0]
    sample["binding"]["semanticTieDiscriminators"] = {}
    sample["target"] = {
        "chosenSemanticAction": None,
        "chosenSemanticResponse": chosen,
    }

    with tempfile.TemporaryDirectory() as directory:
        artifact = _artifact(Path(directory), sample=sample)
        reader = DerivedArtifactReader.open(artifact)
        stream = reader.iter_validated_samples_for_inference()
        validated = next(stream)
        stream.close()
        source_candidates = validated.sample["binding"]["completeLegalDomain"]["candidates"]
        model_candidates = validated.sample["input"]["domain"]["candidates"]
        item = VariableDomainItem(
            model_input=validated.sample["input"],
            candidates=(
                CandidateFeature(
                    feature_view=model_candidates[0],
                    source_binding_ordinal=0,
                    present=True,
                    executable_support=source_candidates[0]["affordable"],
                ),
            ),
            structured_domain=None,
            target_binding_ordinal=0,
        )
        inference_request = InferenceRequest.from_validated_sample(validated, item)
    return PublicObservationTeacherRequestV1.from_inference_request(inference_request), alias_to_source


class C1_03AFoldedOwnershipCharacterizationTests(unittest.TestCase):
    def test_entity_free_folded_response_is_owned_through_normal_selection(self) -> None:
        response = {"optionIndex": 0, "type": "OptionChosenResponse"}
        request, _ = _folded_request(response, copy.deepcopy(response))
        result = _teacher().select(request, _rng())

        self.assertIsInstance(request.inference_request, InferenceRequest)
        self.assertIsInstance(result, SelectedTeacherResultV1)
        self.assertNotIsInstance(result, NoLabelTeacherResultV1)
        self.assertEqual(result.source_binding_ordinal, 0)
        self.assertEqual(result.exact_source_binding.source_binding_ordinal_audit, 0)
        self.assertEqual(
            result.exact_source_binding,
            request.source_bindings.exact_binding_for(result.source_binding_ordinal),
        )
        self.assertEqual(
            result.exact_source_binding.exact_response["response"],
            request.item.candidates[0].feature_view["actionSemantics"],
        )
        self.assertTrue(folded_selection_is_teacher_owned(request, result))

    def test_identity_bearing_folded_response_is_owned_by_factory_binding_authority(self) -> None:
        source_entity_id = "synthetic-folded-card"
        source_response = {
            "selectedCards": [source_entity_id],
            "type": "CardsSelectedResponse",
        }
        public_response = {
            "selectedCards": ["entity-2"],
            "type": "CardsSelectedResponse",
        }
        request, alias_to_source = _folded_request(
            source_response,
            public_response,
            source_entity_id=source_entity_id,
        )
        result = _teacher().select(request, _rng())

        self.assertIsInstance(request.inference_request, InferenceRequest)
        self.assertIsInstance(result, SelectedTeacherResultV1)
        self.assertEqual(result.source_binding_ordinal, 0)
        self.assertEqual(result.exact_source_binding.source_binding_ordinal_audit, 0)

        # A: the selected ordinal addresses the complete legal Folded domain.
        self.assertIn(
            result.source_binding_ordinal,
            request.source_bindings.source_binding_ordinals,
        )
        # B: Selection V2 returned the factory-issued exact binding for that ordinal.
        self.assertIs(
            result.exact_source_binding,
            request.source_bindings.exact_binding_for(result.source_binding_ordinal),
        )
        self.assertEqual(
            result.exact_source_binding.exact_response["response"],
            source_response,
        )
        # C: the public response is the accepted alias projection in the same binding channel.
        projected = request.item.candidates[0].feature_view["actionSemantics"]
        self.assertEqual(projected, public_response)
        self.assertEqual(alias_to_source[projected["selectedCards"][0]], source_entity_id)

        # The source/public representations remain different by contract.
        self.assertNotEqual(
            canonical_json(source_response),
            canonical_json(projected),
        )
        self.assertTrue(folded_selection_is_teacher_owned(request, result))

    def test_current_ownership_predicate_rejects_non_factory_binding_identity(self) -> None:
        source_entity_id = "synthetic-folded-card"
        source_response = {
            "selectedCards": [source_entity_id],
            "type": "CardsSelectedResponse",
        }
        public_response = {
            "selectedCards": ["entity-2"],
            "type": "CardsSelectedResponse",
        }
        request, _ = _folded_request(
            source_response,
            public_response,
            source_entity_id=source_entity_id,
        )
        result = _teacher().select(request, _rng())
        substituted_binding = ExactSemanticSourceBinding(
            exact_action=None,
            exact_response={"response": public_response, "type": "chosen-response"},
            source_binding_ordinal_audit=result.source_binding_ordinal,
        )
        substituted_result = dataclasses.replace(
            result,
            exact_source_binding=substituted_binding,
        )

        self.assertIsNot(
            substituted_result.exact_source_binding,
            request.source_bindings.exact_binding_for(result.source_binding_ordinal),
        )
        self.assertFalse(folded_selection_is_teacher_owned(request, substituted_result))


if __name__ == "__main__":
    unittest.main()

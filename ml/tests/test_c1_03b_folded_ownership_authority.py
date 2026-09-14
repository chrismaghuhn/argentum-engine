import copy
import dataclasses
import unittest

from argentum_ml.characterization.c1_03 import folded_selection_is_teacher_owned
from argentum_ml.selection import ExactSemanticSourceBinding
from argentum_ml.teacher import SelectedTeacherResultV1
from tests.test_c1_03a_folded_ownership_characterization import (
    _folded_request,
    _rng,
    _teacher,
)
from tests.test_public_observation_teacher import _flat_request


def _identity_bearing_request():
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
    return request, source_response, public_response


class C1_03BFoldedOwnershipAuthorityTests(unittest.TestCase):
    def test_identity_bearing_valid_selection_should_be_owned(self) -> None:
        request, _, _ = _identity_bearing_request()
        result = _teacher().select(request, _rng())

        self.assertIsInstance(result, SelectedTeacherResultV1)
        self.assertTrue(folded_selection_is_teacher_owned(request, result))

    def test_forged_public_looking_binding_should_not_be_owned(self) -> None:
        request, _, public_response = _identity_bearing_request()
        result = _teacher().select(request, _rng())
        self.assertIsInstance(result, SelectedTeacherResultV1)
        substituted_binding = ExactSemanticSourceBinding(
            exact_action=None,
            exact_response={"response": public_response, "type": "chosen-response"},
            source_binding_ordinal_audit=result.source_binding_ordinal,
        )
        substituted_result = dataclasses.replace(
            result,
            exact_source_binding=substituted_binding,
        )

        self.assertFalse(folded_selection_is_teacher_owned(request, substituted_result))

    def test_entity_free_valid_selection_remains_owned(self) -> None:
        response = {"optionIndex": 0, "type": "OptionChosenResponse"}
        request, _ = _folded_request(response, copy.deepcopy(response))
        result = _teacher().select(request, _rng())

        self.assertIsInstance(result, SelectedTeacherResultV1)
        self.assertTrue(folded_selection_is_teacher_owned(request, result))

    def test_binding_from_another_folded_ordinal_is_not_owned(self) -> None:
        request = _flat_request(
            [{"kind": "OptionA"}, {"kind": "OptionB"}],
            response_domain=True,
        )
        result = _teacher().select(request, _rng())
        self.assertIsInstance(result, SelectedTeacherResultV1)
        other_ordinal = 1 if result.source_binding_ordinal == 0 else 0
        substituted_result = dataclasses.replace(
            result,
            exact_source_binding=request.source_bindings.exact_binding_for(other_ordinal),
        )

        self.assertFalse(folded_selection_is_teacher_owned(request, substituted_result))

    def test_value_equivalent_binding_copy_is_owned_without_object_identity(self) -> None:
        request, _, _ = _identity_bearing_request()
        result = _teacher().select(request, _rng())
        self.assertIsInstance(result, SelectedTeacherResultV1)
        expected = request.source_bindings.exact_binding_for(result.source_binding_ordinal)
        value_equivalent_copy = ExactSemanticSourceBinding(
            exact_action=expected.exact_action,
            exact_response=expected.exact_response,
            source_binding_ordinal_audit=expected.source_binding_ordinal_audit,
        )
        self.assertEqual(value_equivalent_copy, expected)
        self.assertIsNot(value_equivalent_copy, expected)
        copied_result = dataclasses.replace(
            result,
            exact_source_binding=value_equivalent_copy,
        )

        self.assertTrue(folded_selection_is_teacher_owned(request, copied_result))

    def test_incomplete_chosen_response_binding_is_not_owned(self) -> None:
        request, _, _ = _identity_bearing_request()
        result = _teacher().select(request, _rng())
        self.assertIsInstance(result, SelectedTeacherResultV1)
        malformed_binding = ExactSemanticSourceBinding(
            exact_action=None,
            exact_response={"type": "chosen-response"},
            source_binding_ordinal_audit=result.source_binding_ordinal,
        )
        malformed_result = dataclasses.replace(
            result,
            exact_source_binding=malformed_binding,
        )

        self.assertFalse(folded_selection_is_teacher_owned(request, malformed_result))


if __name__ == "__main__":
    unittest.main()

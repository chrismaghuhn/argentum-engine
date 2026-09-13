import math
import unittest

from argentum_ml.contracts.identities import POLICY_TIE_RNG_IDENTITY
from argentum_ml.selection.policy_tie_rng import PolicyTieRngStateV1
from argentum_ml.selection.selection_v2 import (
    ExactSemanticSourceBinding,
    SelectionCandidate,
    SelectionError,
    select_v2,
)


def _binding(kind: str, ordinal: int | None = None) -> ExactSemanticSourceBinding:
    return ExactSemanticSourceBinding(
        exact_action={"kind": kind} if kind.startswith("action") else None,
        exact_response={"kind": kind} if kind.startswith("response") else None,
        source_binding_ordinal_audit=ordinal,
    )


def _rng() -> PolicyTieRngStateV1:
    return PolicyTieRngStateV1.from_policy_seed(
        4259905,
        0,
        policy_rng_identity=POLICY_TIE_RNG_IDENTITY,
    )


class SelectionV2Tests(unittest.TestCase):
    def test_exactly_one_source_binding_channel_is_required(self) -> None:
        with self.assertRaises(ValueError):
            ExactSemanticSourceBinding(None, None, None)
        with self.assertRaises(ValueError):
            ExactSemanticSourceBinding({"action": 1}, {"response": 1}, None)

    def test_unique_argmax_and_executable_mask_do_not_draw(self) -> None:
        candidates = [
            SelectionCandidate(0, _binding("action-a", 0), 0.5, True, True, None),
            SelectionCandidate(1, _binding("action-b", 1), 0.9, True, False, None),
            SelectionCandidate(2, _binding("action-c", 2), 0.2, True, True, None),
        ]
        result = select_v2(candidates, _rng())
        self.assertEqual(result.exact_source_binding.exact_action, {"kind": "action-a"})
        self.assertEqual(result.audit_source_binding_ordinal, 0)
        self.assertEqual(result.rng_draw_count, 0)
        self.assertEqual(result.cursor_before, result.cursor_after)

    def test_unique_valid_semantic_discriminator_breaks_tie_without_draw(self) -> None:
        candidates = [
            SelectionCandidate(0, _binding("action-a", 0), 1.0, True, True, '{"kind":"b"}'),
            SelectionCandidate(1, _binding("action-b", 1), 1.0, True, True, '{"kind":"a"}'),
        ]
        result = select_v2(candidates, _rng())
        self.assertEqual(result.exact_source_binding.exact_action, {"kind": "action-b"})
        self.assertEqual(result.rng_draw_count, 0)

    def test_missing_or_non_unique_discriminator_uses_policy_rng(self) -> None:
        candidates = [
            SelectionCandidate(0, _binding("action-a", 0), 1.0, True, True, None),
            SelectionCandidate(1, _binding("action-b", 1), 1.0, True, True, None),
        ]
        result = select_v2(candidates, _rng())
        self.assertEqual(result.rng_draw_count, 1)
        self.assertEqual(result.cursor_after, 1)

        duplicate = [
            SelectionCandidate(0, _binding("action-a", 0), 1.0, True, True, '{"kind":"same"}'),
            SelectionCandidate(1, _binding("action-b", 1), 1.0, True, True, '{"kind":"same"}'),
        ]
        self.assertEqual(select_v2(duplicate, _rng()).rng_draw_count, 1)

        forbidden = [
            SelectionCandidate(0, _binding("action-a", 0), 1.0, True, True, '{"rowIndex":0}'),
            SelectionCandidate(1, _binding("action-b", 1), 1.0, True, True, '{"sourceBindingOrdinal":1}'),
        ]
        self.assertEqual(select_v2(forbidden, _rng()).rng_draw_count, 1)

    def test_rejects_duplicate_exact_source_alternatives_before_rng(self) -> None:
        duplicate_binding = ExactSemanticSourceBinding({"kind": "same"}, None, None)
        candidates = [
            SelectionCandidate(0, duplicate_binding, 1.0, True, True, None),
            SelectionCandidate(1, duplicate_binding, 1.0, True, True, None),
        ]
        with self.assertRaises(SelectionError):
            select_v2(candidates, _rng())

    def test_fails_closed_before_rng_for_empty_invalid_or_incomplete_candidates(self) -> None:
        with self.assertRaises(SelectionError):
            select_v2([], _rng())
        with self.assertRaises(SelectionError):
            select_v2([SelectionCandidate(0, _binding("action-a", 0), math.nan, True, True, None)], _rng())
        with self.assertRaises(SelectionError):
            select_v2([SelectionCandidate(0, _binding("action-a", 7), 1.0, True, True, None)], _rng())

    def test_permutation_does_not_change_semantic_selection(self) -> None:
        first = [
            SelectionCandidate(0, _binding("action-a", 0), 1.0, True, True, None),
            SelectionCandidate(1, _binding("action-b", 1), 1.0, True, True, None),
        ]
        second = [first[1], first[0]]
        self.assertEqual(
            select_v2(first, _rng()).exact_source_binding,
            select_v2(second, _rng()).exact_source_binding,
        )

    def test_structured_binding_is_returned_without_completion(self) -> None:
        exact = ExactSemanticSourceBinding(None, {"type": "TargetsResponse", "selected": []}, None)
        candidates = [SelectionCandidate(0, exact, 1.0, True, True, None)]
        result = select_v2(candidates, _rng())
        self.assertEqual(result.exact_source_binding, exact)

    def test_exact_source_binding_defensively_freezes_nested_values(self) -> None:
        action = {"kind": "action", "nested": {"values": [1]}}
        binding = ExactSemanticSourceBinding(action, None, None)
        action["nested"]["values"].append(2)
        self.assertEqual(binding.exact_action["nested"]["values"], [1])
        with self.assertRaises(TypeError):
            binding.exact_action["nested"]["values"].append(3)
        with self.assertRaises(TypeError):
            binding.exact_action["kind"] = "mutated"


if __name__ == "__main__":
    unittest.main()

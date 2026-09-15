import math
import unittest

from argentum_ml.contracts.identities import POLICY_TIE_RNG_IDENTITY
from argentum_ml.contracts.tie_discriminator import SemanticTieDiscriminator
from argentum_ml.selection.ordinal_selection import (
    OrdinalSelectionError,
    SelectionAddressCandidate,
    select_ordinal,
)
from argentum_ml.selection.policy_tie_rng import PolicyTieRngStateV1
from argentum_ml.selection.selection_v2 import (
    ExactSemanticSourceBinding,
    SelectionCandidate,
    select_v2,
)


def _rng() -> PolicyTieRngStateV1:
    return PolicyTieRngStateV1.from_policy_seed(
        4259905,
        0,
        policy_rng_identity=POLICY_TIE_RNG_IDENTITY,
    )


def _discriminator(value: str) -> SemanticTieDiscriminator:
    return SemanticTieDiscriminator.from_json(value, forbidden_raw_values=set())


def _address(
    ordinal: int,
    score: float,
    *,
    present: bool = True,
    executable: bool = True,
    discriminator: SemanticTieDiscriminator | None = None,
) -> SelectionAddressCandidate:
    return SelectionAddressCandidate(
        source_binding_ordinal=ordinal,
        score=score,
        candidate_presence=present,
        candidate_executable_support=executable,
        deterministic_semantic_tie_discriminator=discriminator,
    )


def _exact(
    ordinal: int,
    score: float,
    *,
    present: bool = True,
    executable: bool = True,
    discriminator: SemanticTieDiscriminator | None = None,
) -> SelectionCandidate:
    binding = ExactSemanticSourceBinding(
        exact_action={"kind": f"action-{ordinal}"},
        exact_response=None,
        source_binding_ordinal_audit=ordinal,
    )
    return SelectionCandidate(
        source_binding_ordinal=ordinal,
        exact_source_binding=binding,
        score=score,
        candidate_presence=present,
        candidate_executable_support=executable,
        deterministic_semantic_tie_discriminator=discriminator,
    )


class SelectionAddressTests(unittest.TestCase):
    def test_unique_maximum_returns_only_the_selected_ordinal_without_rng_draw(self) -> None:
        result = select_ordinal(
            [
                _address(7, 0.9),
                _address(3, 0.9, executable=False),
                _address(11, 0.2),
            ],
            _rng(),
        )

        self.assertEqual(result.selected_source_binding_ordinal, 7)
        self.assertEqual(result.rng_draw_count, 0)
        self.assertEqual(result.rng_state.cursor, 0)

    def test_semantic_discriminator_tie_is_independent_of_candidate_order(self) -> None:
        first = [
            _address(7, 1.0, discriminator=_discriminator('{"semantic":"z"}')),
            _address(3, 1.0, discriminator=_discriminator('{"semantic":"a"}')),
        ]
        second = list(reversed(first))

        first_result = select_ordinal(first, _rng())
        second_result = select_ordinal(second, _rng())

        self.assertEqual(first_result.selected_source_binding_ordinal, 3)
        self.assertEqual(second_result.selected_source_binding_ordinal, 3)
        self.assertEqual(first_result.rng_state, second_result.rng_state)

    def test_exact_tie_orders_by_ordinal_before_policy_rng(self) -> None:
        first = select_ordinal([_address(9, 1.0), _address(2, 1.0)], _rng())
        second = select_ordinal([_address(2, 1.0), _address(9, 1.0)], _rng())

        self.assertEqual(first.selected_source_binding_ordinal, second.selected_source_binding_ordinal)
        self.assertEqual(first.rng_state, second.rng_state)
        self.assertEqual(first.rng_draw_count, 1)

    def test_flat_ordinal_core_matches_exact_selection_v2(self) -> None:
        discriminator = _discriminator('{"semantic":"a"}')
        vectors = (
            [
                _address(5, 0.5),
                _address(2, 0.9),
                _address(8, 0.1, executable=False),
            ],
            [
                _address(5, 1.0, discriminator=discriminator),
                _address(2, 1.0, discriminator=_discriminator('{"semantic":"b"}')),
            ],
            [
                _address(5, 1.0),
                _address(2, 1.0),
                _address(8, 1.0, present=False, executable=False),
            ],
        )

        for vector in vectors:
            exact = [
                _exact(
                    candidate.source_binding_ordinal,
                    candidate.score,
                    present=candidate.candidate_presence,
                    executable=candidate.candidate_executable_support,
                    discriminator=candidate.deterministic_semantic_tie_discriminator,
                )
                for candidate in vector
            ]
            ordinal_result = select_ordinal(vector, _rng())
            exact_result = select_v2(exact, _rng())
            self.assertEqual(
                ordinal_result.selected_source_binding_ordinal,
                exact_result.audit_source_binding_ordinal,
            )
            self.assertEqual(ordinal_result.rng_state, exact_result.rng_state)
            self.assertEqual(ordinal_result.rng_draw_count, exact_result.rng_draw_count)

    def test_masks_and_invalid_scores_fail_closed(self) -> None:
        with self.assertRaises(OrdinalSelectionError):
            select_ordinal([_address(1, math.nan)], _rng())
        with self.assertRaises(OrdinalSelectionError):
            select_ordinal([_address(1, 1.0, present=False, executable=True)], _rng())
        with self.assertRaises(OrdinalSelectionError):
            select_ordinal([_address(1, 1.0), _address(1, 0.0)], _rng())
        with self.assertRaises(OrdinalSelectionError):
            select_ordinal([], _rng())


if __name__ == "__main__":
    unittest.main()

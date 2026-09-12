import unittest

from argentum_ml.data.variable_batch import (
    CandidateFeature,
    VariableDomainBatch,
    VariableDomainItem,
)


def _flat_item(count: int, *, target: int, unaffordable: int | None = None) -> VariableDomainItem:
    candidates = tuple(
        CandidateFeature(
            feature_view={"kind": f"kind-{ordinal}"},
            source_binding_ordinal=ordinal,
            present=True,
            executable_support=ordinal != unaffordable,
        )
        for ordinal in range(count)
    )
    model_input = {
        "decisionContext": {},
        "observation": {},
        "domain": {"kind": "ACTION_CANDIDATES", "candidates": [candidate.feature_view for candidate in candidates]},
    }
    return VariableDomainItem(
        model_input=model_input,
        candidates=candidates,
        structured_domain=None,
        target_binding_ordinal=target,
    )


class VariableBatchTests(unittest.TestCase):
    def test_preserves_variable_boundaries_masks_and_structured_domain(self) -> None:
        structured = {"type": "targets", "version": 2, "requirements": []}
        structured_item = VariableDomainItem(
            model_input={
                "decisionContext": {},
                "observation": {},
                "domain": {"kind": "STRUCTURED_DECISION", "structuredType": structured},
            },
            candidates=(),
            structured_domain=structured,
            target_binding_ordinal=None,
        )
        batch = VariableDomainBatch.from_items([
            _flat_item(2, target=1, unaffordable=1),
            _flat_item(4, target=3),
            structured_item,
        ])

        self.assertEqual(batch.item_offsets, (0, 1, 2, 3))
        self.assertEqual(batch.candidate_offsets, (0, 2, 6, 6))
        self.assertEqual(batch.presence_mask, (True, True, True, True, True, True))
        self.assertEqual(batch.executable_support_mask, (True, False, True, True, True, True))
        self.assertEqual(batch.padding_mask, ((False, False, True, True), (False, False, False, False), (True, True, True, True)))
        self.assertEqual(batch.items[2].structured_domain, structured)
        self.assertEqual(batch.items[0].target_binding_ordinal, 1)

    def test_permutation_moves_candidate_records_without_rebinding_target(self) -> None:
        item = _flat_item(4, target=3, unaffordable=2)
        permuted = item.permute_candidates((2, 0, 3, 1))

        self.assertEqual(
            [candidate.source_binding_ordinal for candidate in permuted.candidates],
            [2, 0, 3, 1],
        )
        self.assertEqual(permuted.target_binding_ordinal, 3)
        selected = next(candidate for candidate in permuted.candidates if candidate.source_binding_ordinal == permuted.target_binding_ordinal)
        self.assertTrue(selected.present)
        self.assertTrue(selected.executable_support)

    def test_rejects_inconsistent_offsets_masks_and_target_membership(self) -> None:
        item = _flat_item(2, target=1)
        with self.assertRaises(ValueError):
            VariableDomainBatch(
                items=(item,),
                item_offsets=(0, 2),
                candidate_offsets=(0, 2),
                padding_mask=((False, False),),
            )
        with self.assertRaises(ValueError):
            VariableDomainItem(
                model_input=item.model_input,
                candidates=item.candidates,
                structured_domain=None,
                target_binding_ordinal=99,
            )

    def test_has_no_truncation_or_top_k_operation(self) -> None:
        self.assertFalse(hasattr(VariableDomainBatch, "truncate"))
        self.assertFalse(hasattr(VariableDomainBatch, "top_k"))
        self.assertFalse(hasattr(VariableDomainBatch, "max_candidates"))


if __name__ == "__main__":
    unittest.main()

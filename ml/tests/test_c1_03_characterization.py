import dataclasses
import unittest

from argentum_ml.characterization.c1_03 import (
    ALLOWED_OFFLINE_PARTITIONS,
    C1_03_CHARACTERIZATION_PLAN_IDENTITY,
    C1_03PlanV1,
    FIRST_DIVERGENCE_LIMIT,
    SOURCE_DATASET_ID,
    TEACHER_ADMISSION_PURPOSE_IDENTITY,
)


class C1_03CharacterizationTests(unittest.TestCase):
    def test_plan_is_exactly_dataset_bound_and_uses_no_test_partition(self):
        plan = C1_03PlanV1.reference()
        self.assertEqual(plan.admission_purpose_identity, TEACHER_ADMISSION_PURPOSE_IDENTITY)
        self.assertEqual(plan.source_dataset_id, SOURCE_DATASET_ID)
        self.assertEqual(plan.allowed_partitions, ALLOWED_OFFLINE_PARTITIONS)
        self.assertEqual(plan.allowed_partitions, ("TRAIN", "VALIDATION"))
        self.assertEqual(plan.test_rows_submitted_to_teacher, 0)
        self.assertEqual(plan.first_divergence_limit, FIRST_DIVERGENCE_LIMIT)
        self.assertEqual(plan.teacher_policy_tie_seed, 0)
        self.assertEqual(plan.initial_policy_tie_cursor, 0)
        self.assertFalse(plan.legacy_a9_policy_seed_reused)
        self.assertEqual(plan.first_divergence_per_episode, 1)
        self.assertEqual(plan.policy_a_executions, 16)
        self.assertEqual(plan.policy_b_executions, 16)
        self.assertEqual(plan.plan_identity, C1_03_CHARACTERIZATION_PLAN_IDENTITY)

    def test_plan_serialization_is_canonical_and_immutable(self):
        plan = C1_03PlanV1.reference()
        exported = plan.to_dict()
        self.assertEqual(plan.digest, C1_03PlanV1.from_dict(exported).digest)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            plan.source_dataset_id = "different"


if __name__ == "__main__":
    unittest.main()

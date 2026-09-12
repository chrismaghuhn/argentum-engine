import unittest
import inspect

from argentum_ml.data.split import assign_partition, bucket


class SplitTests(unittest.TestCase):
    def test_kats(self) -> None:
        self.assertEqual(bucket("0" * 64), 75)
        self.assertEqual(assign_partition("0" * 64), "TRAIN")
        self.assertEqual(bucket("09" * 32), 88)
        self.assertEqual(assign_partition("09" * 32), "VALIDATION")
        self.assertEqual(bucket("1c" * 32), 91)
        self.assertEqual(assign_partition("1c" * 32), "TEST")

    def test_only_semantic_episode_id_affects_the_split(self) -> None:
        semantic_episode_id = "0" * 64
        expected = assign_partition(semantic_episode_id)
        self.assertEqual(tuple(inspect.signature(assign_partition).parameters), ("semantic_episode_id",))
        for _decision_count, _trajectory_id, _collection_job_id, _row_order, _path in (
            (0, "trajectory-a", "collection-a", 0, "first.ndjson"),
            (17, "trajectory-b", "collection-b", 99, "other.ndjson"),
        ):
            self.assertEqual(assign_partition(semantic_episode_id), expected)
        with self.assertRaises(ValueError):
            assign_partition("A" * 64)


if __name__ == "__main__":
    unittest.main()

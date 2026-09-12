import unittest

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
        self.assertEqual(assign_partition("0" * 64), assign_partition("0" * 64))
        with self.assertRaises(ValueError):
            assign_partition("A" * 64)


if __name__ == "__main__":
    unittest.main()

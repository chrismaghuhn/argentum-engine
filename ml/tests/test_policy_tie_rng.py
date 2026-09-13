import unittest

from argentum_ml.selection.policy_tie_rng import (
    POLICY_RNG_IDENTITY,
    UINT64_MAX,
    PolicyTieRngError,
    PolicyTieRngStateV1,
    derive_stream_key,
    policy_seed_bits_hex,
)


_V1 = POLICY_RNG_IDENTITY


class PolicyTieRngTests(unittest.TestCase):
    def test_stream_and_raw_word_kats(self) -> None:
        state = PolicyTieRngStateV1.from_policy_seed(4259905, 0, policy_rng_identity=_V1)
        self.assertEqual(state.stream_key.hex(), "e524b34e031fbdc4f616f6725de4335c95207edadbcbfbd09bdb91952152e8d1")
        word, state = state.next_raw_word()
        self.assertEqual(word, 0x43C8392191B2467A)
        word, state = state.next_raw_word()
        self.assertEqual(word, 0xCA6D38CC6027F7AA)
        state = PolicyTieRngStateV1.from_policy_seed(4259905, 0, policy_rng_identity=_V1)
        result, state = state.uniform_below(10)
        self.assertEqual(result, 2)
        self.assertEqual(state.cursor, 1)

        seat_one = PolicyTieRngStateV1.from_policy_seed(4259905, 1, policy_rng_identity=_V1)
        self.assertEqual(seat_one.stream_key.hex(), "12788fa167a4187b53e81f24c9d6b52a3e171e57195ddb8ece312ae1408284f2")
        word, _ = seat_one.next_raw_word()
        self.assertEqual(word, 0x17C97B72E4A765B3)

        seed_zero = PolicyTieRngStateV1.from_policy_seed(0, 3, policy_rng_identity=_V1)
        self.assertEqual(seed_zero.stream_key.hex(), "cb6aba6ce62b94db20e5a055e8c0a33dfec0c02e70689f764c69d6a9dee8c8e3")
        value, seed_zero = seed_zero.uniform_below(9223372036854775809)
        self.assertEqual(value, 6424273174012658111)
        self.assertEqual(seed_zero.cursor, 2)
        probe = PolicyTieRngStateV1.from_policy_seed(0, 3, policy_rng_identity=_V1)
        word, probe = probe.next_raw_word()
        self.assertEqual(word, 0xE0E9C4649D28823B)
        word, _ = probe.next_raw_word()
        self.assertEqual(word, 0x59279A6A1D1881BF)

    def test_signed_long_bits_and_range_validation(self) -> None:
        self.assertEqual(policy_seed_bits_hex(-1), "ffffffffffffffff")
        self.assertEqual(policy_seed_bits_hex(-(1 << 63)), "8000000000000000")
        self.assertEqual(policy_seed_bits_hex((1 << 63) - 1), "7fffffffffffffff")
        for value in (-(1 << 63) - 1, 1 << 63, True, False):
            with self.assertRaises(PolicyTieRngError):
                policy_seed_bits_hex(value)

    def test_identity_and_stream_key_inputs_are_closed(self) -> None:
        with self.assertRaises(PolicyTieRngError):
            derive_stream_key(4259905, 0, policy_rng_identity="explicit-seed/kotlin-policy-state-v1")
        with self.assertRaises(TypeError):
            derive_stream_key(4259905, 0)
        with self.assertRaises(TypeError):
            PolicyTieRngStateV1.from_policy_seed(4259905, 0)
        with self.assertRaises(TypeError):
            derive_stream_key(4259905, 0, semantic_episode_id="episode")
        with self.assertRaises(TypeError):
            derive_stream_key(4259905, 0, actual_engine_seed=7)
        self.assertEqual(POLICY_RNG_IDENTITY, "argentum-ml-policy-tie-rng@v1")

    def test_cursor_exhaustion_rejection_and_value_semantics(self) -> None:
        state = PolicyTieRngStateV1.from_policy_seed(4259905, 0, policy_rng_identity=_V1)
        exhausted = PolicyTieRngStateV1(state.stream_key, UINT64_MAX)
        with self.assertRaises(PolicyTieRngError):
            exhausted.next_raw_word()
        snapshot = state.snapshot()
        fork = state.fork()
        self.assertEqual(snapshot, state)
        self.assertEqual(fork, state)
        restored = PolicyTieRngStateV1.restore(snapshot)
        first, after_first = restored.next_raw_word()
        first_again, _ = state.next_raw_word()
        self.assertEqual(first, first_again)
        self.assertEqual(after_first.cursor, 1)

    def test_uniform_below_rejects_invalid_cardinality(self) -> None:
        state = PolicyTieRngStateV1.from_policy_seed(4259905, 0, policy_rng_identity=_V1)
        for value in (0, 1, UINT64_MAX + 1, True):
            with self.assertRaises(PolicyTieRngError):
                state.uniform_below(value)


if __name__ == "__main__":
    unittest.main()

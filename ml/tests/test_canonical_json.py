import math
import unittest

from argentum_ml.contracts.canonical_json import (
    _RawJsonNumber,
    canonical_bytes,
    canonical_json,
    sha256_hex,
)
from argentum_ml.contracts.identities import (
    CHECKPOINT_MANIFEST_IDENTITY,
    INFERENCE_CONTRACT_IDENTITY,
    NUMERIC_PROFILE_CONTRACT_IDENTITY,
    POLICY_TIE_RNG_IDENTITY,
    POLICY_TIE_STREAM_IDENTITY,
    SELECTION_V1_IDENTITY,
    SELECTION_V2_IDENTITY,
)


class CanonicalJsonTests(unittest.TestCase):
    def test_matches_a3_recursive_key_sort_and_list_order(self) -> None:
        self.assertEqual(
            canonical_json({"z": [3, {"b": "β", "a": True}], "a": None}),
            '{"a":null,"z":[3,{"a":true,"b":"β"}]}',
        )

    def test_rejects_non_finite_and_unsupported_values(self) -> None:
        for value in (math.nan, math.inf, -math.inf):
            with self.assertRaises(ValueError):
                canonical_json(value)
        with self.assertRaises(TypeError):
            canonical_json({1: "non-string key"})
        with self.assertRaises(TypeError):
            canonical_json({"bytes": b"not json"})

    def test_matches_a3_unicode_fixture_bytes_and_digest(self) -> None:
        value = {
            "text": "quote\" slash\\ newline\n tab\t control\u0001 é Ω 😀",
            "emoji😀": "supplementary😀",
        }
        expected = (
            '{"emoji😀":"supplementary😀","text":"quote\\" '
            'slash\\\\ newline\\n tab\\t control\\u0001 é Ω 😀"}'
        )
        self.assertEqual(canonical_json(value), expected)
        self.assertEqual(len(canonical_bytes(value)), 99)
        self.assertEqual(
            sha256_hex(canonical_bytes(value)),
            "d70f61d751958fc2d4c47ee07e27ea45af6325f4984ec5f5a0038fd1a2409875",
        )

    def test_preserves_a3_json_number_spelling(self) -> None:
        self.assertEqual(
            canonical_json(
                {
                    "negative": _RawJsonNumber("-0.0"),
                    "number": _RawJsonNumber("1.00"),
                },
            ),
            '{"negative":-0.0,"number":1.00}',
        )

    def test_exposes_frozen_c0_binding_identities(self) -> None:
        self.assertEqual(CHECKPOINT_MANIFEST_IDENTITY, "argentum-ml-checkpoint-manifest@v1")
        self.assertEqual(INFERENCE_CONTRACT_IDENTITY, "argentum-ml-inference@v1")
        self.assertEqual(
            NUMERIC_PROFILE_CONTRACT_IDENTITY,
            "argentum-ml-numeric-execution-profile@v1",
        )
        self.assertEqual(SELECTION_V1_IDENTITY, "argentum-ml-policy-selection@v1")
        self.assertEqual(SELECTION_V2_IDENTITY, "argentum-ml-policy-selection@v2")
        self.assertEqual(POLICY_TIE_RNG_IDENTITY, "argentum-ml-policy-tie-rng@v1")
        self.assertEqual(POLICY_TIE_STREAM_IDENTITY, "argentum-ml-policy-tie-stream@v1")


if __name__ == "__main__":
    unittest.main()

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from argentum_ml.checkpoint.manifest import (
    ArgentumCheckpointManifestV1,
    CheckpointManifestError,
    NumericExecutionProfileIdentity,
)
from argentum_ml.contracts.canonical_json import canonical_bytes


FIXTURE = Path(__file__).parent / "fixtures" / "checkpoint_manifest_v1.json"
WEIGHT_BYTES = b"c1-checkpoint-fixture-weight-v1\n"


class CheckpointManifestTests(unittest.TestCase):
    def test_parses_fixture_and_recomputes_independent_identity(self) -> None:
        manifest = ArgentumCheckpointManifestV1.from_path(FIXTURE)
        self.assertEqual(manifest.checkpoint_id, "07b88ac3f37a9270bbf1db4888da4fb047429a864f7ab0c778335a69878a4290")
        self.assertEqual(hashlib.sha256(WEIGHT_BYTES).hexdigest(), manifest.weight_content_digest)
        source = json.loads(FIXTURE.read_text(encoding="utf-8"))
        identity_payload = {
            key: value
            for key, value in source.items()
            if key not in {"checkpointId", "version"}
        }
        identity_payload["schema"] = "argentum-ml-checkpoint-id@v1"
        independently_computed = hashlib.sha256(canonical_bytes(identity_payload)).hexdigest()
        self.assertEqual(independently_computed, manifest.checkpoint_id)
        self.assertEqual(manifest.recompute_checkpoint_id(), manifest.checkpoint_id)
        self.assertTrue(manifest.validate_weight_bytes(WEIGHT_BYTES))

    def test_rejects_unknown_or_missing_fields_before_decoding(self) -> None:
        source = json.loads(FIXTURE.read_text(encoding="utf-8"))
        source.pop("modelConfigDigest")
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(source)
        source = json.loads(FIXTURE.read_text(encoding="utf-8"))
        source["futureField"] = True
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(source)

    def test_rejects_bad_kind_pair_profile_and_weight(self) -> None:
        source = json.loads(FIXTURE.read_text(encoding="utf-8"))
        source["policyArtifactKind"] = "UNKNOWN_POLICY"
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(source)
        source = json.loads(FIXTURE.read_text(encoding="utf-8"))
        source["policyRngContractIdentity"] = "NONE_FOR_DETERMINISTIC_MODE"
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(source)
        source = json.loads(FIXTURE.read_text(encoding="utf-8"))
        source["requiredNumericProfileClass"] = ""
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(source)
        manifest = ArgentumCheckpointManifestV1.from_path(FIXTURE)
        with self.assertRaises(CheckpointManifestError):
            manifest.validate_weight_bytes(b"different")

    def test_filename_path_and_weight_identity_are_separate(self) -> None:
        manifest = ArgentumCheckpointManifestV1.from_path(FIXTURE)
        with tempfile.TemporaryDirectory() as directory:
            copied = Path(directory) / "renamed.anything"
            copied.write_bytes(FIXTURE.read_bytes())
            self.assertEqual(
                ArgentumCheckpointManifestV1.from_path(copied).checkpoint_id,
                manifest.checkpoint_id,
            )
        changed = dict(manifest.to_dict())
        changed["weightContentDigest"] = hashlib.sha256(b"changed").hexdigest()
        changed["checkpointId"] = manifest.recompute_checkpoint_id(changed)
        changed_manifest = ArgentumCheckpointManifestV1.from_dict(changed)
        self.assertNotEqual(changed_manifest.checkpoint_id, manifest.checkpoint_id)

    def test_numeric_profile_identity_is_strict(self) -> None:
        profile = NumericExecutionProfileIdentity(
            "argentum-ml-numeric-execution-profile@v1",
            "C1_REFERENCE_NUMERIC_PROFILE",
        )
        self.assertEqual(profile.required_profile_class, "C1_REFERENCE_NUMERIC_PROFILE")
        with self.assertRaises(ValueError):
            NumericExecutionProfileIdentity("wrong@v1", "C1_REFERENCE_NUMERIC_PROFILE")
        with self.assertRaises(ValueError):
            NumericExecutionProfileIdentity("argentum-ml-numeric-execution-profile@v1", "")


if __name__ == "__main__":
    unittest.main()

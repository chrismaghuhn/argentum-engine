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
    def _source(self) -> dict[str, object]:
        return json.loads(FIXTURE.read_text(encoding="utf-8"))

    def _source_with_change(self, key: str, value: object) -> dict[str, object]:
        source = self._source()
        source[key] = value
        identity_payload = {
            name: item
            for name, item in source.items()
            if name not in {"checkpointId", "version"}
        }
        identity_payload["schema"] = "argentum-ml-checkpoint-id@v1"
        source["checkpointId"] = hashlib.sha256(canonical_bytes(identity_payload)).hexdigest()
        return source

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
        source = self._source()
        source.pop("modelConfigDigest")
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(source)
        source = self._source()
        source["futureField"] = True
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(source)

    def test_rejects_non_exact_frozen_contract_identities(self) -> None:
        for key, value in (
            ("modelFacingContractIdentity", "other-model@v99"),
            ("candidateScoringContractIdentity", "other-scoring@v99"),
            ("splitContractIdentity", "other-split@v99"),
        ):
            with self.subTest(key=key):
                with self.assertRaises(CheckpointManifestError):
                    ArgentumCheckpointManifestV1.from_dict(self._source_with_change(key, value))

    def test_rejects_untyped_teacher_provenance_and_identity_shapes(self) -> None:
        for key, value in (
            ("teacherBootstrapProvenance", {"path": "C:\\models\\teacher.pt"}),
            ("weightArtifactIdentity", {"container": "", "artifact": "fixture.weights"}),
            ("weightArtifactIdentity", {"container": "fixture-bytes@v1", "artifact": ""}),
            ("parentCheckpointIdentity", "hello"),
        ):
            with self.subTest(key=key, value=value):
                with self.assertRaises(CheckpointManifestError):
                    ArgentumCheckpointManifestV1.from_dict(self._source_with_change(key, value))

    def test_rejects_boolean_manifest_version(self) -> None:
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(self._source_with_change("version", True))

    def test_manifest_is_deeply_immutable_with_mutable_export(self) -> None:
        source = self._source()
        manifest = ArgentumCheckpointManifestV1.from_dict(source)

        source["weightContentDigest"] = hashlib.sha256(b"changed").hexdigest()
        source["modelImplementationIdentity"]["implementation"] = "changed"
        source["weightArtifactIdentity"]["artifact"] = "changed"
        self.assertEqual(manifest.weight_content_digest, "33f5b2ad62a009da8f27adec3e84b95c4be4340e6d089cd29c8464610642164c")
        self.assertTrue(manifest.validate_weight_bytes(WEIGHT_BYTES))

        with self.assertRaises(TypeError):
            manifest._data["weightContentDigest"] = "rebound"
        with self.assertRaises(TypeError):
            manifest._data["modelImplementationIdentity"]["implementation"] = "rebound"
        with self.assertRaises(TypeError):
            manifest._data["weightArtifactIdentity"]["artifact"] = "rebound"

        exported = manifest.to_dict()
        exported["weightContentDigest"] = hashlib.sha256(b"export mutation").hexdigest()
        exported["modelImplementationIdentity"]["implementation"] = "export mutation"
        exported["weightArtifactIdentity"]["artifact"] = "export mutation"
        self.assertTrue(manifest.validate_weight_bytes(WEIGHT_BYTES))
        self.assertNotEqual(exported["weightContentDigest"], manifest.weight_content_digest)

    def test_rejects_bad_kind_pair_profile_and_weight(self) -> None:
        source = self._source()
        source["policyArtifactKind"] = "UNKNOWN_POLICY"
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(source)
        source = self._source()
        source["policyRngContractIdentity"] = "NONE_FOR_DETERMINISTIC_MODE"
        with self.assertRaises(CheckpointManifestError):
            ArgentumCheckpointManifestV1.from_dict(source)
        source = self._source()
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

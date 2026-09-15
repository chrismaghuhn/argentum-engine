import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from argentum_ml.contracts.canonical_json import canonical_bytes, sha256_hex
from argentum_ml.contracts.identities import ARTIFACT_IDENTITY_SCHEMA
from argentum_ml.data import derived_reader
from argentum_ml.data.derived_reader import DerivedArtifactError, DerivedArtifactReader
from tests.test_derived_reader import _artifact, _sample
from tests.test_dvrp_0_1_reader_validation_performance import (
    _artifact_with_samples,
)


def _read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AssertionError(f"expected object in {path}")
    return value


def _single_artifact(root: Path) -> Path:
    root.mkdir()
    return _artifact(root)


def _same_size_mutation(raw: bytes) -> bytes:
    for index, value in enumerate(raw):
        if value not in (10, 13):
            replacement = 33 if value != 33 else 34
            return raw[:index] + bytes((replacement,)) + raw[index + 1 :]
    raise AssertionError("fixture has no mutable byte")


def _rewrite_manifest_identity(root: Path, manifest: dict) -> None:
    identity_payload = {
        "schema": ARTIFACT_IDENTITY_SCHEMA,
        "derivedViewSchemaIdentity": manifest["derivedViewSchemaIdentity"],
        "sourceDatasetId": manifest["sourceDatasetId"],
        "sourceManifestContentDigest": manifest["sourceManifestContentDigest"],
        "trajectorySchemaIdentity": manifest["trajectorySchemaIdentity"],
        "modelFacingContractIdentity": manifest["modelFacingContractIdentity"],
        "splitContractIdentity": manifest["splitContractIdentity"],
        "materializerImplementationIdentity": manifest["materializerImplementationIdentity"],
        "materializerConfigDigest": manifest["materializerConfigDigest"],
        "samplesContentDigest": manifest["samplesContentDigest"],
        "episodeCountsByPartition": manifest["episodeCountsByPartition"],
        "sampleCountsByPartition": manifest["sampleCountsByPartition"],
    }
    manifest["derivedArtifactId"] = sha256_hex(canonical_bytes(identity_payload))
    content = dict(manifest)
    content.pop("manifestContentDigest")
    manifest["manifestContentDigest"] = sha256_hex(canonical_bytes(content))
    (root / "manifest.json").write_bytes(canonical_bytes(manifest))


class RepeatOpenTrustAttestationCharacterizationTests(unittest.TestCase):
    def test_unchanged_artifact_opens_and_inference_iterates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _single_artifact(Path(directory) / "artifact")
            reader = DerivedArtifactReader.open(root)
            tokens = list(reader.iter_validated_samples_for_inference())
        self.assertEqual(len(tokens), 1)

    def test_second_reader_in_same_process_repeats_full_strict_validation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _single_artifact(Path(directory) / "artifact")
            calls: list[int] = []
            original = derived_reader._validate_sample_file

            def counted(*args: object, **kwargs: object) -> object:
                calls.append(1)
                return original(*args, **kwargs)

            with patch.object(derived_reader, "_validate_sample_file", counted):
                first = DerivedArtifactReader.open(root)
                list(first.iter_validated_samples_for_inference())
                second = DerivedArtifactReader.open(root)
                list(second.iter_validated_samples_for_inference())
        self.assertEqual(len(calls), 2)

    def test_same_size_modified_bytes_are_rejected_before_open(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _single_artifact(Path(directory) / "artifact")
            samples = root / "samples.ndjson"
            original = samples.read_bytes()
            samples.write_bytes(_same_size_mutation(original))
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(root)

    def test_same_size_modified_bytes_are_rejected_after_open_before_first_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _single_artifact(Path(directory) / "artifact")
            reader = DerivedArtifactReader.open(root)
            samples = root / "samples.ndjson"
            samples.write_bytes(_same_size_mutation(samples.read_bytes()))
            with self.assertRaises(DerivedArtifactError):
                list(reader.iter_validated_samples_for_inference())

    def test_truncation_and_append_are_rejected_before_open(self) -> None:
        for operation in ("truncate", "append"):
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as directory:
                root = _single_artifact(Path(directory) / "artifact")
                samples = root / "samples.ndjson"
                original = samples.read_bytes()
                samples.write_bytes(original[:-1] if operation == "truncate" else original + b"\n")
                with self.assertRaises(DerivedArtifactError):
                    DerivedArtifactReader.open(root)

    def test_manifest_mutation_is_rejected_by_new_reader_but_not_reread_by_existing_reader(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _single_artifact(Path(directory) / "artifact")
            reader = DerivedArtifactReader.open(root)
            manifest_path = root / "manifest.json"
            manifest = _read_json(manifest_path)
            manifest["version"] = 2
            manifest_path.write_bytes(canonical_bytes(manifest))
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(root)
            self.assertEqual(len(list(reader.iter_validated_samples_for_inference())), 1)

    def test_replacement_of_samples_file_with_changed_content_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            root = _single_artifact(parent / "artifact")
            replacement_path = parent / "replacement"
            replacement_path.mkdir()
            replacement_root, _ = _artifact_with_samples(replacement_path, 1)
            os.replace(replacement_root / "samples.ndjson", root / "samples.ndjson")
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(root)

    def test_directory_replacement_with_another_valid_artifact_is_not_detected_without_expected_id(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            original_root = _single_artifact(parent / "artifact")
            original_id = _read_json(original_root / "manifest.json")["derivedArtifactId"]
            replacement_path = parent / "replacement"
            replacement_path.mkdir()
            replacement_root, _ = _artifact_with_samples(replacement_path, 1)
            replacement_id = _read_json(replacement_root / "manifest.json")["derivedArtifactId"]
            backup = parent / "original-backup"
            original_root.rename(backup)
            replacement_root.rename(original_root)
            reader = DerivedArtifactReader.open(original_root)
            self.assertEqual(len(list(reader.iter_validated_samples_for_inference())), 1)
        self.assertNotEqual(original_id, replacement_id)

    def test_row_reorder_is_rejected_by_content_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact_path = Path(directory) / "artifact"
            artifact_path.mkdir()
            root, samples = _artifact_with_samples(artifact_path, 2)
            raw = b"".join(canonical_bytes(sample) + b"\n" for sample in samples)
            first = canonical_bytes(samples[0]) + b"\n"
            second = canonical_bytes(samples[1]) + b"\n"
            self.assertEqual(raw, first + second)
            (root / "samples.ndjson").write_bytes(second + first)
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(root)

    def test_byte_identical_copy_to_new_path_reopens(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            original = _single_artifact(parent / "original")
            copied = parent / "copied"
            shutil.copytree(original, copied)
            reader = DerivedArtifactReader.open(copied)
            self.assertEqual(len(list(reader.iter_validated_samples_for_inference())), 1)
            self.assertEqual(
                _read_json(original / "manifest.json")["derivedArtifactId"],
                _read_json(copied / "manifest.json")["derivedArtifactId"],
            )

    def test_hard_linked_samples_are_content_identical_when_supported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            original = _single_artifact(parent / "original")
            linked = parent / "linked"
            linked.mkdir()
            shutil.copy2(original / "manifest.json", linked / "manifest.json")
            try:
                os.link(original / "samples.ndjson", linked / "samples.ndjson")
            except (OSError, NotImplementedError) as exc:
                self.skipTest(f"hard links are not available: {exc}")
            reader = DerivedArtifactReader.open(linked)
            self.assertEqual(len(list(reader.iter_validated_samples_for_inference())), 1)

    def test_mtime_change_with_unchanged_bytes_does_not_affect_content_reader(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _single_artifact(Path(directory) / "artifact")
            samples = root / "samples.ndjson"
            stat = samples.stat()
            os.utime(samples, ns=(stat.st_atime_ns, stat.st_mtime_ns + 5_000_000_000))
            reader = DerivedArtifactReader.open(root)
            self.assertEqual(len(list(reader.iter_validated_samples_for_inference())), 1)

    def test_restored_mtime_does_not_hide_same_size_byte_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _single_artifact(Path(directory) / "artifact")
            samples = root / "samples.ndjson"
            stat = samples.stat()
            samples.write_bytes(_same_size_mutation(samples.read_bytes()))
            os.utime(samples, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(root)

    def test_unknown_schema_and_version_fail_closed(self) -> None:
        for field, value in (("version", 2), ("derivedViewSchemaIdentity", "future-schema@v2")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = _single_artifact(Path(directory) / "artifact")
                manifest_path = root / "manifest.json"
                manifest = _read_json(manifest_path)
                manifest[field] = value
                manifest_path.write_bytes(canonical_bytes(manifest))
                with self.assertRaises(DerivedArtifactError):
                    DerivedArtifactReader.open(root)

    def test_changed_materializer_source_commit_is_not_a_validator_identity_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _single_artifact(Path(directory) / "artifact")
            manifest_path = root / "manifest.json"
            manifest = _read_json(manifest_path)
            manifest["materializerImplementationIdentity"]["sourceCommit"] = "6" * 40
            _rewrite_manifest_identity(root, manifest)
            reader = DerivedArtifactReader.open(root)
            self.assertEqual(len(list(reader.iter_validated_samples_for_inference())), 1)
            self.assertNotIn("validatorContractIdentity", manifest)
            self.assertNotIn("validatorImplementationIdentity", manifest)

    def test_new_process_reopens_by_repeating_full_validation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _single_artifact(Path(directory) / "artifact")
            source_root = Path(__file__).parents[1]
            child_code = """
import sys
from argentum_ml.data import derived_reader
from argentum_ml.data.derived_reader import DerivedArtifactReader

calls = 0
original = derived_reader._validate_sample_file

def counted(*args, **kwargs):
    global calls
    calls += 1
    return original(*args, **kwargs)

derived_reader._validate_sample_file = counted
reader = DerivedArtifactReader.open(sys.argv[1])
rows = len(list(reader.iter_validated_samples_for_inference()))
print(f"FULL_VALIDATION_CALLS={calls};ROWS={rows}")
"""
            environment = os.environ.copy()
            existing_pythonpath = environment.get("PYTHONPATH")
            environment["PYTHONPATH"] = os.pathsep.join(
                value for value in (str(source_root / "src"), existing_pythonpath) if value
            )
            completed = subprocess.run(
                [sys.executable, "-c", child_code, str(root)],
                cwd=source_root,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("FULL_VALIDATION_CALLS=1;ROWS=1", completed.stdout)

    def test_symlink_substitution_is_rejected_when_platform_supports_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            target = _single_artifact(parent / "target")
            link = parent / "link"
            try:
                os.symlink(target, link, target_is_directory=True)
            except (OSError, NotImplementedError) as exc:
                self.skipTest(f"directory symlinks are not available: {exc}")
            with self.assertRaises(DerivedArtifactError):
                DerivedArtifactReader.open(link)


if __name__ == "__main__":
    unittest.main()

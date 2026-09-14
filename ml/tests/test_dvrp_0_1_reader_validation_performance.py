import copy
import hashlib
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from argentum_ml.contracts.canonical_json import canonical_bytes, canonical_json, sha256_hex
from argentum_ml.contracts.identities import (
    ARTIFACT_IDENTITY_SCHEMA,
    DERIVED_VIEW_SCHEMA_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
)
from argentum_ml.data import derived_reader
from argentum_ml.data.derived_reader import (
    DerivedArtifactError,
    DerivedArtifactReader,
    ValidatedDerivedSample,
)
from tests.test_derived_reader import _artifact, _sample


def _sample_for_index(index: int) -> dict:
    sample = copy.deepcopy(_sample())
    source = sample["sourceReference"]
    source["trajectoryId"] = f"{index + 1:064x}"
    source["semanticDecisionId"]["value"] = f"{index + 2:064x}"
    source["decisionIndex"] = index
    source["replayActionIndex"] = index
    source["replayFrameIndex"] = index
    return sample


def _artifact_with_samples(root: Path, sample_count: int) -> tuple[Path, list[dict]]:
    samples = [_sample_for_index(index) for index in range(sample_count)]
    artifact = _artifact(root, sample=samples[0])
    raw_samples = b"".join(canonical_bytes(sample) + b"\n" for sample in samples)
    (artifact / "samples.ndjson").write_bytes(raw_samples)

    manifest = json.loads((artifact / "manifest.json").read_text(encoding="utf-8"))
    manifest["samplesByteCount"] = len(raw_samples)
    manifest["sampleCount"] = sample_count
    manifest["sampleCountsByPartition"] = {
        "TEST": 0,
        "TRAIN": sample_count,
        "VALIDATION": 0,
    }
    manifest["samplesContentDigest"] = hashlib.sha256(raw_samples).hexdigest()
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
    manifest_without_digest = dict(manifest)
    manifest_without_digest.pop("manifestContentDigest")
    manifest["manifestContentDigest"] = sha256_hex(
        canonical_bytes(manifest_without_digest)
    )
    (artifact / "manifest.json").write_bytes(canonical_bytes(manifest))
    return artifact, samples


class _ReaderCounters:
    def __init__(self) -> None:
        self.full_validation_calls = 0
        self.parse_calls = 0
        self.deep_validation_calls = 0


def _measure_once(sample_count: int) -> dict:
    with tempfile.TemporaryDirectory() as directory:
        root, _ = _artifact_with_samples(Path(directory), sample_count)
        counters = _ReaderCounters()
        original_full = derived_reader._validate_sample_file
        original_parse = derived_reader._parse_sample_line
        original_validate = derived_reader._validate_sample

        def counted_full(stream, manifest):
            counters.full_validation_calls += 1
            return original_full(stream, manifest)

        def counted_parse(*args, **kwargs):
            counters.parse_calls += 1
            return original_parse(*args, **kwargs)

        def counted_validate(sample, manifest):
            counters.deep_validation_calls += 1
            return original_validate(sample, manifest)

        open_start = time.perf_counter()
        with (
            patch.object(derived_reader, "_validate_sample_file", counted_full),
            patch.object(derived_reader, "_parse_sample_line", counted_parse),
            patch.object(derived_reader, "_validate_sample", counted_validate),
        ):
            reader = DerivedArtifactReader.open(root)
            open_seconds = time.perf_counter() - open_start
            consumption_start = time.perf_counter()
            tokens = list(reader.iter_validated_samples_for_inference())
            consumption_seconds = time.perf_counter() - consumption_start
        elapsed = open_seconds + consumption_seconds
        return {
            "elapsed_seconds": elapsed,
            "open_seconds": open_seconds,
            "consumption_seconds": consumption_seconds,
            "full_validation_calls": counters.full_validation_calls,
            "parse_calls": counters.parse_calls,
            "deep_validation_calls": counters.deep_validation_calls,
            "sample_count": len(tokens),
            "bytes": (root / "samples.ndjson").stat().st_size,
        }


def measure_repetitions(sample_count: int = 512, repetitions: int = 3) -> list[dict]:
    return [_measure_once(sample_count) for _ in range(repetitions)]


class TrustedReaderValidationPerformanceTests(unittest.TestCase):
    def test_trusted_inference_consumption_does_not_repeat_deep_validation(self) -> None:
        metrics = _measure_once(sample_count=32)

        self.assertEqual(metrics["sample_count"], 32)
        self.assertEqual(metrics["full_validation_calls"], 1)
        self.assertEqual(metrics["deep_validation_calls"], 32)
        self.assertEqual(metrics["parse_calls"], 64)

    def test_validated_sample_sequence_is_semantically_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, expected_samples = _artifact_with_samples(Path(directory), 8)
            reader = DerivedArtifactReader.open(root)
            actual_samples = [
                token.sample for token in reader.iter_validated_samples_for_inference()
            ]

        self.assertEqual(
            [canonical_json(sample) for sample in actual_samples],
            [canonical_json(sample) for sample in expected_samples],
        )

    def test_same_size_mutation_before_iteration_is_rejected_before_first_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, _ = _artifact_with_samples(Path(directory), 4)
            reader = DerivedArtifactReader.open(root)
            raw = (root / "samples.ndjson").read_bytes()
            mutated = raw.replace(b'"decisionIndex":0', b'"decisionIndex":9', 1)
            self.assertEqual(len(mutated), len(raw))
            (root / "samples.ndjson").write_bytes(mutated)
            stream = reader.iter_validated_samples_for_inference()
            with self.assertRaises(DerivedArtifactError):
                next(stream)

    def test_mutated_future_row_is_not_yielded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, _ = _artifact_with_samples(Path(directory), 64)
            reader = DerivedArtifactReader.open(root)
            stream = reader.iter_validated_samples_for_inference()
            first = next(stream)
            self.assertIsInstance(first, ValidatedDerivedSample)
            lines = (root / "samples.ndjson").read_bytes().splitlines(keepends=True)
            lines[63] = lines[63].replace(b'"decisionIndex":63', b'"decisionIndex":99', 1)
            original_lines = (root / "samples.ndjson").read_bytes().splitlines(keepends=True)
            self.assertNotEqual(lines[63], original_lines[63])
            (root / "samples.ndjson").write_bytes(b"".join(lines))
            try:
                for _ in range(62):
                    next(stream)
                with self.assertRaises(DerivedArtifactError):
                    next(stream)
            finally:
                stream.close()

    def test_truncation_and_extra_row_are_rejected_before_first_token(self) -> None:
        for mutate in (
            lambda raw: raw[: -len(raw.splitlines(keepends=True)[-1])],
            lambda raw: raw + raw.splitlines(keepends=True)[0],
        ):
            with self.subTest(mutate=mutate):
                with tempfile.TemporaryDirectory() as directory:
                    root, _ = _artifact_with_samples(Path(directory), 4)
                    reader = DerivedArtifactReader.open(root)
                    raw = (root / "samples.ndjson").read_bytes()
                    (root / "samples.ndjson").write_bytes(mutate(raw))
                    stream = reader.iter_validated_samples_for_inference()
                    with self.assertRaises(DerivedArtifactError):
                        next(stream)

    def test_reader_issues_tokens_once_and_preserves_close_semantics(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root, _ = _artifact_with_samples(Path(directory), 2)
            reader = DerivedArtifactReader.open(root)
            tokens = list(reader.iter_validated_samples_for_inference())
            self.assertEqual(len(tokens), 2)
            with self.assertRaises(DerivedArtifactError):
                list(reader.iter_validated_samples_for_inference())

    def test_validated_sample_constructor_remains_closed(self) -> None:
        with self.assertRaises(TypeError):
            ValidatedDerivedSample({}, object())


if __name__ == "__main__":
    print(json.dumps(measure_repetitions(), sort_keys=True))

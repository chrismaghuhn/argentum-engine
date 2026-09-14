import copy
import hashlib
import tempfile
import unittest
from pathlib import Path

from argentum_ml.contracts.canonical_json import canonical_bytes, sha256_hex
from argentum_ml.data.label_artifact import (
    LabelArtifactError,
    LabelArtifactReader,
    write_label_artifact,
)
from tests.test_derived_reader import _sample, _sha


def _row() -> dict:
    sample = _sample()
    return {
        "version": 1,
        "partition": "TRAIN",
        "decisionFamily": "ACTION_CANDIDATES",
        "sourceReference": sample["sourceReference"],
        "target": sample["target"],
        "binding": {
            "selectedExactSourceBinding": sample["binding"]["selectedExactSourceBinding"],
            "sourceBindingOrdinal": 0,
        },
        "provenance": {
            "teacherResultSchemaIdentity": "argentum-ml-public-observation-teacher-result@v1",
            "teacherConfigDigest": _sha("a"),
            "selectionContractIdentity": "argentum-ml-policy-selection@v2",
            "policyRngIdentity": "argentum-ml-policy-tie-rng@v1",
            "teacherSeatIndex": 0,
            "candidateCount": 1,
            "rngDrawCount": 0,
            "cursorBefore": 0,
            "cursorAfter": 0,
            "tieOccurred": False,
            "policyTieRngWordsConsumed": 0,
        },
    }


def _identity() -> dict:
    return {
        "sourceDatasetId": _sha("1"),
        "sourceManifestContentDigest": _sha("2"),
        "sourceDerivedArtifactId": _sha("3"),
        "sourceDerivedViewSchemaIdentity": "argentum-ml-derived-learner-view@v1",
        "trajectorySchemaIdentity": "argentum-trajectory@v1",
        "modelFacingContractIdentity": "argentum-ml-model-facing-decision-sample@v1",
        "splitContractIdentity": "argentum-ml-dataset-split@v1",
        "teacherProvenance": {
            "teacherContractIdentity": "argentum-ml-teacher-bootstrap@v1",
            "teacherPolicyIdentity": "argentum-ml-public-observation-bootstrap-teacher@v1",
            "teacherSourceIdentity": "argentum-ml-public-observation-teacher-source@v1",
            "teacherSourceCommit": "b" * 40,
            "teacherConfigSchemaIdentity": "argentum-ml-public-observation-teacher-config@v1",
            "teacherConfigDigest": _sha("a"),
            "scorerIdentity": "argentum-ml-public-observation-generic-kind-scorer@v1",
            "selectionContractIdentity": "argentum-ml-policy-selection@v2",
            "policyRngIdentity": "argentum-ml-policy-tie-rng@v1",
            "teacherPolicyTieScheduleIdentity": "argentum-ml-c1-03-teacher-policy-tie-schedule@v1",
            "teacherPolicyTieSeed": 0,
            "initialPolicyTieCursor": 0,
            "teacherTieStateScope": "semanticEpisodeId|teacherPolicyIdentity|seatIndex",
            "legacyA9PolicySeedReused": False,
            "teacherExecutionConfigDigest": _sha("c"),
            "teacherAdmissionPurposeIdentity": "argentum-ml-flat-reference-bootstrap@v1",
            "teacherAdmissionResult": "ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP",
            "teacherAdmissionPlanIdentity": "argentum-ml-c1-03-teacher-quality-and-admission@v1",
            "teacherAdmissionPlanDigest": _sha("d"),
        },
        "labelMaterializerImplementationIdentity": {
            "implementation": "argentum-ml-label-materializer@v1",
            "sourceCommit": "e" * 40,
        },
        "labelMaterializerConfigDigest": _sha("f"),
        "allowedPartitions": ["TRAIN", "VALIDATION"],
        "sourceDecisionKeyIdentity": "argentum-ml-source-decision-key@v1",
    }


def _accounting() -> dict:
    return {
        "sourceRowsByPartition": {"TEST": 0, "TRAIN": 1, "VALIDATION": 0},
        "processedRowsByPartition": {"TEST": 0, "TRAIN": 1, "VALIDATION": 0},
        "teacherCallsByPartition": {"TEST": 0, "TRAIN": 1, "VALIDATION": 0},
        "labelCountsByPartition": {"TEST": 0, "TRAIN": 1, "VALIDATION": 0},
        "labelCountsByDecisionFamily": {
            "ACTION_CANDIDATES": 1,
            "FOLDED_DECISION_OPTIONS": 0,
        },
        "expectedNoLabelByPartitionAndReason": {
            "TEST": {},
            "TRAIN": {},
            "VALIDATION": {},
        },
        "rejectedInvalidSourceBindingByPartition": {"TEST": 0, "TRAIN": 0, "VALIDATION": 0},
        "rejectedSplitByPartition": {"TEST": 0, "TRAIN": 0, "VALIDATION": 0},
        "rejectedProvenanceByPartition": {"TEST": 0, "TRAIN": 0, "VALIDATION": 0},
        "duplicateDecisionKeyCount": 0,
        "conflictingLabelCount": 0,
        "otherFailClosedMaterializerErrorCount": 0,
        "testRowsConsumed": 0,
    }


class LabelArtifactTests(unittest.TestCase):
    def test_write_and_reopen_canonical_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = write_label_artifact(
                Path(directory),
                rows=[_row()],
                identity=_identity(),
                accounting=_accounting(),
            )
            reader = LabelArtifactReader.open(Path(directory))
            self.assertEqual(list(reader.iter_labels()), [_row()])
            self.assertEqual(manifest["labelCount"], 1)
            self.assertEqual(reader.manifest["labelArtifactId"], manifest["labelArtifactId"])

    def test_reader_rejects_changed_label_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_label_artifact(root, rows=[_row()], identity=_identity(), accounting=_accounting())
            labels = root / "labels.ndjson"
            labels.write_bytes(labels.read_bytes().replace(b"TRAIN", b"TRAIN ", 1))
            with self.assertRaises(LabelArtifactError):
                LabelArtifactReader.open(root)

    def test_reader_rejects_unknown_manifest_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_label_artifact(root, rows=[_row()], identity=_identity(), accounting=_accounting())
            manifest = __import__("json").loads((root / "manifest.json").read_text(encoding="utf-8"))
            manifest["version"] = 2
            (root / "manifest.json").write_bytes(canonical_bytes(manifest))
            with self.assertRaises(LabelArtifactError):
                LabelArtifactReader.open(root)

    def test_writer_rejects_test_label(self) -> None:
        row = _row()
        row["partition"] = "TEST"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory),
                    rows=[row],
                    identity=_identity(),
                    accounting=_accounting(),
                )

    def test_duplicate_source_key_rejects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory),
                    rows=[_row(), copy.deepcopy(_row())],
                    identity=_identity(),
                    accounting=_accounting(),
                )


if __name__ == "__main__":
    unittest.main()

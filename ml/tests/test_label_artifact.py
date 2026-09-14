import copy
import hashlib
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from argentum_ml.contracts.canonical_json import canonical_bytes, sha256_hex
from argentum_ml.data.derived_reader import DerivedArtifactReader
from argentum_ml.teacher.contracts import NoLabelReason
from argentum_ml.teacher.contracts import PublicObservationTeacherConfigV1
from argentum_ml.teacher.execution import C1_05AdmissionBindingV1, TeacherExecutionBindingV1
from argentum_ml.data.label_artifact import (
    LabelArtifactError,
    LabelArtifactReader,
    _identity_payload,
    write_label_artifact,
)
from argentum_ml.data.label_contracts import (
    LABEL_MATERIALIZER_IMPLEMENTATION_IDENTITY,
    LabelMaterializerConfigV1,
)
from tests.test_derived_reader import _artifact, _sample, _sha
from tests.test_label_materializer import _source_fixture


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
            "teacherConfigDigest": PublicObservationTeacherConfigV1.reference().digest,
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


def _identity(source_manifest: dict | None = None) -> dict:
    source_manifest = source_manifest or {
        "sourceDatasetId": _sha("1"),
        "sourceManifestContentDigest": _sha("2"),
        "derivedArtifactId": _sha("3"),
    }
    return {
        "sourceDatasetId": source_manifest["sourceDatasetId"],
        "sourceManifestContentDigest": source_manifest["sourceManifestContentDigest"],
        "sourceDerivedArtifactId": source_manifest["derivedArtifactId"],
        "sourceDerivedViewSchemaIdentity": "argentum-ml-derived-learner-view@v1",
        "trajectorySchemaIdentity": "argentum-trajectory@v1",
        "modelFacingContractIdentity": "argentum-ml-model-facing-decision-sample@v1",
        "splitContractIdentity": "argentum-ml-dataset-split@v1",
        "teacherProvenance": {
            "teacherContractIdentity": "argentum-ml-teacher-bootstrap@v1",
            "teacherPolicyIdentity": "argentum-ml-public-observation-bootstrap-teacher@v1",
            "teacherSourceIdentity": "argentum-ml-public-observation-teacher-source@v1",
            "teacherSourceCommit": C1_05AdmissionBindingV1.reference().teacher_source_commit,
            "teacherConfigSchemaIdentity": "argentum-ml-public-observation-teacher-config@v1",
            "teacherConfigDigest": PublicObservationTeacherConfigV1.reference().digest,
            "scorerIdentity": "argentum-ml-public-observation-generic-kind-scorer@v1",
            "selectionContractIdentity": "argentum-ml-policy-selection@v2",
            "policyRngIdentity": "argentum-ml-policy-tie-rng@v1",
            **TeacherExecutionBindingV1.reference().to_dict(),
        },
        "labelMaterializerImplementationIdentity": {
            "implementation": LABEL_MATERIALIZER_IMPLEMENTATION_IDENTITY,
            "sourceCommit": "e" * 40,
        },
        "labelMaterializerConfigDigest": LabelMaterializerConfigV1.reference().digest,
        "allowedPartitions": ["TRAIN", "VALIDATION"],
        "sourceDecisionKeyIdentity": "argentum-ml-source-decision-key@v1",
    }


def _test_admission(source_manifest: dict) -> C1_05AdmissionBindingV1:
    return replace(
        C1_05AdmissionBindingV1.reference(),
        source_dataset_id=source_manifest["sourceDatasetId"],
        source_manifest_content_digest=source_manifest["sourceManifestContentDigest"],
        source_derived_artifact_id=source_manifest["derivedArtifactId"],
    )


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
            "TEST": {reason.value: 0 for reason in NoLabelReason},
            "TRAIN": {reason.value: 0 for reason in NoLabelReason},
            "VALIDATION": {reason.value: 0 for reason in NoLabelReason},
        },
        "rejectedInvalidSourceBindingByPartition": {"TEST": 0, "TRAIN": 0, "VALIDATION": 0},
        "rejectedInvalidSelectedLabelByPartition": {"TEST": 0, "TRAIN": 0, "VALIDATION": 0},
        "rejectedSplitByPartition": {"TEST": 0, "TRAIN": 0, "VALIDATION": 0},
        "rejectedProvenanceByPartition": {"TEST": 0, "TRAIN": 0, "VALIDATION": 0},
        "duplicateDecisionKeyCount": 0,
        "conflictingLabelCount": 0,
        "otherFailClosedMaterializerErrorCount": 0,
        "testRowsConsumed": 0,
    }


class LabelArtifactTests(unittest.TestCase):
    def test_materializer_config_reference_has_canonical_digest(self) -> None:
        config = LabelMaterializerConfigV1.reference()
        self.assertEqual(config.digest, sha256_hex(canonical_bytes(config.to_dict())))
        self.assertEqual(config.to_dict()["schemaIdentity"], "argentum-ml-label-materializer-config@v1")

    def test_writer_rejects_unknown_materializer_identity(self) -> None:
        identity = _identity()
        identity["labelMaterializerImplementationIdentity"] = {
            "implementation": "future-label-materializer@v2",
            "sourceCommit": "e" * 40,
        }
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row()],
                    identity=identity,
                    accounting=_accounting(),
                )

    def test_writer_rejects_unknown_materializer_config_digest(self) -> None:
        identity = _identity()
        identity["labelMaterializerConfigDigest"] = _sha("f")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row()],
                    identity=identity,
                    accounting=_accounting(),
                )

    def test_writer_rejects_wrong_source_derived_view_schema_identity(self) -> None:
        identity = _identity()
        identity["sourceDerivedViewSchemaIdentity"] = "future-derived-view@v2"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row()],
                    identity=identity,
                    accounting=_accounting(),
                )

    def test_writer_rejects_wrong_trajectory_schema_identity(self) -> None:
        identity = _identity()
        identity["trajectorySchemaIdentity"] = "future-trajectory@v2"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row()],
                    identity=identity,
                    accounting=_accounting(),
                )

    def test_label_artifact_id_uses_reviewed_identity_field_preimage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = write_label_artifact(
                root / "labels",
                rows=[_row()],
                identity=_identity(),
                accounting=_accounting(),
            )
            payload = _identity_payload(manifest)
            expected = sha256_hex(canonical_bytes(payload))
            self.assertEqual(manifest["labelArtifactId"], expected)

    def test_authoritative_reader_requires_source_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "labels"
            write_label_artifact(root, rows=[_row()], identity=_identity(), accounting=_accounting())
            with self.assertRaises(LabelArtifactError):
                LabelArtifactReader.open(root)

    def test_authoritative_reader_rejects_caller_defined_source_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "labels"
            write_label_artifact(root, rows=[_row()], identity=_identity(), accounting=_accounting())
            with self.assertRaises(LabelArtifactError):
                LabelArtifactReader.open(
                    root,
                    source_artifact_root=Path(directory) / "source",
                    expected_source_identity={
                        "sourceDatasetId": _sha("1"),
                        "sourceManifestContentDigest": _sha("2"),
                        "sourceDerivedArtifactId": _sha("3"),
                    },
                )

    def test_authoritative_reader_rejects_label_absent_from_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_root = root / "source"
            source_root.mkdir()
            source_reader = DerivedArtifactReader.open(_artifact(source_root))
            source_manifest = dict(source_reader.manifest)
            source_reader.close()
            row = _row()
            row["sourceReference"] = dict(row["sourceReference"])
            row["sourceReference"]["decisionIndex"] = 99
            label_root = root / "labels"
            write_label_artifact(
                label_root,
                rows=[row],
                identity=_identity(source_manifest),
                accounting=_accounting(),
            )
            with self.assertRaises(LabelArtifactError):
                LabelArtifactReader._open_for_test(
                    label_root,
                    source_artifact_root=source_root,
                    expected_source_identity={
                        "sourceDatasetId": source_manifest["sourceDatasetId"],
                        "sourceManifestContentDigest": source_manifest["sourceManifestContentDigest"],
                        "sourceDerivedArtifactId": source_manifest["derivedArtifactId"],
                    },
                    admission_binding=_test_admission(source_manifest),
                )

    def test_reader_rejects_semantically_valid_target_with_wrong_ordinal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_root = root / "source"
            source_root.mkdir()
            source_sample = _source_fixture(candidate_count=2)
            source_reader = DerivedArtifactReader.open(_artifact(source_root, sample=source_sample))
            source_manifest = dict(source_reader.manifest)
            source_reader.close()
            row = _row()
            row["sourceReference"] = source_sample["sourceReference"]
            row["target"] = source_sample["target"]
            row["binding"] = {
                "selectedExactSourceBinding": source_sample["binding"]["selectedExactSourceBinding"],
                "sourceBindingOrdinal": 1,
            }
            row["provenance"]["candidateCount"] = 2
            label_root = root / "labels"
            write_label_artifact(
                label_root,
                rows=[row],
                identity=_identity(source_manifest),
                accounting=_accounting(),
            )
            with self.assertRaises(LabelArtifactError):
                LabelArtifactReader._open_for_test(
                    label_root,
                    source_artifact_root=source_root,
                    expected_source_identity={
                        "sourceDatasetId": source_manifest["sourceDatasetId"],
                        "sourceManifestContentDigest": source_manifest["sourceManifestContentDigest"],
                        "sourceDerivedArtifactId": source_manifest["derivedArtifactId"],
                    },
                    admission_binding=_test_admission(source_manifest),
                )

    def test_write_and_reopen_canonical_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_root = root / "source"
            source_root.mkdir()
            source_sample = _sample()
            source_sample["provenance"] = {
                "environmentIdentity": {
                    "roster": [
                        {"playerId": "player-0", "seatIndex": 0},
                        {"playerId": "player-1", "seatIndex": 1},
                    ]
                }
            }
            source_reader = DerivedArtifactReader.open(_artifact(source_root, sample=source_sample))
            source_manifest = dict(source_reader.manifest)
            source_reader.close()
            manifest = write_label_artifact(
                root / "labels",
                rows=[_row()],
                identity=_identity(dict(source_manifest)),
                accounting=_accounting(),
            )
            reader = LabelArtifactReader._open_for_test(
                root / "labels",
                source_artifact_root=source_root,
                expected_source_identity={
                    "sourceDatasetId": source_manifest["sourceDatasetId"],
                    "sourceManifestContentDigest": source_manifest["sourceManifestContentDigest"],
                    "sourceDerivedArtifactId": source_manifest["derivedArtifactId"],
                },
                admission_binding=_test_admission(source_manifest),
            )
            self.assertEqual(list(reader.iter_labels()), [_row()])
            self.assertEqual(manifest["labelCount"], 1)
            self.assertEqual(reader.manifest["labelArtifactId"], manifest["labelArtifactId"])

    def test_reader_rejects_changed_label_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_root = root / "source"
            source_root.mkdir()
            source_reader = DerivedArtifactReader.open(_artifact(source_root))
            source_manifest = dict(source_reader.manifest)
            source_reader.close()
            label_root = root / "labels"
            write_label_artifact(label_root, rows=[_row()], identity=_identity(dict(source_manifest)), accounting=_accounting())
            labels = label_root / "labels.ndjson"
            labels.write_bytes(labels.read_bytes().replace(b"TRAIN", b"TRAIN ", 1))
            with self.assertRaises(LabelArtifactError):
                LabelArtifactReader._open_for_test(
                    label_root,
                    source_artifact_root=source_root,
                    expected_source_identity={
                        "sourceDatasetId": source_manifest["sourceDatasetId"],
                        "sourceManifestContentDigest": source_manifest["sourceManifestContentDigest"],
                        "sourceDerivedArtifactId": source_manifest["derivedArtifactId"],
                    },
                    admission_binding=_test_admission(source_manifest),
                )

    def test_reader_rejects_unknown_manifest_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_root = root / "source"
            source_root.mkdir()
            source_reader = DerivedArtifactReader.open(_artifact(source_root))
            source_manifest = dict(source_reader.manifest)
            source_reader.close()
            label_root = root / "labels"
            write_label_artifact(label_root, rows=[_row()], identity=_identity(dict(source_manifest)), accounting=_accounting())
            manifest = __import__("json").loads((label_root / "manifest.json").read_text(encoding="utf-8"))
            manifest["version"] = 2
            (label_root / "manifest.json").write_bytes(canonical_bytes(manifest))
            with self.assertRaises(LabelArtifactError):
                LabelArtifactReader._open_for_test(
                    label_root,
                    source_artifact_root=source_root,
                    expected_source_identity={
                        "sourceDatasetId": source_manifest["sourceDatasetId"],
                        "sourceManifestContentDigest": source_manifest["sourceManifestContentDigest"],
                        "sourceDerivedArtifactId": source_manifest["derivedArtifactId"],
                    },
                    admission_binding=_test_admission(source_manifest),
                )

    def test_writer_rejects_test_label(self) -> None:
        row = _row()
        row["partition"] = "TEST"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[row],
                    identity=_identity(),
                    accounting=_accounting(),
                )

    def test_writer_rejects_nonreconciling_accounting(self) -> None:
        accounting = _accounting()
        accounting["processedRowsByPartition"]["TRAIN"] = 0
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row()],
                    identity=_identity(),
                    accounting=accounting,
                )

    def test_writer_rejects_wrong_actual_partition_counts(self) -> None:
        accounting = _accounting()
        accounting["labelCountsByPartition"] = {"TEST": 0, "TRAIN": 0, "VALIDATION": 1}
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row()],
                    identity=_identity(),
                    accounting=accounting,
                )

    def test_writer_rejects_nonzero_split_rejections(self) -> None:
        accounting = _accounting()
        accounting["rejectedSplitByPartition"]["TRAIN"] = 1
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row()],
                    identity=_identity(),
                    accounting=accounting,
                )

    def test_writer_rejects_nonzero_provenance_rejections(self) -> None:
        accounting = _accounting()
        accounting["rejectedProvenanceByPartition"]["VALIDATION"] = 1
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row()],
                    identity=_identity(),
                    accounting=accounting,
                )

    def test_writer_rejects_test_no_label_accounting(self) -> None:
        accounting = _accounting()
        accounting["expectedNoLabelByPartitionAndReason"]["TEST"][NoLabelReason.STRUCTURED_DOMAIN_NOT_SCOREABLE.value] = 1
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row()],
                    identity=_identity(),
                    accounting=accounting,
                )

    def test_writer_rejects_rng_cursor_draw_mismatch(self) -> None:
        row = _row()
        row["provenance"] = dict(row["provenance"])
        row["provenance"]["cursorAfter"] = 1
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[row],
                    identity=_identity(),
                    accounting=_accounting(),
                )

    def test_writer_rejects_wrong_teacher_result_schema_identity(self) -> None:
        row = _row()
        row["provenance"] = dict(row["provenance"])
        row["provenance"]["teacherResultSchemaIdentity"] = "future-teacher-result@v2"
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[row],
                    identity=_identity(),
                    accounting=_accounting(),
                )

    def test_duplicate_source_key_rejects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(LabelArtifactError):
                write_label_artifact(
                    Path(directory) / "labels",
                    rows=[_row(), copy.deepcopy(_row())],
                    identity=_identity(),
                    accounting=_accounting(),
                )


if __name__ == "__main__":
    unittest.main()

import json
import tempfile
import unittest
from pathlib import Path

from argentum_ml.characterization.dvrp_2 import (
    Dvrp2ArtifactSpec,
    characterize_c1_06_preparation,
    render_report,
    verify_artifact_identity,
    write_outputs,
)
from argentum_ml.data.label_artifact import write_label_artifact
from tests.test_derived_reader import _artifact
from tests.test_label_artifact import _accounting, _identity, _row


class Dvrp2CharacterizationTests(unittest.TestCase):
    def _fixture(self, root: Path) -> tuple[Path, Path, Dvrp2ArtifactSpec]:
        source_path = root / "source"
        source_path.mkdir()
        source_root = _artifact(source_path)
        source_manifest = json.loads(
            (source_root / "manifest.json").read_text(encoding="utf-8")
        )
        label_root = root / "labels"
        label_manifest = write_label_artifact(
            label_root,
            rows=[_row()],
            identity=_identity(source_manifest),
            accounting=_accounting(),
        )
        return (
            source_root,
            label_root,
            Dvrp2ArtifactSpec(
                source_derived_artifact_id=source_manifest["derivedArtifactId"],
                label_artifact_id=label_manifest["labelArtifactId"],
                labels_content_digest=label_manifest["labelsContentDigest"],
                manifest_content_digest=label_manifest["manifestContentDigest"],
            ),
        )

    def test_synthetic_run_observes_current_loader_boundaries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_root, label_root, spec = self._fixture(root)
            result = characterize_c1_06_preparation(
                label_root=label_root,
                source_artifact_root=source_root,
                artifact_spec=spec,
                train_limit=1,
                validation_limit=0,
                test_limit=0,
                measure_tensorization=False,
            )

        self.assertEqual(result["LABEL_ROWS_VISITED"], 1)
        self.assertEqual(result["LABEL_ROWS_SELECTED_TRAIN"], 1)
        self.assertEqual(result["LABEL_ROWS_SELECTED_VALIDATION"], 0)
        self.assertEqual(result["SOURCE_ROWS_VISITED"], 1)
        self.assertEqual(result["SELECTED_LABELS_REQUESTED"], 1)
        self.assertEqual(result["SELECTED_LABELS_MATCHED"], 1)
        self.assertEqual(result["SELECTED_LABELS_MISSING"], 0)
        self.assertEqual(result["LAST_SELECTED_LABEL_SOURCE_ROW_INDEX"], 1)
        self.assertEqual(result["ROWS_SCANNED_AFTER_LAST_NEEDED_MATCH"], 0)
        self.assertEqual(result["TRAIN_SAMPLES_CREATED"], 1)
        self.assertEqual(result["VALIDATION_SAMPLES_CREATED"], 0)
        self.assertEqual(result["TEST_ROWS_CONSUMED"], 0)
        self.assertEqual(result["SOURCE_RAW_IO_PASSES"], 3)
        self.assertEqual(result["SOURCE_SEMANTIC_VALIDATION_PASSES"], 1)
        self.assertEqual(result["TENSORIZATION_WALL_SECONDS"], None)

    def test_manifest_mismatch_and_test_limit_fail_closed_before_reader_use(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source_root, label_root, spec = self._fixture(Path(directory))
            with self.assertRaises(ValueError):
                verify_artifact_identity(
                    label_root=label_root,
                    source_artifact_root=source_root,
                    artifact_spec=Dvrp2ArtifactSpec(
                        source_derived_artifact_id="f" * 64,
                        label_artifact_id=spec.label_artifact_id,
                        labels_content_digest=spec.labels_content_digest,
                        manifest_content_digest=spec.manifest_content_digest,
                    ),
                )
            with self.assertRaises(ValueError):
                characterize_c1_06_preparation(
                    label_root=label_root,
                    source_artifact_root=source_root,
                    artifact_spec=spec,
                    train_limit=1,
                    validation_limit=0,
                    test_limit=1,
                    measure_tensorization=False,
                )

    def test_report_and_json_sidecar_are_rendered_from_one_result(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_root, label_root, spec = self._fixture(root)
            result = characterize_c1_06_preparation(
                label_root=label_root,
                source_artifact_root=source_root,
                artifact_spec=spec,
                train_limit=1,
                validation_limit=0,
                test_limit=0,
                measure_tensorization=False,
            )
            report = render_report(result)
            self.assertIn("NEXT_RECOMMENDED_SLICE=", report)
            json_path = root / "result.json"
            report_path = root / "result.md"
            write_outputs(result, report_out=report_path, json_out=json_path)
            self.assertEqual(json.loads(json_path.read_text(encoding="utf-8")), result)
            self.assertEqual(report_path.read_text(encoding="utf-8"), report)


if __name__ == "__main__":
    unittest.main()

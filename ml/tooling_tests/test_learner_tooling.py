import hashlib
import os
import pickle
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import safetensors
import torch

from argentum_ml.checkpoint import (
    ArgentumCheckpointManifestV1,
    CheckpointManifestError,
)
from argentum_ml.contracts.canonical_json import canonical_bytes
from argentum_ml.learner import (
    SAFETENSORS_CONTAINER_IDENTITY,
    TrackioBoundaryError,
    TrackioRun,
    WeightArtifactError,
    load_state_dict,
    save_state_dict,
    torch_runtime_provenance,
)


FIXTURE = Path(__file__).parents[1] / "tests" / "fixtures" / "checkpoint_manifest_v1.json"


class TinyModule(torch.nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.layers = torch.nn.Sequential(
            torch.nn.Linear(2, 3),
            torch.nn.Linear(3, 1),
        )
        self.register_buffer("scale", torch.tensor([1, 2], dtype=torch.int64))


def _manifest_bound_to(
    digest: str,
    *,
    container: str = SAFETENSORS_CONTAINER_IDENTITY,
) -> ArgentumCheckpointManifestV1:
    manifest = ArgentumCheckpointManifestV1.from_path(FIXTURE)
    data = manifest.to_dict()
    data["weightArtifactIdentity"] = {
        "container": container,
        "artifact": "weights.safetensors",
    }
    data["weightContentDigest"] = digest
    data["checkpointId"] = manifest.recompute_checkpoint_id(data)
    return ArgentumCheckpointManifestV1.from_dict(data)


class LearnerToolingTests(unittest.TestCase):
    def test_pytorch_import_and_runtime_provenance(self) -> None:
        provenance = torch_runtime_provenance()

        self.assertEqual(provenance.framework, "pytorch")
        self.assertEqual(
            provenance.torch_version,
            torch.__version__,
        )
        self.assertEqual(provenance.python_version, "3.13.15")
        self.assertIsInstance(provenance.cuda_available, bool)
        self.assertTrue(TinyModule().state_dict())

    def test_pytorch_provenance_preserves_build_suffix(self) -> None:
        with patch.object(torch, "__version__", "2.14.0+cpu"):
            provenance = torch_runtime_provenance()

        self.assertEqual(provenance.torch_version, "2.14.0+cpu")

    def test_safetensors_round_trip_preserves_tensor_contract(self) -> None:
        state = TinyModule().state_dict()

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weights.safetensors"
            artifact = save_state_dict(state, path)
            manifest = _manifest_bound_to(artifact.content_digest)
            loaded = load_state_dict(path, manifest)

            self.assertTrue(path.is_file())
            self.assertEqual(
                artifact.content_digest,
                hashlib.sha256(path.read_bytes()).hexdigest(),
            )

        self.assertEqual(artifact.tensor_names, tuple(state))
        self.assertEqual(set(loaded), set(state))
        for name, tensor in state.items():
            self.assertEqual(loaded[name].shape, tensor.shape)
            self.assertEqual(loaded[name].dtype, tensor.dtype)
            self.assertTrue(torch.equal(loaded[name], tensor))

    def test_manifest_accepts_exact_weight_bytes_and_rejects_changed_bytes(self) -> None:
        state = TinyModule().state_dict()

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weights.safetensors"
            artifact = save_state_dict(state, path)
            manifest = _manifest_bound_to(artifact.content_digest)

            self.assertTrue(manifest.validate_weight_file(path))
            changed = bytearray(path.read_bytes())
            changed[-1] ^= 1
            path.write_bytes(changed)

            with self.assertRaises(CheckpointManifestError):
                manifest.validate_weight_file(path)

    def test_tampered_weight_is_rejected_before_safetensors_decode(self) -> None:
        state = TinyModule().state_dict()

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weights.safetensors"
            artifact = save_state_dict(state, path)
            manifest = _manifest_bound_to(artifact.content_digest)
            changed = bytearray(path.read_bytes())
            changed[-1] ^= 1
            path.write_bytes(changed)

            import safetensors.torch as safetensors_torch

            with patch.object(
                safetensors_torch,
                "load",
                side_effect=AssertionError("decode must not run"),
            ) as decoder:
                with self.assertRaises(CheckpointManifestError):
                    load_state_dict(path, manifest)

            decoder.assert_not_called()

    def test_wrong_container_identity_is_rejected_before_safetensors_decode(self) -> None:
        state = TinyModule().state_dict()

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weights.safetensors"
            artifact = save_state_dict(state, path)
            manifest = _manifest_bound_to(
                artifact.content_digest,
                container="other-container@v1",
            )

            import safetensors.torch as safetensors_torch

            with patch.object(
                safetensors_torch,
                "load",
                side_effect=AssertionError("decode must not run"),
            ) as decoder:
                with self.assertRaises(WeightArtifactError):
                    load_state_dict(path, manifest)

            decoder.assert_not_called()

    def test_only_dense_contiguous_tensors_and_regular_paths_are_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(WeightArtifactError):
                save_state_dict({"not-a-tensor": "pickle"}, root / "weights.safetensors")
            with self.assertRaises(WeightArtifactError):
                save_state_dict(TinyModule().state_dict(), root)

            missing_parent = root / "missing" / "weights.safetensors"
            with self.assertRaises(WeightArtifactError):
                save_state_dict(TinyModule().state_dict(), missing_parent)
            with self.assertRaises(WeightArtifactError):
                load_state_dict(root, _manifest_bound_to("a" * 64))

    def test_save_does_not_require_pickle_or_torch_save(self) -> None:
        state = TinyModule().state_dict()

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weights.safetensors"
            with (
                patch.object(torch, "save", side_effect=AssertionError("torch.save forbidden")),
                patch.object(pickle, "dump", side_effect=AssertionError("pickle forbidden")),
                patch.object(pickle, "dumps", side_effect=AssertionError("pickle forbidden")),
            ):
                artifact = save_state_dict(state, path)

        self.assertGreater(len(artifact.content_digest), 0)
        self.assertTrue(safetensors.__version__)

    def test_trackio_local_smoke_logs_allowed_scalars_and_preserves_ids(self) -> None:
        manifest = _manifest_bound_to("a" * 64)
        checkpoint_id = manifest.checkpoint_id
        weight_digest = manifest.weight_content_digest
        dataset_id = manifest.to_dict()["sourceDatasetIdentity"]

        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"TRACKIO_DIR": directory},
            clear=False,
        ):
            for key in (
                "TRACKIO_SPACE_ID",
                "TRACKIO_SERVER_URL",
                "TRACKIO_WRITE_TOKEN",
                "TRACKIO_WEBHOOK_URL",
            ):
                os.environ.pop(key, None)

            run = TrackioRun.start(
                "argentum-c1-04-tooling-test",
                semantic_references={
                    "checkpointId": checkpoint_id,
                    "weightContentDigest": weight_digest,
                    "sourceDatasetIdentity": dataset_id,
                    "trainingRunIdentity": "test-run@v1",
                },
            )
            run.log(
                {"train_loss": 0.5, "candidate_accuracy": 1.0},
                step=0,
            )
            run.log(
                {
                    "validation_loss": 0.25,
                    "samples_per_sec": 4.0,
                    "batches_per_sec": 2.0,
                },
                step=1,
            )
            run.finish()
            self.assertTrue(any(Path(directory).iterdir()))

        self.assertEqual(manifest.checkpoint_id, checkpoint_id)
        self.assertEqual(manifest.weight_content_digest, weight_digest)
        self.assertEqual(manifest.to_dict()["sourceDatasetIdentity"], dataset_id)

    def test_trackio_rejects_remote_configuration_before_init(self) -> None:
        for key, value in (
            ("TRACKIO_SPACE_ID", "owner/project"),
            ("TRACKIO_SERVER_URL", "https://127.0.0.1:7860"),
            ("TRACKIO_WRITE_TOKEN", "not-a-token"),
            ("TRACKIO_WEBHOOK_URL", "https://example.invalid/hook"),
        ):
            with self.subTest(key=key):
                with patch.dict(os.environ, {key: value}, clear=False):
                    with self.assertRaises(TrackioBoundaryError):
                        TrackioRun.start("remote-forbidden")

    def test_trackio_rejects_unknown_or_invalid_values(self) -> None:
        with self.assertRaises(TrackioBoundaryError):
            TrackioRun.start(
                "bad-reference",
                semantic_references={"teacherBootstrapProvenance": "forbidden"},
            )
        with self.assertRaises(TrackioBoundaryError):
            TrackioRun.start(
                "bad-reference",
                semantic_references={"checkpointId": "not-a-sha256"},
            )

        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            {"TRACKIO_DIR": directory},
            clear=False,
        ):
            for key in (
                "TRACKIO_SPACE_ID",
                "TRACKIO_SERVER_URL",
                "TRACKIO_WRITE_TOKEN",
                "TRACKIO_WEBHOOK_URL",
            ):
                os.environ.pop(key, None)
            run = TrackioRun.start("finish-once")
            with self.assertRaises(TrackioBoundaryError):
                run.log({"unknown_metric": 1.0})
            with self.assertRaises(TrackioBoundaryError):
                run.log({"train_loss": float("nan")})
            with self.assertRaises(TrackioBoundaryError):
                run.log({"train_loss": True})
            with self.assertRaises(TrackioBoundaryError):
                run.log({"train_loss": 1.0}, step=-1)
            run.finish()
            with self.assertRaises(TrackioBoundaryError):
                run.log({"train_loss": 1.0})
            with self.assertRaises(TrackioBoundaryError):
                run.finish()


if __name__ == "__main__":
    unittest.main()

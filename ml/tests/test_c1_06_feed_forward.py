import importlib.util
import unittest
from types import SimpleNamespace
from unittest.mock import patch


def _optional_torch_available() -> bool:
    try:
        return importlib.util.find_spec("torch") is not None
    except ModuleNotFoundError:
        return False


_REQUIRES_TORCH = unittest.skipUnless(
    _optional_torch_available(),
    "optional PyTorch learner tooling is not installed",
)


class _FakeCuda:
    def __init__(self, available: bool, count: int) -> None:
        self._available = available
        self._count = count

    def is_available(self) -> bool:
        return self._available

    def device_count(self) -> int:
        return self._count


class _FakeTorch:
    def __init__(self, available: bool, count: int) -> None:
        self.cuda = _FakeCuda(available, count)

    @staticmethod
    def device(value: str) -> str:
        return value


class C1_06GpuGateTests(unittest.TestCase):
    def test_cuda_gate_rejects_unavailable_cuda_before_training(self) -> None:
        from argentum_ml.learner.c1_06 import C1_06GpuGateError, require_cuda_device

        with self.assertRaises(C1_06GpuGateError):
            require_cuda_device(_FakeTorch(False, 0))

    def test_cuda_gate_requires_device_zero(self) -> None:
        from argentum_ml.learner.c1_06 import C1_06GpuGateError, require_cuda_device

        with self.assertRaises(C1_06GpuGateError):
            require_cuda_device(_FakeTorch(True, 0))
        self.assertEqual(require_cuda_device(_FakeTorch(True, 1)), "cuda:0")


class C1_06ModelContractTests(unittest.TestCase):
    def test_reference_model_config_is_explicit_and_feed_forward(self) -> None:
        from argentum_ml.learner.c1_06 import C1_06ModelConfigV1

        config = C1_06ModelConfigV1.reference()
        self.assertEqual(config.architecture_identity, "argentum-ml-c1-06-feed-forward-candidate-scorer@v1")
        self.assertEqual(config.hidden_width, 128)
        self.assertEqual(config.hidden_layers, 2)
        self.assertEqual(config.dtype, "float32")

    @_REQUIRES_TORCH
    def test_real_model_candidate_permutation_preserves_semantic_scores(self) -> None:
        import torch

        from argentum_ml.learner.c1_06 import (
            C1_06ModelConfigV1,
            C1_06TrainingSample,
            FeedForwardCandidateScorer,
            tensorize_samples,
        )

        config = C1_06ModelConfigV1.reference()
        torch.manual_seed(11)
        model = FeedForwardCandidateScorer(config, device=torch.device("cpu"))
        candidates = (
            {"kind": "CastSpell", "cardId": "card-a", "affordable": True},
            {"kind": "CastSpell", "cardId": "card-b", "affordable": True},
            {"kind": "CastSpell", "cardId": "card-c", "affordable": True},
        )

        def scores_for(candidate_views: tuple[dict[str, object], ...], target_index: int) -> dict[str, float]:
            sample = C1_06TrainingSample(
                model_input={"observation": {"phase": "BEGINNING"}},
                candidate_feature_views=candidate_views,
                target_index=target_index,
                partition="VALIDATION",
                source_key=("episode-a", 0, "decision-a"),
            )
            batch = tensorize_samples(
                [sample],
                torch_module=torch,
                device=torch.device("cpu"),
                config=config,
            )
            with torch.no_grad():
                values = model(batch.observation, batch.candidates)[0]
            return {candidate["cardId"]: float(values[index]) for index, candidate in enumerate(candidate_views)}

        original = scores_for(candidates, target_index=0)
        permuted_candidates = (candidates[2], candidates[0], candidates[1])
        permuted = scores_for(permuted_candidates, target_index=1)
        self.assertEqual(original, permuted)

    def test_model_rejects_empty_or_truncated_candidate_batch(self) -> None:
        from argentum_ml.learner.c1_06 import validate_candidate_batch

        with self.assertRaises(ValueError):
            validate_candidate_batch([], target_index=0)
        with self.assertRaises(ValueError):
            validate_candidate_batch([{"kind": "PassPriority"}], target_index=1)

    @_REQUIRES_TORCH
    def test_feed_forward_model_supports_variable_candidate_counts(self) -> None:
        import torch

        from argentum_ml.learner.c1_06 import (
            C1_06TrainingSample,
            FeedForwardCandidateScorer,
            tensorize_samples,
        )

        config = __import__("argentum_ml.learner.c1_06", fromlist=["C1_06ModelConfigV1"]).C1_06ModelConfigV1.reference()
        model = FeedForwardCandidateScorer(config, device=torch.device("cpu"))
        batch = tensorize_samples(
            [
                C1_06TrainingSample(
                    model_input={"observation": {"phase": "BEGINNING"}},
                    candidate_feature_views=({"kind": "PassPriority"},),
                    target_index=0,
                    partition="TRAIN",
                    source_key=("episode-a", 0, "decision-a"),
                ),
                C1_06TrainingSample(
                    model_input={"observation": {"phase": "COMBAT"}},
                    candidate_feature_views=tuple({"kind": kind} for kind in ("PassPriority", "PlayLand", "ActivateAbility")),
                    target_index=1,
                    partition="TRAIN",
                    source_key=("episode-b", 1, "decision-b"),
                ),
            ],
            torch_module=torch,
            device=torch.device("cpu"),
            config=config,
        )
        scores = model(batch.observation, batch.candidates)
        self.assertEqual(tuple(scores.shape), (2, 3))
        self.assertTrue(torch.isfinite(scores).all().item())

    @_REQUIRES_TORCH
    def test_training_step_has_finite_cuda_or_explicit_cpu_test_tensors(self) -> None:
        import torch

        from argentum_ml.learner.c1_06 import (
            C1_06TrainingSample,
            FeedForwardCandidateScorer,
            candidate_selection_loss,
            tensorize_samples,
        )

        config = __import__("argentum_ml.learner.c1_06", fromlist=["C1_06ModelConfigV1"]).C1_06ModelConfigV1.reference()
        model = FeedForwardCandidateScorer(config, device=torch.device("cpu"))
        batch = tensorize_samples(
            [
                C1_06TrainingSample(
                    model_input={"observation": {"phase": "BEGINNING"}},
                    candidate_feature_views=({"kind": "PassPriority"}, {"kind": "PlayLand"}),
                    target_index=1,
                    partition="TRAIN",
                    source_key=("episode-a", 0, "decision-a"),
                )
            ],
            torch_module=torch,
            device=torch.device("cpu"),
            config=config,
        )
        optimizer = torch.optim.Adam(model.parameters(), lr=0.01)
        scores = model(batch.observation, batch.candidates)
        loss = candidate_selection_loss(scores, batch.target_indices, batch.candidate_mask)
        self.assertTrue(torch.isfinite(loss).item())
        loss.backward()
        self.assertTrue(all(parameter.grad is not None for parameter in model.parameters()))
        optimizer.step()

    @_REQUIRES_TORCH
    def test_tiny_overfit_loss_decreases_on_explicit_test_cpu(self) -> None:
        import torch

        from argentum_ml.learner.c1_06 import (
            C1_06ModelConfigV1,
            C1_06TrainingSample,
            FeedForwardCandidateScorer,
            run_training_loop,
        )

        config = C1_06ModelConfigV1.reference()
        torch.manual_seed(7)
        model = FeedForwardCandidateScorer(config, device=torch.device("cpu"))
        samples = [
            C1_06TrainingSample(
                model_input={"observation": {"phase": "BEGINNING"}},
                candidate_feature_views=({"kind": "PassPriority"}, {"kind": "PlayLand"}),
                target_index=0,
                partition="TRAIN",
                source_key=("episode-a", index, f"decision-{index}"),
            )
            for index in range(4)
        ]
        result = run_training_loop(
            model,
            samples,
            validation_samples=samples,
            config=config,
            torch_module=torch,
            device=torch.device("cpu"),
            optimizer_steps=30,
            batch_size=4,
            learning_rate=0.01,
        )
        self.assertLess(result.final_train_loss, result.initial_train_loss)
        self.assertEqual(result.training_examples_processed, 120)
        self.assertTrue(result.nonfinite_scores == 0)
        self.assertTrue(result.nonfinite_losses == 0)

    def test_tiny_overfit_requires_strong_loss_decrease_and_agreement_increase(self) -> None:
        from argentum_ml.learner.c1_06 import validate_tiny_overfit

        weak = SimpleNamespace(
            initial_train_loss=1.0,
            final_train_loss=0.75,
            initial_train_top1_agreement=0.5,
            train_top1_agreement=0.5,
        )
        with self.assertRaises(ValueError):
            validate_tiny_overfit(weak)

        strong = SimpleNamespace(
            initial_train_loss=1.0,
            final_train_loss=0.4,
            initial_train_top1_agreement=0.5,
            train_top1_agreement=0.6,
        )
        validate_tiny_overfit(strong)

    @_REQUIRES_TORCH
    def test_cuda_kernel_probe_requires_real_cuda(self) -> None:
        import torch

        from argentum_ml.learner.c1_06 import C1_06GpuGateError, run_cuda_kernel_probe

        if not torch.cuda.is_available():
            with self.assertRaises(C1_06GpuGateError):
                run_cuda_kernel_probe(torch)
        else:
            evidence = run_cuda_kernel_probe(torch)
            self.assertGreater(evidence.peak_memory_bytes, 0)


class C1_06CheckpointContractTests(unittest.TestCase):
    def test_checkpoint_builder_binds_c1_05_label_identity(self) -> None:
        from argentum_ml.learner.c1_06 import build_feed_forward_checkpoint_identity

        identity = build_feed_forward_checkpoint_identity(
            source_dataset_identity="a" * 64,
            label_artifact_id="b" * 64,
            labels_content_digest="c" * 64,
            manifest_content_digest="d" * 64,
            model_config_digest="e" * 64,
            source_commit="f" * 40,
        )
        self.assertEqual(identity["policyArtifactKind"], "FEED_FORWARD_POLICY")
        self.assertEqual(identity["recurrentSequenceContractIdentity"], "NONE_FOR_FEED_FORWARD")
        self.assertEqual(identity["labelArtifactId"], "b" * 64)

    @_REQUIRES_TORCH
    def test_checkpoint_safetensors_round_trip_preserves_scores(self) -> None:
        import tempfile
        from pathlib import Path

        import torch

        from argentum_ml.learner.c1_06 import (
            C1_06ModelConfigV1,
            C1_06TrainingSample,
            FeedForwardCandidateScorer,
            load_c1_06_checkpoint,
            save_c1_06_checkpoint,
            tensorize_samples,
        )

        config = C1_06ModelConfigV1.reference()
        model = FeedForwardCandidateScorer(config, device=torch.device("cpu"))
        batch = tensorize_samples(
            [
                C1_06TrainingSample(
                    model_input={"observation": {"phase": "BEGINNING"}},
                    candidate_feature_views=({"kind": "PassPriority"}, {"kind": "PlayLand"}),
                    target_index=0,
                    partition="VALIDATION",
                    source_key=("episode-a", 0, "decision-a"),
                )
            ],
            torch_module=torch,
            device=torch.device("cpu"),
            config=config,
        )
        before = model(batch.observation, batch.candidates).detach().clone()
        with tempfile.TemporaryDirectory() as directory:
            result = save_c1_06_checkpoint(
                model,
                Path(directory),
                source_dataset_identity="a" * 64,
                label_artifact_id="b" * 64,
                labels_content_digest="c" * 64,
                manifest_content_digest="d" * 64,
                source_commit="e" * 40,
                config=config,
            )
            reloaded = FeedForwardCandidateScorer(config, device=torch.device("cpu"))
            load_c1_06_checkpoint(
                reloaded,
                result.manifest_path,
                result.weight_path,
                expected_source_dataset_identity="a" * 64,
                expected_label_artifact_id="b" * 64,
                expected_labels_content_digest="c" * 64,
                expected_manifest_content_digest="d" * 64,
                expected_source_commit="e" * 40,
                config=config,
            )
            after = reloaded(batch.observation, batch.candidates).detach()
        self.assertTrue(torch.equal(before, after))

    @_REQUIRES_TORCH
    def test_checkpoint_reload_rejects_wrong_source_identity(self) -> None:
        import tempfile
        from pathlib import Path

        import torch

        from argentum_ml.learner.c1_06 import (
            C1_06DataGateError,
            C1_06ModelConfigV1,
            FeedForwardCandidateScorer,
            load_c1_06_checkpoint,
            save_c1_06_checkpoint,
        )

        config = C1_06ModelConfigV1.reference()
        model = FeedForwardCandidateScorer(config, device=torch.device("cpu"))
        with tempfile.TemporaryDirectory() as directory:
            result = save_c1_06_checkpoint(
                model,
                Path(directory),
                source_dataset_identity="a" * 64,
                label_artifact_id="b" * 64,
                labels_content_digest="c" * 64,
                manifest_content_digest="d" * 64,
                source_commit="e" * 40,
                config=config,
            )
            with self.assertRaises(C1_06DataGateError):
                load_c1_06_checkpoint(
                    FeedForwardCandidateScorer(config, device=torch.device("cpu")),
                    result.manifest_path,
                    result.weight_path,
                    expected_source_dataset_identity="z" * 64,
                    expected_label_artifact_id="b" * 64,
                    expected_labels_content_digest="c" * 64,
                    expected_manifest_content_digest="d" * 64,
                    expected_source_commit="e" * 40,
                    config=config,
                )

    @_REQUIRES_TORCH
    def test_checkpoint_reload_rejects_wrong_label_identity(self) -> None:
        import tempfile
        from pathlib import Path

        import torch

        from argentum_ml.learner.c1_06 import (
            C1_06DataGateError,
            C1_06ModelConfigV1,
            FeedForwardCandidateScorer,
            load_c1_06_checkpoint,
            save_c1_06_checkpoint,
        )

        config = C1_06ModelConfigV1.reference()
        model = FeedForwardCandidateScorer(config, device=torch.device("cpu"))
        with tempfile.TemporaryDirectory() as directory:
            result = save_c1_06_checkpoint(
                model,
                Path(directory),
                source_dataset_identity="a" * 64,
                label_artifact_id="b" * 64,
                labels_content_digest="c" * 64,
                manifest_content_digest="d" * 64,
                source_commit="e" * 40,
                config=config,
            )
            with self.assertRaises(C1_06DataGateError):
                load_c1_06_checkpoint(
                    FeedForwardCandidateScorer(config, device=torch.device("cpu")),
                    result.manifest_path,
                    result.weight_path,
                    expected_source_dataset_identity="a" * 64,
                    expected_label_artifact_id="z" * 64,
                    expected_labels_content_digest="c" * 64,
                    expected_manifest_content_digest="d" * 64,
                    expected_source_commit="e" * 40,
                    config=config,
                )


class C1_06DataJoinTests(unittest.TestCase):
    def test_source_label_join_derives_target_index_from_current_candidate_transport(self) -> None:
        from argentum_ml.learner.c1_06 import source_label_to_training_sample

        source = {
            "partition": "TRAIN",
            "sourceReference": {
                "trajectoryId": "a" * 64,
                "decisionIndex": 4,
                "semanticDecisionId": {"value": "b" * 64},
            },
            "input": {
                "decisionContext": {"domainKind": "ACTION_CANDIDATES"},
                "observation": {"phase": "BEGINNING"},
                "domain": {
                    "candidates": [
                        {"kind": "PassPriority", "affordable": True},
                        {"kind": "PlayLand", "affordable": True},
                    ]
                },
            },
            "binding": {"sourceBindingOrdinals": [0, 1]},
        }
        label = {
            "partition": "TRAIN",
            "sourceReference": source["sourceReference"],
            "binding": {"sourceBindingOrdinal": 1},
        }
        sample = source_label_to_training_sample(source, label)
        self.assertEqual(sample.target_index, 1)
        self.assertEqual(len(sample.candidate_feature_views), 2)
        self.assertEqual(sample.partition, "TRAIN")

    def test_bounded_label_selection_is_stable_and_excludes_test(self) -> None:
        from argentum_ml.learner.c1_06 import select_bounded_label_rows

        labels = [
            {"partition": "TEST", "sourceReference": {"semanticEpisodeId": "z"}},
            {"partition": "TRAIN", "sourceReference": {"semanticEpisodeId": "b", "trajectoryId": "b", "decisionIndex": 1, "semanticDecisionId": {"value": "b"}}},
            {"partition": "TRAIN", "sourceReference": {"semanticEpisodeId": "a", "trajectoryId": "a", "decisionIndex": 1, "semanticDecisionId": {"value": "a"}}},
        ]
        selected = select_bounded_label_rows(labels, train_limit=1, validation_limit=0)
        self.assertEqual(len(selected), 1)
        self.assertEqual(selected[0]["sourceReference"]["semanticEpisodeId"], "a")

    def test_source_label_join_rejects_test_and_missing_target(self) -> None:
        from argentum_ml.learner.c1_06 import C1_06DataGateError, source_label_to_training_sample

        with self.assertRaises(C1_06DataGateError):
            source_label_to_training_sample(
                {"partition": "TEST", "input": {}, "binding": {}, "sourceReference": {}},
                {"partition": "TEST", "binding": {"sourceBindingOrdinal": 0}},
            )


if __name__ == "__main__":
    unittest.main()

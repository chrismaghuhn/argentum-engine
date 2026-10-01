import gzip
import json
import math
import tempfile
import unittest
from pathlib import Path

import torch
from safetensors.torch import save_file

from argentum_ml.p1 import ppo
from argentum_ml.p1.features import Vocab, encode
from argentum_ml.p1.model import P1Model, P1ModelConfig, collate
from argentum_ml.p1.serve import choose
from tests.test_p1_features import _sample

TINY = P1ModelConfig(d_model=32, heads=4, layers=1, ff=64, dropout=0.0)


def _row(game, seat, step, played, outcome, logprob=-0.7, value=0.0, executed=True, allowed=None):
    row = _sample(game=game, chosen=played, outcome=outcome)
    row.update({"seat": seat, "engineStep": step, "played": played, "logprob": logprob, "value": value,
                "executed": executed, "source": "ppo", "opponent": "self",
                "allowed": allowed if allowed is not None else [True, True]})
    row["turn"] = step  # distinct positions
    return row


def _write_game(directory: Path, game: int, rows: list[dict]) -> None:
    with gzip.open(directory / f"game-{game:06d}.jsonl.gz", "wt", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row) + "\n")


def _checkpoint(directory: Path, vocab: Vocab) -> P1Model:
    torch.manual_seed(0)
    model = P1Model(vocab, TINY)
    directory.mkdir(parents=True)
    save_file({k: v.contiguous() for k, v in model.state_dict().items()}, str(directory / "model.safetensors"))
    (directory / "vocab.json").write_text(json.dumps(vocab.to_json()), encoding="utf-8")
    (directory / "config.json").write_text(json.dumps(TINY.to_json()), encoding="utf-8")
    return model.eval()


class GaeTest(unittest.TestCase):
    def test_terminal_reward_flows_back_with_lambda_one(self):
        advantages, returns = ppo.gae([0.0, 0.0, 0.0], 1.0, gamma=1.0, lam=1.0)
        self.assertEqual(advantages, [1.0, 1.0, 1.0])
        self.assertEqual(returns, [1.0, 1.0, 1.0])

    def test_lambda_zero_is_one_step_td(self):
        advantages, returns = ppo.gae([0.2, 0.5], -1.0, gamma=1.0, lam=0.0)
        self.assertAlmostEqual(advantages[1], -1.5)
        self.assertAlmostEqual(advantages[0], 0.5 - 0.2)
        self.assertAlmostEqual(returns[0], 0.5)


class TrajectoryTest(unittest.TestCase):
    def test_groups_by_file_and_seat_in_decision_order(self):
        rows = [_row(0, "Akiri", 30, 0, 1), _row(0, "Chevill", 20, 1, -1), _row(0, "Akiri", 10, 1, 1)]
        for row in rows:
            row["_file"] = "g0"
        groups = ppo.trajectories(rows)
        self.assertEqual([[r["engineStep"] for r in g] for g in groups], [[10, 30], [20]])

    def test_allowed_mask_falls_back_to_all_candidates(self):
        self.assertEqual(ppo.allowed_mask(_row(0, "Akiri", 1, 0, 1, allowed=[False, False])), [True, True])
        self.assertEqual(ppo.allowed_mask(_row(0, "Akiri", 1, 0, 1, allowed=[True, False])), [True, False])


class PpoLossTest(unittest.TestCase):
    def test_unchanged_policy_has_ratio_one(self):
        vocab = Vocab.build([_sample()])
        torch.manual_seed(0)
        model = P1Model(vocab, TINY).eval()
        rows = [_row(0, "Akiri", s, s % 2, 1) for s in range(4)]
        with torch.no_grad():
            for row in rows:
                scores, value = model(collate([encode(dict(row, chosen=row["played"]), vocab)]))
                row["logprob"] = float(torch.log_softmax(scores[0], -1)[row["played"]])
                row["value"] = float(value[0])
                row["_file"] = "g0"
        steps = ppo.build_steps(rows, vocab, gamma=1.0, lam=0.95)
        _, stats = ppo.ppo_loss(model, ppo.collate_steps(steps, "cpu"), 0.2, 0.5, 0.01, 0.0)
        self.assertAlmostEqual(stats["approxKl"], 0.0, places=5)
        self.assertEqual(stats["clipFraction"], 0.0)
        self.assertGreater(stats["entropy"], 0.0)

    def test_disallowed_candidate_gets_no_probability(self):
        scores = torch.tensor([[1.0, 2.0, float("-inf")]])
        allowed = torch.tensor([[True, False, False]])
        logprobs = ppo.masked_logprobs(scores, allowed)
        self.assertAlmostEqual(logprobs[0, 0].item(), 0.0)
        self.assertTrue(math.isinf(logprobs[0, 1].item()))


class PpoMainTest(unittest.TestCase):
    def test_writes_a_ppo_checkpoint_from_rollouts(self):
        vocab = Vocab.build([_sample()])
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _checkpoint(root / "start", vocab)
            rollouts = root / "rollouts"
            rollouts.mkdir()
            _write_game(rollouts, 0, [_row(0, "Akiri", s, s % 2, 1) for s in range(6)]
                        + [_row(0, "Chevill", s + 100, 0, -1, executed=s != 2) for s in range(6)])
            _write_game(rollouts, 1, [_row(1, "Akiri", s, 1, -1) for s in range(5)])
            out = ppo.main(["--checkpoint", str(root / "start"), "--reference", str(root / "start"),
                            "--data", str(rollouts), "--runs", str(root / "runs"), "--epochs", "2",
                            "--batch-size", "4"])
            self.assertEqual(out.name, "p1-ppo-0001")
            metrics = json.loads((out / "metrics.json").read_text(encoding="utf-8"))
            self.assertEqual(metrics["kind"], "ppo")
            self.assertEqual(metrics["data"]["trainSamples"], 17)
            self.assertEqual(metrics["rollouts"]["byOpponent"]["self"]["seatGames"], 3)
            self.assertEqual(metrics["rollouts"]["byOpponent"]["self"]["wins"], 1)
            self.assertTrue((out / "model.safetensors").exists())


class SampledServeTest(unittest.TestCase):
    def test_sampling_respects_mask_and_reports_its_logprob(self):
        vocab = Vocab.build([_sample()])
        torch.manual_seed(0)
        model = P1Model(vocab, TINY).eval()
        generator = torch.Generator().manual_seed(3)
        picks = {choose(model, vocab, _sample(), allowed=[False, True], generator=generator)["chosen"] for _ in range(20)}
        self.assertEqual(picks, {1})
        reply = choose(model, vocab, _sample(), allowed=[True, True], generator=generator)
        self.assertLessEqual(reply["logprob"], 0.0)
        greedy = choose(model, vocab, _sample())
        self.assertEqual(greedy["chosen"], max(range(2), key=lambda i: greedy["scores"][i]))


if __name__ == "__main__":
    unittest.main()

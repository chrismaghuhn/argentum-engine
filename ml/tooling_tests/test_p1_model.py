import unittest

import torch
from torch.nn import functional as F

from argentum_ml.p1.features import Vocab, encode
from argentum_ml.p1.model import P1Model, P1ModelConfig, collate, collate_tensors, pretensorize
from tests.test_p1_features import _sample


class P1ModelTest(unittest.TestCase):
    def setUp(self):
        torch.manual_seed(0)
        torch.set_num_threads(2)
        # Distinct positions: identical observations with different labels cannot be learned.
        few = _sample(chosen=0, outcome=-1)
        few["turn"] = 9
        few["players"][0]["life"] = 12
        many = _sample(chosen=2, outcome=1)
        many["candidates"].append({"kind": "CastSpell", "source": 2, "targets": [], "manaCost": "{1}{W}", "hasX": False})
        self.raw = [_sample(chosen=1), few, many]
        self.vocab = Vocab.build(self.raw)
        self.samples = [encode(s, self.vocab) for s in self.raw]

    def test_collate_pads_candidates_and_scores_padding_as_impossible(self):
        batch = collate(self.samples)
        self.assertEqual(tuple(batch["candidate_kind"].shape), (3, 3))
        self.assertTrue(batch["candidate_pad"][0, 2].item())
        model = P1Model(self.vocab, P1ModelConfig(d_model=32, heads=4, layers=1, ff=64, dropout=0.0))
        scores, value = model(batch)
        self.assertEqual(tuple(scores.shape), (3, 3))
        self.assertTrue(torch.isinf(scores[0, 2]).item())
        self.assertTrue(((value >= -1) & (value <= 1)).all().item())

    def test_fast_collate_matches_reference_collate(self):
        reference = collate(self.samples)
        fast = collate_tensors([pretensorize(s) for s in self.samples])
        self.assertEqual(set(reference), set(fast))
        for name, tensor in reference.items():
            self.assertTrue(torch.equal(tensor, fast[name]), name)

    def test_learns_a_tiny_batch(self):
        batch = collate(self.samples)
        model = P1Model(self.vocab, P1ModelConfig(d_model=32, heads=4, layers=1, ff=64, dropout=0.0))
        optimizer = torch.optim.Adam(model.parameters(), lr=3e-3)
        for _ in range(150):
            scores, value = model(batch)
            loss = F.cross_entropy(scores, batch["chosen"]) + F.mse_loss(value, batch["outcome"])
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
        scores, value = model(batch)
        self.assertTrue(torch.equal(scores.argmax(-1), batch["chosen"]))
        self.assertLess(F.mse_loss(value, batch["outcome"]).item(), 0.1)


if __name__ == "__main__":
    unittest.main()

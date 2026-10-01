import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

import torch

from argentum_ml.p1 import cardtext
from argentum_ml.p1.cardtext import CardFeatures, parse_mana_cost, text_terms, tokenize
from argentum_ml.p1.features import Vocab, encode
from argentum_ml.p1.model import P1Model, P1ModelConfig, collate, collate_tensors, load_checkpoint, pretensorize, save_checkpoint
from argentum_ml.p1.train import _zero_card_feature_layers
from tests.test_p1_features import _sample

TINY = dict(d_model=32, heads=4, layers=1, ff=64, dropout=0.0)
TABLE = {
    "Puresteel Paladin": {"oracleText": "Whenever an Equipment you control enters, you may draw a card.\n"
                                        "Metalcraft - Equipment you control have equip {0}.",
                          "manaCost": "{W}{W}", "subtypes": ["Human", "Knight"]},
    "Vulshok Morningstar": {"oracleText": "Equipped creature gets +2/+2.\nEquip {2}", "manaCost": "{2}", "subtypes": ["Equipment"]},
    "Grizzly Bears": {"oracleText": "", "manaCost": "{1}{G}", "subtypes": ["Bear"]},
    "Lightning Bolt": {"oracleText": "Lightning Bolt deals 3 damage to any target.", "manaCost": "{R}", "subtypes": []},
}


class TokenizeTest(unittest.TestCase):
    def test_self_name_symbols_and_modifiers(self):
        words = tokenize("Lightning Bolt deals 3 damage to any target.\n{T}: Add {C}. Gets +2/+2.", "Lightning Bolt")
        self.assertEqual(words[0], "~")
        self.assertIn("3", words)
        self.assertIn("{t}", words)
        self.assertIn("{c}", words)
        self.assertIn("+2/+2", words)
        self.assertEqual(words.count("<nl>"), 2)

    def test_terms_add_bigrams(self):
        terms = text_terms("Draw a card.")
        self.assertIn("draw a", terms)
        self.assertIn("a card", terms)


class ManaCostTest(unittest.TestCase):
    def test_generic_colored_x_hybrid_phyrexian(self):
        def cost(text):
            return dict(zip(cardtext.COST_FEATURES, (v * 10 for v in parse_mana_cost(text))))
        self.assertEqual(cost("{2}{G}{G}")["generic"], 2)
        self.assertEqual(cost("{2}{G}{G}")["G"], 2)
        self.assertEqual(cost("{X}{R}")["X"], 1)
        self.assertEqual(cost("{W/U}")["hybrid"], 1)
        self.assertEqual(cost("{W/U}")["W"], 0.5)
        self.assertEqual(cost("{B/P}")["phyrexian"], 1)
        self.assertEqual(cost("{C}")["C"], 1)
        self.assertEqual(sum(parse_mana_cost("")), 0)


class CardFeaturesV2Test(unittest.TestCase):
    def setUp(self):
        torch.manual_seed(0)
        self.vocab = Vocab.build([_sample()])
        self.cards = CardFeatures.build(TABLE, sorted(self.vocab.maps["name"]), "test")
        self.samples = [encode(_sample(), self.vocab), encode(_sample(chosen=0, outcome=-1), self.vocab)]

    def test_rows_follow_the_name_vocabulary(self):
        text, subtypes, cost = self.cards.rows(self.vocab.maps["name"], self.vocab.size("name"))
        paladin = self.vocab.id("name", "Puresteel Paladin")
        self.assertGreater(sum(1 for t in text[paladin] if t), 5)
        self.assertEqual(sum(1 for s in subtypes[paladin] if s), 2)
        self.assertAlmostEqual(cost[paladin][1], 0.2)  # {W}{W}
        self.assertEqual(sum(text[0]) + sum(text[1]), 0)  # pad and unknown have no text

    def test_v2_forward_and_name_dropout_only_in_training(self):
        config = P1ModelConfig(**TINY, text=True, subtypes=True, mana_cost=True, name_dropout=1.0)
        model = P1Model(self.vocab, config, self.cards)
        batch = collate(self.samples)
        model.eval()
        a, _ = model(batch)
        b, _ = model(batch)
        self.assertTrue(torch.equal(a, b))
        self.assertTrue(torch.isfinite(a[:, :2]).all())
        model.train()
        c, _ = model(batch)
        self.assertFalse(torch.allclose(a[:, :2], c[:, :2]))  # every name hidden in training

    def test_candidate_cost_reaches_both_collates(self):
        reference = collate(self.samples)
        fast = collate_tensors([pretensorize(s) for s in self.samples])
        self.assertTrue(torch.equal(reference["candidate_cost"], fast["candidate_cost"]))
        self.assertAlmostEqual(reference["candidate_cost"][0, 1, 0].item(), 0.2)  # {2}

    def test_warm_start_from_v1_plays_identically(self):
        v1 = P1Model(self.vocab, P1ModelConfig(**TINY)).eval()
        v2 = P1Model(self.vocab, P1ModelConfig(**TINY, text=True, subtypes=True, mana_cost=True), self.cards)
        missing, unexpected = v2.load_state_dict(v1.state_dict(), strict=False)
        self.assertFalse(unexpected)
        _zero_card_feature_layers(v2)
        v2.eval()
        batch = collate(self.samples)
        s1, val1 = v1(batch)
        s2, val2 = v2(batch)
        self.assertTrue(torch.allclose(s1, s2, atol=1e-6))
        self.assertTrue(torch.allclose(val1, val2, atol=1e-6))

    def test_v2_checkpoint_round_trip_and_v1_still_loads(self):
        with tempfile.TemporaryDirectory() as tmp:
            v2 = P1Model(self.vocab, P1ModelConfig(**TINY, text=True, mana_cost=True, name_dropout=0.2), self.cards).eval()
            save_checkpoint(v2, self.vocab, Path(tmp) / "v2")
            loaded, _ = load_checkpoint(Path(tmp) / "v2")
            loaded.eval()
            self.assertTrue(loaded.config.text and loaded.config.mana_cost and not loaded.config.subtypes)
            self.assertEqual(loaded.config.name_dropout, 0.2)
            batch = collate(self.samples)
            self.assertTrue(torch.equal(v2(batch)[0], loaded(batch)[0]))

            v1 = P1Model(self.vocab, P1ModelConfig(**TINY)).eval()
            save_checkpoint(v1, self.vocab, Path(tmp) / "v1")
            self.assertFalse((Path(tmp) / "v1" / "card_features.json").exists())
            loaded1, _ = load_checkpoint(Path(tmp) / "v1")
            self.assertEqual(loaded1.config, replace(P1ModelConfig(**TINY)))


if __name__ == "__main__":
    unittest.main()

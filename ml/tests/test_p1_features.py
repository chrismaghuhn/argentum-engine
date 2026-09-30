import gzip
import json
import tempfile
import unittest
from pathlib import Path

from argentum_ml.p1 import features
from argentum_ml.p1.features import MAX_TARGETS, PAD, UNK, Vocab, encode


def _card(name, zone="BATTLEFIELD", side="self", **extra):
    card = {
        "name": name,
        "zone": zone,
        "side": side,
        "types": ["CREATURE"],
        "colors": ["WHITE"],
        "keywords": [],
        "manaValue": 2,
        "power": 2,
        "toughness": 2,
        "tapped": False,
        "summoningSick": False,
        "faceDown": False,
        "damage": 0,
        "counters": {},
        "attachedTo": -1,
    }
    card.update(extra)
    return card


def _sample(game=0, chosen=1, outcome=1):
    return {
        "schema": features.SAMPLE_SCHEMA,
        "game": game,
        "engineStep": 10,
        "seat": "Akiri",
        "turn": 3,
        "phase": "PRECOMBAT_MAIN",
        "step": "PRECOMBAT_MAIN",
        "activeIsSelf": True,
        "players": [
            {"side": "self", "life": 40, "hand": 5, "library": 90, "graveyard": 1, "exile": 0,
             "mana": [0, 0, 0, 0, 0, 0], "active": True},
            {"side": "opponent", "life": 38, "hand": 6, "library": 91, "graveyard": 0, "exile": 0,
             "mana": [0, 0, 0, 0, 0, 0], "active": False},
        ],
        "cards": [
            _card("Puresteel Paladin"),
            _card("Vulshok Morningstar", types=["ARTIFACT"], power=None, toughness=None, attachedTo=0),
            _card("Grizzly Bears", side="opponent", counters={"+1/+1": 2}),
        ],
        "stack": [{"name": "Lightning Bolt", "kind": "SPELL", "controller": "opponent", "source": -1, "targets": [0]}],
        "candidates": [
            {"kind": "PassPriority", "source": -1, "targets": [], "manaCost": "", "hasX": False},
            {"kind": "ActivateAbility", "source": 1, "targets": [0, 2, 7], "manaCost": "{2}", "hasX": False},
        ],
        "chosen": chosen,
        "outcome": outcome,
    }


class P1FeaturesTest(unittest.TestCase):
    def test_vocab_reserves_pad_and_unknown(self):
        vocab = Vocab.build([_sample()])
        self.assertEqual(vocab.id("name", "Puresteel Paladin"), 2)
        self.assertEqual(vocab.id("name", "Never Seen"), UNK)
        self.assertEqual(vocab.size("kind"), 2 + 2)
        self.assertNotEqual(vocab.id("zone", "STACK"), UNK)

    def test_vocab_round_trips_through_json(self):
        vocab = Vocab.build([_sample()])
        restored = Vocab.from_json(json.loads(json.dumps(vocab.to_json())))
        self.assertEqual(restored.maps, vocab.maps)

    def test_encode_shapes_references_and_attachments(self):
        vocab = Vocab.build([_sample()])
        encoded = encode(_sample(), vocab)
        # three cards plus one stack item
        self.assertEqual(len(encoded.token_name), 4)
        self.assertEqual(encoded.token_zone[3], vocab.id("zone", "STACK"))
        self.assertEqual(encoded.token_side, [0, 0, 1, 1])
        self.assertEqual(len(encoded.token_numeric[0]), len(features.TOKEN_NUMERIC))
        self.assertEqual(len(encoded.global_numeric), len(features.GLOBAL_NUMERIC))
        # the paladin carries one attachment, the equipment is attached
        attached = features.TOKEN_NUMERIC.index("attached")
        count = features.TOKEN_NUMERIC.index("attachmentCount")
        self.assertEqual(encoded.token_numeric[1][attached], 1.0)
        self.assertEqual(encoded.token_numeric[0][count], 0.25)
        # candidate references stay card indices; out-of-range targets are dropped, -1 padded
        self.assertEqual(encoded.candidate_source, [-1, 1])
        self.assertEqual(encoded.candidate_targets[1], [0, 2, -1, -1][:MAX_TARGETS])
        self.assertEqual(encoded.candidate_targets[0], [-1] * MAX_TARGETS)
        self.assertEqual(encoded.chosen, 1)
        self.assertEqual(encoded.outcome, 1.0)
        self.assertEqual(encoded.token_types[1][1:], [PAD] * (features.MAX_TYPES - 1))

    def test_split_holds_out_every_tenth_game_and_builds_vocab_from_training_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for game in (0, 9):
                sample = _sample(game=game)
                if game == 9:
                    sample["cards"].append(_card("Validation Only Card"))
                with gzip.open(root / f"game-{game:06d}.jsonl.gz", "wt", encoding="utf-8") as handle:
                    handle.write(json.dumps(sample) + "\n")
            vocab, train, val = features.load_split(root)
            self.assertEqual([s.game for s in train], [0])
            self.assertEqual([s.game for s in val], [9])
            self.assertEqual(vocab.id("name", "Validation Only Card"), UNK)

    def test_rejects_unknown_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "game-000000.jsonl.gz"
            with gzip.open(path, "wt", encoding="utf-8") as handle:
                handle.write(json.dumps({"schema": "something-else"}) + "\n")
            with self.assertRaises(ValueError):
                list(features.read_samples(path))


if __name__ == "__main__":
    unittest.main()

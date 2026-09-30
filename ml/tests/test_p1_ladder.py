import json
import tempfile
import unittest
from pathlib import Path

from argentum_ml.p1 import ladder


def _row(a, b, winner, deck_a="Akiri", terminal=True, choices=10, fallbacks=0):
    return {
        "schema": ladder.RESULT_SCHEMA, "game": 0, "seatA": a, "seatB": b, "deckA": deck_a,
        "aStarts": True, "terminal": terminal, "winner": winner, "turns": 20, "engineSteps": 500,
        "modelChoices": choices, "modelFallbacks": fallbacks, "seconds": 1.0,
    }


class P1LadderTest(unittest.TestCase):
    def test_engine_is_anchored_and_stronger_player_rates_higher(self):
        rows = [_row("ckpt-2", "engine:x", "A")] * 7 + [_row("ckpt-2", "engine:x", "B")] * 3
        rows += [_row("ckpt-1", "engine:x", "A")] * 3 + [_row("ckpt-1", "engine:x", "B")] * 7
        ratings = ladder.elo(rows)
        self.assertEqual(ratings["engine:x"], 1500.0)
        self.assertGreater(ratings["ckpt-2"], 1500.0)
        self.assertLess(ratings["ckpt-1"], 1500.0)

    def test_even_record_rates_near_anchor(self):
        rows = [_row("ckpt-1", "engine:x", "A"), _row("ckpt-1", "engine:x", "B")] * 10
        self.assertAlmostEqual(ladder.elo(rows)["ckpt-1"], 1500.0, delta=1.0)

    def test_matchups_count_both_sides_decks_and_unfinished_as_half(self):
        rows = [
            _row("ckpt-1", "engine:x", "A", deck_a="Akiri", fallbacks=2),
            _row("ckpt-1", "engine:x", "-", deck_a="Chevill", terminal=False),
        ]
        stats = ladder.matchups(rows)
        mine = stats[("ckpt-1", "engine:x")]
        theirs = stats[("engine:x", "ckpt-1")]
        self.assertEqual(mine.games, 2)
        self.assertEqual(mine.points, 1.5)
        self.assertEqual(theirs.points, 0.5)
        self.assertEqual(mine.unfinished, 1)
        self.assertEqual(mine.by_deck, {"Akiri": [1, 1.0], "Chevill": [1, 0.5]})
        self.assertEqual(theirs.by_deck, {"Chevill": [1, 0.0], "Akiri": [1, 0.5]})
        self.assertEqual(mine.fallbacks, 2)
        self.assertEqual(theirs.fallbacks, 0)

    def test_card_usage_aggregates_per_player_seat_and_kind(self):
        first = _row("ckpt-1", "engine:x", "A")
        first["cardsA"] = {"Boros Charm": {"types": "INSTANT", "Cast": [3, 1]}}
        first["cardsB"] = {"Boros Charm": {"types": "INSTANT", "Cast": [2, 2]}}
        second = _row("engine:x", "ckpt-1", "B")
        second["cardsB"] = {
            "Boros Charm": {"types": "INSTANT", "Cast": [1, 0]},
            "Vulshok Morningstar": {"types": "ARTIFACT", "Cast": [1, 1], "Ability": [4, 2]},
        }
        usage = ladder.card_usage([first, second], "ckpt-1")
        self.assertEqual(usage["Boros Charm"], {"types": "INSTANT", "kinds": {"Cast": [4, 1]}})
        self.assertEqual(usage["Vulshok Morningstar"]["kinds"], {"Cast": [1, 1], "Ability": [4, 2]})
        self.assertEqual(ladder.card_usage([first], "engine:x")["Boros Charm"]["kinds"], {"Cast": [2, 2]})

    def test_read_results_rejects_foreign_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.jsonl"
            path.write_text(json.dumps({"schema": "nope"}) + "\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                ladder.read_results([path])


if __name__ == "__main__":
    unittest.main()

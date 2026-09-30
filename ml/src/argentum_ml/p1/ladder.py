"""Dependency-free Elo ladder over `argentum-p1-match-result@v1` result lines.

Ratings are a Bradley-Terry maximum-likelihood fit on decided games (draws and unfinished games
count as half a win each way), anchored so the engine AI (`engine:*`) sits at 1500. A small prior
game against the anchor keeps players with only wins or only losses finite.
"""

from __future__ import annotations

import json
import math
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

RESULT_SCHEMA = "argentum-p1-match-result@v1"
ANCHOR_RATING = 1500.0
ELO_SCALE = 400.0 / math.log(10.0)


def read_results(paths: Iterable[Path]) -> list[dict]:
    rows = []
    for path in paths:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                if row.get("schema") != RESULT_SCHEMA:
                    raise ValueError(f"{path}: unexpected result schema {row.get('schema')!r}")
                rows.append(row)
    return rows


def _score_a(row: dict) -> float:
    return {"A": 1.0, "B": 0.0}.get(row["winner"], 0.5)


@dataclass
class MatchupStats:
    player: str
    opponent: str
    games: int = 0
    points: float = 0.0
    unfinished: int = 0
    by_deck: dict | None = None
    fallbacks: int = 0
    model_choices: int = 0
    turns: int = 0

    @property
    def win_rate(self) -> float:
        return self.points / self.games if self.games else 0.0


def matchups(rows: list[dict]) -> dict[tuple[str, str], MatchupStats]:
    """Per ordered (player, opponent) statistics, counting each game from both sides."""
    stats: dict[tuple[str, str], MatchupStats] = {}
    for row in rows:
        score_a = _score_a(row)
        deck_b = "Chevill" if row["deckA"] == "Akiri" else "Akiri"
        for player, opponent, score, deck in (
            (row["seatA"], row["seatB"], score_a, row["deckA"]),
            (row["seatB"], row["seatA"], 1.0 - score_a, deck_b),
        ):
            s = stats.setdefault((player, opponent), MatchupStats(player, opponent, by_deck={}))
            s.games += 1
            s.points += score
            s.unfinished += 0 if row["terminal"] else 1
            s.turns += row["turns"]
            deck_stats = s.by_deck.setdefault(deck, [0, 0.0])
            deck_stats[0] += 1
            deck_stats[1] += score
        # Model-choice counters belong to whichever seats were models; attribute them to both
        # orderings only for the model side (engine seats never report choices).
        for player, opponent in ((row["seatA"], row["seatB"]), (row["seatB"], row["seatA"])):
            if not player.startswith("engine"):
                stats[(player, opponent)].fallbacks += row["modelFallbacks"]
                stats[(player, opponent)].model_choices += row["modelChoices"]
    return stats


def elo(rows: list[dict], iterations: int = 500, prior_games: float = 1.0) -> dict[str, float]:
    """Bradley-Terry ratings in Elo units, engine seats anchored at 1500."""
    players = sorted({row["seatA"] for row in rows} | {row["seatB"] for row in rows})
    if not players:
        return {}
    anchor = next((p for p in players if p.startswith("engine")), players[0])
    wins: dict[tuple[str, str], float] = defaultdict(float)
    games: dict[tuple[str, str], float] = defaultdict(float)
    for row in rows:
        a, b = row["seatA"], row["seatB"]
        score = _score_a(row)
        wins[(a, b)] += score
        wins[(b, a)] += 1.0 - score
        games[(a, b)] += 1
        games[(b, a)] += 1
    for p in players:
        if p != anchor:  # one virtual draw against the anchor
            wins[(p, anchor)] += prior_games / 2
            wins[(anchor, p)] += prior_games / 2
            games[(p, anchor)] += prior_games
            games[(anchor, p)] += prior_games
    strength = {p: 1.0 for p in players}
    for _ in range(iterations):
        updated = {}
        for p in players:
            total_wins = sum(w for (x, _), w in wins.items() if x == p)
            denominator = sum(n / (strength[p] + strength[o]) for (x, o), n in games.items() if x == p)
            updated[p] = total_wins / denominator if denominator > 0 and total_wins > 0 else strength[p]
        norm = updated[anchor]
        strength = {p: v / norm for p, v in updated.items()}
    return {p: round(ANCHOR_RATING + ELO_SCALE * math.log(strength[p]), 1) for p in players}

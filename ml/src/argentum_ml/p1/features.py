"""Dependency-free reading and featurization of `argentum-p1-selfplay-sample@v1` samples.

A sample (written by the Kotlin `Phase1SelfPlayCollector`) is the acting player's public
observation, the candidate list and the engine AI's chosen index. This module turns it into
integer ids and small float vectors; tensors are built later in `model.collate`.

Vocabularies are built from training data only and stored next to every checkpoint, so a
checkpoint always carries the exact id mapping it was trained with. Index 0 is padding and index 1
is "unknown" in every vocabulary.
"""

from __future__ import annotations

import gzip
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator

from .cardtext import parse_mana_cost

SAMPLE_SCHEMA = "argentum-p1-selfplay-sample@v1"
PAD = 0
UNK = 1

MAX_TYPES = 6
MAX_COLORS = 5
MAX_KEYWORDS = 10
MAX_COUNTER_TYPES = 4
MAX_TARGETS = 4

VOCAB_FIELDS = ("name", "zone", "type", "color", "keyword", "counter", "kind", "phase", "step")

# Numeric per-token features, in this order.
TOKEN_NUMERIC = (
    "manaValue",
    "power",
    "hasPower",
    "toughness",
    "hasToughness",
    "tapped",
    "summoningSick",
    "faceDown",
    "damage",
    "attached",
    "attachmentCount",
    "counterTotal",
    "isStack",
)
# Numeric global features, in this order: self then opponent player stats, then timing.
PLAYER_NUMERIC = ("life", "hand", "library", "graveyard", "exile", "active")
GLOBAL_NUMERIC = (
    tuple(f"self_{name}" for name in PLAYER_NUMERIC)
    + tuple(f"self_mana_{i}" for i in range(6))
    + tuple(f"opp_{name}" for name in PLAYER_NUMERIC)
    + tuple(f"opp_mana_{i}" for i in range(6))
    + ("turn", "activeIsSelf", "stackSize", "cardCount")
)
CANDIDATE_NUMERIC = ("hasX", "manaSymbols", "hasSource", "targetCount")


def iter_game_files(directory: Path) -> list[Path]:
    return sorted(Path(directory).glob("game-*.jsonl.gz"))


def game_index(path: Path) -> int:
    return int(path.name.removeprefix("game-").split(".")[0])


def read_samples(path: Path) -> Iterator[dict]:
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            sample = json.loads(line)
            if sample.get("schema") != SAMPLE_SCHEMA:
                raise ValueError(f"{path}: unexpected sample schema {sample.get('schema')!r}")
            yield sample


def is_validation_game(game: int, every: int = 10) -> bool:
    """Deterministic whole-game split: every `every`-th game is held out."""
    return game % every == every - 1


@dataclass
class Vocab:
    """Id maps for every categorical field. Frozen after building."""

    maps: dict[str, dict[str, int]] = field(default_factory=lambda: {f: {} for f in VOCAB_FIELDS})

    def add(self, name: str, value: str) -> None:
        table = self.maps[name]
        if value not in table:
            table[value] = len(table) + 2  # 0 = pad, 1 = unknown

    def id(self, name: str, value: str) -> int:
        return self.maps[name].get(value, UNK)

    def size(self, name: str) -> int:
        return len(self.maps[name]) + 2

    def to_json(self) -> dict:
        return {"schema": "argentum-p1-vocab@v1", "maps": self.maps}

    @classmethod
    def from_json(cls, payload: dict) -> "Vocab":
        if payload.get("schema") != "argentum-p1-vocab@v1":
            raise ValueError("unexpected vocab schema")
        return cls(maps={name: dict(payload["maps"].get(name, {})) for name in VOCAB_FIELDS})

    @classmethod
    def build(cls, samples: Iterable[dict]) -> "Vocab":
        vocab = cls()
        vocab.extend(samples)
        return vocab

    def extend(self, samples: Iterable[dict]) -> None:
        """Append every value the samples use and the vocabulary lacks; existing ids never change,
        so a model trained on this vocabulary can keep training on new data (fine-tuning)."""
        vocab = self
        for sample in samples:
            vocab.add("phase", sample["phase"])
            vocab.add("step", sample["step"])
            for card in sample["cards"]:
                vocab.add("name", card["name"])
                vocab.add("zone", card["zone"])
                for value in card["types"]:
                    vocab.add("type", value)
                for value in card["colors"]:
                    vocab.add("color", value)
                for value in card["keywords"]:
                    vocab.add("keyword", value)
                for value in card["counters"]:
                    vocab.add("counter", value)
            for item in sample["stack"]:
                vocab.add("name", item["name"])
            for candidate in sample["candidates"]:
                vocab.add("kind", candidate["kind"])
        vocab.add("zone", "STACK")


@dataclass
class EncodedSample:
    """One sample as ids and floats. Token 0..len(cards)-1 are cards, then stack items."""

    token_name: list[int]
    token_zone: list[int]
    token_side: list[int]  # 0 = self, 1 = opponent, 2 = none
    token_types: list[list[int]]
    token_colors: list[list[int]]
    token_keywords: list[list[int]]
    token_counters: list[list[int]]
    token_numeric: list[list[float]]
    global_numeric: list[float]
    phase: int
    step: int
    candidate_kind: list[int]
    candidate_source: list[int]  # token index or -1
    candidate_targets: list[list[int]]  # token indices, -1 padded
    candidate_numeric: list[list[float]]
    chosen: int
    outcome: float
    game: int
    # Parsed printed cost per candidate (cardtext.COST_FEATURES); read only by v2 models.
    candidate_cost: list[list[float]] = field(default_factory=list)


def _padded(ids: list[int], width: int) -> list[int]:
    ids = ids[:width]
    return ids + [PAD] * (width - len(ids))


def _side(value: str) -> int:
    return {"self": 0, "opponent": 1}.get(value, 2)


def _mana_symbols(cost: str) -> int:
    return cost.count("{")


def encode(sample: dict, vocab: Vocab) -> EncodedSample:
    cards = sample["cards"]
    stack = sample["stack"]
    attachment_counts = [0] * len(cards)
    for card in cards:
        target = card["attachedTo"]
        if 0 <= target < len(cards):
            attachment_counts[target] += 1

    token_name, token_zone, token_side = [], [], []
    token_types, token_colors, token_keywords, token_counters, token_numeric = [], [], [], [], []
    for index, card in enumerate(cards):
        token_name.append(vocab.id("name", card["name"]))
        token_zone.append(vocab.id("zone", card["zone"]))
        token_side.append(_side(card["side"]))
        token_types.append(_padded([vocab.id("type", v) for v in card["types"]], MAX_TYPES))
        token_colors.append(_padded([vocab.id("color", v) for v in card["colors"]], MAX_COLORS))
        token_keywords.append(_padded([vocab.id("keyword", v) for v in card["keywords"]], MAX_KEYWORDS))
        token_counters.append(_padded([vocab.id("counter", v) for v in card["counters"]], MAX_COUNTER_TYPES))
        power = card.get("power")
        toughness = card.get("toughness")
        token_numeric.append([
            card["manaValue"] / 10.0,
            (power or 0) / 10.0,
            1.0 if power is not None else 0.0,
            (toughness or 0) / 10.0,
            1.0 if toughness is not None else 0.0,
            1.0 if card["tapped"] else 0.0,
            1.0 if card["summoningSick"] else 0.0,
            1.0 if card["faceDown"] else 0.0,
            card["damage"] / 10.0,
            1.0 if card["attachedTo"] >= 0 else 0.0,
            attachment_counts[index] / 4.0,
            sum(card["counters"].values()) / 10.0,
            0.0,
        ])
    stack_zone = vocab.id("zone", "STACK")
    for item in stack:
        token_name.append(vocab.id("name", item["name"]))
        token_zone.append(stack_zone)
        token_side.append(_side(item["controller"]))
        token_types.append([PAD] * MAX_TYPES)
        token_colors.append([PAD] * MAX_COLORS)
        token_keywords.append([PAD] * MAX_KEYWORDS)
        token_counters.append([PAD] * MAX_COUNTER_TYPES)
        token_numeric.append([0.0] * (len(TOKEN_NUMERIC) - 1) + [1.0])

    players = {player["side"]: player for player in sample["players"]}

    def player_features(player: dict | None) -> list[float]:
        if player is None:
            return [0.0] * (len(PLAYER_NUMERIC) + 6)
        return [
            player["life"] / 40.0,
            player["hand"] / 10.0,
            player["library"] / 100.0,
            player["graveyard"] / 50.0,
            player["exile"] / 50.0,
            1.0 if player["active"] else 0.0,
        ] + [amount / 10.0 for amount in player["mana"]]

    global_numeric = (
        player_features(players.get("self"))
        + player_features(players.get("opponent"))
        + [
            sample["turn"] / 30.0,
            1.0 if sample["activeIsSelf"] else 0.0,
            len(stack) / 5.0,
            len(cards) / 100.0,
        ]
    )

    candidate_kind, candidate_source, candidate_targets, candidate_numeric, candidate_cost = [], [], [], [], []
    for candidate in sample["candidates"]:
        candidate_kind.append(vocab.id("kind", candidate["kind"]))
        source = candidate["source"]
        candidate_source.append(source if 0 <= source < len(cards) else -1)
        targets = [t for t in candidate["targets"] if 0 <= t < len(cards)][:MAX_TARGETS]
        candidate_targets.append(targets + [-1] * (MAX_TARGETS - len(targets)))
        candidate_numeric.append([
            1.0 if candidate["hasX"] else 0.0,
            _mana_symbols(candidate["manaCost"]) / 10.0,
            1.0 if 0 <= source < len(cards) else 0.0,
            len(candidate["targets"]) / 4.0,
        ])
        candidate_cost.append(parse_mana_cost(candidate["manaCost"]))

    return EncodedSample(
        token_name=token_name,
        token_zone=token_zone,
        token_side=token_side,
        token_types=token_types,
        token_colors=token_colors,
        token_keywords=token_keywords,
        token_counters=token_counters,
        token_numeric=token_numeric,
        global_numeric=global_numeric,
        phase=vocab.id("phase", sample["phase"]),
        step=vocab.id("step", sample["step"]),
        candidate_kind=candidate_kind,
        candidate_source=candidate_source,
        candidate_targets=candidate_targets,
        candidate_numeric=candidate_numeric,
        chosen=int(sample["chosen"]),
        outcome=float(sample["outcome"]),
        game=int(sample["game"]),
        candidate_cost=candidate_cost,
    )


def load_split(
    directory: Path | list[Path], vocab: Vocab | None = None, extend: bool = False,
) -> tuple[Vocab, list[EncodedSample], list[EncodedSample]]:
    """Read every game of one or more data directories (e.g. engine-AI games plus DAgger rounds),
    build the vocabulary from training games only (or, with `extend`, append what the given
    vocabulary lacks), and encode both splits."""
    directories = directory if isinstance(directory, list) else [directory]
    files = [f for d in directories for f in iter_game_files(d)]
    if not files:
        raise FileNotFoundError(f"no game-*.jsonl.gz files in {directories}")
    raw_train: list[dict] = []
    raw_val: list[dict] = []
    for path in files:
        target = raw_val if is_validation_game(game_index(path)) else raw_train
        target.extend(read_samples(path))
    if vocab is not None and extend:
        vocab.extend(raw_train)
    vocab = vocab or Vocab.build(raw_train)
    return vocab, [encode(s, vocab) for s in raw_train], [encode(s, vocab) for s in raw_val]

"""Static per-card facts for the P1 v2 model: rules text, printed mana cost and subtypes.

The P1 samples carry each card's name but not what the card does. The Kotlin
`Phase1CardTableExportTest` exports `argentum-p1-card-table@v1` (name -> oracleText, manaCost,
subtypes) from the server's card registry; this module turns it into fixed-size rows keyed by the
model's *name vocabulary id*, so the model looks the text up per card token and the sample format
stays unchanged (docs/ml/p1-card-text-features.md). Dependency-free; tensors are built in `model`.

Index 0 is padding and index 1 is "unknown" in both the text and the subtype vocabulary.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

CARD_FEATURES_SCHEMA = "argentum-p1-card-features@v1"
CARD_TABLE_SCHEMA = "argentum-p1-card-table@v1"
PAD = 0
UNK = 1
MAX_TEXT = 160  # unigram + bigram ids per card
MAX_SUBTYPES = 4
# generic, W, U, B, R, G, C pips, X, hybrid, Phyrexian
COST_FEATURES = ("generic", "W", "U", "B", "R", "G", "C", "X", "hybrid", "phyrexian")

_SYMBOL = re.compile(r"\{([^}]+)\}")
_TOKEN = re.compile(r"\{[^}]+\}|[+\-]\d+/[+\-]\d+|~|[a-z]+(?:'[a-z]+)?|\d+")


def parse_mana_cost(cost: str) -> list[float]:
    """Mana-cost string such as `{2}{G}{G}`, `{X}{R}`, `{W/U}`, `{B/P}` -> COST_FEATURES, each /10."""
    values = dict.fromkeys(COST_FEATURES, 0.0)
    for symbol in _SYMBOL.findall(cost or ""):
        symbol = symbol.upper()
        if symbol.isdigit():
            values["generic"] += int(symbol)
        elif symbol == "X":
            values["X"] += 1
        elif "/" in symbol:
            parts = symbol.split("/")
            values["phyrexian" if "P" in parts else "hybrid"] += 1
            for part in parts:
                if part in "WUBRG" and len(part) == 1:
                    values[part] += 0.5
        elif symbol in ("W", "U", "B", "R", "G", "C"):
            values[symbol] += 1
    return [values[name] / 10.0 for name in COST_FEATURES]


def tokenize(text: str, name: str = "") -> list[str]:
    """Rules text -> word tokens: lowercase, the card's own name as `~`, mana/tap symbols as single
    tokens, P/T modifiers kept whole, `<nl>` between abilities."""
    if name:
        text = text.replace(name, "~")
    tokens: list[str] = []
    for line in text.lower().split("\n"):
        line_tokens = _TOKEN.findall(line)
        if line_tokens:
            tokens.extend(line_tokens)
            tokens.append("<nl>")
    return tokens


def text_terms(text: str, name: str = "") -> list[str]:
    """Unigrams followed by bigrams; bigrams carry the order a bag of words would lose."""
    words = tokenize(text, name)
    return words + [f"{a} {b}" for a, b in zip(words, words[1:])]


def read_card_table(path: Path) -> dict[str, dict]:
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    if payload.get("schema") != CARD_TABLE_SCHEMA:
        raise ValueError(f"{path}: unexpected card table schema {payload.get('schema')!r}")
    return payload["cards"]


@dataclass
class CardFeatures:
    """Text/subtype vocabularies plus the card facts for every name the model knows."""

    cards: dict[str, dict]
    text_vocab: dict[str, int] = field(default_factory=dict)
    subtype_vocab: dict[str, int] = field(default_factory=dict)
    source_commit: str = ""
    # Names that occurred in the training data. Only these get a learned name embedding; every
    # other card the table knows (e.g. the held-out decks) is "unknown" by name but keeps its text.
    trained_names: list[str] = field(default_factory=list)

    @classmethod
    def build(cls, card_table: dict[str, dict], names: list[str], source_commit: str = "",
              trained_names: list[str] | None = None) -> "CardFeatures":
        """Card facts for every name in `names`; text and subtype vocabularies come from the
        trained names only, so a word that never appeared in training reads as unknown."""
        trained = sorted(trained_names if trained_names is not None else names)
        cards = {name: card_table[name] for name in names if name in card_table}
        features = cls(cards=cards, source_commit=source_commit, trained_names=trained)
        for name in trained:
            card = cards.get(name)
            if card is None:
                continue
            for term in text_terms(card.get("oracleText", ""), name):
                features.text_vocab.setdefault(term, len(features.text_vocab) + 2)
            for subtype in card.get("subtypes", []):
                features.subtype_vocab.setdefault(subtype, len(features.subtype_vocab) + 2)
        return features

    def text_size(self) -> int:
        return len(self.text_vocab) + 2

    def subtype_size(self) -> int:
        return len(self.subtype_vocab) + 2

    def rows(self, name_vocab: dict[str, int], name_vocab_size: int) -> tuple[list[list[int]], list[list[int]], list[list[float]]]:
        """Per name-vocabulary id: text ids (MAX_TEXT, padded), subtype ids (MAX_SUBTYPES, padded),
        cost features. Pad/unknown ids and names without a table entry (tokens) get empty rows."""
        text = [[PAD] * MAX_TEXT for _ in range(name_vocab_size)]
        subtypes = [[PAD] * MAX_SUBTYPES for _ in range(name_vocab_size)]
        cost = [[0.0] * len(COST_FEATURES) for _ in range(name_vocab_size)]
        for name, index in name_vocab.items():
            card = self.cards.get(name)
            if card is None or not 0 <= index < name_vocab_size:
                continue
            ids = [self.text_vocab.get(term, UNK) for term in text_terms(card.get("oracleText", ""), name)][:MAX_TEXT]
            text[index] = ids + [PAD] * (MAX_TEXT - len(ids))
            sub = [self.subtype_vocab.get(s, UNK) for s in card.get("subtypes", [])][:MAX_SUBTYPES]
            subtypes[index] = sub + [PAD] * (MAX_SUBTYPES - len(sub))
            cost[index] = parse_mana_cost(card.get("manaCost", ""))
        return text, subtypes, cost

    def to_json(self) -> dict:
        return {"schema": CARD_FEATURES_SCHEMA, "sourceCommit": self.source_commit, "cards": self.cards,
                "textVocab": self.text_vocab, "subtypeVocab": self.subtype_vocab, "trainedNames": self.trained_names}

    @classmethod
    def from_json(cls, payload: dict) -> "CardFeatures":
        if payload.get("schema") != CARD_FEATURES_SCHEMA:
            raise ValueError("unexpected card features schema")
        return cls(cards=payload["cards"], text_vocab=dict(payload["textVocab"]),
                   subtype_vocab=dict(payload["subtypeVocab"]), source_commit=payload.get("sourceCommit", ""),
                   trained_names=list(payload.get("trainedNames", payload["cards"].keys())))

    def extend_vocab(self, vocab) -> None:
        """Give every card in the table a name id (appended after the trained ones), so its text
        can be looked up even when the card never occurred in training."""
        for name in sorted(self.cards):
            vocab.add("name", name)

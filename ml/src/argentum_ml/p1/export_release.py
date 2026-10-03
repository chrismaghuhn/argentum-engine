"""Copy a P1 checkpoint for public release with card rules text limited to a set of decks.

    python -m argentum_ml.p1.export_release --checkpoint DIR --out DIR --decks a.json [--decks b.json ...]

A v2 checkpoint carries the oracle text of every card the engine supports (card_features.json, plus
the same text as term ids in the `name_text` buffer). The release copy keeps the card facts (oracle
text, mana cost, subtypes) only for cards in the given deck pools and the trained names; every other
card keeps its name id, its seen/unseen flag and its frozen text-embedding vector, but no text, cost
or subtypes. The term vocabulary is kept, so ids stay stable. For decisions that only involve kept
cards the release copy scores exactly like the original. Local data paths in metrics.json are
reduced to their last component. v1 checkpoints (no card features) are copied unchanged.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path

import torch

from .cardtext import CardFeatures
from .model import CARD_FEATURES_FILE


def deck_card_names(paths: list[Path]) -> set[str]:
    names: set[str] = set()
    for path in paths:
        for cards in json.loads(path.read_text(encoding="utf-8")).values():
            names.update(cards)
    return names


def _strip_paths(text: str) -> str:
    # "C:\\Dev\\data\\argentum-p1\\std-snap-1" / "/root/data/hz/std-big/big-03" -> last component
    return re.sub(r'"(?:[A-Za-z]:)?(?:[\\/]+[^"\\/]+)+[\\/]+([^"\\/]+)"', r'"\1"', text)


def export(checkpoint: Path, out: Path, keep: set[str]) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    for name in ("config.json", "vocab.json"):
        shutil.copyfile(checkpoint / name, out / name)
    metrics = checkpoint / "metrics.json"
    if metrics.exists():
        (out / "metrics.json").write_text(_strip_paths(metrics.read_text(encoding="utf-8")), encoding="utf-8")
    card_file = checkpoint / CARD_FEATURES_FILE
    if not card_file.exists():
        shutil.copyfile(checkpoint / "model.safetensors", out / "model.safetensors")
        return {"checkpoint": checkpoint.name, "cards": None}
    from safetensors.torch import load_file, save_file

    features = CardFeatures.from_json(json.loads(card_file.read_text(encoding="utf-8")))
    kept_names = keep | set(features.trained_names)
    features.cards = {name: card for name, card in features.cards.items() if name in kept_names}
    (out / CARD_FEATURES_FILE).write_text(json.dumps(features.to_json(), sort_keys=True), encoding="utf-8")
    state = load_file(str(checkpoint / "model.safetensors"))
    vocab = json.loads((checkpoint / "vocab.json").read_text(encoding="utf-8"))
    name_vocab = (vocab.get("maps") or vocab)["name"]
    text, subtypes, cost = features.rows(name_vocab, state["name_text"].shape[0])
    state["name_text"] = torch.tensor(text, dtype=state["name_text"].dtype)
    state["name_subtypes"] = torch.tensor(subtypes, dtype=state["name_subtypes"].dtype)
    state["name_cost"] = torch.tensor(cost, dtype=state["name_cost"].dtype)
    save_file({k: v.contiguous() for k, v in state.items()}, str(out / "model.safetensors"))
    return {"checkpoint": checkpoint.name, "cards": len(features.cards)}


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, action="append", required=True)
    parser.add_argument("--out", type=Path, required=True, help="output root; one subdirectory per checkpoint")
    parser.add_argument("--decks", type=Path, action="append", default=[], help="deck pool JSON whose cards keep their text")
    args = parser.parse_args(argv)
    keep = deck_card_names(args.decks)
    for checkpoint in args.checkpoint:
        result = export(checkpoint, args.out / checkpoint.name, keep)
        print(f"{result['checkpoint']}: " + (f"{result['cards']} cards with text" if result["cards"] is not None else "v1, copied"))


if __name__ == "__main__":
    main()

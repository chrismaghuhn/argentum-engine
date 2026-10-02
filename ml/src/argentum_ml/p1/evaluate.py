"""Offline accuracy of P1 checkpoints on a data directory (e.g. games of decks never trained on).

    python -m argentum_ml.p1.evaluate --checkpoint DIR [--checkpoint DIR ...] --data DIR [--out FILE.json]

Each checkpoint encodes the samples with its own vocabulary (v1: unseen cards become "unknown";
v2: unseen cards keep their rules text), then reports teacher-move accuracy overall, without pass
decisions, and value-sign accuracy, plus the share of card tokens whose name the model never saw.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch

from . import features
from .model import load_checkpoint, pretensorize
from .train import evaluate


def evaluate_checkpoint(directory: Path, data: list[Path], device) -> dict:
    model, vocab = load_checkpoint(directory, device)
    model.eval()
    raw = [s for d in data for f in features.iter_game_files(d) for s in features.read_samples(f)]
    encoded = [features.encode(s, vocab) for s in raw]
    tokens = sum(len(e.token_name) for e in encoded)
    unknown = sum(sum(1 for n in e.token_name if n == features.UNK) for e in encoded)
    unseen = unknown
    if hasattr(model, "name_seen"):
        seen = model.name_seen.cpu()
        unseen = sum(sum(1 for n in e.token_name if not bool(seen[n])) for e in encoded)
    metrics = evaluate(model, [pretensorize(e) for e in encoded], device, value_weight=0.1,
                       pass_kind=vocab.id("kind", "PassPriority"))
    metrics.update({"checkpoint": directory.name, "unknownNameTokens": round(unknown / max(tokens, 1), 4),
                    "unseenNameTokens": round(unseen / max(tokens, 1), 4)})
    return metrics


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, action="append", required=True)
    parser.add_argument("--data", type=Path, action="append", required=True)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    results = [evaluate_checkpoint(c, args.data, device) for c in args.checkpoint]
    for r in results:
        print(f"{r['checkpoint']}: top1 {r['top1']:.3f} non-pass {r['top1NonPass']:.3f} "
              f"value-sign {r['valueSignAccuracy']:.3f} policyLoss {r['policyLoss']:.4f} "
              f"unseen-name tokens {r['unseenNameTokens']:.1%} ({r['samples']} samples)")
    if args.out:
        args.out.write_text(json.dumps({"data": [str(d) for d in args.data], "results": results}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()

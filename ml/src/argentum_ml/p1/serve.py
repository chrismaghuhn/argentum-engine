"""Line-oriented policy worker for a P1 checkpoint.

    python -m argentum_ml.p1.serve --checkpoint DIR [--device cpu]

Reads one JSON object per stdin line: `{"sample": <observation sample>}` where the sample is the
model-facing part of `argentum-p1-selfplay-sample@v1` (no `chosen`/`outcome` needed). Writes one
JSON line per request: `{"chosen": i, "value": v, "scores": [...]}` with `chosen` the argmax over
the candidates, or `{"error": "..."}`. After loading it prints `{"ready": "<checkpoint name>"}`.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import torch
from safetensors.torch import load_file

from .features import Vocab, encode
from .model import P1Model, P1ModelConfig, collate


def load_checkpoint(directory: Path, device: str = "cpu") -> tuple[P1Model, Vocab]:
    vocab = Vocab.from_json(json.loads((directory / "vocab.json").read_text(encoding="utf-8")))
    config = P1ModelConfig.from_json(json.loads((directory / "config.json").read_text(encoding="utf-8")))
    model = P1Model(vocab, config)
    model.load_state_dict(load_file(str(directory / "model.safetensors")))
    model.to(device).eval()
    return model, vocab


@torch.no_grad()
def choose(model: P1Model, vocab: Vocab, sample: dict, device: str = "cpu") -> dict:
    prepared = dict(sample)
    prepared.setdefault("chosen", 0)
    prepared.setdefault("outcome", 0)
    prepared.setdefault("game", 0)
    encoded = encode(prepared, vocab)
    scores, value = model(collate([encoded], device))
    row = scores[0, : len(encoded.candidate_kind)]
    return {
        "chosen": int(row.argmax().item()),
        "value": float(value[0].item()),
        "scores": [round(float(x), 4) for x in row.tolist()],
    }


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--threads", type=int, default=1)
    args = parser.parse_args(argv)
    torch.set_num_threads(args.threads)
    model, vocab = load_checkpoint(args.checkpoint, args.device)
    out = sys.stdout
    out.write(json.dumps({"ready": args.checkpoint.name}) + "\n")
    out.flush()
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            reply = choose(model, vocab, json.loads(line)["sample"], args.device)
        except Exception as error:  # the Kotlin seat counts errors as fallbacks
            reply = {"error": f"{type(error).__name__}: {error}"}
        out.write(json.dumps(reply) + "\n")
        out.flush()


if __name__ == "__main__":
    main()

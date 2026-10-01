"""Line-oriented policy worker for a P1 checkpoint.

    python -m argentum_ml.p1.serve --checkpoint DIR [--device cpu] [--sample --seed N]

Reads one JSON object per stdin line: `{"sample": <observation sample>, "allowed": [bool, ...]}` where
the sample is the model-facing part of `argentum-p1-selfplay-sample@v1` (no `chosen`/`outcome`
needed) and the optional `allowed` mask excludes candidates the seat may not pick (e.g. ones the
player cannot currently afford; the teacher never picks those, but the sample has no affordability
feature). Writes one
JSON line per request: `{"chosen": i, "logprob": l, "value": v, "scores": [...]}` with `chosen` the
argmax over the allowed candidates, or `{"error": "..."}`. With `--sample` the worker instead draws
`chosen` from the softmax over the allowed candidates (PPO rollouts need the model to explore), and
an optional request `seed` makes that draw reproducible. `logprob` is always the log-probability of
`chosen` under that masked softmax, which is what the PPO trainer compares against. After loading it
prints `{"ready": "<checkpoint name>"}`.
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
def choose(
    model: P1Model, vocab: Vocab, sample: dict, device: str = "cpu", allowed: list[bool] | None = None,
    generator: torch.Generator | None = None,
) -> dict:
    """Argmax over the allowed candidates, or a draw from their softmax when `generator` is given."""
    prepared = dict(sample)
    prepared.setdefault("chosen", 0)
    prepared.setdefault("outcome", 0)
    prepared.setdefault("game", 0)
    encoded = encode(prepared, vocab)
    scores, value = model(collate([encoded], device))
    row = scores[0, : len(encoded.candidate_kind)]
    pick = row
    if allowed is not None and len(allowed) == len(row) and any(allowed):
        mask = torch.tensor([not a for a in allowed], device=row.device)
        pick = row.masked_fill(mask, float("-inf"))
    logprobs = torch.log_softmax(pick.float(), dim=-1)
    if generator is None:
        chosen = int(pick.argmax().item())
    else:
        chosen = int(torch.multinomial(logprobs.exp().cpu(), 1, generator=generator).item())
    return {
        "chosen": chosen,
        "logprob": float(logprobs[chosen].item()),
        "value": float(value[0].item()),
        "scores": [round(float(x), 4) for x in row.tolist()],
    }


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--sample", action="store_true", help="draw from the policy instead of argmax")
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args(argv)
    torch.set_num_threads(args.threads)
    generator = torch.Generator().manual_seed(args.seed) if args.sample else None
    model, vocab = load_checkpoint(args.checkpoint, args.device)
    out = sys.stdout
    out.write(json.dumps({"ready": args.checkpoint.name}) + "\n")
    out.flush()
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            request = json.loads(line)
            draw = generator
            if args.sample and request.get("seed") is not None:
                # A per-decision seed makes a draw independent of which worker process served it.
                draw = torch.Generator().manual_seed(int(request["seed"]))
            reply = choose(model, vocab, request["sample"], args.device, request.get("allowed"), draw)
        except Exception as error:  # the Kotlin seat counts errors as fallbacks
            reply = {"error": f"{type(error).__name__}: {error}"}
        out.write(json.dumps(reply) + "\n")
        out.flush()


if __name__ == "__main__":
    main()

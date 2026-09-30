"""Train the P1 candidate scorer on engine-AI self-play samples and write a numbered checkpoint.

    python -m argentum_ml.p1.train --data C:/Dev/data/argentum-p1/selfplay-v1 --runs C:/Dev/data/argentum-p1/checkpoints

Every checkpoint directory holds `model.safetensors`, `vocab.json`, `config.json` and
`metrics.json` (training history plus the final validation metrics), which is what the tournament
runner and the devlog read.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import time
from pathlib import Path

import torch
from safetensors.torch import save_file
from torch.nn import functional as F

from . import features
from .model import P1Model, P1ModelConfig, collate

CHECKPOINT_SCHEMA = "argentum-p1-checkpoint@v1"


def _batches(samples, batch_size, shuffle, rng):
    order = list(range(len(samples)))
    if shuffle:
        rng.shuffle(order)
    for start in range(0, len(order), batch_size):
        yield [samples[i] for i in order[start:start + batch_size]]


def _losses(model, batch, value_weight):
    scores, value = model(batch)
    policy = F.cross_entropy(scores, batch["chosen"])
    value_loss = F.mse_loss(value, batch["outcome"])
    return policy + value_weight * value_loss, policy, value_loss, scores, value


@torch.no_grad()
def evaluate(model, samples, device, batch_size=256, value_weight=0.5, pass_kind=None):
    model.eval()
    totals = {"n": 0, "loss": 0.0, "policy": 0.0, "value": 0.0, "correct": 0,
              "nonPassN": 0, "nonPassCorrect": 0, "alwaysPassCorrect": 0, "valueSignCorrect": 0, "decidedN": 0}
    for chunk in _batches(samples, batch_size, False, None):
        batch = collate(chunk, device)
        loss, policy, value_loss, scores, value = _losses(model, batch, value_weight)
        n = len(chunk)
        predicted = scores.argmax(-1)
        correct = predicted == batch["chosen"]
        totals["n"] += n
        totals["loss"] += loss.item() * n
        totals["policy"] += policy.item() * n
        totals["value"] += value_loss.item() * n
        totals["correct"] += correct.sum().item()
        if pass_kind is not None:
            chosen_kind = torch.gather(batch["candidate_kind"], 1, batch["chosen"].unsqueeze(1)).squeeze(1)
            is_pass = chosen_kind == pass_kind
            totals["nonPassN"] += (~is_pass).sum().item()
            totals["nonPassCorrect"] += (correct & ~is_pass).sum().item()
            first_pass = (batch["candidate_kind"] == pass_kind).float().argmax(-1)
            totals["alwaysPassCorrect"] += (first_pass == batch["chosen"]).sum().item()
        decided = batch["outcome"] != 0
        totals["decidedN"] += decided.sum().item()
        totals["valueSignCorrect"] += ((value.sign() == batch["outcome"].sign()) & decided).sum().item()
    n = max(totals["n"], 1)
    return {
        "samples": totals["n"],
        "loss": totals["loss"] / n,
        "policyLoss": totals["policy"] / n,
        "valueLoss": totals["value"] / n,
        "top1": totals["correct"] / n,
        "top1NonPass": totals["nonPassCorrect"] / max(totals["nonPassN"], 1),
        "alwaysPassBaseline": totals["alwaysPassCorrect"] / n,
        "valueSignAccuracy": totals["valueSignCorrect"] / max(totals["decidedN"], 1),
    }


def next_checkpoint_dir(runs: Path) -> Path:
    runs.mkdir(parents=True, exist_ok=True)
    existing = sorted(p for p in runs.glob("p1-ckpt-*") if p.is_dir())
    number = int(existing[-1].name.removeprefix("p1-ckpt-")) + 1 if existing else 1
    return runs / f"p1-ckpt-{number:04d}"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--epochs", type=int, default=8)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--value-weight", type=float, default=0.5)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--note", default="")
    args = parser.parse_args(argv)

    torch.manual_seed(args.seed)
    rng = random.Random(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    started = time.time()
    vocab, train, val = features.load_split(args.data)
    games = len(features.iter_game_files(args.data))
    print(f"data: {games} games, {len(train)} train / {len(val)} val samples, "
          f"{vocab.size('name')} card names, device={device}")
    pass_kind = vocab.id("kind", "PassPriority")

    config = P1ModelConfig()
    model = P1Model(vocab, config).to(device)
    parameters = sum(p.numel() for p in model.parameters())
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    steps_per_epoch = math.ceil(len(train) / args.batch_size)
    total_steps = steps_per_epoch * args.epochs
    scheduler = torch.optim.lr_scheduler.OneCycleLR(optimizer, max_lr=args.lr, total_steps=total_steps)

    history = []
    for epoch in range(1, args.epochs + 1):
        model.train()
        seen, running = 0, 0.0
        for chunk in _batches(train, args.batch_size, True, rng):
            batch = collate(chunk, device)
            with torch.autocast(device_type=device.type, dtype=torch.bfloat16, enabled=device.type == "cuda"):
                loss, _, _, _, _ = _losses(model, batch, args.value_weight)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()
            seen += len(chunk)
            running += loss.item() * len(chunk)
        metrics = evaluate(model, val, device, value_weight=args.value_weight, pass_kind=pass_kind)
        metrics.update({"epoch": epoch, "trainLoss": running / max(seen, 1)})
        history.append(metrics)
        print(f"epoch {epoch}: train {metrics['trainLoss']:.4f} | val loss {metrics['loss']:.4f} "
              f"top1 {metrics['top1']:.3f} (non-pass {metrics['top1NonPass']:.3f}, always-pass "
              f"{metrics['alwaysPassBaseline']:.3f}) value-sign {metrics['valueSignAccuracy']:.3f}")

    out = next_checkpoint_dir(args.runs)
    out.mkdir(parents=True)
    save_file({k: v.detach().cpu().contiguous() for k, v in model.state_dict().items()}, str(out / "model.safetensors"))
    (out / "vocab.json").write_text(json.dumps(vocab.to_json(), sort_keys=True), encoding="utf-8")
    (out / "config.json").write_text(json.dumps(config.to_json(), sort_keys=True, indent=2), encoding="utf-8")
    manifests = sorted(args.data.glob("manifest-*.json"))
    (out / "metrics.json").write_text(json.dumps({
        "schema": CHECKPOINT_SCHEMA,
        "checkpoint": out.name,
        "note": args.note,
        "data": {
            "directory": str(args.data),
            "games": games,
            "trainSamples": len(train),
            "valSamples": len(val),
            "manifests": [json.loads(p.read_text(encoding="utf-8")) for p in manifests],
        },
        "training": {
            "epochs": args.epochs, "batchSize": args.batch_size, "lr": args.lr,
            "valueWeight": args.value_weight, "seed": args.seed, "parameters": parameters,
            "device": str(device), "seconds": round(time.time() - started, 1),
        },
        "history": history,
        "final": history[-1] if history else None,
    }, indent=2), encoding="utf-8")
    print(f"wrote {out}")
    return out


if __name__ == "__main__":
    main()

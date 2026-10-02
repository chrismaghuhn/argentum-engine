"""Train the P1 candidate scorer on engine-AI self-play samples and write a numbered checkpoint.

    python -m argentum_ml.p1.train --data C:/Dev/data/argentum-p1/selfplay-v1 --runs C:/Dev/data/argentum-p1/checkpoints

Every checkpoint directory holds `model.safetensors`, `vocab.json`, `config.json` and
`metrics.json` (training history plus the final validation metrics), which is what the tournament
runner and the devlog read.

v2 card features (docs/ml/p1-card-text-features.md): `--card-table` plus any of `--text`,
`--subtypes`, `--mana-cost` and `--name-dropout P`; the checkpoint then also stores
`card_features.json`. `--warm-start DIR` starts from a v1 checkpoint (its vocabulary and weights) with
the new card-feature layers zero-initialized, so training starts from exactly that checkpoint's play.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import time
from pathlib import Path

import torch
from torch.nn import functional as F

from . import cardtext, features
from . import model as p1_model
from .model import P1Model, P1ModelConfig, collate_tensors, pretensorize

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
        batch = collate_tensors(chunk, device)
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


def next_checkpoint_dir(runs: Path, prefix: str = "p1-ckpt") -> Path:
    runs.mkdir(parents=True, exist_ok=True)
    existing = sorted(p for p in runs.glob(f"{prefix}-*") if p.is_dir() and p.name.removeprefix(f"{prefix}-").isdigit())
    number = int(existing[-1].name.removeprefix(f"{prefix}-")) + 1 if existing else 1
    return runs / f"{prefix}-{number:04d}"


def _zero_card_feature_layers(model: P1Model) -> None:
    """New v2 layers start silent, so a warm-started model plays exactly like its v1 source."""
    with torch.no_grad():
        for name in ("text", "subtype", "card_cost", "candidate_cost"):
            module = getattr(model, name, None)
            if module is not None:
                for parameter in module.parameters():
                    parameter.zero_()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, action="append", required=True,
                        help="data directory; repeat to combine engine-AI games with DAgger rounds")
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--epochs", type=int, default=8)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--value-weight", type=float, default=0.1)
    parser.add_argument(
        "--select-by", default="policyLoss",
        help="validation metric (lower is better) that picks the kept epoch; the policy is what plays",
    )
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--note", default="")
    parser.add_argument("--prefix", default="p1-ckpt", help="checkpoint directory prefix, e.g. p1-v2a")
    parser.add_argument("--card-table", type=Path, help="argentum-p1-card-table@v1 JSON (needed for v2 features)")
    parser.add_argument("--text", action="store_true", help="v2: rules-text features")
    parser.add_argument("--subtypes", action="store_true", help="v2: subtype features")
    parser.add_argument("--mana-cost", action="store_true", help="v2: parsed printed-cost features (cards and candidates)")
    parser.add_argument("--name-dropout", type=float, default=0.0, help="v2: hide card names with this probability in training")
    parser.add_argument("--warm-start", type=Path, help="start from this v1 checkpoint (vocabulary + weights)")
    args = parser.parse_args(argv)

    torch.manual_seed(args.seed)
    rng = random.Random(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    started = time.time()
    warm_vocab = None
    if args.warm_start:
        warm_vocab = features.Vocab.from_json(json.loads((args.warm_start / "vocab.json").read_text(encoding="utf-8")))
    vocab, train, val = features.load_split(args.data, warm_vocab)
    train = [pretensorize(s) for s in train]
    val = [pretensorize(s) for s in val]
    games = sum(len(features.iter_game_files(d)) for d in args.data)
    print(f"data: {games} games, {len(train)} train / {len(val)} val samples, "
          f"{vocab.size('name')} card names, device={device}")
    pass_kind = vocab.id("kind", "PassPriority")

    config = P1ModelConfig(text=args.text, subtypes=args.subtypes, mana_cost=args.mana_cost, name_dropout=args.name_dropout)
    card_features = None
    if config.uses_card_features:
        if not args.card_table:
            raise SystemExit("--text/--subtypes/--mana-cost need --card-table")
        table_payload = json.loads(args.card_table.read_text(encoding="utf-8"))
        table = cardtext.read_card_table(args.card_table)
        trained_names = sorted(vocab.maps["name"])
        card_features = cardtext.CardFeatures.build(
            table, sorted(set(trained_names) | set(table)), table_payload.get("sourceCommit", ""), trained_names,
        )
        card_features.extend_vocab(vocab)  # appends ids, so already-encoded samples keep theirs
        print(f"card features: {len(card_features.cards)}/{len(vocab.maps['name'])} names with text, "
              f"{card_features.text_size()} text terms, {card_features.subtype_size()} subtypes")
    model = P1Model(vocab, config, card_features)
    if args.warm_start:
        source, _ = p1_model.load_checkpoint(args.warm_start)
        state = source.state_dict()
        own = model.state_dict()
        for key, tensor in list(state.items()):
            # The name vocabulary grew (whole card table); copy the trained rows, keep the rest.
            if key in own and own[key].shape != tensor.shape and own[key].dim() == tensor.dim() \
                    and own[key].shape[1:] == tensor.shape[1:] and own[key].shape[0] > tensor.shape[0]:
                grown = own[key].clone()
                grown[: tensor.shape[0]] = tensor
                state[key] = grown
        missing, unexpected = model.load_state_dict(state, strict=False)
        if unexpected:
            raise SystemExit(f"warm start: unexpected tensors {unexpected}")
        _zero_card_feature_layers(model)
        print(f"warm start from {args.warm_start.name}: {len(missing)} new tensors")
    model = model.to(device)
    parameters = sum(p.numel() for p in model.parameters())
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    steps_per_epoch = math.ceil(len(train) / args.batch_size)
    total_steps = steps_per_epoch * args.epochs
    scheduler = torch.optim.lr_scheduler.OneCycleLR(optimizer, max_lr=args.lr, total_steps=total_steps)

    history = []
    best_state, best_epoch, best_loss = None, 0, float("inf")
    for epoch in range(1, args.epochs + 1):
        model.train()
        seen, running = 0, 0.0
        for chunk in _batches(train, args.batch_size, True, rng):
            batch = collate_tensors(chunk, device)
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
        # Keep the epoch with the best policy on held-out games. Selecting on the total loss picked
        # epoch 1 on 509 games: the value head memorizes per-game outcomes and its loss rises while
        # the policy keeps improving for several more epochs.
        if val and metrics[args.select_by] < best_loss:
            best_loss, best_epoch = metrics[args.select_by], epoch
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
        print(f"epoch {epoch}: train {metrics['trainLoss']:.4f} | val loss {metrics['loss']:.4f} "
              f"top1 {metrics['top1']:.3f} (non-pass {metrics['top1NonPass']:.3f}, always-pass "
              f"{metrics['alwaysPassBaseline']:.3f}) value-sign {metrics['valueSignAccuracy']:.3f}")

    if best_state is not None:
        model.load_state_dict(best_state)
        print(f"keeping epoch {best_epoch} (lowest validation {args.select_by} {best_loss:.4f})")
    final = history[best_epoch - 1] if best_epoch else (history[-1] if history else None)
    out = next_checkpoint_dir(args.runs, args.prefix)
    out.mkdir(parents=True)
    p1_model.save_checkpoint(model, vocab, out)
    manifests = sorted(m for d in args.data for m in d.glob("manifest-*.json"))
    (out / "metrics.json").write_text(json.dumps({
        "schema": CHECKPOINT_SCHEMA,
        "checkpoint": out.name,
        "note": args.note,
        "data": {
            "directories": [str(d) for d in args.data],
            "games": games,
            "trainSamples": len(train),
            "valSamples": len(val),
            "manifests": [json.loads(p.read_text(encoding="utf-8")) for p in manifests],
        },
        "training": {
            "epochs": args.epochs, "batchSize": args.batch_size, "lr": args.lr,
            "valueWeight": args.value_weight, "selectBy": args.select_by, "seed": args.seed, "parameters": parameters,
            "device": str(device), "seconds": round(time.time() - started, 1),
            "architecture": config.to_json()["architecture"],
            "cardFeatures": {"text": args.text, "subtypes": args.subtypes, "manaCost": args.mana_cost,
                             "nameDropout": args.name_dropout,
                             "cardTable": str(args.card_table) if args.card_table else None,
                             "cardTableCommit": card_features.source_commit if card_features else None},
            "warmStart": str(args.warm_start) if args.warm_start else None,
        },
        "history": history,
        "selectedEpoch": best_epoch or len(history),
        "final": final,
    }, indent=2), encoding="utf-8")
    print(f"wrote {out}")
    return out


if __name__ == "__main__":
    main()

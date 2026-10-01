"""PPO fine-tuning of a P1 checkpoint on games it played itself against a league.

    python -m argentum_ml.p1.ppo --checkpoint DIR --reference DIR --data ROLLOUT_DIR --runs DIR

Imitation (behaviour cloning, then DAgger) can only get as good as the engine-AI teacher. PPO
instead learns from the result of the model's own games: the rollout collector
(`Phase1PpoCollectTest`) lets the current checkpoint *sample* its moves (`serve --sample`) against
itself, earlier checkpoints and the engine AI, and records every learner decision with the
probability it had when it was played (`logprob`) and the value head's estimate (`value`). This
module turns one batch of such games into a new checkpoint:

* Reward is only the game result (+1 win, -1 loss, 0 unfinished), from each decision's player.
* Advantages come from GAE over each seat's sequence of decisions, using the rollout values.
* The policy update is the clipped PPO surrogate; decisions the engine could not execute (fallbacks)
  stay in the value chain but are left out of the policy loss.
* A KL penalty to a fixed reference checkpoint (the imitation model) keeps the policy close to
  sensible play while the value head is still weak ("Bremse"). An entropy bonus keeps it exploring.
* The update stops early once the policy has moved too far from the rollout policy (`--target-kl`).

The output is an ordinary P1 checkpoint (`p1-ppo-NNNN`) that the tournament and devlog read as-is.
"""

from __future__ import annotations

import argparse
import dataclasses
import gzip
import json
import math
import random
import time
from collections import defaultdict
from pathlib import Path

import torch

from . import features
from . import model as p1_model
from .model import P1Model, collate_tensors, pretensorize
from .train import CHECKPOINT_SCHEMA

PPO_SOURCE = "ppo"


# ---------------------------------------------------------------------------------------------
# Rollouts


def read_rollout_rows(directories: list[Path]) -> list[dict]:
    """All learner decisions of every `game-*.jsonl.gz` in the rollout directories."""
    rows: list[dict] = []
    for directory in directories:
        for path in features.iter_game_files(directory):
            for row in features.read_samples(path):
                if row.get("source") != PPO_SOURCE:
                    raise ValueError(f"{path}: not a PPO rollout row (source={row.get('source')!r})")
                row["_file"] = str(path)
                rows.append(row)
    return rows


def trajectories(rows: list[dict]) -> list[list[dict]]:
    """One trajectory per (game file, seat), in the order the decisions were made."""
    grouped: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for row in rows:
        grouped[(row["_file"], row["seat"])].append(row)
    return [sorted(steps, key=lambda r: r["engineStep"]) for _, steps in sorted(grouped.items())]


def gae(values: list[float], final_reward: float, gamma: float, lam: float) -> tuple[list[float], list[float]]:
    """Generalized advantage estimation for a trajectory whose only reward arrives after the last
    decision. Returns (advantages, returns); the episode ends after the last step (no bootstrap)."""
    advantages = [0.0] * len(values)
    running = 0.0
    for t in reversed(range(len(values))):
        last = t == len(values) - 1
        reward = final_reward if last else 0.0
        next_value = 0.0 if last else values[t + 1]
        delta = reward + gamma * next_value - values[t]
        running = delta + gamma * lam * running
        advantages[t] = running
    return advantages, [a + v for a, v in zip(advantages, values)]


def allowed_mask(row: dict) -> list[bool]:
    allowed = row.get("allowed")
    count = len(row["candidates"])
    if not allowed or len(allowed) != count or not any(allowed):
        return [True] * count
    return [bool(a) for a in allowed]


def build_steps(rows: list[dict], vocab: features.Vocab, gamma: float, lam: float) -> list[dict]:
    """Tensors plus PPO targets for every decision, ready for [collate_steps]."""
    steps: list[dict] = []
    for trajectory in trajectories(rows):
        values = [float(r["value"]) for r in trajectory]
        advantages, returns = gae(values, float(trajectory[-1]["outcome"]), gamma, lam)
        for row, advantage, ret in zip(trajectory, advantages, returns):
            prepared = dict(row)
            prepared["chosen"] = int(row["played"])
            prepared.setdefault("outcome", 0)
            tensors = pretensorize(features.encode(prepared, vocab))
            tensors["allowed"] = torch.tensor(allowed_mask(row), dtype=torch.bool)
            tensors["old_logprob"] = torch.tensor(float(row["logprob"]))
            tensors["old_value"] = torch.tensor(float(row["value"]))
            tensors["advantage"] = torch.tensor(advantage)
            tensors["return"] = torch.tensor(ret)
            tensors["policy_mask"] = torch.tensor(1.0 if row.get("executed", True) else 0.0)
            steps.append(tensors)
    return steps


def collate_steps(steps: list[dict], device) -> dict[str, torch.Tensor]:
    from torch.nn.utils.rnn import pad_sequence

    batch = collate_tensors(steps, device)
    batch["allowed"] = pad_sequence([s["allowed"] for s in steps], batch_first=True, padding_value=False).to(device)
    for name in ("old_logprob", "advantage", "return", "policy_mask"):
        batch[name] = torch.stack([s[name] for s in steps]).to(device)
    if "ref_logprobs" in steps[0]:
        batch["ref_logprobs"] = pad_sequence(
            [s["ref_logprobs"] for s in steps], batch_first=True, padding_value=float("-inf"),
        ).to(device)
    return batch


# ---------------------------------------------------------------------------------------------
# Loss


def masked_logprobs(scores: torch.Tensor, allowed: torch.Tensor) -> torch.Tensor:
    """Log-softmax over the allowed candidates only (padding is already -inf in `scores`)."""
    return torch.log_softmax(scores.float().masked_fill(~allowed, float("-inf")), dim=-1)


def ppo_loss(model: P1Model, batch: dict, clip: float, value_coef: float, entropy_coef: float, kl_coef: float):
    scores, value = model(batch)
    logprobs = masked_logprobs(scores, batch["allowed"])
    valid = torch.isfinite(logprobs)
    probs = logprobs.exp()
    played = batch["chosen"]
    new_logprob = logprobs.gather(1, played.unsqueeze(1)).squeeze(1)

    mask = batch["policy_mask"]
    denominator = mask.sum().clamp(min=1.0)
    advantage = batch["advantage"]
    log_ratio = new_logprob - batch["old_logprob"]
    ratio = log_ratio.exp()
    surrogate = torch.minimum(ratio * advantage, ratio.clamp(1 - clip, 1 + clip) * advantage)
    policy_loss = -(surrogate * mask).sum() / denominator

    value_loss = torch.mean((value.float() - batch["return"]) ** 2)
    safe = torch.where(valid, logprobs, torch.zeros_like(logprobs))
    entropy = -(probs * safe).sum(-1).mean()
    loss = policy_loss + value_coef * value_loss - entropy_coef * entropy

    kl_ref = torch.zeros((), device=scores.device)
    if kl_coef > 0 and "ref_logprobs" in batch:
        # KL(pi || pi_ref) over the allowed candidates of each decision.
        reference = torch.where(valid, batch["ref_logprobs"], torch.zeros_like(logprobs))
        kl_ref = (probs * (safe - reference)).sum(-1).mean()
        loss = loss + kl_coef * kl_ref

    with torch.no_grad():
        approx_kl = (((ratio - 1) - log_ratio) * mask).sum() / denominator
        clip_fraction = (((ratio - 1).abs() > clip).float() * mask).sum() / denominator
    stats = {
        "loss": loss.item(), "policyLoss": policy_loss.item(), "valueLoss": value_loss.item(),
        "entropy": entropy.item(), "klRef": kl_ref.item(), "approxKl": approx_kl.item(),
        "clipFraction": clip_fraction.item(),
    }
    return loss, stats


# ---------------------------------------------------------------------------------------------
# Checkpoints


def load_model(directory: Path, device) -> tuple[P1Model, features.Vocab]:
    # No dropout: PPO compares the probability of the same move before and after an update, and
    # dropout noise in that ratio would read as policy change. Name dropout is switched off for the
    # same reason; the v2 card text keeps working because it is looked up by the true name.
    model, vocab = p1_model.load_checkpoint(directory, device, dropout=0.0)
    model.config = dataclasses.replace(model.config, name_dropout=0.0)
    return model, vocab


@torch.no_grad()
def attach_reference_logprobs(reference: P1Model, steps: list[dict], device, batch_size: int = 512) -> None:
    reference.eval()
    for start in range(0, len(steps), batch_size):
        chunk = steps[start:start + batch_size]
        batch = collate_steps(chunk, device)
        scores, _ = reference(batch)
        logprobs = masked_logprobs(scores, batch["allowed"]).cpu()
        for row, step in zip(logprobs, chunk):
            step["ref_logprobs"] = row[: step["allowed"].shape[0]].clone()


def next_ppo_dir(runs: Path) -> Path:
    runs.mkdir(parents=True, exist_ok=True)
    existing = sorted(p for p in runs.glob("p1-ppo-*") if p.is_dir())
    number = int(existing[-1].name.removeprefix("p1-ppo-")) + 1 if existing else 1
    return runs / f"p1-ppo-{number:04d}"


def rollout_summary(directories: list[Path], rows: list[dict]) -> dict:
    """Win rate of the learner per opponent in the rollout games (sampled play, so a noisy signal)."""
    per_opponent: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0, 0])  # games, wins, losses, unfinished
    seen = set()
    for row in rows:
        key = (row["_file"], row["seat"])
        if key in seen:
            continue
        seen.add(key)
        stats = per_opponent[row.get("opponent", "?")]
        stats[0] += 1
        outcome = row["outcome"]
        stats[1 if outcome > 0 else 2 if outcome < 0 else 3] += 1
    games = sum(len(features.iter_game_files(d)) for d in directories)
    return {
        "games": games,
        "decisions": len(rows),
        "byOpponent": {
            name: {"seatGames": g, "wins": w, "losses": l, "unfinished": u, "winRate": round(w / g, 4) if g else None}
            for name, (g, w, l, u) in sorted(per_opponent.items())
        },
    }


# ---------------------------------------------------------------------------------------------


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True, help="the policy that played the rollouts")
    parser.add_argument("--reference", type=Path, required=True, help="fixed imitation checkpoint for the KL brake")
    parser.add_argument("--data", type=Path, action="append", required=True, help="rollout directory (repeatable)")
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=512)
    parser.add_argument("--lr", type=float, default=1.5e-4)
    parser.add_argument("--clip", type=float, default=0.2)
    parser.add_argument("--gamma", type=float, default=1.0)
    parser.add_argument("--lam", type=float, default=0.95)
    parser.add_argument("--value-coef", type=float, default=0.5)
    parser.add_argument("--entropy-coef", type=float, default=0.01)
    parser.add_argument("--kl-coef", type=float, default=0.1)
    parser.add_argument("--target-kl", type=float, default=0.03)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--note", default="")
    args = parser.parse_args(argv)

    torch.manual_seed(args.seed)
    rng = random.Random(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    started = time.time()

    model, vocab = load_model(args.checkpoint, device)
    rows = read_rollout_rows(args.data)
    if not rows:
        raise SystemExit(f"no rollout decisions in {args.data}")
    steps = build_steps(rows, vocab, args.gamma, args.lam)
    advantages = torch.stack([s["advantage"] for s in steps])
    mean, std = advantages.mean(), advantages.std().clamp(min=1e-6)
    for s in steps:
        s["advantage"] = (s["advantage"] - mean) / std
    if args.kl_coef > 0:
        reference, _ = load_model(args.reference, device)
        attach_reference_logprobs(reference, steps, device)
        del reference
    summary = rollout_summary(args.data, rows)
    returns = torch.stack([s["return"] for s in steps])
    values = torch.stack([s["old_value"] for s in steps])
    explained = 1 - torch.var(returns - values) / torch.var(returns).clamp(min=1e-6)
    print(f"rollouts: {summary['games']} games, {len(steps)} decisions, device={device}, "
          f"value explained variance {explained.item():.3f}")
    for name, s in summary["byOpponent"].items():
        print(f"  vs {name}: {s['wins']}-{s['losses']} ({s['unfinished']} unfinished) of {s['seatGames']} seat-games")

    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.0)
    history = []
    stopped_early = False
    for epoch in range(1, args.epochs + 1):
        model.train()
        totals: dict[str, float] = defaultdict(float)
        batches = 0
        order = list(range(len(steps)))
        rng.shuffle(order)
        for start in range(0, len(order), args.batch_size):
            batch = collate_steps([steps[i] for i in order[start:start + args.batch_size]], device)
            loss, stats = ppo_loss(model, batch, args.clip, args.value_coef, args.entropy_coef, args.kl_coef)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 0.5)
            optimizer.step()
            for key, value in stats.items():
                totals[key] += value
            batches += 1
            if stats["approxKl"] > 1.5 * args.target_kl:
                stopped_early = True
                break
        metrics = {key: round(value / max(batches, 1), 5) for key, value in totals.items()}
        metrics.update({"epoch": epoch, "batches": batches})
        history.append(metrics)
        print(f"epoch {epoch}: loss {metrics['loss']:.4f} policy {metrics['policyLoss']:.4f} "
              f"value {metrics['valueLoss']:.4f} entropy {metrics['entropy']:.3f} klRef {metrics['klRef']:.4f} "
              f"approxKl {metrics['approxKl']:.4f} clip {metrics['clipFraction']:.3f}"
              + (" (stopped early: target KL)" if stopped_early else ""))
        if stopped_early:
            break

    out = next_ppo_dir(args.runs)
    out.mkdir(parents=True)
    p1_model.save_checkpoint(model, vocab, out)
    manifests = sorted(m for d in args.data for m in d.glob("manifest-*.json"))
    final = history[-1] if history else {}
    (out / "metrics.json").write_text(json.dumps({
        "schema": CHECKPOINT_SCHEMA,
        "checkpoint": out.name,
        "kind": "ppo",
        "note": args.note,
        "parent": str(args.checkpoint),
        "reference": str(args.reference),
        "data": {
            "directories": [str(d) for d in args.data],
            "games": summary["games"],
            "trainSamples": len(steps),
            "valSamples": 0,
            "manifests": [json.loads(p.read_text(encoding="utf-8")) for p in manifests],
        },
        "rollouts": {**summary, "valueExplainedVariance": round(explained.item(), 4)},
        "training": {
            "algorithm": "ppo", "epochs": args.epochs, "batchSize": args.batch_size, "lr": args.lr,
            "clip": args.clip, "gamma": args.gamma, "lam": args.lam, "valueCoef": args.value_coef,
            "entropyCoef": args.entropy_coef, "klCoef": args.kl_coef, "targetKl": args.target_kl,
            "stoppedEarly": stopped_early, "seed": args.seed,
            "parameters": sum(p.numel() for p in model.parameters()),
            "device": str(device), "seconds": round(time.time() - started, 1),
        },
        "history": history,
        "selectedEpoch": len(history),
        "final": final,
    }, indent=2), encoding="utf-8")
    print(f"wrote {out}")
    return out


if __name__ == "__main__":
    main()

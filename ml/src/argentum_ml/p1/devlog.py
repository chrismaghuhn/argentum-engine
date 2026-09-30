"""Assemble devlog data from P1 checkpoints and tournament results.

    python -m argentum_ml.p1.devlog --checkpoint DIR [--checkpoint DIR ...] --results FILE.jsonl [...]         --out devlog.json [--html devlog.html]

One entry per checkpoint, in the order given: training metrics (selected epoch and history), data
size, match summaries against every opponent it met, per-card usage next to the engine AI's usage in
the same games, showcase replays, and the Elo ladder over all results. The output is plain JSON that
the devlog page renders; nothing here depends on torch.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path

from . import ladder

DEVLOG_SCHEMA = "argentum-p1-devlog@v1"
TEMPLATE = Path(__file__).with_name("devlog_template.html")


def _checkpoint_entry(directory: Path, rows: list[dict], ratings: dict[str, float]) -> dict:
    metrics = json.loads((directory / "metrics.json").read_text(encoding="utf-8"))
    name = metrics["checkpoint"]
    label = directory.name if directory.name != name else name
    player_rows = [r for r in rows if label in (r["seatA"], r["seatB"])]
    stats = ladder.matchups(player_rows)
    matches = []
    for (player, opponent), s in sorted(stats.items()):
        if player != label:
            continue
        matches.append({
            "opponent": opponent,
            "games": s.games,
            "points": s.points,
            "winRate": round(s.win_rate, 4),
            "unfinished": s.unfinished,
            "byDeck": {deck: {"games": v[0], "winRate": round(v[1] / v[0], 4)} for deck, v in sorted(s.by_deck.items())},
            "averageTurns": round(s.turns / s.games, 1) if s.games else None,
            "modelChoices": s.model_choices,
            "fallbacks": s.fallbacks,
            "loopBreaks": sum(r.get("loopBreaks", 0) for r in player_rows if opponent in (r["seatA"], r["seatB"])),
        })

    usage = ladder.card_usage(player_rows, label)
    engine_usage: dict[str, dict] = {}
    for opponent in {m["opponent"] for m in matches if m["opponent"].startswith("engine")}:
        for card, stats_ in ladder.card_usage(player_rows, opponent).items():
            entry = engine_usage.setdefault(card, {"types": stats_["types"], "kinds": {}})
            for kind, (offered, played) in stats_["kinds"].items():
                o, p = entry["kinds"].get(kind, [0, 0])
                entry["kinds"][kind] = [o + offered, p + played]

    cards = []
    for card in sorted(set(usage) | set(engine_usage)):
        mine = usage.get(card, {"kinds": {}})
        theirs = engine_usage.get(card, {"kinds": {}})
        cards.append({
            "card": card,
            "types": (usage.get(card) or engine_usage.get(card) or {}).get("types", ""),
            "model": mine["kinds"],
            "engine": theirs["kinds"],
        })

    showcases = [
        {"game": r["game"], "file": r["replayFile"], "fidelity": r.get("replayFidelity"),
         "opponent": r["seatB"] if r["seatA"] == label else r["seatA"],
         "deck": r["deckA"] if r["seatA"] == label else ("Chevill" if r["deckA"] == "Akiri" else "Akiri"),
         "won": (r["winner"] == "A") == (r["seatA"] == label) and r["winner"] != "-",
         "turns": r["turns"]}
        for r in player_rows if r.get("replayFile")
    ]
    final = metrics.get("final") or {}
    return {
        "checkpoint": label,
        "note": metrics.get("note", ""),
        "created": dt.datetime.fromtimestamp((directory / "metrics.json").stat().st_mtime).isoformat(timespec="minutes"),
        "data": {k: metrics["data"].get(k) for k in ("games", "trainSamples", "valSamples")},
        "training": {k: metrics["training"].get(k) for k in ("epochs", "parameters", "seconds", "device")},
        "selectedEpoch": metrics.get("selectedEpoch", len(metrics.get("history", []))),
        "validation": {k: final.get(k) for k in ("top1", "top1NonPass", "alwaysPassBaseline", "valueSignAccuracy", "loss")},
        "history": [{k: h.get(k) for k in ("epoch", "trainLoss", "loss", "top1", "top1NonPass", "valueSignAccuracy")}
                    for h in metrics.get("history", [])],
        "elo": ratings.get(label),
        "matches": matches,
        "cards": cards,
        "showcases": showcases,
    }


def build(checkpoints: list[Path], results: list[Path]) -> dict:
    rows = ladder.read_results(results)
    ratings = ladder.elo(rows)
    return {
        "schema": DEVLOG_SCHEMA,
        "generated": dt.datetime.now().isoformat(timespec="minutes"),
        "ladder": [{"player": p, "elo": r} for p, r in sorted(ratings.items(), key=lambda kv: -kv[1])],
        "entries": [_checkpoint_entry(c, rows, ratings) for c in checkpoints],
    }


def render_html(data: dict) -> str:
    """The devlog page with the data embedded; `</` is escaped so the JSON cannot close its script tag."""
    payload = json.dumps(data, ensure_ascii=False).replace("</", "<\\/")
    return TEMPLATE.read_text(encoding="utf-8").replace("/*DEVLOG_DATA*/", payload)


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, action="append", required=True)
    parser.add_argument("--results", type=Path, action="append", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--html", type=Path)
    args = parser.parse_args(argv)
    data = build(args.checkpoint, args.results)
    args.out.write_text(json.dumps(data, indent=2), encoding="utf-8")
    if args.html:
        args.html.write_text(render_html(data), encoding="utf-8")
    print(f"wrote {args.out}: {len(data['entries'])} entries, ladder {data['ladder']}")


if __name__ == "__main__":
    main()

"""Precompute a frozen sentence embedding of every card's rules text (DraftFM-style card channel).

    python -m argentum_ml.p1.embed_cards --card-table card-table-full.json --out card-text-emb.npz

Text per card: its subtypes and its oracle text with the card's own name replaced by "this card",
encoded by a small pretrained text encoder (default BAAI/bge-small-en-v1.5, MIT; CLS pooling, L2
normalized). Cards without rules text or subtypes get a zero vector. The model maps the vector with a
learned linear layer; the encoder itself is never trained and is not needed at play time, because
the vectors are stored in each checkpoint (docs/ml/p1-card-text-features.md).
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import torch

from . import cardtext

DEFAULT_ENCODER = "BAAI/bge-small-en-v1.5"


def card_sentence(name: str, card: dict) -> str:
    text = (card.get("oracleText") or "").replace(name, "this card").strip()
    subtypes = " ".join(card.get("subtypes") or [])
    return f"{subtypes}. {text}".strip(". ").strip() if (text or subtypes) else ""


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--card-table", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--encoder", default=DEFAULT_ENCODER)
    parser.add_argument("--batch-size", type=int, default=256)
    args = parser.parse_args(argv)
    from transformers import AutoModel, AutoTokenizer

    table = cardtext.read_card_table(args.card_table)
    names = sorted(table)
    sentences = [card_sentence(n, table[n]) for n in names]
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    tokenizer = AutoTokenizer.from_pretrained(args.encoder)
    encoder = AutoModel.from_pretrained(args.encoder).to(device).eval()
    vectors = np.zeros((len(names), encoder.config.hidden_size), dtype=np.float16)
    todo = [i for i, s in enumerate(sentences) if s]
    with torch.no_grad():
        for start in range(0, len(todo), args.batch_size):
            batch = todo[start:start + args.batch_size]
            tokens = tokenizer([sentences[i] for i in batch], padding=True, truncation=True, max_length=256,
                               return_tensors="pt").to(device)
            cls = encoder(**tokens).last_hidden_state[:, 0]
            vectors[batch] = torch.nn.functional.normalize(cls, dim=-1).cpu().numpy().astype(np.float16)
    np.savez_compressed(args.out, names=np.array(names), vectors=vectors, encoder=np.array(args.encoder))
    print(f"embedded {len(todo)}/{len(names)} cards with {args.encoder} ({vectors.shape[1]} dims) -> {args.out}")


if __name__ == "__main__":
    main()

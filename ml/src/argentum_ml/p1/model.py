"""P1 candidate scorer with a value head (requires the learner extra: torch).

Architecture: every visible card and stack item is a token (name, zone, side, type/color/keyword/
counter bags, numeric state). A global token carries player stats and timing. A small transformer
encodes the set; each legal candidate is scored from its kind, its source token and its target
tokens against the encoded global context. The value head predicts the game outcome (-1..1) from
the acting player's perspective.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
from torch import nn

from . import features
from .features import MAX_TARGETS, EncodedSample, Vocab

MODEL_ARCHITECTURE = "argentum-p1-set-transformer-candidate-scorer@v1"


@dataclass(frozen=True)
class P1ModelConfig:
    d_model: int = 128
    heads: int = 4
    layers: int = 3
    ff: int = 256
    dropout: float = 0.1

    def to_json(self) -> dict:
        return {"architecture": MODEL_ARCHITECTURE, **asdict(self)}

    @classmethod
    def from_json(cls, payload: dict) -> "P1ModelConfig":
        if payload.get("architecture") != MODEL_ARCHITECTURE:
            raise ValueError("unexpected P1 model architecture")
        return cls(**{k: payload[k] for k in ("d_model", "heads", "layers", "ff", "dropout")})


class P1Model(nn.Module):
    def __init__(self, vocab: Vocab, config: P1ModelConfig = P1ModelConfig()):
        super().__init__()
        d = config.d_model
        self.config = config
        self.name = nn.Embedding(vocab.size("name"), d, padding_idx=0)
        self.zone = nn.Embedding(vocab.size("zone"), d, padding_idx=0)
        self.side = nn.Embedding(3, d)
        self.types = nn.EmbeddingBag(vocab.size("type"), d, mode="sum", padding_idx=0)
        self.colors = nn.EmbeddingBag(vocab.size("color"), d, mode="sum", padding_idx=0)
        self.keywords = nn.EmbeddingBag(vocab.size("keyword"), d, mode="sum", padding_idx=0)
        self.counters = nn.EmbeddingBag(vocab.size("counter"), d, mode="sum", padding_idx=0)
        self.token_numeric = nn.Linear(len(features.TOKEN_NUMERIC), d)
        self.global_numeric = nn.Linear(len(features.GLOBAL_NUMERIC), d)
        self.phase = nn.Embedding(vocab.size("phase"), d, padding_idx=0)
        self.step = nn.Embedding(vocab.size("step"), d, padding_idx=0)
        layer = nn.TransformerEncoderLayer(
            d_model=d, nhead=config.heads, dim_feedforward=config.ff, dropout=config.dropout,
            batch_first=True, norm_first=True,
        )
        self.encoder = nn.TransformerEncoder(layer, num_layers=config.layers, enable_nested_tensor=False)
        self.kind = nn.Embedding(vocab.size("kind"), d, padding_idx=0)
        self.no_source = nn.Parameter(torch.zeros(d))
        self.candidate_numeric = nn.Linear(len(features.CANDIDATE_NUMERIC), d)
        self.score = nn.Sequential(nn.Linear(3 * d, d), nn.GELU(), nn.Linear(d, 1))
        self.value = nn.Sequential(nn.Linear(d, d), nn.GELU(), nn.Linear(d, 1), nn.Tanh())

    def forward(self, batch: dict[str, torch.Tensor]) -> tuple[torch.Tensor, torch.Tensor]:
        b, t = batch["token_name"].shape
        bag = lambda module, ids: module(ids.reshape(b * t, -1)).reshape(b, t, -1)  # noqa: E731
        tokens = (
            self.name(batch["token_name"])
            + self.zone(batch["token_zone"])
            + self.side(batch["token_side"])
            + bag(self.types, batch["token_types"])
            + bag(self.colors, batch["token_colors"])
            + bag(self.keywords, batch["token_keywords"])
            + bag(self.counters, batch["token_counters"])
            + self.token_numeric(batch["token_numeric"])
        )
        global_token = (
            self.global_numeric(batch["global_numeric"]) + self.phase(batch["phase"]) + self.step(batch["step"])
        ).unsqueeze(1)
        sequence = torch.cat([global_token, tokens], dim=1)
        padding = torch.cat([torch.zeros(b, 1, dtype=torch.bool, device=tokens.device), batch["token_pad"]], dim=1)
        encoded = self.encoder(sequence, src_key_padding_mask=padding)
        context = encoded[:, 0]
        cards = encoded[:, 1:]

        def gather(indices: torch.Tensor) -> torch.Tensor:
            # indices: [b, c] token indices, -1 = none
            safe = indices.clamp(min=0)
            picked = torch.gather(cards, 1, safe.unsqueeze(-1).expand(-1, -1, cards.size(-1)))
            return picked * (indices >= 0).unsqueeze(-1)

        source = gather(batch["candidate_source"])
        source = torch.where((batch["candidate_source"] >= 0).unsqueeze(-1), source, self.no_source)
        c = batch["candidate_kind"].shape[1]
        targets = gather(batch["candidate_targets"].reshape(b, c * MAX_TARGETS)).reshape(b, c, MAX_TARGETS, -1)
        target_count = (batch["candidate_targets"] >= 0).sum(-1, keepdim=True).clamp(min=1)
        targets = targets.sum(2) / target_count
        candidate = (
            self.kind(batch["candidate_kind"]) + source + targets
            + self.candidate_numeric(batch["candidate_numeric"])
        )
        ctx = context.unsqueeze(1).expand_as(candidate)
        scores = self.score(torch.cat([candidate, ctx, candidate * ctx], dim=-1)).squeeze(-1)
        scores = scores.masked_fill(batch["candidate_pad"], float("-inf"))
        value = self.value(context).squeeze(-1)
        return scores, value


def collate(samples: list[EncodedSample], device: torch.device | str = "cpu") -> dict[str, torch.Tensor]:
    """Pad a list of encoded samples into one batch of tensors."""
    b = len(samples)
    t = max(1, max(len(s.token_name) for s in samples))
    c = max(len(s.candidate_kind) for s in samples)
    long = dict(dtype=torch.long)
    out = {
        "token_name": torch.zeros(b, t, **long),
        "token_zone": torch.zeros(b, t, **long),
        "token_side": torch.full((b, t), 2, **long),
        "token_types": torch.zeros(b, t, features.MAX_TYPES, **long),
        "token_colors": torch.zeros(b, t, features.MAX_COLORS, **long),
        "token_keywords": torch.zeros(b, t, features.MAX_KEYWORDS, **long),
        "token_counters": torch.zeros(b, t, features.MAX_COUNTER_TYPES, **long),
        "token_numeric": torch.zeros(b, t, len(features.TOKEN_NUMERIC)),
        "token_pad": torch.ones(b, t, dtype=torch.bool),
        "global_numeric": torch.tensor([s.global_numeric for s in samples], dtype=torch.float32),
        "phase": torch.tensor([s.phase for s in samples], **long),
        "step": torch.tensor([s.step for s in samples], **long),
        "candidate_kind": torch.zeros(b, c, **long),
        "candidate_source": torch.full((b, c), -1, **long),
        "candidate_targets": torch.full((b, c, MAX_TARGETS), -1, **long),
        "candidate_numeric": torch.zeros(b, c, len(features.CANDIDATE_NUMERIC)),
        "candidate_pad": torch.ones(b, c, dtype=torch.bool),
        "chosen": torch.tensor([s.chosen for s in samples], **long),
        "outcome": torch.tensor([s.outcome for s in samples], dtype=torch.float32),
    }
    for i, s in enumerate(samples):
        n = len(s.token_name)
        if n:
            out["token_name"][i, :n] = torch.tensor(s.token_name)
            out["token_zone"][i, :n] = torch.tensor(s.token_zone)
            out["token_side"][i, :n] = torch.tensor(s.token_side)
            out["token_types"][i, :n] = torch.tensor(s.token_types)
            out["token_colors"][i, :n] = torch.tensor(s.token_colors)
            out["token_keywords"][i, :n] = torch.tensor(s.token_keywords)
            out["token_counters"][i, :n] = torch.tensor(s.token_counters)
            out["token_numeric"][i, :n] = torch.tensor(s.token_numeric)
            out["token_pad"][i, :n] = False
        else:
            out["token_pad"][i, 0] = False  # keep at least one attendable token
        m = len(s.candidate_kind)
        out["candidate_kind"][i, :m] = torch.tensor(s.candidate_kind)
        out["candidate_source"][i, :m] = torch.tensor(s.candidate_source)
        out["candidate_targets"][i, :m] = torch.tensor(s.candidate_targets)
        out["candidate_numeric"][i, :m] = torch.tensor(s.candidate_numeric)
        out["candidate_pad"][i, :m] = False
    return {k: v.to(device, non_blocking=True) for k, v in out.items()}

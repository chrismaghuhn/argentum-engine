"""P1 candidate scorer with a value head (requires the learner extra: torch).

Architecture: every visible card and stack item is a token (name, zone, side, type/color/keyword/
counter bags, numeric state). A global token carries player stats and timing. A small transformer
encodes the set; each legal candidate is scored from its kind, its source token and its target
tokens against the encoded global context. The value head predicts the game outcome (-1..1) from
the acting player's perspective.

v2 (docs/ml/p1-card-text-features.md) adds what a card *does*: per card name the model holds fixed
rows of rules-text term ids, subtype ids and parsed printed cost (built from a card table, stored as
buffers, so a checkpoint is self-contained), looked up by the token's name id. Name dropout replaces
the learned name embedding by "unknown" during training only, so the model has to play from text,
cost and type instead of memorized names; the text lookup always uses the true name.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, replace
from pathlib import Path

import torch
from torch import nn

from . import cardtext, features
from .cardtext import CardFeatures
from .features import MAX_TARGETS, UNK, EncodedSample, Vocab

MODEL_ARCHITECTURE = "argentum-p1-set-transformer-candidate-scorer@v1"
MODEL_ARCHITECTURE_V2 = "argentum-p1-set-transformer-candidate-scorer@v2"
CARD_FEATURES_FILE = "card_features.json"


@dataclass(frozen=True)
class P1ModelConfig:
    d_model: int = 128
    heads: int = 4
    layers: int = 3
    ff: int = 256
    dropout: float = 0.1
    # v2 card features (all off = the v1 architecture)
    text: bool = False
    subtypes: bool = False
    mana_cost: bool = False
    name_dropout: float = 0.0
    # Frozen pretrained sentence embedding of each card's rules text (0 = off); see embed_cards.py.
    text_embedding_dim: int = 0

    @property
    def uses_card_features(self) -> bool:
        return self.text or self.subtypes or self.mana_cost or self.text_embedding_dim > 0

    def to_json(self) -> dict:
        if not self.uses_card_features and self.name_dropout == 0.0:
            base = {k: v for k, v in asdict(self).items() if k in ("d_model", "heads", "layers", "ff", "dropout")}
            return {"architecture": MODEL_ARCHITECTURE, **base}
        return {"architecture": MODEL_ARCHITECTURE_V2, **asdict(self)}

    @classmethod
    def from_json(cls, payload: dict) -> "P1ModelConfig":
        architecture = payload.get("architecture")
        if architecture == MODEL_ARCHITECTURE:
            return cls(**{k: payload[k] for k in ("d_model", "heads", "layers", "ff", "dropout")})
        if architecture == MODEL_ARCHITECTURE_V2:
            return cls(**{k: payload[k] for k in asdict(cls()) if k in payload})
        raise ValueError(f"unexpected P1 model architecture {architecture!r}")


class P1Model(nn.Module):
    def __init__(self, vocab: Vocab, config: P1ModelConfig = P1ModelConfig(), card_features: CardFeatures | None = None,
                 text_embeddings: dict[str, "torch.Tensor"] | None = None):
        super().__init__()
        d = config.d_model
        self.config = config
        self.card_features = card_features
        if config.uses_card_features:
            if card_features is None:
                raise ValueError("a v2 model with card features needs a CardFeatures table")
            text, subtypes, cost = card_features.rows(vocab.maps["name"], vocab.size("name"))
            self.register_buffer("name_text", torch.tensor(text, dtype=torch.long))
            self.register_buffer("name_subtypes", torch.tensor(subtypes, dtype=torch.long))
            self.register_buffer("name_cost", torch.tensor(cost, dtype=torch.float32))
            seen = torch.zeros(vocab.size("name"), dtype=torch.bool)
            seen[: features.UNK + 1] = True
            for name in card_features.trained_names:
                index = vocab.maps["name"].get(name)
                if index is not None:
                    seen[index] = True
            for name, index in vocab.maps["name"].items():  # names without a table entry (tokens) come from training data
                if name not in card_features.cards:
                    seen[index] = True
            self.register_buffer("name_seen", seen)
        if config.text:
            self.text = nn.EmbeddingBag(card_features.text_size(), d, mode="mean", padding_idx=0)
        if config.subtypes:
            self.subtype = nn.EmbeddingBag(card_features.subtype_size(), d, mode="sum", padding_idx=0)
        if config.mana_cost:
            self.card_cost = nn.Linear(len(cardtext.COST_FEATURES), d)
            self.candidate_cost = nn.Linear(len(cardtext.COST_FEATURES), d)
        if config.text_embedding_dim > 0:
            # Rows by name id; filled from the precomputed vectors when training, from the checkpoint
            # (state dict) when loading.
            rows = torch.zeros(vocab.size("name"), config.text_embedding_dim)
            for name, index in vocab.maps["name"].items():
                vector = (text_embeddings or {}).get(name)
                if vector is not None and 0 <= index < rows.shape[0]:
                    rows[index] = vector
            self.register_buffer("name_textemb", rows)
            self.text_embedding = nn.Linear(config.text_embedding_dim, d)
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
        names = batch["token_name"]
        shown = names
        if hasattr(self, "name_seen"):
            # Cards that never occurred in training have no learned name; they play from text alone.
            shown = torch.where(self.name_seen[names], names, torch.full_like(names, UNK))
        if self.training and self.config.name_dropout > 0:
            hide = (torch.rand(names.shape, device=names.device) < self.config.name_dropout) & (names > UNK)
            shown = shown.masked_fill(hide, UNK)
        tokens = (
            self.name(shown)
            + self.zone(batch["token_zone"])
            + self.side(batch["token_side"])
            + bag(self.types, batch["token_types"])
            + bag(self.colors, batch["token_colors"])
            + bag(self.keywords, batch["token_keywords"])
            + bag(self.counters, batch["token_counters"])
            + self.token_numeric(batch["token_numeric"])
        )
        if self.config.text:
            tokens = tokens + bag(self.text, self.name_text[names])
        if self.config.subtypes:
            tokens = tokens + bag(self.subtype, self.name_subtypes[names])
        if self.config.mana_cost:
            tokens = tokens + self.card_cost(self.name_cost[names])
        if self.config.text_embedding_dim > 0:
            tokens = tokens + self.text_embedding(self.name_textemb[names])
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
        if self.config.mana_cost:
            candidate = candidate + self.candidate_cost(batch["candidate_cost"])
        ctx = context.unsqueeze(1).expand_as(candidate)
        scores = self.score(torch.cat([candidate, ctx, candidate * ctx], dim=-1)).squeeze(-1)
        scores = scores.masked_fill(batch["candidate_pad"], float("-inf"))
        value = self.value(context).squeeze(-1)
        return scores, value


_TOKEN_FIELDS = ("token_name", "token_zone", "token_side", "token_types", "token_colors", "token_keywords",
                 "token_counters", "token_numeric")
_CANDIDATE_FIELDS = ("candidate_kind", "candidate_source", "candidate_targets", "candidate_numeric", "candidate_cost")
_PAD_VALUES = {"token_side": 2, "candidate_source": -1, "candidate_targets": -1}


def pretensorize(sample: EncodedSample, compact: bool = False) -> dict[str, torch.Tensor]:
    """Convert one encoded sample to tensors once, so training batches only pad and stack.
    `compact` stores ids as int32 (collate_tensors widens them), roughly halving memory for large runs."""
    out: dict[str, torch.Tensor] = {}
    for name in _TOKEN_FIELDS + _CANDIDATE_FIELDS:
        values = getattr(sample, name)
        dtype = torch.float32 if name.endswith(("numeric", "cost")) else (torch.int32 if compact else torch.long)
        if name == "candidate_cost" and not values:
            values = [[0.0] * len(cardtext.COST_FEATURES) for _ in sample.candidate_kind]
        out[name] = torch.tensor(values, dtype=dtype)
    if out["token_name"].numel() == 0:  # keep at least one attendable token
        for name in _TOKEN_FIELDS:
            width = {"token_types": features.MAX_TYPES, "token_colors": features.MAX_COLORS,
                     "token_keywords": features.MAX_KEYWORDS, "token_counters": features.MAX_COUNTER_TYPES,
                     "token_numeric": len(features.TOKEN_NUMERIC)}.get(name)
            shape = (1, width) if width else (1,)
            dtype = torch.float32 if name.endswith("numeric") else torch.long
            out[name] = torch.full(shape, _PAD_VALUES.get(name, 0), dtype=dtype)
        out["token_count"] = torch.tensor(1)
    else:
        out["token_count"] = torch.tensor(len(sample.token_name))
    out["global_numeric"] = torch.tensor(sample.global_numeric, dtype=torch.float32)
    out["phase"] = torch.tensor(sample.phase)
    out["step"] = torch.tensor(sample.step)
    out["chosen"] = torch.tensor(sample.chosen)
    out["outcome"] = torch.tensor(sample.outcome, dtype=torch.float32)
    return out


def collate_tensors(samples: list[dict[str, torch.Tensor]], device: torch.device | str = "cpu") -> dict[str, torch.Tensor]:
    """Fast batch assembly from [pretensorize] outputs; same result as [collate]."""
    from torch.nn.utils.rnn import pad_sequence

    out: dict[str, torch.Tensor] = {}
    for name in _TOKEN_FIELDS + _CANDIDATE_FIELDS:
        out[name] = pad_sequence([s[name] for s in samples], batch_first=True, padding_value=_PAD_VALUES.get(name, 0))
        if out[name].dtype == torch.int32:
            out[name] = out[name].long()
    counts = torch.stack([s["token_count"] for s in samples])
    out["token_pad"] = torch.arange(out["token_name"].shape[1]).unsqueeze(0) >= counts.unsqueeze(1)
    candidate_counts = torch.tensor([s["candidate_kind"].shape[0] for s in samples])
    out["candidate_pad"] = torch.arange(out["candidate_kind"].shape[1]).unsqueeze(0) >= candidate_counts.unsqueeze(1)
    for name in ("global_numeric", "phase", "step", "chosen", "outcome"):
        out[name] = torch.stack([s[name] for s in samples])
    return {k: v.to(device, non_blocking=True) for k, v in out.items()}


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
        "candidate_cost": torch.zeros(b, c, len(cardtext.COST_FEATURES)),
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
        if s.candidate_cost:
            out["candidate_cost"][i, :m] = torch.tensor(s.candidate_cost)
        out["candidate_pad"][i, :m] = False
    return {k: v.to(device, non_blocking=True) for k, v in out.items()}


def load_checkpoint(directory: Path, device="cpu", dropout: float | None = None) -> tuple[P1Model, Vocab]:
    """Load a v1 or v2 checkpoint directory (v2 carries its card table in card_features.json).
    `dropout` overrides the stored training dropout (PPO uses 0); name dropout only acts in train()."""
    directory = Path(directory)
    vocab = Vocab.from_json(json.loads((directory / "vocab.json").read_text(encoding="utf-8")))
    config = P1ModelConfig.from_json(json.loads((directory / "config.json").read_text(encoding="utf-8")))
    if dropout is not None:
        config = replace(config, dropout=dropout)
    card_file = directory / CARD_FEATURES_FILE
    card_features = (CardFeatures.from_json(json.loads(card_file.read_text(encoding="utf-8")))
                     if card_file.exists() else None)
    from safetensors.torch import load_file

    model = P1Model(vocab, config, card_features)
    model.load_state_dict(load_file(str(directory / "model.safetensors")))
    return model.to(device), vocab


def save_checkpoint(model: P1Model, vocab: Vocab, directory: Path) -> None:
    """Write model.safetensors, vocab.json, config.json and (v2) card_features.json."""
    from safetensors.torch import save_file

    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    save_file({k: v.detach().cpu().contiguous() for k, v in model.state_dict().items()}, str(directory / "model.safetensors"))
    (directory / "vocab.json").write_text(json.dumps(vocab.to_json(), sort_keys=True), encoding="utf-8")
    (directory / "config.json").write_text(json.dumps(model.config.to_json(), sort_keys=True, indent=2), encoding="utf-8")
    if model.card_features is not None:
        (directory / CARD_FEATURES_FILE).write_text(json.dumps(model.card_features.to_json(), sort_keys=True), encoding="utf-8")

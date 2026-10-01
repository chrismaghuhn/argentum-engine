# P1 card-text and mana-cost features — plan and spec

Status: **planned** (2026-10-01). Build after the upstream sync (`chris/sync-upstream-20261001`) and the
fix for engine-AI searches that hang PPO rollout games have landed, so the feature work starts on the
synced code. Owner branch: the P1/PPO line (`chris/p1-02-ppo-league-20261001` or its successor).

Related: [`p1-first-playing-model.md`](p1-first-playing-model.md) (P1 design, sample schema),
PPO trainer `ml/src/argentum_ml/p1/ppo.py`, model `ml/src/argentum_ml/p1/model.py`,
features `ml/src/argentum_ml/p1/features.py`.

## 1. Problem

The P1 model sees a card as: name id, zone, side, card types, colors, keywords, mana value,
power/toughness and state (tapped, damage, counters, attachments). It does **not** see what the card
does. Rules text, the exact mana cost and subtypes are missing.

Evidence from checkpoint 4 against the engine AI (40 games). The figures are how often each card was
played when it was offered:

| Card | Model played / offered | Engine played / offered |
|---|---|---|
| Arcane Signet | 1 / 319 | 16 / 451 |
| Mind Stone | 0 / 192 | 9 / 221 |
| Golgari Signet | 0 / 236 | 8 / 336 |
| Night's Whisper | 0 / 225 | 5 / 200 |
| Read the Bones | 0 / 80 | 5 / 206 |
| Undying Malice | 0 / 709 | 9 / 473 |
| Beast Within | 0 / 34 | 13 / 99 |

For the model, Arcane Signet is "artifact, mana value 2, colorless, no keywords". The fact that it
makes mana exists only in its rules text. With two locked decks, the name embedding can in principle
learn a card's role from outcomes. In practice this is slow for cards whose payoff comes turns later.
For P2 (random sealed decks, unseen cards) it does not work at all.

Part of the gap is the imitation and argmax effect: the engine casts a rock once per game but has it
available at dozens of decisions. PPO with sampling addresses that. Rules text addresses the
"does not know what it does" part. The two are complementary.

## 2. What already exists

`TrainingObservation.EntityFeatures` (gym contract) already carries:

- `oracleText` (printed rules text, not rewritten by text-changing effects);
- `manaCost` (canonical string such as `{1}{R}{R}`);
- `subtypes` (projected);
- `StackItemView.oracleText`;
- `LegalActionView.manaCost`, `description`, `maxAffordableX`.

`Phase1SelfPlayCollector.cardJson` drops oracleText, manaCost and subtypes when it writes samples. The
schema stays `argentum-p1-selfplay-sample@v1`.

## 3. Design

### 3.1 Card table (static per-card facts, joined by name)

Rules text, printed mana cost and base subtypes are static per card, so they are **not** added to
every sample. A card table is exported once from the card registry:

```json
{"schema": "argentum-p1-card-table@v1", "sourceCommit": "...", "cards": {
  "Arcane Signet": {"oracleText": "{T}: Add one mana of any color in your commander's color identity.",
                    "manaCost": "{2}", "subtypes": [], "types": ["ARTIFACT"]}, ...}}
```

- **Export:** a small opt-in gym test (`Phase1CardTableExportTest`, `-Dphase1.cardTable=true`) walks
  `ServerRegistries.cardRegistry()`. It writes every card of the two locked decks plus their tokens,
  or optionally the whole registry.
- **Joining:** features join on `name`. Old and new samples are joined the same way, so all existing
  data (selfplay-v1, dagger-r1/r2, PPO rollouts) stays usable without re-collection.
- **Tokens and face-down cards:** a token name maps to the token definition. A face-down card has
  name `""`, so it gets no text, which is correct because the text is hidden.
- **Packaging:** each checkpoint stores `card_table.json` next to `vocab.json`. A checkpoint
  therefore always serves with exactly the text it was trained on. Its hash is recorded in
  `metrics.json`.
- **Forward-only collector change (optional):** add projected `subtypes` per card to samples, because
  type-changing effects make it dynamic. Not required for v2.

### 3.2 Rules-text encoding

- **Normalization:**
  - lowercase;
  - replace the card's own name with `~`;
  - keep mana and tap symbols as single tokens (`{t}`, `{2}`, `{g}`, `{x}`);
  - split on whitespace and punctuation;
  - keep numbers;
  - mark each ability line break with `<nl>`.
- **Vocabulary:** unigrams plus bigrams built from the card table of the training cards, with a
  minimum count of 1. It is stored as `text_vocab.json` in the checkpoint. Unknown tokens map to UNK.
  The current decks give a vocabulary of a few hundred to a few thousand entries.
- **Encoder, start simple:** `EmbeddingBag(mode="mean")` over the token and bigram ids, followed by a
  linear projection to `d_model`. The result is added to the card token, the same way keywords are
  added today.
- **Optional upgrade:** a one-layer transformer over at most 96 tokens.
- **Caching:** text vectors depend only on the card name. The batch computes them once per unique
  name and gathers them per token, which makes the training and serving cost negligible.
- **Stack items:** stack items get the text vector of their source card.
- **Later option (needs a download, so ask first):** a frozen pretrained sentence-embedding model
  that precomputes a vector per card. This is out of scope for v2.

### 3.3 Mana-cost features

Parse a mana-cost string into numbers:

- generic amount;
- W/U/B/R/G/C pip counts;
- an X flag;
- a hybrid flag;
- a Phyrexian flag.

This gives 10 numbers, each scaled /10. They are added:

- **per card**, from the card table's printed cost (lands and tokens get all zeros);
- **per candidate**, replacing today's `manaSymbols` count, parsed from `LegalActionView.manaCost`.

Candidate features also gain `maxAffordableX` (scaled /10) when the observation provides it.

### 3.4 Subtypes

Subtypes become a bag embedding like types and keywords, from the card table's base subtypes and
later from the projected per-sample subtypes. Equipment, Aura, Treasure and creature types matter in
these decks.

### 3.5 Name dropout ("forcing" generalization)

During training only, each card token's name id is replaced by UNK with probability `p_name_drop`
(default 0.2, configurable, 0 at serving). The model then has to play from text, cost and type, not
from memorized names. This is the main lever for P2.

### 3.6 Candidate description (optional, measure first)

`LegalActionView.description` distinguishes two activated abilities of the same source, which
`kind + source` cannot. Before using it, verify that it contains no entity ids or other
run-specific strings. If it is clean, encode it with the same text vocabulary as a candidate-side bag.

### 3.7 Model and checkpoint versioning

- **Architecture id:** `argentum-p1-set-transformer-candidate-scorer@v2`. `P1ModelConfig` gains
  `text`, `mana_cost`, `subtypes` and `name_dropout` switches, so ablations share one code path.
- **Checkpoint files:** `model.safetensors`, `config.json`, `vocab.json`, `card_table.json`,
  `text_vocab.json`, `metrics.json`.
- **Compatibility:** `serve.py` loads v1 and v2 checkpoints. A tournament or PPO league can mix v1
  and v2 seats because each worker loads its own checkpoint. `ppo.py` loads the reference model by
  its own config, so a v2 learner can still use a v1 reference for the KL brake only if their
  candidate sets agree. They do, because candidates come from the engine. The reference must be
  encoded with its own features, so KL is computed from each model's own forward pass.
- **Warm start, optional:** load the matching v1 tensors (encoder layers, scorer, value head, name
  embedding rows) and zero-initialize the new projections, so the v2 model starts identical to v1.

## 4. Training and evaluation plan

All behaviour-cloning variants use the same data, split and seed. The data is selfplay-v1, dagger-r1
and dagger-r2 (1,986 games). Every variant trains on the local GPU in about 15 minutes.

| Variant | Features | Purpose |
|---|---|---|
| A | v1 retrain | baseline under identical conditions (≈ ckpt 4) |
| B | + mana-cost features + subtypes | cheap structural gain |
| C | B + rules text | main change |
| D | C + name dropout 0.2 | generalization lever |
| E | D, warm start from ckpt 4 | does transfer help? |

Each variant is measured on:

- **Offline:** validation top-1, non-pass top-1, and top-1 restricted to non-pass actions of the
  "delayed payoff" cards (rocks, draw, ramp, removal) listed in section 1.
- **Online, argmax:** 100 games against the engine AI (tournament seeds 910000000+g, the same deals
  for every variant), 100 games against ckpt 4, and the card-usage table for the cards in section 1
  (`C:/Dev/data/argentum-p1/card_usage_compare.py`).
- **Run conditions:** the engine AI thinks on wall-clock time, so the vs-engine matches run on an
  idle machine (or with a work-bounded engine profile) and the load is noted.

The best variant becomes `p1-ckpt-0005`. PPO then continues from it with the AWS loop
(`C:/Dev/data/argentum-p1/aws/`, same league plus ckpt 4 and the best PPO v1 checkpoint as opponents).
It is compared against the p1-ppo-* v1 line in the devlog.

## 5. Acceptance criteria

- [ ] The card table export covers every name in the two locked decks and their tokens (0 missing),
      and its hash is recorded in every v2 checkpoint.
- [ ] Old samples (selfplay-v1, dagger-r1/r2, PPO rollouts) encode under v2 without re-collection.
- [ ] `serve.py` serves v1 and v2 checkpoints. A mixed v1/v2 tournament runs without fallbacks
      beyond today's level.
- [ ] Unit tests cover:
  - the tokenizer (self-name replacement, mana symbols);
  - mana-cost parsing (generic, colored, X, hybrid, Phyrexian);
  - name dropout off at eval;
  - v1 loading;
  - warm start giving identical outputs at step 0.
- [ ] The ablation table A–E is reported in the devlog with offline and online metrics.
- [ ] Section 1 is re-measured for the chosen variant: the play rate for rocks and draw spells is
      closer to the engine AI's than ckpt 4's is.

## 6. Order of work

1. Wait for the upstream sync. The card texts or the `EntityFeatures` fields may change, and the card
   table must be exported from the synced registry.
2. Card table export plus the Python loader and tokenizer, with tests.
3. Mana-cost, subtype and text features in `features.py` and `model.py`, with config switches, name
   dropout, v2 checkpoint files, and `serve.py` and `ppo.py` support.
4. Behaviour-cloning ablations A–E locally, then tournaments and card usage, then the devlog.
5. PPO from the best v2 on AWS. Ask before launching, giving instance type, games and cost.

## 7. Forward to P2 (random sealed)

The same features are the prerequisite for P2: per-game random sealed pools from fully implemented
sets, AI-built 40-card decks, engine-AI teacher, then PPO. P2 additionally needs:

- a card table over all cards in the chosen sets;
- a held-out set of cards (or a whole set) for an honest generalization test;
- deciding whether name embeddings are kept at all, or replaced entirely by text plus a hashed-name
  fallback.

## 8. Risks

- **Text vocabulary drift after the upstream sync (errata):** the card table hash pins it per
  checkpoint, and a re-export means a new checkpoint lineage.
- **Overfitting to two decks:** the text could act as just another name id. Name dropout and the
  ablations show whether it does. The held-out evaluation belongs to P2.
- **Serving speed:** text vectors are cached per card name inside the serve process, so per-decision
  cost stays at about 3 ms.
- **Copy and text-changing effects:** the oracle text is the base definition, a known small lie that
  the gym contract already documents.

# P1 — First Playing Model (Engine-AI Teacher, Engine-Handled Sub-Decisions)

Date: 2026-09-30
Status: in progress (P1_00 measured, P1_01 collector implemented)

## Goal

A first model that **completes Commander games** on the locked Akiri vs Chevill matchup and can be
measured by win rate against the built-in engine AI. Earlier C1 work proved the data and replay
pipeline but produced nothing that can play:

- the C1_06 scorer's inputs are SHA-256 digest bytes of canonical JSON
  (`ml/src/argentum_ml/learner/c1_06.py`, `_feature_vector`), so similar positions share nothing;
- the admitted C1_03C teacher scores candidates by action type only (+1 act, −1 pass);
- its source data (A9, `DeterministicExternalPolicy`) mostly ends at the 2,000-step cap;
- live play stops at the first payment the live seat cannot express (ARENA_ML_01).

## Decisions (project owner, 2026-09-30)

These are explicit, scoped exceptions to earlier contracts, not silent drift.

| decision | overrides | scope |
|---|---|---|
| **The built-in engine AI (`AIPlayer`, profile `production-candidate-expiring`) is the P1 teacher**, although it reads the full `GameState`. | C1_01 rejection of `AIPlayer`/`Strategist` (`REJECT_RAW_GAME_STATE`, `REJECT_HIDDEN_INFORMATION`, `REJECT_HIDDEN_POLICY`) and the C0_05 public-teacher requirement | P1 only. The **student** still receives only the acting player's public observation (`ObservationBuilder`), so no hidden information enters a sample. |
| **The engine handles mana payment and structured sub-decisions** (targets, modes, combat assignments) for the P1 model; the model chooses the top-level action. | The live-seat "no fallback" rule (ARENA_HUMAN_01 design, `LivePolicySourceAdapter` fail-closed) | P1 only. The payment-construction grammar (`chris/c1-live-payment-choice-boundary-02a-20260918`, TASK 2) remains the long-term path. |
| **P1 data does not go through the KA/B2 trust pipeline** (replay verification, TrajectoryV1 shards). | nothing — additive | P1 samples are reproducible from `(sourceCommit, baseSeed + game, profile)` instead. The verified pipeline is reattached once a model plays. |

## P1_00 — engine AI vs engine AI measurement

`:gym:phase1SelfPlayMeasureTest` (`Phase1EngineAiCommanderSelfPlayMeasurementTest`).

- Before the fix: game 0 never finished. A 1,500-step trace showed the engine AI re-equipping
  Vulshok Morningstar onto the creature it was already attached to, 1,200+ times in one main phase
  (turn 7). Puresteel Paladin made equip free; `StateProgress` did not ignore the player's
  `EquipActivationsThisTurnComponent`, so each no-op re-equip read as a new position.
  Fixed in PR #216.
- After the fix, 4 games: **4/4 terminal**, 19–32 turns, 450–789 engine steps, 54–167 s per game
  (one thread, Ryzen 7 5800X). Akiri won all 4, from both seats; the matchup is likely skewed
  under the engine AI, so evaluation must report per seat/deck.

## P1_01 — self-play sample collector

`:gym:phase1SelfPlayCollectTest` (`Phase1SelfPlayCollector`, `Phase1SelfPlayCollectTest`):

```bash
./gradlew :gym:phase1SelfPlayCollectTest -Dphase1.collect=true -Dphase1.games=N -Dphase1.workers=W -Dphase1.outputDir=DIR
```

Game `g` uses seed `baseSeed + g` (default `20260930`); seat order alternates every game and the
starting player every two games. The seed fixes the deal, not the play: the teacher profile's
`TieredBudgetPolicy` stops search at wall-clock deadlines, so the engine AI's choices depend on CPU
speed and load (two runs of the same seed on unchanged code diverged in 4 of 6 games while the
machine was busy). Samples are still valid teacher play; they are not byte-reproducible, and a
heavily loaded or slow machine yields a weaker teacher. A work-bounded budget (same tiers and
allowances, no clock) removes both effects and is the recommended teacher budget for future runs. Output per run: `game-NNNNNN.jsonl.gz` (one sample per line),
`summary-NNNNNN.tsv`, `manifest-NNNNNN.json` (source commit, teacher profile, seeds).

A sample is written for every priority choice with **≥ 2 candidates after removing mana
abilities**. Pending decisions are answered by the engine AI and not recorded. Schema
`argentum-p1-selfplay-sample@v1`:

| field | content |
|---|---|
| `seat`, `turn`, `phase`, `step`, `activeIsSelf` | acting player's deck and timing |
| `players[]` | `side` (self first), life, hand/library/graveyard/exile sizes, mana pool (WUBRGC), active |
| `cards[]` | every card visible to the acting player: name (empty if face down), zone, side, types, colors, keywords, mana value, power/toughness, tapped, summoning sick, damage, counters, `attachedTo` (card index or −1) |
| `stack[]` | name, kind, controller side, source and target card indices |
| `candidates[]` | kind, source card index, target card indices, mana cost, has X |
| `chosen` | index into `candidates` of the engine AI's choice (matched with `GameEnvironment.isCurrentActionCandidate`) |
| `outcome` | +1 / −1 from the acting player's perspective, 0 for a draw or an unfinished game |

Engine entity ids are never written; all references are indices into the sample's `cards`.

Known P1 limitation: `GameEnvironment.step()` in LEGACY mode runs the simulator's quiet-state loop
after each submitted action, passing priority automatically while the stack is non-empty (see
`docs/ml/headless-replay-showcase.md`). Neither the teacher nor a model seat ever responds to an
object on the stack, so P1 data contains no instant-speed responses. Candidates also include
currently unaffordable actions without an affordability feature; the teacher never picks them,
and tournament model seats mask them at inference.

## Next

1. P1_02 — featurization and model (Python, `ml/`): card-name embeddings over the small fixed
   vocabulary, a set encoder over `cards`, a candidate scorer plus a value head trained on
   `outcome`.
2. P1_03 — collect ~1,000 games locally (estimate ~5 h with 6 workers; to be measured).
3. P1_04 — evaluation seat: the model picks the top-level candidate, the engine AI fills targets,
   payment and pending decisions; report win rate vs the engine AI per deck and seat.

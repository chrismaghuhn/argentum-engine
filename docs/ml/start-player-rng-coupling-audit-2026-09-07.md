# Start-player / RNG coupling audit

Date: 2026-09-07
Task: START_PLAYER_RNG_COUPLING_AUDIT_01
Tracker: #119 — Post-B2 bounded characterization, dataset scaling, and C0 measurement contract

## Result

~~~text
RNG_COUPLING_CLASSIFICATION=PARTIALLY_COUPLED_WITH_DEFINED_BOUNDARY
~~~

The same explicit engine seed and roster produce the same GameRng cursor and per-player opening library/hand state when only startingPlayerIndex changes. The first public observation still differs because active player, priority, turn order, perspective routing, and the observation digest differ.

The coupling boundary is the first mulligan shuffle. TakeMulligan shuffles the acting player's hand/library through the shared GameRng, and mulligan action order follows turn order. Swapping the starting player therefore binds later RNG segments to different players. After equal numbers of mulligan shuffles, the total cursor is equal but per-player zones can differ.

The first-player draw path does not advance GameRng. The A9 policy has a separate DeterministicPolicyState whose seed formula includes startingPlayerIndex; that policy state must not be conflated with engine GameRng.

## Provenance and scope

~~~text
BASE=417c6a19061d62ed8031cecadce2b5ee4afd8c2b
HEAD=417c6a19061d62ed8031cecadce2b5ee4afd8c2b
ORIGIN_MAIN_AT_START=417c6a19061d62ed8031cecadce2b5ee4afd8c2b
BRANCH=chris/start-player-rng-coupling-audit
WORKTREE=C:\Users\chris\.config\superpowers\worktrees\argentum-engine\start-player-rng-coupling-audit-20260907
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM_MAIN=5021faf88093a93091e4de7914fbe0f411499d58
PRODUCTION_CODE_CHANGED=NO
~~~

This audit did not change RNG implementation, seed semantics, decks, rules, Trajectory V1, PlayerObservationV1, replay semantics, or production code. It did not generate a large dataset or run learner/training work.

## Source audit

### RNG ownership and setup flow

~~~text
GameConfig.seed
 -> GameInitializer.initializeGame
 -> explicit seed or System.nanoTime()
 -> GameState(rng = GameRng.seeded(resolvedSeed))
 -> immutable GameState.rng threaded by GameState.nextRandom
~~~

Primary sources:

- GameRng is immutable SplitMix64 state. nextLong advances one cursor; shuffle uses Fisher-Yates over that cursor; split exists but is not used by GameInitializer setup. Source: mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/model/GameRng.kt:23-105.
- GameState exposes rng: GameRng and nextRandom returns the advanced state. Source: rules-engine/src/main/kotlin/com/wingedsheep/engine/state/GameState.kt:301-310,1009-1019.
- Seed resolution and initial state creation: rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameInitializer.kt:154-162.
- Explicit non-team startingPlayerIndex rotates player IDs without nextRandom: GameInitializer.kt:267-269.
- Unspecified start player shuffles turn order and advances GameRng: GameInitializer.kt:270-273.
- Libraries shuffle in original player/config order: GameInitializer.kt:367-371,416-420.
- Ordinary opening draws run in original player/config order and do not call nextRandom: GameInitializer.kt:373-382,423-466.
- Hand smoothing consumes GameRng, but candidate and remaining-library shuffles also run in config order: GameInitializer.kt:491-553.
- TakeMulligan shuffles the acting player's hand/library through GameRng: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/MulliganHandler.kt:82-157.
- Draw-step execution delegates to DrawCardsExecutor; no GameRng call occurs in that path: rules-engine/src/main/kotlin/com/wingedsheep/engine/core/DrawPhaseManager.kt:145-165.
- Replay stores the actual seed and startingPlayerIndex in ReplaySetup: game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt:230-250; capture occurs in game-server/src/main/kotlin/com/wingedsheep/gameserver/session/GameSession.kt:552-570.

### Separate policy state

The A9 trusted-generation test creates a separate DeterministicPolicyState before the first policy choice and advances its choice ordinal after each choice. Source: gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1TrustedGenerationTest.kt:315-330,372-377.

Its policy seed is:

~~~text
policySeed = engineSeed * 1_000_003
           + startingPlayerIndex * 97_409
           + rosterIdentity * 65_537
~~~

Source: EnvironmentV1TrustedGenerationTest.kt:814-818. The policy state type is a policySeed plus choiceOrdinal; the current policy documentation says the seed is reserved for future seeded tie-breaking, while current choice selection is stable-order based. Source: gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt:50-59.

## Focused test matrix

Test file:

~~~text
gym/src/test/kotlin/com/wingedsheep/gym/StartPlayerRngCouplingAuditTest.kt
~~~

Inputs:

~~~text
SEEDS=0,1,42,987654321
DECKS=docs/ml/curriculum/akiri-v0.1.txt + chevill-v0.1.txt
ROSTER=Akiri/Chevill with stable explicit player IDs
START_VARIANTS=0,1
~~~

| Case | Evidence | Result |
|---|---|---|
| RNG-01 same seed + same setup | Whole initial GameState/events equal for all four seeds | PASS |
| RNG-02 start reversal | GameRng cursor, per-player library/hand IDs, and setup events equal; turn metadata/state differ | PASS |
| RNG-03 hand smoother | Cursor and per-player zones equal across start variants | PASS |
| RNG-04 first public observation | Cursor equal; digest, active player, agent, and perspective routing differ; owner-keyed zone contents equal | PASS |
| RNG-05 first-player draw | Draw leaves GameRng cursor unchanged for both variants | PASS |
| RNG-06 one mulligan per player in engine turn order | Total cursor equal; per-player zones differ after equal shuffle counts | PASS |
| RNG-07 roster reversal separately | Total cursor equal; per-player library assignment differs | PASS |

Focused result:

~~~text
FOCUSED_TESTS=PASS (7/7)
~~~

## Coupling evidence

~~~text
RNG_STATE_EQUAL=YES at post-initialization start-player reversal
RNG_STATE_EQUAL=YES with hand smoothing enabled
RNG_STATE_EQUAL=YES after equal-count mulligan comparison (total cursor)
GAME_STATE_EQUAL=NO for start-player reversal
GAME_STATE_DIFFERENT=YES (turnOrder, activePlayerId, priorityPlayerId, first-player turn counter)
OBSERVATION_EQUAL=NO at the full first public observation
OBSERVATION_DIFFERENT=YES
OBSERVABLE_ZONE_CONTENT_EQUAL=YES when normalized by owner ID
EXACT_RNG_CURSOR_OBSERVABILITY=YES
~~~

The first policy-visible boundary therefore has equal engine RNG state but different game/observation state. The separate A9 policySeed differs by startingPlayerIndex even though the current policy does not sample randomly.

## Phase classification

| Boundary | Engine GameRng | State/observation | Classification |
|---|---|---|---|
| Explicit start assignment | Same cursor | Turn/priority metadata differs | Coupled RNG, divergent metadata |
| Opening shuffle | Same cursor progression | Per-player zones equal | Coupled |
| Ordinary opening draw | No RNG consumption | Hands equal | Coupled |
| Hand smoothing | Same config-order cursor progression | Selected hands equal | Coupled to config order |
| First public observation | Same cursor | Active/agent/perspective/digest differ | Publicly divergent |
| Equal-count mulligans | Same final total cursor | Player zones can differ by turn-order ownership | Partial coupling boundary |
| First-player draw | No RNG advancement | Deterministic draw | Coupled for RNG |
| A9 policy state | Separate seed differs by start variant | Separate policy contract | Not engine-RNG evidence |

## Statistical consequence

~~~text
SAME_SEED_GROUPING_FOR_NATURAL_AGGREGATE_BLOCKING=CONDITIONAL
SPLIT_GROUPING_REMAINS_SEPARATE=YES
~~~

Same-seed blocking is conditionally valid only for measurements explicitly bounded before turn-order-dependent mulligan actions and with separate policy-state controls. It is not valid as a whole-trajectory natural aggregate block across start-player variants. Start-player variants must remain separate for split/leakage grouping, and rare-family confidence claims must not be based on numeric seed blocking.

## Limitations

- Exact engine RNG cursor observability is available as GameState.rng.state, but there is no per-operation draw counter; operation boundaries are source-derived and test-validated.
- The audit uses bounded deterministic initialization and one mulligan per player, not a large soak.
- The first public observation uses the existing GameGymEnv/TrainingObservation seam; no production instrumentation was added.
- Policy RNG is test-harness state, not GameRng. A future policy with actual seeded sampling needs a separate policy-state audit.
- Roster reversal was measured separately from start-player reversal.

## Completion status

~~~text
TASK=START_PLAYER_RNG_COUPLING_AUDIT_01

BASE=417c6a19061d62ed8031cecadce2b5ee4afd8c2b
HEAD=417c6a19061d62ed8031cecadce2b5ee4afd8c2b (source head; delivery commit recorded after verification)
PARENT=SOURCE_HEAD
REMOTE_HEAD=SOURCE_HEAD

PRODUCTION_CODE_CHANGED=NO
TEST_ONLY_CODE_CHANGED=YES
DOC_FILES_CHANGED=YES

DETERMINISTIC_REPEAT_BASELINE=PASS
START_PLAYER_ASSIGNMENT_CONSUMES_RNG=NO (explicit non-team start index)
OPENING_SHUFFLE_COUPLING=YES
OPENING_HAND_COUPLING=YES
MULLIGAN_SETUP_COUPLING=PARTIAL; turn-order shuffle ownership is the boundary
FIRST_PLAYER_DRAW_RNG_EFFECT=NO
PREGAME_RANDOM_SERVICE_COUPLING=YES for hand smoothing in config order; no additional setup RNG service observed
FIRST_POLICY_VISIBLE_RNG_STATE=ENGINE_EQUAL; separate A9 policySeed differs by start variant

RNG_COUPLING_CLASSIFICATION=PARTIALLY_COUPLED_WITH_DEFINED_BOUNDARY

SAME_SEED_GROUPING_FOR_NATURAL_AGGREGATE_BLOCKING=CONDITIONAL
SPLIT_GROUPING_REMAINS_SEPARATE=YES

FOCUSED_TESTS=PASS (7/7)
GIT_DIFF_CHECK=PASS

P1_FINDINGS=0
P2_FINDINGS=0

START_PLAYER_RNG_COUPLING_IMPLEMENTATION_PASS=YES
START_PLAYER_RNG_COUPLING_CODE_REVIEW_PASS=NO
START_PLAYER_RNG_COUPLING_FINAL_ACCEPTANCE_PASS=NO

POST_B2_CHARACTERIZATION_IMPLEMENTATION_PASS=NO
POST_B2_CODE_REVIEW_PASS=NO
POST_B2_FINAL_ACCEPTANCE_PASS=NO

TRAINING_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
~~~

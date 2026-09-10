# History-D final acceptance contract

```text
TASK=HISTORY_D_FINAL_ACCEPTANCE_CONTRACT_DEFINITION_02
CONTRACT_STATUS=RATIFIED
CONTRACT_VERSION=HISTORY_D_FINAL_ACCEPTANCE_V1
BASE=0b7db8cf154ee1bbe4dc3bb0a1e58d26bbf3e92d
BASE_PARENT=b564e7ece69e2ca1044c927d5dc43ec6244ad39e
ORIGIN_MAIN_AT_START=886dc85620690ab83471e02a12897f2508a1d881
UPSTREAM_MAIN_AT_START=3f46367d87c88bcf156a843a9e69fd29e1693872
```

## Purpose and gate state

This document is the authoritative bounded History-D final-acceptance contract from this
definition commit onward. It resolves the previously documented numeric ambiguity; it does not
execute the matrix and does not change runtime, Rules, B0/B1/B2, replay, decks, performance, or
ML code.

```text
VERIFICATION_RUN=NOT_RUN
HISTORY_D_FINAL_ACCEPTANCE_PASS=NO
TRACK_B_HISTORY_COMPLETE=NO
```

The reviewed `b564e7ece...` result remains accepted evidence for one clean bounded witness. It is
not retroactively relabeled as this contract's two-run final acceptance.

## Contract authority and boundaries

The contract composes the existing History-D implementation and structural contracts:

- [`PerspectiveHistoryV1.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/PerspectiveHistoryV1.kt)
  owns the version/schema identity, semantic entries, canonical JSON, digest, and contiguous
  perspective-local ordinals.
- [`PerspectiveHistoryStateV1.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/history/PerspectiveHistoryStateV1.kt)
  owns the episode-scoped pair of perspective histories.
- [`GameGymEnv.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt) appends only
  successful committed strict transitions and exposes typed terminal/interruption state.
- [`PerspectiveHistoryV1Test.kt`](../../gym/src/test/kotlin/com/wingedsheep/gym/PerspectiveHistoryV1Test.kt)
  covers the structural lifecycle, privacy, canonicalization, snapshot, fork, reset, and
  failed-transition invariants.
- [`PostStep3522LockedHistoryFirstBlockerCharacterizationTest.kt`](../../gym/src/test/kotlin/com/wingedsheep/gym/PostStep3522LockedHistoryFirstBlockerCharacterizationTest.kt)
  records the reviewed one-run 4000-step locked witness.
- [Issue #100](https://github.com/chrismaghuhn/argentum-engine/issues/100) and
  [Issue #119](https://github.com/chrismaghuhn/argentum-engine/issues/119) remain B2/data and
  post-B2 planning authorities, not substitutes for this History-D gate.

The contract is deliberately narrower than B2 corpus acceptance. B2's terminal/interrupted
episode distribution, replay-backed publication, and multi-cell corpus matrix remain separate
requirements. Likewise, B0 reliability/soak, B1 performance, and C0/C1 learner work are not
History-D final-acceptance criteria.

## RATIFIED_HISTORY_D_CONTRACT

### Exact workload

Both runs must use the same current accepted implementation and the persisted curriculum files:

```text
decks=docs/ml/curriculum/akiri-v0.1.txt
      docs/ml/curriculum/chevill-v0.1.txt
roster=Akiri vs Chevill
format=Commander
startingLife=40
startingPlayer=Akiri
skipMulligans=true
engineSeed=0
policy=DeterministicExternalPolicy
policySeed=0x41L
maxSteps=4000
semanticEpisodeId=history-d-final-acceptance-v1
```

The deck files are authoritative; no manual deck reconstruction or deck change is part of this
contract.

The perspective mapping is also fixed by the workload:

```text
P1=Akiri / first initialized player
P2=Chevill / second initialized player
```

The same mapping must hold in both runs. The existing `PerspectiveHistoryV1.perspectivePlayerId`
field is part of the canonical contract; it is not normalized away.

### Bounded acceptance

History-D final acceptance is a bounded-prefix gate. A naturally terminal game is not required.
For each of two fresh independent runs, require:

```text
successfulChoices=4000
committedSteps=4000
step3522Crossed=YES
History-D failure=NONE
terminated=false
truncated=true
truncationCause=the configured maxSteps=4000 only
```

The run must not stop early, fail closed, reach a terminal state, or use an alternate stopping
condition. The resulting semantic closure is the explicit horizon interruption, not a fabricated
winner, draw, or terminal outcome.

No `maxSteps=8000` or other horizon extension is implied or authorized by this contract.

### Two-run independence

Run 1 and Run 2 each start a fresh `GameEnvironment`, fresh `GameGymEnv`, fresh `GameState`, fresh
history state, and fresh deterministic policy state with the exact workload above. They must not
share a fork, snapshot continuation, accumulated history, or policy state. Reusing the fixed
`semanticEpisodeId=history-d-final-acceptance-v1` is intentional and does not make the runs the
same runtime episode.

The verification compares the existing full `PerspectiveHistoryV1.canonicalJson()` and
`semanticDigest()` values without normalization or ad-hoc projection. Those canonical values
include the explicitly contracted `semanticEpisodeId` and `perspectivePlayerId`. Raw event/runtime
object IDs, object stamps, and internal witnesses are not compared as model-facing identity.

### Per-perspective determinism

Inspect both player perspectives in both runs. Let `P1` and `P2` denote the two perspective-local
histories; they are not required to equal one another. The required equalities are:

```text
Run1.P1.canonicalHistory == Run2.P1.canonicalHistory
Run1.P2.canonicalHistory == Run2.P2.canonicalHistory
Run1.P1.semanticDigest   == Run2.P1.semanticDigest
Run1.P2.semanticDigest   == Run2.P2.semanticDigest
Run1.P1.perspectivePlayerId == Run2.P1.perspectivePlayerId
Run1.P2.perspectivePlayerId == Run2.P2.perspectivePlayerId
Run1.semanticClosure/result == Run2.semanticClosure/result
```

The semantic closure/result comparison includes the accepted bounded outcome:

```text
stepCount=4000
terminated=false
truncated=true
interruptionReason=HORIZON_REACHED
History-D failure=NONE
```

There is no cross-perspective equality requirement:

```text
Run1.P1 !=/may differ from Run1.P2
Run1.P1 == Run2.P1
Run1.P2 == Run2.P2
```

Canonical histories must continue to satisfy the existing V1 privacy and structural rules:
contiguous ordinals, perspective isolation, no raw `EntityId`, object stamp, hidden value, raw
coordinate, or internal ledger field in model-facing history.

## Final pass predicate

The final gate may be set only when all of the following are true:

1. The accepted A/B/C/D implementation is the exact implementation under verification.
2. Both fresh runs satisfy the bounded acceptance conditions above.
3. Both perspective histories are successfully produced in both runs.
4. The two per-perspective canonical-history and digest equalities pass exactly as specified.
5. The semantic closure/result equality passes and no History-D exception or failure occurs.

The contract-definition commit itself cannot set the final gate:

```text
HISTORY_D_FINAL_ACCEPTANCE_PASS=NO until HISTORY_D_FINAL_ACCEPTANCE_VERIFICATION_03 passes
```

## TRACK_B_HISTORY_COMPLETE rule

```text
TRACK_B_HISTORY_COMPLETE=YES
iff
  the accepted A/B/C/D implementation is in force
  AND the ratified two-run / two-perspective / maxSteps=4000 matrix passes
  AND no History-D failure occurs in either run
  AND both per-perspective canonical histories and semantic digests match across runs
  AND both runs have the required bounded closure
```

Otherwise:

```text
TRACK_B_HISTORY_COMPLETE=NO
```

This document does not set it to `YES`. The rule does not claim universal completeness for every
engine seed, policy seed, roster orientation, or eventual natural game; those require separate
confidence or separately ratified contracts.

## Hard gate versus confidence evidence

### Hard History-D gate

The hard gate is exactly:

```text
1 workload cell
× 2 fresh independent runs
× both perspective histories per run
× maxSteps=4000
```

It requires the exact workload, 4000/4000 committed progress, no History-D failure, bounded
interruption, and per-perspective semantic determinism described above.

### Not part of this hard gate

The following remain optional confidence/soak evidence or belong to another layer:

- additional engine seeds or policy seeds;
- reversed roster orientation or alternate starting player;
- natural terminal-game frequency or a completed-game-only requirement;
- the B2 64-cell/episode corpus matrix and its terminal/interrupted distribution;
- longer horizons such as 8000 steps;
- B0 soak/reliability, B1 timing/scaling, memory, or throughput measurements;
- replay/dataset publication beyond the already accepted B2 contract;
- C0/C1 sequence, learner, BC, RL, self-play, or training experiments.

Additional seeds, policies, orientations, and longer runs may increase confidence only under a
separate authorization. They cannot silently change this hard gate.

## Previously underspecified items, now resolved

```text
bounded prefix vs natural terminal     = bounded prefix accepted
natural terminal required               = NO
additional seeds/policy seeds           = confidence only
additional orientations                = confidence only
perspectives                           = both, compared within same perspective across runs
repeat count                           = 2 fresh independent runs
horizon                                = maxSteps=4000 exactly
semantic episode identity               = history-d-final-acceptance-v1 in both runs
perspective mapping                     = P1 Akiri, P2 Chevill in both runs
B2 terminal/interrupted corpus         = separate B2 requirement
TRACK_B_HISTORY_COMPLETE                = derived only from this passing matrix + accepted A/B/C/D
```

## NEXT_SINGLE_TASK

```text
HISTORY_D_FINAL_ACCEPTANCE_VERIFICATION_03
```

That task may execute only the ratified matrix, report both perspective results and exact semantic
digests, and set `HISTORY_D_FINAL_ACCEPTANCE_PASS=YES` plus
`TRACK_B_HISTORY_COMPLETE=YES` only if the complete predicate passes. It must not add event
families, extend the horizon, optimize, or start C0/C1/training work.

```text
PERFORMANCE_DIAGNOSTIC_PASS=BLOCKED
PERFORMANCE_OPTIMIZATION_AUTHORIZED=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
RL_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
```

```text
STOP_FOR_EXACT_SHA_REVIEW=YES
```

# B1 Exact-Pair Replay V5 Gate

This report repairs the B1 exact-pair acceptance bridge only. It does not change production replay
code, replay semantics, the Gym observation contract, or any performance behavior. The earlier B1
Task-6 report recorded the precondition failure; this report records the focused harness repair and
the resulting executable V5 gate.

## Checkpoint and classification

```text
BASE_ORIGIN_MAIN=d29bc691e63d867147dc8534c5129c9e3a0d3fcf
UPSTREAM_MAIN=36c5a0c2a2c688e2a814940495ce64c55baced25
BRANCH=chris/b1-replay-v5-exact-pair-gate

ROOT_CAUSE_CLASSIFICATION=A. ACCEPTANCE_HARNESS_VERSION_STALE

PRODUCTION_COMPACT_REPLAY_CURRENT_VERSION=5
EXACT_PAIR_BRIDGE_VERSION_BEFORE=4
EXACT_PAIR_BRIDGE_VERSION_AFTER=loaded CompactReplay.CURRENT_VERSION (5)

PRODUCTION_COMPACT_REPLAY_SUPPORT=YES
EXPLICIT_V3_PRODUCTION_SUPPORT=YES
V5_CODEC_EXISTING_TEST=PASS
V5_RECONSTRUCTION_EXISTING_TEST=PASS
REPLAY_FINGERPRINT_V5_SUPPORT=PASS
```

Production `GameSession` initializes new recordings from `CompactReplay.CURRENT_VERSION`, and the
production flush/finalization paths carry the sampled replay version through to `CompactReplay`.
`CompactReplay.CURRENT_VERSION` is 5; its constructor explicitly requires v5 for
`PaymentStrategy.ExplicitV3`. `ReplayFingerprint.of(state, replayVersion)` dispatches both v4 and
v5 to the existing v4 state-fingerprint semantics. No production replay file was changed.

## RED characterization

Command:

```powershell
$env:KOTEST_FILTER_TESTS = '*exact-pair replay gate replays complete semantic trajectories*'
.\gradlew.bat :gym:environmentV1AcceptanceTest --tests "com.wingedsheep.gym.EnvironmentV1ExactPairAcceptanceTest" --console=plain --rerun-tasks
```

Result on the unmodified current `origin/main`:

```text
RED_EXACT_PAIR=YES
39 tests completed, 1 failed, 38 skipped
cases=2; authoritativeCompactReplay=0; failures=2
failure=Akiri-vs-Chevill/seed=0/starting=0: PaymentStrategy.ExplicitV3 requires CompactReplay v5 or newer
failure=Chevill-vs-Akiri/seed=0/starting=1: PaymentStrategy.ExplicitV3 requires CompactReplay v5 or newer
```

Both traces completed with 2,001 frames and 2,000 decisions before the bridge constructed the
replay. The first captured `ExplicitV3` action in each trace was identified with temporary,
test-only diagnostic output and that diagnostic was removed before the fix:

| Case | First action index (0-based) | Action | Captured payment evidence |
| --- | ---: | --- | --- |
| Akiri-vs-Chevill, seed 0, start 0 | 163 | `CastSpell`, card `e189`, target player `e0` | `e106/GREEN Forest` plus `e134/BLACK` |
| Chevill-vs-Akiri, seed 0, start 1 | 8 | `CastSpell`, card `e157`, no target | `e105/WHITE Plains` |

The exact bridge at `EnvironmentV1ExactPairAcceptanceTest.kt` passed literal version `4`; the
loaded production replay class exposed `CURRENT_VERSION=5`. This is the failing seam, not a
production codec or reconstruction failure.

The permanent regression is at the existing exact-pair bridge seam: the bridge reads the loaded
`CompactReplay.CURRENT_VERSION`, and `verify()` asserts that the created replay's version equals the
same loaded runtime value. The gate also reports the created version for every authoritative case.

## GREEN exact-pair gate

Command:

```powershell
$env:KOTEST_FILTER_TESTS = '*exact-pair replay gate replays complete semantic trajectories*'
.\gradlew.bat :gym:environmentV1AcceptanceTest --tests "com.wingedsheep.gym.EnvironmentV1ExactPairAcceptanceTest" --console=plain --rerun-tasks
```

Result after the test-only bridge fix:

```text
GREEN_EXACT_PAIR=YES
39 tests completed, 0 failed, 38 skipped
cases=2; authoritativeCompactReplay=2; failures=0
compactReplay=Akiri-vs-Chevill/seed=0/starting=0; version=5; codecRoundTrip=true; fidelity=EXACT; frames=2001; checkpoints=101
compactReplay=Chevill-vs-Akiri/seed=0/starting=1; version=5; codecRoundTrip=true; fidelity=EXACT; frames=2001; checkpoints=101
```

The exact-pair path reached the existing `ReplayCodec`, `ReplayFingerprint`, and
`ReplayReconstructor` implementation. No semantic divergence or tail/checkpoint mismatch occurred.
The two traces use the locked Akiri/Chevill configurations, seed 0, the existing deterministic
external policy, and the existing 2,000-action horizon.

## Replay semantic audit

```text
COMPACT_REPLAY_VERSION=5
EXPLICIT_V3_ACTION_CARRIER=PRESERVED
PAYMENT_PLAN_V3_ORDERING=PRESERVED
ACTIVATION_COST_LEDGER_REFERENCES=PRESERVED
CODEC_ROUND_TRIP=EXACT
REPLAY_FINGERPRINT_SEMANTICS=UNCHANGED (v5 uses existing v4 state semantics)
CHECKPOINT_CADENCE=UNCHANGED
REPLAY_RECONSTRUCTION=EXACT
TAIL_VERIFICATION=PRESERVED
ACTION_ORDER=UNCHANGED
RNG_SEED=UNCHANGED
CARD_PINS=UNCHANGED
YIELD_MUTATIONS=UNCHANGED
DECISION_CONTINUATIONS=UNCHANGED
```

The bridge does not hard-code a new schema version, duplicate replay code, add a Gym-to-server
production dependency, or alter fingerprint bytes. The captured `PaymentStrategy.ExplicitV3`
actions and their ordered `PaymentPlanV3` data are carried into the existing v5 codec and
reconstructor unchanged.

## Verification

The repository `just` attempts were made first:

```text
just test-gym   -> BLOCKED: WSL CreateProcessCommon: execvpe(/bin/bash) failed
just test-server -> BLOCKED: WSL CreateProcessCommon: execvpe(/bin/bash) failed
JUST_WRAPPER=BLOCKED
```

Native JDK-21 fallback results on the final worktree:

```text
FOCUSED_REPLAY_TESTS=PASS (included in the full game-server suite)
GYM_CONTRACT_TESTS=PASS (included in the full Gym suite)
B0_HARNESS=PASS (included in the full Gym suite)
GYM_TESTS=PASS; 432 tests, 0 failures, 1 expected skip
GAME_SERVER_TESTS=PASS; 557 tests, 0 failures, 13 expected skips
GIT_DIFF_CHECK=PASS
NATIVE_FALLBACK=PASS
```

Commands executed for the module gates:

```powershell
.\gradlew.bat :gym:test --console=plain --rerun-tasks
.\gradlew.bat :game-server:test --console=plain --rerun-tasks
```

The focused production replay command was also run before the bridge change and passed all selected
`CompactReplayV5PaymentTest`, `PaymentPlanReplayTest`, `CompactReplayReconstructionTest`,
`ReplayDecisionContinuationTest`, `ReplayDecisionNonceCanonicalizationTest`, and
`ReplayFingerprintV3Test` cases. The subsequent full `:game-server:test` rerun passed these same
classes on the final worktree.

The existing B1 profiler does not expose an isolated CompactReplay/ReplayFingerprint timing
counter; it measures public semantic capture/verify instead. No new production instrumentation was
added for this task:

```text
REPLAYFINGERPRINT_OVERHEAD=NOT_RUN
REASON=existing B1 harness has no isolated CompactReplay timing boundary; task scope forbids new profiler hooks
```

## Scope audit

```text
PRODUCTION_FILES_CHANGED=0
PRODUCTION_REPLAY_SEMANTICS_CHANGED=0
REPLAY_SCHEMA_CHANGED=0
FINGERPRINT_SEMANTICS_CHANGED=0
PAYMENT_SEMANTICS_CHANGED=0
RULES_CHANGED=0
GYM_OBSERVATION_CHANGED=0
DECKS_CHANGED=0

DATA_TRUSTED=NO
B1_REPLAY_GATE_PASS=YES
B1_FINAL_ACCEPTANCE=NOT_CLAIMED
```

Only `gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExactPairAcceptanceTest.kt` and this
test/acceptance report are in scope for the repair. The previous B1 performance and cache work, the
canonicalizer optimization, and all later Deep Performance Audit candidates are untouched.

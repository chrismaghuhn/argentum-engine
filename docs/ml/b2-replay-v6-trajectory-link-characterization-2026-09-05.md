# B2 — Replay v6 to Trajectory Link Characterization

Date: 2026-09-05
Task: `B2_A8_REPLAY_V6_TRAJECTORY_LINK_CHARACTERIZATION_01`

Status: **blocker confirmed; characterization only**.

This report does not change production code, `TrajectoryV1`, `CompactReplay`, any schema/version,
locked decks, goldens, frozen baselines, or A9 state. It does not reinterpret a v6 replay as v5.

## Source boundary

```text
BASE=47d9e3660bed342e902a4fda388d601b4631538b
ORIGIN_MAIN=47d9e3660bed342e902a4fda388d601b4631538b
UPSTREAM_MAIN=5021faf88093a93091e4de7914fbe0f411499d58
branch=chris/b2-replay-v6-trajectory-link-characterization-20260905
worktree=C:/Users/chris/.config/superpowers/worktrees/argentum-engine/b2-replay-v6-trajectory-link-characterization-20260905
```

The base is merged A8 PR #131. The starting worktree was clean and was created directly from the
current fork `origin/main`.

## Version contract findings

```text
CURRENT_COMPACT_REPLAY_VERSION=6
NEW_SESSION_REPLAY_VERSION=6

TRAJECTORY_LINK_ACCEPTED_REPLAY_VERSION=5
TRAJECTORY_LINK_ACCEPTED_REPLAY_SCHEMA_IDENTITY=argentum-compact-replay@v5

REPLAY_CONTENT_IDENTITY_VERSION_OPEN=positive replayVersion; equality is required only against A4
A4_BINDING_REQUIRES_VERSION_EQUALITY=YES
A6_ADMISSION_REQUIRES_VERSION_EQUALITY=YES
```

The current source evidence is:

* `CompactReplay.CURRENT_VERSION` is `6`. `ReplayRecordingSnapshot` defaults new recordings to that
  version.
* A pending `ManaSourcesSelectedResponse.paymentPlan` requires `CompactReplay` version 6 or newer.
  A v5 `CompactReplay` rejects that action shape during construction.
* `ReplayContentIdentityV1` permits any positive replay version. `ReplayVerificationBindingV1`
  requires its content identity version to equal `VerifiedReplayVerification.replayVersion`.
* `ReplayTrajectoryBindingV1` accepts the v6 content identity, v6 exact verification, and chosen
  input binding when those bindings identify the same content and action range.
* `CompactReplayLinkV1` defaults to and requires replay version 5 and
  `argentum-compact-replay@v5`. A direct v6 link construction rejects with
  `Unsupported linked replay version: 6`.
* `EnvironmentIdentityV1` also defaults to and requires the v5 replay schema identity.
* `TrajectoryV1Admission` compares the trajectory link version with both the content identity and
  A4 verification version. A v5-labelled trajectory link paired with v6 evidence is quarantined as
  `REPLAY_VERSION_MISMATCH`.

## Existing-test audit

```text
EXISTING_TRAJECTORY_TESTS_USE_REAL_V6=NO
EXISTING_A6_ADMISSION_TESTS_USE_REAL_V6=NO
EXISTING_PENDING_PAYMENT_REPLAY_TEST_REACHES_TRAJECTORY_ADMISSION=NO
```

The existing `TrajectoryV1ContractTest` and `TrajectoryV1WriterTest` use synthetic/default v5
trajectory-link metadata. Their admission tests prove the v5 DTO contract, not the current v6
CompactReplay path.

`PendingPaymentReplayV6Test` is a real session witness: it reaches pending `PaymentPlanV3`, records
version 6, round-trips the CompactReplay codec, computes the v6 content identity, and obtains
`ReplayFidelity.EXACT` from `ReplayReconstructor`. It stops before
`GymReplayFrameSource.verifyTrajectoryBinding()` is connected to a `TrajectoryV1` envelope or
`TrajectoryV1Admission`.

`ReplayChosenInputBindingIntegrationTest` exercises a real v6 `GymReplayFrameSource` binding, but
does not construct or admit a `TrajectoryV1`. Separate v6 replay evidence and separate v5
trajectory evidence therefore do not prove the integrated chain.

## Minimal characterization

Added test-only file:

```text
gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/trajectory/ReplayV6TrajectoryLinkCharacterizationTest.kt
```

The test uses the existing valid A5 fixture and does not fabricate a game rule or state. It:

1. changes the fixture's content identity and exact A4 verification to replay version 6;
2. constructs a v6 `ReplayVerificationBindingV1`, `ReplayChosenInputBindingV1`, and
   `ReplayTrajectoryBindingV1`, proving those neutral bindings accept the same v6 evidence;
3. attempts the corresponding current `CompactReplayLinkV1` and records the exact v5-only rejection;
4. pairs the v6 binding with the fixture's v5-labelled trajectory and proves A6 quarantines it as
   `REPLAY_VERSION_MISMATCH`.

This is intentionally a green characterization of the current rejection so the ordinary module
suite remains green:

```text
TARGET_DESIRED_BEHAVIOR=RED
CURRENT_REJECTION_REPRODUCED=PASS
FOCUSED_TEST=PASS
```

Focused command:

```text
./gradlew.bat :gym-trainer:test --tests "com.wingedsheep.gym.trainer.trajectory.ReplayV6TrajectoryLinkCharacterizationTest" --rerun-tasks --console=plain
```

Result: one test passed, zero failures, zero skips, `BUILD SUCCESSFUL`.

## Surrounding regression

Native Windows Gradle was used for the required module gates because the repository `just` wrapper
cannot start on this host when `/bin/bash` is unavailable. This is a tooling limitation, not a
changed production behavior.

```text
./gradlew.bat :gym-trainer:test --rerun-tasks --console=plain
  PASS: 138 tests, 0 failures, 1 configured skip; BUILD SUCCESSFUL

./gradlew.bat :game-server:test --rerun-tasks --console=plain
  PASS: 609 tests, 0 failures, 13 configured skips; BUILD SUCCESSFUL

git diff --check
  pending until final scope check
```

The `gym-trainer` skip is the existing Windows symlinked-shard-ancestor test. The `game-server`
skips are the existing benchmark/database integration exclusions. They remain `SKIPPED`, not
individual passes.

## Negative down-label proof

```text
V6_TRAJECTORY_LINK_ATTEMPT=REJECTED
DOWNLABEL_V6_TO_V5_ALLOWED=NO
CURRENT_REJECTION_REPRODUCED=PASS
```

The pending payment contract makes the down-label invalid: a `SubmitDecision` carrying
`ManaSourcesSelectedResponse(paymentPlan = PaymentPlanV3(...))` requires CompactReplay v6 or newer.
Calling the same replay v5 would make the replay input stream illegal under the CompactReplay
constructor. Independently, retaining a v5 trajectory link while supplying v6 A4/content/chosen
input evidence fails the admission equality check. Metadata lying cannot make the replay/trajectory
chain valid.

## Future seam audit — no fix selected

A separately authorized fix would need to make an explicit, compatible decision about:

* which replay versions `CompactReplayLinkV1` accepts;
* the replay schema identity for v6 and its relation to the link schema;
* whether and how the replay schema participates in `EnvironmentIdentityV1` and semantic episode
  identity;
* the A6 equality checks between link, content identity, and A4 verification;
* writer/reader and manifest compatibility;
* continued acceptance of historical v5 trajectories;
* real v6 pending-payment replay admission;
* fail-closed behavior for unknown future replay versions.

No production fix or schema decision is made here.

## Scope and status

```text
BLOCKER=YES
BLOCKER_CLASS=REPLAY_TRAJECTORY_VERSION_BINDING_GAP
A8_TRAJECTORY_REPRESENTATION_CLAIM_INVALIDATED=YES
A9_BLOCKED=YES
PRODUCTION_CODE_CHANGED=NO
PRODUCTION_CHANGE_REQUIRED=YES (future fix task; not implemented here)
LOCKED_DECKS_CHANGED=NO
GOLDENS_CHANGED=NO
FROZEN_BASELINE_REBLESSED=NO
A9_STARTED=NO
DATASET_GENERATION_RUN=NO
DATA_TRUSTED=NO
C0_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

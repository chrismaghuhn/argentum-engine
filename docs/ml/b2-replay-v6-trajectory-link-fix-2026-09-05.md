# B2 — Replay v6 to Trajectory Link Fix

Date: 2026-09-05
Task: `B2_A8_REPLAY_V6_TRAJECTORY_LINK_FIX_01`

Status: **implementation complete; independent review and final acceptance pending**.

This fix follows the RED characterization in commit
`50a5895df76d32f6d9a8f96645d25003cad44ce0`. The standalone fix commit is the one immediately after
that characterization in this branch; its exact SHA is recorded in the final task report after Git
assigns it. No previous commit was amended or squashed.

## Contract change

The outer contracts remain V1:

```text
TRAJECTORY_V1_VERSION=1
COMPACT_REPLAY_LINK_V1_VERSION=1
DATASET_MANIFEST_V1_VERSION=1
```

The trajectory package now uses one explicit closed mapping:

```text
5 ↔ argentum-compact-replay@v5
6 ↔ argentum-compact-replay@v6
```

```text
SUPPORTED_TRAJECTORY_REPLAY_VERSIONS={5,6}
CURRENT_TRAJECTORY_REPLAY_VERSION=6
```

`CompactReplayLinkV1` validates the exact version/schema pair. Arbitrary positive versions,
cross-pairs, and unknown future identities remain rejected. New link and environment defaults are
the v6 pair. Historical v5 pair values remain accepted when explicitly supplied.

`EnvironmentIdentityV1` accepts only the two known replay schema identities. `TrajectoryV1Validator`
now explicitly rejects an environment replay schema that disagrees with the episode's replay-link
schema using the existing `REPLAY_LINK_INVALID` reason. A6 version equality remains strict and
unchanged:

```text
trajectory link replayVersion
== replay content identity replayVersion
== A4 verification replayVersion
== chosen-input content replayVersion
```

The v5/v6 environment schema remains part of `EnvironmentIdentityV1.identityDigest()`, so v5 and v6
semantic episode identities remain distinct.

## Regression coverage

`ReplayV6TrajectoryLinkCharacterizationTest` was turned from the current-rejection characterization
into the desired v6 acceptance regression. `TrajectoryReplayVersionCompatibilityTest` covers:

```text
(5,@v5) accepted
(6,@v6) accepted
(5,@v6) rejected
(6,@v5) rejected
(4,anything) rejected
(7,anything) rejected
unknown future identity rejected
environment/link schema mismatch rejected at A5
strict v5/v6 A6 equality
v5 and v6 A5/A6 admission
v5 and v6 writer → manifest/shard → A7 reader
v5 and v6 storage JSON round-trip
distinct v5/v6 environment and semantic episode identities
```

The storage tests use temporary directories only. No dataset shard is persisted in the repository.
The historical v5 `validFixture()` now states its v5 pair explicitly instead of inheriting the new
current v6 default.

## Evidence

```text
FOCUSED_TRAJECTORY_TESTS=PASS (8 tests, 0 failures, 0 skips)
REAL_PENDING_PAYMENT_V6_REPLAY=PASS (PendingPaymentReplayV6Test)
REPLAY_CHOSEN_INPUT_V6_BINDING=PASS (ReplayChosenInputBindingIntegrationTest)

V6_A5_VALIDATION=PASS
V6_A6_ADMISSION=PASS
V6_WRITER_PUBLICATION=PASS
V6_A7_READER=PASS
V5_BACKWARD_COMPATIBILITY=PASS
V5_WRITER_READER=PASS
V5_STORAGE_ROUNDTRIP=PASS
V6_STORAGE_ROUNDTRIP=PASS
V5_V6_ENVIRONMENT_IDENTITY_DISTINCT=PASS
V5_V6_SEMANTIC_EPISODE_ID_DISTINCT=PASS
```

Required module gates were executed with native `gradlew.bat` on Windows:

```text
./gradlew.bat :gym-trainer:test --rerun-tasks --console=plain
  PASS: 145 tests, 0 failures, 1 configured skip

./gradlew.bat :game-server:test --rerun-tasks --console=plain
  PASS: 609 tests, 0 failures, 13 configured skips

./gradlew.bat :gym:test --rerun-tasks --console=plain
  PASS: 553 tests, 0 failures, 6 configured skips

git diff --check
  PASS
```

The configured skips remain `SKIPPED`: one Windows symlinked-shard-ancestor test in
`gym-trainer`, benchmark/replay/database exclusions in `game-server`, and the existing B1
measurement/isolation exclusions in `gym`. `just` remains unavailable on this host before Gradle
because `/bin/bash` is missing; native Gradle results are reported separately.

## Scope

```text
PRODUCTION_CHANGED_FILES=
  gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt

TEST_CHANGED_FILES=
  gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/trajectory/ReplayV6TrajectoryLinkCharacterizationTest.kt
  gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryReplayVersionCompatibilityTest.kt
  gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1WriterTest.kt

DOC_CHANGED_FILES=
  docs/ml/b2-replay-v6-trajectory-link-fix-2026-09-05.md
```

No changes were made to CompactReplay semantics, payment execution, Rules, Gym observation/domain
contracts, outer Trajectory V1 schema, Dataset V1 schema, locked decks, goldens, or the frozen
baseline. `TrajectoryV1Writer`, `TrajectoryV1Reader`, `TrajectoryV1Manifest`,
`TrajectoryV1ShardValidator`, NDJSON framing, checksums, publication atomicity, and quarantine
layout required no production changes.

```text
FROZEN_BASELINE_REBLESSED=NO
GOLDENS_CHANGED=NO
LOCKED_DECKS_CHANGED=NO
A8_FINAL_ACCEPTANCE_PASS=NO
A9_STARTED=NO
DATASET_GENERATION_RUN=NO
DATA_TRUSTED=NO
C0_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

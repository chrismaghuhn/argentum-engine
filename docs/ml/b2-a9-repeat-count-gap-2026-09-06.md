# B2 A9 repeat-count chosen-input gap

Date: 2026-09-06
Task: `B2_A9_FRESH_RESTART_AFTER_PR134_01`

## Gate and fresh-start provenance

Gate 0 was verified against the repository and GitHub authority before the
restart:

```text
PR_134_MERGED=YES
PR_134_HEAD=370c63f69a31fcfa5ded446949818ac3462c0701
PR_134_HOSTED_CI_PASS=YES
PR_134_COVERAGE=SKIPPED_BY_WORKFLOW
ORIGIN_MAIN=e74d0097d59c0ade48b86269073e3100fa767868
A8_FINAL_ACCEPTANCE_PASS=YES
```

The generation worktree was new and clean at the verified main head:

```text
BASE=e74d0097d59c0ade48b86269073e3100fa767868
BRANCH=chris/b2-a9-fresh-restart-after-pr134-20260906
WORKTREE=C:/Users/chris/.config/superpowers/worktrees/argentum-engine/b2-a9-fresh-restart-after-pr134-20260906
EPISODE_ORDINAL_START=0
PREVIOUS_A9_WORKTREE_REUSED=NO
PREVIOUS_A9_OUTPUT_REUSED=NO
PREVIOUS_A9_STAGING_REUSED=NO
```

The primary schedule was the requested 64 jobs: four roster/start cells,
each with engine seeds `0..15`. The run stopped at the first new semantic
blocker, so no bounded extension was attempted.

```text
POLICY_IDENTITY=b2-a9-deterministic-external-policy@v1
POLICY_RNG_IDENTITY=explicit-seed/kotlin-policy-state-v1
POLICY_SOURCE_IDENTITY=EnvironmentV1ExternalPolicy.kt@sha256:3BB2A24C3BE0EADF5EB0D322278CE565207952E7F6C5F8B292F714F04BDEADBB
REPLAY_VERSION=6
REPLAY_SCHEMA_IDENTITY=argentum-compact-replay@v6
MAX_STEPS=2000
```

Locked deck identities and the current locked-card definition identity were
verified before the run:

```text
AKIRI_DECK_SHA256=0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6
CHEVILL_DECK_SHA256=D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2
CARD_DEFINITION_IDENTITY=3C3C2DF4993D875D1239F49D4D3DACF059D8842BC2A6E0D03DDF31CDB7901E23
```

## First blocker

The real run executed three live locked-pair jobs from ordinal 0. The first
two jobs reached the horizon and were admitted into temporary staging. The
third job reached the real replay boundary at replay action `1773`. Its A4
`GymReplayFrameSource.verifyTrajectoryBinding()` failed while rebuilding the
chosen-input binding:

```text
Replay chosen-input binding did not cover every replay action:
public replay boundary at action 1773 failed:
Chosen action payload has no complete stored-domain validator for: repeatCount
```

The current source path is:

```text
ActionPayloadRequirements
  -> adds repeatCount when maxRepeatableActivations is published
EnvironmentV1ExternalPolicy
  -> chooses the public actionSemantics.repeatCount
GymReplayFrameSource.verifyTrajectoryBinding
  -> ChosenSemanticActionV1.fromRecordedAction
  -> StoredActionPayloadValidator
  -> repeatCount has no validator
```

This is a reusable public chosen-input contract gap, not a Rules legality
failure and not a private-state policy failure. `repeatCount` is absent from
the current `fieldsWithCompleteStoredDomainValidation` set in
`gym/src/main/kotlin/com/wingedsheep/gym/contract/ChosenSemanticInput.kt`.
No production fix was attempted.

## Partial run evidence

```text
A9_OUTPUT_ROOT=C:/Users/chris/AppData/Local/Temp/argentum-b2-a9-10010565591536427871
FINAL_DATASET_DIRECTORY=NOT_CREATED
TARGET_EPISODES=64
ATTEMPTED_EPISODES=3
GENERATED_EPISODES=2
TRUSTED_EPISODES=2
FAILED_EPISODES=1
QUARANTINED_EPISODES=0
GAME_TERMINAL_COUNT=0
INTERRUPTED_COUNT=2
FAILED_COUNT=1
FIRST_FAILURE=ordinal 2; seat0 Akiri; starter Akiri; engine seed 2; replay action 1773
```

The two complete staged trajectories were both `INTERRUPTED` at 2,000
actions. Their temporary shard files were:

```text
publisher/.staging/dataset-4023510341012056998/shards/shard-000000-9e519dfd42844541c35060c0b4f56135881b5ae4aa949edb1f3327811b364868.ndjson
bytes=111190308; episodeOrdinal=0; lines=2002
publisher/.staging/dataset-4023510341012056998/shards/shard-000001-fe2d6d40ad33a5643ff0b108d24c2484f7b26417126c1223463f66e1a6130822.ndjson
bytes=92596249; episodeOrdinal=1; lines=2002
```

These are staging artifacts only. There is no finalized manifest, immutable
dataset directory, or trusted A9 dataset.

For the two completed jobs:

```text
REPLAY_EXACT_EPISODES=2
REPLAY_DIVERGED_EPISODES=0
REPLAY_INCOMPLETE_EPISODES=0
A5_VALID_EPISODES=2
A6_ADMITTED_EPISODES=2
A6_QUARANTINED_EPISODES=0
```

The failing job did not produce an admitted trajectory. The failure is counted
as a chosen-input binding failure, not as a replay divergence.

```text
UNSUPPORTED_DIAGNOSTICS=0
NATIVE_FALLBACKS=0
PUBLIC_CHOICE_REJECTIONS=0
CHOSEN_NOT_IN_DOMAIN=0
UNSUPPORTED_REACHABLE_FAMILIES=1 (repeatCount chosen-input validator)
UNKNOWN_DECISION_FAMILIES=NOT_FINALIZED
DYNAMIC_PENDING_FAMILIES=PARTIAL_ONLY; final matrix not reached
DYNAMIC_ACTION_KINDS=PARTIAL_ONLY; final matrix not reached
```

## Minimal RED characterization

`gym/src/test/kotlin/com/wingedsheep/gym/contract/ChosenSemanticRepeatCountGapTest.kt`
constructs a valid stored `ActivateAbility` candidate whose public semantic
payload requires `repeatCount=2`, then passes a recorded
`ActivateAbility(repeatCount=2)` through the production
`ChosenSemanticActionV1.fromRecordedAction` path. It proves the exact current
rejection without a live state, registry, solver, or fabricated Rules state:

```text
TARGET_DESIRED_BEHAVIOR=RED
CURRENT_REJECTION_REPRODUCED=PASS
FOCUSED_CHARACTERIZATION=PASS (1/1)
REJECTION=Chosen action payload has no complete stored-domain validator for: repeatCount
```

## Publication and later gates

The stop occurred before finalization, so these gates were not run:

```text
DATASET_ID=NOT_CREATED
MANIFEST_CONTENT_DIGEST=NOT_CREATED
MANIFEST_EPISODE_COUNT=NOT_CREATED
MANIFEST_DECISION_COUNT=NOT_CREATED
SHARD_COUNT=PARTIAL_STAGING_ONLY (2)
A7_PREFLIGHT=NOT_RUN
A7_STREAM_EPISODES=NOT_RUN
SERIALIZED_PRIVACY_SCAN=NOT_RUN
DETERMINISM_SPOTCHECK=NOT_RUN
```

The frozen shard bounds used by the temporary composition root were:

```text
MAX_SHARD_BYTES=268435456
MAX_EPISODES_PER_SHARD=1
```

No full A9 acceptance dataset is claimed. The next action is a separate
generic `repeatCount` public-domain/chosen-input characterization and fix
review. It must not be fixed opportunistically in this restart task.

## Status

```text
A9_HEAVY_GATE=FAIL/BLOCKED
A9_IMPLEMENTATION_PASS=NO
A9_HOSTED_CI_PASS=NOT_RUN
A9_CODE_REVIEW_PASS=NO
A9_FINAL_ACCEPTANCE_PASS=NO
B2_FINAL_ACCEPTANCE_PASS=NO
DATA_TRUSTED=NO
C0_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
A9_GENERATION_RESUMED=NO
DATASET_GENERATION_RUN=NO (no finalized dataset)
PRODUCTION_CODE_CHANGED=NO
LOCKED_DECKS_CHANGED=NO
GOLDENS_CHANGED=NO
FROZEN_BASELINE_REBLESSED=NO
PR_CREATED=NO
```

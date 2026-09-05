# B2 A9 bounded trusted generation — blocker characterization

Date: 2026-09-05
Task: `B2_A9_BOUNDED_TRUSTED_GENERATION_01`

## Gate and provenance

Gate 0 was re-verified against the repository authority before the A9 attempt:

```text
PR_132_MERGED=YES
PR_132_HEAD=ad1ed9a0c34be55abf7c82f622abfe7830ea6b48
PR_132_HOSTED_CI_PASS=YES
A8_FINAL_ACCEPTANCE_PASS=YES

BASE=6b7e286788a7b09c7cb0e8ca575121d08d5efff2
ORIGIN_MAIN=6b7e286788a7b09c7cb0e8ca575121d08d5efff2
UPSTREAM_MAIN=5021faf88093a93091e4de7914fbe0f411499d58
BRANCH=chris/b2-a9-bounded-trusted-generation-20260905
WORKTREE=C:/Users/chris/.config/superpowers/worktrees/argentum-engine/b2-a9-bounded-trusted-generation-20260905
```

The branch was created from the merged A8 main and the worktree was clean at
the start of the A9 attempt. The accepted PR #132 head and merge commit were
checked with `gh pr view` and `gh pr checks`; nine checks succeeded and the
configured coverage check was skipped.

The locked-pair provenance used by the existing Environment V1 acceptance
harness is:

```text
AKIRI_DECK_SHA256=0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6
CHEVILL_DECK_SHA256=D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2
CARD_DEFINITION_IDENTITY=3C3C2DF4993D875D1239F49D4D3DACF059D8842BC2A6E0D03DDF31CDB7901E23
```

## Bounded-generation result

The dedicated A9 generator was started with the frozen 64-episode primary
schedule, `maxSteps=2000`, CompactReplay v6, and the accepted observation-only
deterministic external policy. It reached the first real exact-pair public
activated-ability choice in ordinal 0, where the durable chosen-action
conversion failed. No episode was admitted or finalized.

```text
A9_OUTPUT_ROOT=C:/Users/chris/AppData/Local/Temp/argentum-b2-a9-7200937717703386497
FINAL_DATASET_DIRECTORY=NOT_CREATED

TARGET_EPISODES=64
GENERATED_EPISODES=0
TRUSTED_EPISODES=0
FAILED_EPISODES=1
QUARANTINED_EPISODES=0

GAME_TERMINAL_COUNT=0
INTERRUPTED_COUNT=0
FAILED_COUNT=1

ROSTER_START_MATRIX=NOT_REACHED_BEYOND_ORDINAL_0; first cell seat0 Akiri/starter Akiri
ENGINE_SEEDS=0..15 frozen schedule declared; execution stopped at ordinal 0
POLICY_SEEDS=ordinal 0 seed 0 start 0 seat0 Akiri = 4259905

POLICY_IDENTITY=b2-a9-deterministic-external-policy@v1
POLICY_RNG_IDENTITY=explicit-seed/kotlin-policy-state-v1
POLICY_SOURCE_IDENTITY=EnvironmentV1ExternalPolicy.kt@sha256:0A30BE612470CA10B280793DAEBBE17831771891BE9F899BFC6C4710ECD01D0A

REPLAY_VERSION=6
REPLAY_SCHEMA_IDENTITY=argentum-compact-replay@v6
REPLAY_EXACT_EPISODES=0
REPLAY_DIVERGED_EPISODES=0
REPLAY_INCOMPLETE_EPISODES=0

A5_VALID_EPISODES=0
A6_ADMITTED_EPISODES=0
A6_QUARANTINED_EPISODES=0
```

`FAILED_EPISODES=1` records the failed schedule slot; `GENERATED_EPISODES=0`
means that no complete trajectory was produced, admitted, or published.
The external output directory contains only the frozen schedule and staging
location; it contains no finalized manifest, shard, or dataset artifact.

## Confirmed blocker

The public action contract advertises `costPayment` for an `ActivateAbility`
and the accepted external policy can construct a source-bound or published-
domain-backed `AdditionalCostPayment`. The A9 attempt then calls the real
`ChosenSemanticActionV1.fromRecordedAction` path with that Rules action.

The current durable validator rejects the resulting public field:

```text
java.lang.IllegalArgumentException:
Chosen action payload has no complete stored-domain validator for: costPayment
```

The rejection is raised by `StoredActionPayloadValidator` in
`gym/src/main/kotlin/com/wingedsheep/gym/contract/ChosenSemanticInput.kt`.
Its closed `fieldsWithCompleteStoredDomainValidation` set contains targets,
X, payment strategy, mana color, attack declarations, and blocker
declarations, but not `costPayment`. This is a production contract seam, not
an invented test field or a Rules fixture error: the public producer and
external policy both explicitly use `costPayment`, and the first A9 run
reached it through the real locked-pair environment.

```text
A9_BLOCKER=YES
BLOCKER_CLASS=PUBLIC_CHOSEN_INPUT_COST_PAYMENT_GAP
UNSUPPORTED_REACHABLE_FAMILIES=1 (costPayment chosen-input representation gap)
PRODUCTION_CHANGE_REQUIRED=YES
PRODUCTION_CODE_CHANGED=NO
```

No production fix is attempted in this task. The smallest future fix must
define a complete, public-domain-backed durable representation and validator
for this field before A9 can continue; its architecture is intentionally not
decided here.

## Test-only characterization

`gym/src/test/kotlin/com/wingedsheep/gym/contract/ChosenSemanticCostPaymentGapTest.kt`
constructs a real `ActivateAbility` carrying `AdditionalCostPayment`, a
complete semantic `LegalActionView` candidate, and a
`CompleteLegalDomainV1`. It asserts the current rejection and therefore keeps
the ordinary suite green while recording the desired future state as RED:

```text
TARGET_DESIRED_BEHAVIOR=RED
CURRENT_REJECTION_REPRODUCED=PASS
```

The characterization does not change the validator, payment execution,
Rules, replay, or trajectory production code.

## Trust and publication state

```text
DATASET_ID=NOT_CREATED
MANIFEST_CONTENT_DIGEST=NOT_CREATED
MANIFEST_EPISODE_COUNT=0
MANIFEST_DECISION_COUNT=0
SHARD_COUNT=0
SHARD_DIGESTS=NOT_CREATED
TOTAL_DATASET_BYTES=0

A7_PREFLIGHT=NOT_RUN
A7_STREAM_EPISODES=NOT_RUN
READER_EPISODE_COUNT=0
READER_DECISION_COUNT=0

SERIALIZED_PRIVACY_SCAN=NOT_RUN
RAW_GAMESTATE_PRESENT=NOT_RUN
LIVE_ROUTING_IDS_PRESENT=NOT_RUN

DYNAMIC_PENDING_FAMILIES=NOT_REACHED
DYNAMIC_ACTION_KINDS=NOT_REACHED
UNKNOWN_DECISION_FAMILIES=0
UNSUPPORTED_DIAGNOSTICS=0
NATIVE_FALLBACKS=0
PUBLIC_CHOICE_REJECTIONS=0
CHOSEN_NOT_IN_DOMAIN=NOT_REACHED
DETERMINISM_SPOTCHECK=NOT_RUN

MAX_SHARD_BYTES=256 MiB (declared schedule setting; no dataset)
MAX_EPISODES_PER_SHARD=1
```

The error occurs while converting an accepted external choice into the
durable chosen-input contract, before A5 trajectory validation, A6 admission,
replay verification, privacy scanning, or A7 publication. Consequently no
dataset can be called trusted.

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
A9_STARTED=YES (bounded attempt only)
DATASET_GENERATION_RUN=NO (no dataset finalized)
```

The branch contains only this focused test characterization and this report.
It is pushed for independent review and fix design. No PR is created, no A9
dataset shards are committed, and no production workaround is included.

Known limitation: A9 remains blocked until the generic `costPayment`
chosen-input contract is implemented and independently accepted. The primary
64-episode matrix, extension schedule, trajectory admission, writer/reader
publication, and determinism spot check were not run to completion.

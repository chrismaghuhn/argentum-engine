# B2 A9 bounded trusted-generation restart — blocker characterization

Date: 2026-09-05
Task: `B2_A9_BOUNDED_TRUSTED_GENERATION_RESTART_01`

## Fresh-start provenance

```text
BASE=7725f9fa80c938ae9e8d6258cb83b9c10a8be94d
ORIGIN_MAIN=7725f9fa80c938ae9e8d6258cb83b9c10a8be94d
UPSTREAM_MAIN=5021faf88093a93091e4de7914fbe0f411499d58
BRANCH=chris/b2-a9-bounded-trusted-generation-restart-20260905
WORKTREE=C:/Users/chris/.config/superpowers/worktrees/argentum-engine/b2-a9-bounded-trusted-generation-restart-20260905
EPISODE_ORDINAL_START=0
PREVIOUS_A9_OUTPUT_REUSED=NO
PREVIOUS_A9_STAGING_REUSED=NO
```

The worktree was created from the fetched merged main. The previous A9
worktree and its output roots were not used.

Locked-pair inputs remain the accepted Environment V1 inputs:

```text
AKIRI_DECK_SHA256=0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6
CHEVILL_DECK_SHA256=D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2
CARD_DEFINITION_IDENTITY=3C3C2DF4993D875D1239F49D4D3DACF059D8842BC2A6E0D03DDF31CDB7901E23
POLICY_IDENTITY=b2-a9-deterministic-external-policy@v1
POLICY_RNG_IDENTITY=explicit-seed/kotlin-policy-state-v1
POLICY_SOURCE_IDENTITY=EnvironmentV1ExternalPolicy.kt@sha256:0A30BE612470CA10B280793DAEBBE17831771891BE9F899BFC6C4710ECD01D0A
REPLAY_VERSION=6
REPLAY_SCHEMA_IDENTITY=argentum-compact-replay@v6
```

## Restart result

The fresh bounded generator started the primary matrix at ordinal 0 and
stopped at the first real chosen-input contract failure. No episode was
admitted, replay-verified, written, or published.

```text
A9_OUTPUT_ROOT=C:/Users/chris/AppData/Local/Temp/argentum-b2-a9-3136640026913223204
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
ENGINE_SEEDS=0..15 frozen schedule declared; stopped at ordinal 0
POLICY_SEEDS=ordinal 0 seed 0 start 0 seat0 Akiri = 4259905

REPLAY_EXACT_EPISODES=0
REPLAY_DIVERGED_EPISODES=0
REPLAY_INCOMPLETE_EPISODES=0
A5_VALID_EPISODES=0
A6_ADMITTED_EPISODES=0
A6_QUARANTINED_EPISODES=0
```

The fresh output root contains only `a9-schedule.txt` and an empty staging
directory. No finalized manifest or shard exists. The bounded extension was
not reached.

## New blocker

After the prior `costPayment` fix, the first restart reached a different real
public action payload with required field `additionalCostPayment`. The current
path was:

```text
real locked-pair Gym action
→ accepted external public choice
→ ChosenSemanticActionV1.fromRecordedAction
→ StoredActionPayloadValidator
→ rejected: additionalCostPayment has no complete stored-domain validator
```

Observed failure:

```text
java.lang.IllegalArgumentException:
Chosen action payload has no complete stored-domain validator for: additionalCostPayment
```

The field is a current public contract field: `ActionPayloadRequirements`
maps `CastSpell` additional costs to `additionalCostPayment`, and the accepted
observation-only policy explicitly publishes that field. The failure occurred
in `gym/src/main/kotlin/com/wingedsheep/gym/contract/ChosenSemanticInput.kt`
before A5/A6 trajectory admission. It is therefore a reusable chosen-input
representation gap, not an unknown decision family or a private-state
legality failure.

```text
A9_BLOCKER=YES
BLOCKER_CLASS=PUBLIC_CHOSEN_INPUT_ADDITIONAL_COST_PAYMENT_GAP
UNSUPPORTED_REACHABLE_FAMILIES=1 (additionalCostPayment)
UNKNOWN_DECISION_FAMILIES=0
PRODUCTION_CHANGE_REQUIRED=YES
PRODUCTION_CODE_CHANGED=NO
```

No production fix is attempted. This task stops here as required; the prior
`costPayment` blocker remains fixed, but the full A9 chain is not yet closed.

## Focused RED characterization

`gym/src/test/kotlin/com/wingedsheep/gym/contract/ChosenSemanticAdditionalCostPaymentGapTest.kt`
constructs a real `CastSpell` carrier, a complete public sacrifice candidate,
and invokes the production `ChosenSemanticActionV1.fromRecordedAction` path.
It passes by asserting today's rejection, leaving the desired future behavior
explicitly RED:

```text
TARGET_DESIRED_BEHAVIOR=RED
CURRENT_REJECTION_REPRODUCED=PASS
FOCUSED_CHARACTERIZATION=PASS (1/1)
```

The test does not add a validator, public-domain workaround, dataset, or
runtime fallback.

## Gate status at stop

```text
DYNAMIC_PENDING_FAMILIES=NOT_FINALIZED
DYNAMIC_ACTION_KINDS=CastSpell (first additionalCostPayment boundary)
UNSUPPORTED_DIAGNOSTICS=0
NATIVE_FALLBACKS=0
PUBLIC_CHOICE_REJECTIONS=0
CHOSEN_NOT_IN_DOMAIN=0

A9_HEAVY_GATE=FAIL/BLOCKED
GYM_TEST=PASS (focused characterization only; 1 test; 0 failures)
GYM_TRAINER_TEST=NOT_RUN
GAME_SERVER_TEST=NOT_RUN
RULES_ENGINE_TEST=NOT_RUN
GIT_DIFF_CHECK=PASS

A9_RESTARTED_FROM_MAIN=YES
A9_GENERATION_RESUMED=NO (no continuation after the first failure)
DATASET_GENERATION_RUN=NO
A9_IMPLEMENTATION_PASS=NO
A9_HOSTED_CI_PASS=NOT_RUN
A9_CODE_REVIEW_PASS=NO
A9_FINAL_ACCEPTANCE_PASS=NO
B2_FINAL_ACCEPTANCE_PASS=NO
DATA_TRUSTED=NO
C0_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

No A9 dataset is trusted. The next action requires a separate generic
production chosen-input fix for `additionalCostPayment`, followed by review,
Hosted CI, merge, and a fresh A9 restart from the then-current main. No
opportunistic fix is included here.

# B2 A9 recorded `costPayment` public validation fix

Date: 2026-09-05
Task: `B2_A9_RECORDED_COST_PAYMENT_PUBLIC_VALIDATION_FIX_01`

## Lineage and scope

```text
ORIGIN_MAIN=6b7e286788a7b09c7cb0e8ca575121d08d5efff2
CHARACTERIZATION_SHA=32ad2f998306c08b69c91f67b9ae1fb166560448
FIX_COMMIT=this report and fix are published together; final SHA is recorded in the task report
```

The fix is intentionally generic and changes only the Gym chosen-input
validator. It does not change Rules, payment execution, replay, Trajectory V1,
A6/A7, observation production, locked decks, or any dataset schema.

## Authoritative `AdditionalCostPayment` audit

The serialized type is:

```text
mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/scripting/AdditionalCost.kt:478
com.wingedsheep.sdk.scripting.AdditionalCostPayment
```

Current serialized semantic fields and defaults are:

```text
sacrificedPermanents       = emptyList()
discardedCards             = emptyList()
lifePaid                   = 0
exiledCards                = emptyList()
variableCostPermanents     = emptyList()
beheldCards                = emptyList()
tappedPermanents           = emptyList()
bouncedPermanents          = emptyList()
blightTargets              = emptyList()
blightAmount               = 0
payXLifeAmount              = 0
distributedCounterRemovals = emptyList()
```

The current authoritative type has no `bargainSacrifice` member. A nested
`bargainSacrifice` key is therefore rejected by strict serialization rather
than treated as a payment choice.

The field/public-authority classification for the current action-level
`costPayment` contract is:

```text
PUBLICLY_VALIDATABLE_FIELDS=sacrificedPermanents,tappedPermanents
CANONICAL_NO_OP_ONLY_FIELDS=discardedCards,lifePaid,exiledCards,variableCostPermanents,beheldCards,bouncedPermanents,blightTargets,blightAmount,payXLifeAmount,distributedCounterRemovals
NO_PUBLIC_AUTHORITY_FIELDS=bargainSacrifice (not present in the current type; strict unknown-field rejection)
```

`sacrificedPermanents` uses the existing Rules-owned public candidate fields:

```text
PUBLIC_SACRIFICE_DOMAIN_COMPLETE=YES
SACRIFICE_DOMAIN_FIELDS=validSacrificeTargets,sacrificeCount,sacrificeMinCount,sacrificeMaxCount
BARGAIN_USES_SAME_PUBLIC_DOMAIN=NO (no current bargain field exists)
```

For the current source-bound activated-cost slice, `tappedPermanents` is
validated only when the stored public `actionSemantics` contains the host
permanent's direct `CostTap` node; the stored `sourceEntityId` must then be the
only tapped permanent. No live state, registry, or cost solver is consulted.

## Implemented contract

`StoredActionPayloadValidator` now:

1. decodes `costPayment` with `A3SemanticJson.decodeStrict` and the
   authoritative `AdditionalCostPayment.serializer()`;
2. validates stored sacrifice candidates for canonical IDs, duplicate-free
   membership, valid min/max bounds, fixed-count consistency, and selected
   cardinality;
3. validates the source-bound tap acknowledgement from the stored semantic
   cost tree and source identity;
4. accepts only serializer-defined no-op values for every current additional
   payment channel without a complete public domain; and
5. preserves the existing outer candidate match, affordability, exact payload
   field, payment-plan, and all other semantic validators.

Adding `costPayment` to the validator set is therefore not a blind allowlist:

```text
COST_PAYMENT_STORED_VALIDATOR_ADDED=YES
COST_PAYMENT_BLIND_ALLOWLIST=NO
```

The validator accepts no runtime authority. A source guard test covers
`GameState`, `CardRegistry`, `ManaSolver`, `ObservationBuilder`, and
`ActionRegistry` dependencies.

## RED to GREEN evidence

The historical RED test at `32ad2f...` failed with:

```text
Chosen action payload has no complete stored-domain validator for: costPayment
```

The updated focused suite now proves:

```text
VALID_SACRIFICE_ACCEPTED=PASS
OUTSIDE_DOMAIN_SACRIFICE_REJECTED=PASS
DUPLICATE_SACRIFICE_REJECTED=PASS
TOO_FEW_SACRIFICES_REJECTED=PASS
TOO_MANY_SACRIFICES_REJECTED=PASS
MALFORMED_COST_PAYMENT_REJECTED=PASS
UNKNOWN_COST_PAYMENT_FIELD_REJECTED=PASS
```

It also proves that current non-default `discardedCards`, `exiledCards`,
`lifePaid`, variable permanents, behold, bounce, blight, pay-X-life, and
distributed-counter channels remain rejected, while their canonical no-op
values remain accepted.

The real witness uses the locked Akiri/Chevill deck files, seed `0`, starting
player `0`, the accepted observation-only `DeterministicExternalPolicy`, the
normal `MultiEnvService` public observation and action registry, and the actual
bound `GameAction`:

```text
ORIGINAL_A9_FIRST_ACTION_FIXED=YES
REAL_EXACT_PAIR_RECORDED_ACTION_TEST=PASS
```

It stops at the first public `costPayment` action and does not continue an A9
episode, resume the 64-episode matrix, or create a dataset.

## Verification and status

```text
FOCUSED_COST_PAYMENT_TEST=PASS (13/13)
CHOSEN_SEMANTIC_REGRESSIONS=PASS (66/66)
GYM_TEST=PASS (567 tests; 6 SKIPPED; 0 failures)
GYM_TRAINER_TEST=PASS (145 tests; 1 SKIPPED; 0 failures)
GAME_SERVER_TEST=PASS (609 tests; 13 SKIPPED; 0 failures)
RULES_ENGINE_TEST=NOT_RUN (no Rules production or semantic change)
GIT_DIFF_CHECK=PASS

PRODUCTION_SCOPE_EXPANSION_REQUIRED=NO
NEW_PUBLIC_ADDITIONAL_COST_DOMAIN_GAP=NO
LOCKED_PAIR_REACHES_UNVALIDATABLE_NONDEFAULT_FIELD=NO
RULES_PRODUCTION_CHANGED=NO
TRAJECTORY_PRODUCTION_CHANGED=NO
REPLAY_PRODUCTION_CHANGED=NO
LOCKED_DECKS_CHANGED=NO
FROZEN_BASELINE_REBLESSED=NO
GOLDENS_CHANGED=NO

A8_FINAL_ACCEPTANCE_PASS=YES
A9_GENERATION_RESUMED=NO
DATASET_GENERATION_RUN=NO
A9_IMPLEMENTATION_PASS=NO
A9_FINAL_ACCEPTANCE_PASS=NO
B2_FINAL_ACCEPTANCE_PASS=NO
DATA_TRUSTED=NO
C0_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

A9 remains blocked pending independent review, Hosted CI, merge, and a
separately authorized bounded generation run. No PR is created by this task.

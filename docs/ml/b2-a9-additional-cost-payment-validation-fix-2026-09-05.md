# B2 A9 additional-cost payment public validation fix

Date: 2026-09-05

## Scope and provenance

This is the focused remediation for the RED characterization at:

```text
4adf532bd6b18d10d9f52f3af4ffd0ee13830abb
```

The branch started from the merged A9 baseline:

```text
BASE=7725f9fa80c938ae9e8d6258cb83b9c10a8be94d
BRANCH=chris/b2-a9-bounded-trusted-generation-restart-20260905
```

The prior bounded restart reached a real locked Akiri/Chevill `CastSpell` action at ordinal 0,
engine seed 0, and policy seed 4,259,905. Its public candidate required
`additionalCostPayment`, but `ChosenSemanticActionV1.fromRecordedAction` rejected the recorded
action because `StoredActionPayloadValidator` had no validator for that payload field.

This change does not resume A9 generation, create a dataset, or train a model. The fix commit is
the commit containing this report; its exact SHA is recorded in the task completion report.

## Authoritative payment shape

The serialized type is:

```text
mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/scripting/AdditionalCost.kt
AdditionalCostPayment
```

Its serialized members and defaults are:

```text
sacrificedPermanents: List<EntityId> = []
discardedCards: List<EntityId> = []
lifePaid: Int = 0
exiledCards: List<EntityId> = []
variableCostPermanents: List<EntityId> = []
beheldCards: List<EntityId> = []
tappedPermanents: List<EntityId> = []
bouncedPermanents: List<EntityId> = []
blightTargets: List<EntityId> = []
blightAmount: Int = 0
payXLifeAmount: Int = 0
distributedCounterRemovals: List<DistributedCounterRemoval> = []
```

`isEmpty` and `NONE` are derived/companion values, not serialized members. The current type has no
`bargainSacrifice` member; a nested field with that name is therefore rejected by strict decoding.

The public producer is `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt`.
For `LegalActionView`, it publishes the Rules-owned sacrifice surface:

```text
validSacrificeTargets
sacrificeCount
sacrificeMinCount
sacrificeMaxCount
```

No complete stored candidate domains currently exist for the other payment channels. The
`ActionPayloadRequirements` mapping remains action-specific:

```text
ActivateAbility -> costPayment
CastSpell       -> additionalCostPayment
```

## Authority matrix

| Member | Stored public authority | Implemented semantics |
| --- | --- | --- |
| `sacrificedPermanents` | `validSacrificeTargets`, `sacrificeCount`, `sacrificeMinCount`, `sacrificeMaxCount` | Membership, distinct/nonblank IDs, cardinality, fixed-count consistency |
| `discardedCards` | none | Empty list only |
| `lifePaid` | none | `0` only |
| `exiledCards` | none | Empty list only |
| `variableCostPermanents` | none | Empty list only |
| `beheldCards` | none | Empty list only |
| `tappedPermanents` | none for `CastSpell` additional cost | Empty list only; existing `costPayment` source-bound tap validation is unchanged |
| `bouncedPermanents` | none | Empty list only |
| `blightTargets` | none | Empty list only |
| `blightAmount` | none | `0` only |
| `payXLifeAmount` | none | `0` only |
| `distributedCounterRemovals` | none | Empty list only |
| `bargainSacrifice` | absent from the authoritative type | Rejected as an unknown nested field |

This is deliberately not a global claim that every `AdditionalCost` mechanic has a complete
public domain. It records that no new reachable locked-environment blocker remains after the
supported sacrifice channel was validated; meaningful unsupported channels remain fail-closed.

## Implementation

`gym/src/main/kotlin/com/wingedsheep/gym/contract/ChosenSemanticInput.kt` now:

- recognizes `additionalCostPayment` only with a nested strict `AdditionalCostPayment` decoder;
- reuses the existing public sacrifice membership/cardinality helper;
- rejects duplicate, blank, outside-domain, too-few, and too-many sacrifice IDs;
- preserves the existing `costPayment` source-bound tap contract;
- accepts only canonical no-op values for unsupported additional-cost channels;
- rejects unknown nested fields and malformed payloads; and
- continues to validate from the stored candidate and recorded payload only.

The existing outer invariants remain unchanged: exact stored candidate membership, affordable
candidate admission, required-payload exactness, chosen-in-domain checking, and `PaymentDomainV5`
validation.

## RED to GREEN evidence

`gym/src/test/kotlin/com/wingedsheep/gym/contract/ChosenSemanticAdditionalCostPaymentGapTest.kt`
now covers:

- public sacrifice acceptance and durable payload equality;
- outside-domain, duplicate, too-few, too-many, and malformed public-domain rejection;
- malformed nested payload rejection;
- strict rejection of the unknown `bargainSacrifice` field;
- rejection of every audited non-default unsupported payment channel; and
- acceptance of canonical no-op channels.

`gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExactPairChosenCostPaymentTest.kt` adds the
real locked-pair witness. It uses the normal public observation, the deterministic external
policy, engine seed 0, and policy seed 4,259,905, resolves the real legal `CastSpell`, binds the
recorded action, and passes it through `ChosenSemanticActionV1.fromRecordedAction`. The witness
stops before executing the target action and does not generate A9 output.

The existing source guard for `StoredActionPayloadValidator` continues to reject dependencies on
`GameState`, `CardRegistry`, `ManaSolver`, `ObservationBuilder`, and `ActionRegistry`.

## Verification

```text
FOCUSED_TESTS=12/12 PASS
GYM=578 tests, 0 failures, 6 configured SKIPPED
GYM_TRAINER=145 tests, 0 failures, 1 configured SKIPPED
GAME_SERVER=609 tests, 0 failures, 13 configured SKIPPED
RULES_ENGINE=NOT_RUN (no Rules production or semantic change)
GIT_DIFF_CHECK=PASS
```

Configured skips are reported as `SKIPPED`, not as passes. No production scope expansion was
required. Rules, replay, trajectory, decks, goldens, and frozen baselines were not changed.

## Gate state

```text
ORIGINAL_A9_COST_PAYMENT_BLOCKER=CLOSED
NEW_PUBLIC_ADDITIONAL_COST_DOMAIN_GAP=NO reachable locked-pair blocker
A9_GENERATION_RESUMED=NO
DATASET_GENERATION_RUN=NO
A9_IMPLEMENTATION_PASS=NO
A9_FINAL_ACCEPTANCE_PASS=NO
B2_FINAL_ACCEPTANCE_PASS=NO
DATA_TRUSTED=NO
```

This fix is ready for independent review and Hosted CI. A9 must restart from a newly accepted
main after this change; no A9 restart or final acceptance is claimed here.

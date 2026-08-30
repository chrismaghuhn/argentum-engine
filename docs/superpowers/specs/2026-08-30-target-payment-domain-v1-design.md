# TargetPaymentDomainV1 Design Specification

Status: approved architecture, pending written-spec review
Baseline: `458022a8e61eb6399ac3b8fe8f0cbce2e28e34ce`
Scope: finite, single-target, target-dependent activated-ability payment domains in the Gym.

This is an additive Gym observation contract. It does not change `PaymentDomainV5`,
`PaymentPlanV3`, `SourceActivationV2`, `ExplicitV3`, `GameAction`, or CompactReplay version 5.

## 1. Boundary and decisions

`TargetPaymentDomainV1` publishes the complete payment capability for every legal member of one
finite, controller-chosen, single-target domain. Strict submission binds the chosen target and the
chosen payment plan to the same published binding.

It is not a general multi-target solver, a policy-side cost calculator, a new mana-payment version,
or a replacement for the existing target-domain contract.

| Area | Normative decision |
| --- | --- |
| New contract | `TargetPaymentDomainV1` with non-null per-target `PaymentDomainV5` |
| Existing payment | `PaymentDomainV5` unchanged |
| Existing plan | `PaymentPlanV3` and `SourceActivationV2` unchanged |
| Action carrier | `GameAction` and `PaymentStrategy.ExplicitV3` unchanged |
| Replay | CompactReplay remains version 5 |
| Observation | Add nullable `LegalActionView.targetPaymentDomain` |
| Gym schema | `argentum-gym-contract@v1.25-target-payment-domain` |
| Cost authority | Rules-owned `ActivatedAbilityCostCalculator` with concrete target |
| Ordering | Existing producer-owned `targetDomain` candidate order |
| Unsupported shapes | Whole domain fails closed; no partial publication or fallback |

`TargetPaymentDomainV1` is an Observation contract. It is not placed in `GameAction`, the replay
carrier, or Rules state. The selected target and the selected `ExplicitV3` plan remain the replayed
action input already supported by the current carrier.

## 2. Public DTO contract

The exact Kotlin file placement may follow the existing payment contract, but this serialized shape is
normative:

```kotlin
@Serializable
data class TargetPaymentDomainV1(
    val version: Int = 1,
    val targetBindings: List<TargetPaymentBindingV1>,
)

@Serializable
data class TargetPaymentBindingV1(
    val target: EntityId,
    val effectiveManaCost: String,
    val affordable: Boolean,
    val paymentDomain: PaymentDomainV5,
)
```

`target` is the public `EntityId` from the existing target domain. `effectiveManaCost` is the
Rules-owned canonical cost calculated with that target bound. `affordable` is the current Rules
affordability result for the fully bound action. `paymentDomain` is always non-null for a certified
binding, even when `affordable` is false.

An unaffordable binding is a legal target with no current payment plan. It is not an unknown domain.
If any candidate lacks a representable V5 domain, the complete `TargetPaymentDomainV1` is
unsupported.

Add this field to `LegalActionView`:

```kotlin
val targetPaymentDomain: TargetPaymentDomainV1? = null
```

When the field is non-null, the parent action must satisfy:

```text
targetDomain is present and supported
targetDomain has exactly one requirement
targetDomain.requirements.single().candidates is finite and complete
targetPaymentDomain.targetBindings is non-empty
binding targets are unique
targetDomain candidates == binding targets in identical producer order
LegalActionView.manaCost == null
LegalActionView.paymentDomain == null
LegalActionView.affordable == targetBindings.any { it.affordable }
```

The action-wide `manaCost` and `paymentDomain` are cleared to avoid competing authorities. Existing
actions without this field retain their current action-level payment contract.

## 3. Rules-owned qualification

The producer may publish this contract only when all conditions hold:

1. The action is `ActivateAbility`.
2. There is exactly one controller-chosen `TargetRequirement`.
3. Its `minTargets` and `maxTargets` are both `1`.
4. Its candidate set is finite, complete, public, addressable, and duplicate-free.
5. The existing target-domain mapping is `SUPPORTED`.
6. Target selection is the only unresolved choice that can affect payment cost.
7. The complete ability can be resolved for every candidate.
8. `ActivatedAbilityCostCalculator.calculate` can calculate the complete target-bound cost for every candidate.
9. Every target-bound cost is a fixed ordinary mana cost accepted by the existing V5 builder.
10. Every candidate produces a non-null, internally valid `PaymentDomainV5`.
11. Deterministic non-mana costs and already-resolved action-level choices satisfy the existing V5
    timing, stability, side-effect, and perspective certificates.

The qualifier is structural and state-aware. It must not inspect card names, set names, source-ID
conventions, or Fervent Champion-specific conditions.

### 3.1 Target binding calculation

For each candidate in the existing Rules-owned target order, the producer performs:

```text
target candidate
  → resolve the same ActivatedAbility
  → calculate with targets=[ChosenTarget.Permanent(candidate)]
  → canonicalize the effective AbilityCost
  → resolve deterministic non-mana costs
  → build PaymentDomainV5 from that bound cost
  → publish one TargetPaymentBindingV1
```

The unbound `LegalAction.manaCostString` is never the target-bound cost authority. A target-dependent
cost that cannot be represented by the V5 builder fails the whole target-payment domain.

The implementation may retain the existing action-level domain when it proves that every candidate
has the same effective cost. Once target-dependent cost is detected, it must use this contract or
fail closed.

### 3.2 Unsupported V1 shapes

The following remain fail-closed with `TARGET_PAYMENT_DOMAIN_UNSUPPORTED` or the repository's
equivalent typed diagnostic:

```text
two or more target requirements
optional, unlimited, or "any number of" targets
dynamic or X-driven target cardinality
target combinations whose joint selection changes cost
mode, ordering, or distribution choices that change cost
convoke, waterbend, or tap-for-generic choices
unresolved alternative-payment choices
unresolved additional-cost choices
non-fixed or non-ordinary target-bound costs
any candidate whose effective cost or PaymentDomainV5 is not certifiable
```

Unsupported cases must not be represented by an optimistic action-wide cost, a partial binding list,
AutoPay, native fallback, legacy payment, or policy-side Rules reconstruction.

## 4. Domain and ordering invariants

The producer rejects the whole contract if any invariant fails:

```text
targetBindings.size == targetDomain.requirements.single().candidates.size
targetBindings.map { it.target }.distinct().size == targetBindings.size
targetDomain candidates == binding targets in identical order
every effectiveManaCost is canonical
every paymentDomain is non-null and valid
no target is omitted because its binding is unaffordable
```

The target domain owns candidate ordering. Consumers do not sort, deduplicate, repair, or otherwise
reorder bindings. The policy chooses a binding; it does not choose the producer's ordering.

## 5. Strict Gym execution

The trusted path treats target and payment as one submission:

```text
registered action
  → fresh Rules legal action and target domains
  → fresh TargetPaymentDomainV1
  → validate exactly one submitted target
  → resolve the binding for that target
  → require binding.affordable
  → require ExplicitV3 with PaymentPlanV3
  → validate the plan against binding.paymentDomain
  → re-resolve the target-bound effective cost
  → compare registered and current binding
  → execute only after all checks pass
```

Reject before mutation, events, or action-cursor advancement when:

```text
target is missing, duplicated, or outside targetDomain
target has no binding
binding.affordable is false
the plan is missing or malformed
the plan belongs to another target binding
registered/current target binding differs
registered/current effective cost differs
registered/current nested PaymentDomainV5 differs
```

Rejected submissions return the original immutable state and empty events. The strict path never
reads parent `LegalActionView.manaCost` or parent `paymentDomain` when target payment is present.

This is invalid even when both targets are legal:

```text
target=e147
payment plan from binding(target=e146)
```

If the registered binding says `target=e146, effectiveManaCost={0}` and current Rules resolution
says `{1}`, the submission is stale and rejected. It is not recalculated, retargeted, or auto-paid.

## 6. External policy contract

The policy may only:

1. Read the published target and target-payment domains.
2. Verify their published correspondence.
3. Select one binding with `affordable == true`.
4. Construct a plan from that binding's published `PaymentDomainV5`.
5. Submit the target and plan together.

It must not inspect `GameState` or `CardRegistry`, rerun the cost calculator, infer costs from names
or descriptions, use parent `manaCost`, filter bindings, or fall back to AutoPay/native/legacy
payment. The B0 policy remains unchanged until this production contract is independently reviewed.

## 7. Wire, digest, and compatibility

The new DTO is serializable and included in the v1.25 Gym wire and semantic observation:

```text
targetDomain
targetPaymentDomain.version
targetPaymentDomain.targetBindings in producer order
target
effectiveManaCost
affordable
complete nested PaymentDomainV5
```

`ObservationCanonicalizer` and `StateDigest` must include the field without consumer-side sorting. A
target-specific cost or nested payment-domain change changes the semantic digest.

The schema identifier is:

```text
argentum-gym-contract@v1.25-target-payment-domain
```

v1.24 remains historical. Unknown future schema versions fail closed. CompactReplay remains v5
because it stores the concrete `GameAction` with the selected target and `ExplicitV3` plan, not the
observation-only target-payment domain. Replay exactness must be tested without reconstructing the
Gym domain from replay data.

## 8. Implementation seams

| Seam | Responsibility |
| --- | --- |
| `gym/.../contract/PaymentDomain.kt` or adjacent contract file | DTOs and invariant checks |
| `gym/.../contract/TrainingObservation.kt` | `LegalActionView.targetPaymentDomain` |
| `gym/.../contract/ObservationBuilder.kt` | qualification, target-bound cost, V5 publication |
| `gym/.../contract/ObservationCanonicalizer.kt` | wire and semantic inclusion |
| `gym/.../contract/SchemaHash.kt` | v1.25 identifier |
| `gym/.../GameGymEnv.kt` | strict target/payment coupling and stale rejection |
| `gym` tests | contract, B0, strict, digest, privacy, replay evidence |
| `docs/data-contracts.md` | public contract documentation |

No SDK vocabulary, card definition, replay serializer, `PaymentDomainV5`, or `PaymentPlanV3` change
belongs to this slice.

## 9. Required adversarial test matrix

```text
TARGET-PAYMENT-01
  finite one-target action with two different target-aware fixed costs
  → one non-null V5 binding per candidate

TARGET-PAYMENT-02
  costs {0} and {1}/{2}
  → exact bound costs; parent manaCost/paymentDomain are null
  → parent affordable equals any binding affordable

TARGET-PAYMENT-03
  missing, duplicate, extra, or reordered binding
  → whole domain unsupported

TARGET-PAYMENT-04
  one binding's V5 build fails
  → no partial publication

TARGET-PAYMENT-05
  multi-target, optional, dynamic, X, mode, or unresolved choice
  → typed unsupported result

TARGET-PAYMENT-06
  exact B0 decision-1027 state
  → Sword of the Animist, Swiftfoot Boots, and Mask of Memory each publish bindings
  → e146 and e147 costs are independently verified
  → Slayers' Stronghold is not counted as a production offender

TARGET-PAYMENT-07
  affordable target plus matching plan
  → accepted and executed

TARGET-PAYMENT-08
  target=e147 plus plan from target=e146
  → rejection, original state, zero events

TARGET-PAYMENT-09
  registered/current target-bound cost drift
  → stale rejection before mutation

TARGET-PAYMENT-10
  registered/current nested V5 domain drift
  → stale rejection before mutation

TARGET-PAYMENT-11
  missing, duplicate, or off-domain target
  → rejection without fallback

TARGET-PAYMENT-12
  perspective-equivalent states
  → equivalent public target-payment domains and digests

TARGET-PAYMENT-13
  JSON round-trip and producer-order preservation
  → exact DTO equality; no consumer sort

TARGET-PAYMENT-14
  accepted ExplicitV3 target-bound action
  → CompactReplay v5 exact final-state fidelity
```

The reusable fixture must exercise Rules-owned target-aware cost modification without depending on
entity IDs, card names, or an Equip-only predicate. The exact B0 fixture remains evidence only.

## 10. Acceptance gates

```text
TARGET_PAYMENT_DOMAIN_V1=IMPLEMENTED
TARGET_BINDING_COST_AUTHORITY=ACTIVATED_ABILITY_COST_CALCULATOR
TARGET_BINDING_PAYMENT_DOMAIN=NON_NULL_FOR_EVERY_CERTIFIED_BINDING
TARGET_DOMAIN_BINDING_BIJECTION=PASS
PARENT_COST_AUTHORITY_DISABLED_WHEN_TARGET_BOUND=PASS
STRICT_TARGET_PAYMENT_COUPLING=PASS
STALE_REJECTION_AND_ZERO_MUTATION=PASS
NO_AUTOPAY_OR_NATIVE_FALLBACK=PASS
GYM_SCHEMA=v1.25-target-payment-domain
COMPACT_REPLAY_VERSION=5
PAYMENT_DOMAIN_V5_UNCHANGED=PASS
PAYMENT_PLAN_V3_UNCHANGED=PASS
```

If any target-binding, strict-coupling, or stale-certificate requirement cannot be proved, the
implementation stops and remains fail-closed. B0 smoke, 512, and 2048 stay blocked until the
production implementation and its independent review are complete.

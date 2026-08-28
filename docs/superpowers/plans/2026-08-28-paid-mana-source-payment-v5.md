# Paid Mana Source Payment V5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define the reviewed, reusable public contract for paying a mana source's own mana activation cost, using an ordered acyclic payment program and one global resource ledger, while preserving every historical V1/V2/V4 payment and replay wire contract.

**Architecture:** The public seam is a new `PaymentDomainV5` paired with `PaymentPlanV3`. The plan is an ordered list of `SourceActivationV2` nodes; each node may consume only initial-pool resources or outputs of earlier nodes, and all activation-cost and outer-cost allocations are normalized into one Rules-owned resource ledger. A deep Rules module validates and executes this complete program atomically; public policy may search only the published domain and never calls `ManaSolver`, reads `GameState`, or supplies hidden choices.

**Tech Stack:** Kotlin, kotlinx.serialization, immutable ECS `GameState`, the existing `ManaSource`/mana-ability profile machinery, Gym JSON contracts, `PaymentStrategy`, and `CompactReplay`. Verification is recorded per implementation task; `just` remains the repository-level gate, with native Gradle runs reported separately when the Windows wrapper cannot start.

---

## Status, authority, and hard scope

**Status:** Approved normative design proposal. The implementation is being delivered in isolated tasks on the branch for PR #107; this document remains the contract and does not authorize changes to B0 policy, locked decks, or historical payment/replay versions.

**Base:** `origin/main = 7db6ea409f93580bf83ab0bc0ca86f3384d33a9a`.

**Issue:** [#106 Paid Mana Source Public Payment Audit](https://github.com/chrismaghuhn/argentum-engine/issues/106).

The following are fixed decisions for implementation review:

- `AUDIT_REVIEW=PASS`.
- `ROOT_CAUSE_CONFIRMED=YES`.
- `B0_POLICY_BUG=NO`.
- `DECK_ISSUE=NO`.
- `CARD_DEFINITION_BUG=NO`.
- `PAYMENT_DOMAIN_V4_EXPRESSIVENESS_GAP=YES`.
- `REUSABLE_PAYMENT_PRIMITIVE_MISSING=YES`.
- `DESIGN_OPTION_B=APPROVED_WITH_REFINEMENTS`.
- `ORDERED_ACYCLIC_PAYMENT_PROGRAM=RECOMMENDED`.
- `GENERAL_ARBITRARY_GRAPH=NOT_RECOMMENDED`.
- `SINGLE_GLOBAL_RESOURCE_LEDGER=REQUIRED`.
- `COMPACT_REPLAY_V5=REQUIRED`.
- `GYM_SCHEMA_BUMP=REQUIRED`.
- `WRITTEN_SPEC_APPROVED=YES`.
- `GO_TO_RED=COMPLETE` for the characterized V5 contract.
- `GO_TO_IMPLEMENTATION=YES` for the approved task sequence.
- B0 policy, locked decks, and card definitions remain out of scope. Historical payment/replay
  contracts remain unchanged.

Normative words such as **MUST**, **MUST NOT**, **SHOULD**, and **MAY** describe the contract that a later implementation and its tests must satisfy.

---

## 1. Audit anchor: exact B0 state and observed failure

The reproduction was performed against the exact base above, using the B0 episode `b0-v1-0-akiri_seat_0-akiri`:

- engine seed: `0`;
- policy seed: `-1059386116538784978`;
- Akiri is seat 0 and the starting player;
- 1,546 semantic/external transitions were reached;
- the post-transition observation had 29 legal actions, including 9 payable actions;
- the closure was `FAILED`, reason `UNSUPPORTED`, at `post-action-observation`;
- `PAYMENT_DOMAIN_UNSUPPORTED` occurred for 8 of the 9 payable actions;
- no native fallback, public-choice rejection, or replay divergence occurred;
- the last valid fingerprint was `0813ebce7568cceea586d110dcddb6b9f585bd7ea8e00b70cf3981d5152bd332`;
- the last historical V4 public domain identity was `2c92f13bd7bd9e294db556d901e74129c37d6668443a278511c779d35fe1af3c`.

The captured solver discovery order was:

`[e115 Forest, e106 Forest, e117 Swamp, e118 Swamp, e165 Golgari Signet]`.

The builder's stable candidate order was:

`[e106 Forest, e115 Forest, e117 Swamp, e118 Swamp, e165 Golgari Signet]`.

For the table, `F106`, `F115`, `Sw117`, `Sw118`, and `GS165` mean those exact entity/card pairs.

| Payable action | Required mana cost | Action-source exclusion | Discovered `ManaSource` values | First `supportsPaymentPlanV1()` failure | `PaymentDomainV4` |
|---|---|---|---|---|---|
| Ravenous Chupacabra (`e147`) | `{2}{B}{B}` | none | F106, F115, Sw117, Sw118, GS165 | GS165: non-empty activation-cost metadata and composite paid ability | not produced (`null`) |
| Moldervine Reclamation (`e176`) | `{3}{B}{G}` | none | F106, F115, Sw117, Sw118, GS165 | GS165: non-empty activation-cost metadata and composite paid ability | not produced (`null`) |
| Leyline Prowler (`e139`) | `{1}{B}{G}` | none | F106, F115, Sw117, Sw118, GS165 | GS165: non-empty activation-cost metadata and composite paid ability | not produced (`null`) |
| Garruk Relentless (`e177`) | `{3}{G}` | none | F106, F115, Sw117, Sw118, GS165 | GS165: non-empty activation-cost metadata and composite paid ability | not produced (`null`) |
| Ohran Frostfang (`e158`) | `{3}{G}{G}` | none | F106, F115, Sw117, Sw118, GS165 | GS165: non-empty activation-cost metadata and composite paid ability | not produced (`null`) |
| Damnation (`e191`) | `{2}{B}{B}` | none | F106, F115, Sw117, Sw118, GS165 | GS165: non-empty activation-cost metadata and composite paid ability | not produced (`null`) |
| Deathreap Ritual (`e175`) | `{2}{B}{G}` | none | F106, F115, Sw117, Sw118, GS165 | GS165: non-empty activation-cost metadata and composite paid ability | not produced (`null`) |
| Golgari Signet activation (`e165`) | `{1}`; action also pays `TapSelf` | `[e165]` | F106, F115, Sw117, Sw118 | none | produced |
| War Room activation (`e136`) | `{3}`; action also has `TapSelf` and deterministic commander-colour-identity life payment | `[e136]`; `e136` is not in discovered mana sources | F106, F115, Sw117, Sw118, GS165 | GS165: non-empty activation-cost metadata and composite paid ability | not produced (`null`) |

The immediate root cause is therefore confirmed, with the important contract qualification:

> `PaymentDomainBuilder` iterates every discovered, non-excluded source and returns `null` when any source does not satisfy `supportsPaymentPlanV1()`. Golgari Signet is discovered for the eight Chevill actions, but V4 cannot express the Signet's own `{1}` payment or its placement among the activation-cost components. Filtering it out would make the published domain incomplete, so the existing fail-closed result is correct.

### Golgari Signet characterization

The exact card definition is `mtg-sets/2003-2007/src/main/kotlin/com/wingedsheep/mtg/sets/definitions/rav/cards/GolgariSignet.kt`:

`{1}, {T}: Add {B}{G}.`

The discovered runtime shape for `e165` was:

- `producesColors=[BLACK]`;
- `producesColorless=false`;
- `manaAmount=1`;
- `bonusManaPerTap=1`;
- `bonusManaColor=GREEN`;
- `colorActivationManaCost` was non-empty in the captured source ( `{BLACK -> 1}`); the underlying card cost is the generic `{1}`;
- `paymentManaProductionProfiles` contained a fixed structural profile `FixedOutputBundle([BLACK, GREEN])`;
- the matching side-effect certificate was `NoSideEffect`;
- `requiresSacrifice=false`;
- `tapPermanentsSubCost=null`;
- the source had no independently selectable output colour for this ability; it produces the fixed B/G bundle.

The ability's runtime ID (captured as `ability_1875`) is not a wire identity. The stable `ManaAbilityIdentity.key(ability)` is structural and excludes the runtime `id` and `descriptionOverride`, so V5 MUST carry that stable key and re-resolve the current ability before accepting a plan.

That structural key is usable only when it is injective within one source's currently legal mana
ability options. During V5 domain construction, if two distinct legal options for the same source
produce the same `manaAbilityKey`, the whole action domain is `PAYMENT_DOMAIN_UNSUPPORTED`. The builder
MUST NOT use runtime `AbilityId`, `descriptionOverride`, collection position, or any other ephemeral
tie-break to separate the collision. The validator likewise MUST resolve a submitted key to exactly
one current legal option; zero or multiple matches reject the plan. Keys may repeat across different
sources because `sourceId` remains part of the source/ability reference.

The current `supportsPaymentPlanV1()` rejection has two directly relevant predicates:

1. `colorActivationManaCost.isEmpty()` is false because the source has a positive mana activation cost.
2. `ordinaryTapManaAbilitiesOnly()` is false because the ability cost is `Composite(Mana("{1}"), Tap)`, not a bare `AbilityCost.Tap`.

The Signet action itself is different. The action's source `e165` is excluded because the action pays `TapSelf`. Only the four lands are then discovered, all four satisfy V1, and V4 can publish a domain for the action's outer `{1}`. That domain proves that the action can be paid *with lands*; it does not prove that Signet can be used as a source while paying another action. The eight Chevill actions leave `e165` in the candidate set, hit the global gate, and return `null`.

---

## 2. Existing contract audit

The exact-head audit covered the following modules and establishes what V5 must add without weakening:

| Existing module | Current contract | V5 consequence |
|---|---|---|
| `gym/.../contract/PaymentDomain.kt` / `PaymentDomainV4` | Publishes outer cost units, floating provenance, and source activation/production options. The builder is globally fail-closed if any discovered source fails `supportsPaymentPlanV1()`. | Keep V4 historical. Add a separate V5 builder and DTO with explicit activation-cost metadata. Do not relax V4 or silently omit GS165. |
| `PaymentSourceActivationDomain` | Contains source ID/name, stable mana-ability key, and production choices, but no activation-cost representation. | Add `PaymentSourceActivationDomainV2` with exact fixed activation mana units and a support-kind descriptor. |
| `rules-engine/.../core/PaymentPlan.kt` / `SourceActivation` | Carries source ID, ability key, production choice, and reserved/rejected secondary choices. | Add `SourceActivationV2` as the ordered node. It must carry activation-cost allocations and be serializable independently of the old V1/V2 types. |
| `PaymentPlanV2` | Extends the outer payment plan for floating/provenance buckets. It does not model a cost of activating a selected source. | Do not add optional fields to V2. Add `PaymentPlanV3` with ordered activation nodes and resource references. |
| `PaymentPlanValidator.validateV2()` | Validates explicit production/pool/allocation choices and exact outer totals. It intentionally does not call `ManaSolver.solve()`. | Add `validateV3()` with the same explicit-choice rule plus one ledger covering activation costs and outer costs. It MUST not search. |
| `ExplicitPaymentPlanExecutor` | Validates the outer plan, then activates selected sources. There is no intermediate booking/payment for an activation's own mana cost. | Add a V3 ordered-program execution path. It MUST validate the complete program before mutation and execute the selected or Rules-approved canonical cost-component order before producing outputs. |
| `ManaAbilitySideEffectExecutor` | Owns deterministic mana-ability side effects such as tapping and PayLife. It has no `CostAtom.Mana` payment stage. | Retain it as the authority for deterministic non-mana side effects. The V3 program executor invokes it at the selected cost-component position; it must not hide a payment order or solver choice. |
| `ManaSolver` | Discovers sources and has limited first-level activation-cost handling for automatic solving. It does not provide a recursive public dependency plan. | It MAY remain a legacy/native helper. It MUST NOT be used by public policy or `validateV3()` to choose sources, production, order, or allocations. |
| `ObservationBuilder.paymentDomainFor()` | Applies action-source exclusion for deterministic source costs, then builds the current domain. | V5 preserves the exclusion semantics and applies the complete-source gate after only the explicit action exclusion. |
| `ActivateAbilityHandler` and spell payment handlers | Explicit paths validate submitted choices; automatic paths use `ManaSolver`. When the action itself pays `TapSelf`, its source is excluded from payment sources. | Add an explicit V3 dispatch. Do not route V3 to AutoPay, source-only legacy handling, or V2. |
| `GameGymEnv` | A payable legal action without a complete public domain is a strict unsupported boundary. AutoPay/native fallback is forbidden for the public contract. | V5 remains strict. A missing V5 domain is `PAYMENT_DOMAIN_UNSUPPORTED`, not a policy fallback. |
| `CompactReplay` | Current base is v4. It already guards `ExplicitV2` from replay versions below v4. | V5 changes the persisted action carrier, so the replay format MUST become v5 with a parallel `ExplicitV3` guard. |
| `SchemaHash` | Current value is `argentum-gym-contract@v1.22-attack-declaration-order`. | Bump to the repo-conforming paid-source contract identifier `argentum-gym-contract@v1.23-paid-mana-source-payment` or an equivalent unique v1.23 name chosen in implementation review. |

The design preserves the existing authority split:

- the domain builder publishes complete public possibilities;
- the external policy selects a source, ability, production profile, ordered activation subset, activation-cost allocation, and outer allocation;
- Rules re-resolves authoritative current facts and validates the submitted choices;
- the executor applies the accepted program atomically.

---

## 3. Selected design: ordered acyclic payment program

### Public domain versus submitted plan

The domain answers **what can be done**. It MUST NOT contain a precomputed engine payment or a policy-selected activation subset.

The plan answers **what was chosen**. Every source, ability, production, order, resource, and allocation choice in the plan is explicit.

The public V5 shape is conceptually:

```
PaymentDomainV5(
    version = 5,
    outerAtomicCostUnits,
    initialPoolBuckets,
    orderedSourceActivationOptions
)

PaymentSourceActivationDomainV2(
    sourceId,
    sourceName,
    manaAbilityKey,
    productionChoices,
    atomicActivationManaCostUnits,
    deterministicNonManaCosts,
    activationCostOrderOptions
)
```

`orderedSourceActivationOptions` contains one entry for every supported legal source/ability option in
Rules-owned order. If one source has multiple legal mana abilities, each stable ability key is
represented explicitly; the builder MUST NOT collapse them to the cheapest ability or one
solver-preferred option.

`PaymentDomainV5` and `PaymentPlanV3` deliberately have no `domainIdentity` field and no Gym observation
hash. Freshness is already owned by the strict Gym action seam: the step-local `ActionRegistry` binds
the opaque handle to a registered `LegalAction`; `GameGymEnv` re-builds the current payment domain before
a payable step; and `GameEnvironment.stepFromCandidateStrict` re-enumerates the current legal action and
checks candidate membership. Rules then re-resolves the current source, ability, effective cost, and
production profile. A plan does not need to echo an observation-domain digest.

If a later engine-level anti-replay certificate is needed, it MUST be a versioned Rules-owned payment certificate whose inputs and validation live entirely in the Rules module. It MUST NOT be a hash of a Gym DTO and is not required for #106.

The plan is conceptually:

```
PaymentPlanV3(
    activations = List<SourceActivationV2>,  // execution order
    outerAllocation
)

SourceActivationV2(
    sourceId,
    manaAbilityKey,
    productionChoice,
    activationCostOrder,
    activationCostAllocation
)
```

The implementation MUST choose one canonical serialized node name. This proposal recommends `SourceActivationV2` because it is the versioned successor of the existing `SourceActivation`. Its semantic role is an ordered `ManaActivationNodeV1`. The activation's identity is its zero-based position in `activations`; it has no separately chosen or serialized identifier. The two names MUST NOT become two independent wire formats.

### Smallest reusable contract

The smallest contract that can represent #106 without hidden payment is the following atomic-cost, bucket, output, and allocation vocabulary:

```
AtomicManaCostUnitV1(
    symbolIndex,
    unitIndexWithinSymbol,
    kind,
    allowedColors
)

InitialPoolBucketKeyV1 =
    UnrestrictedPoolBucket(color)
  | CertifiedFloatingBucket(existingCompleteBucketKey)

InitialPoolBucketV1(
    key: InitialPoolBucketKeyV1,
    availableAmount
)

ActivationCostComponentRefV1 =
    ManaComponent
  | DeterministicNonManaComponent(index)

ActivationCostOrderV1 = List<ActivationCostComponentRefV1>

ManaResourceRefV1 =
    InitialPoolResource(bucketKey: InitialPoolBucketKeyV1)
  | ActivationOutputUnit(activationIndex, outputIndex)

PaymentTargetV1 =
    ActivationCostUnit(activationIndex, symbolIndex, unitIndexWithinSymbol)
  | OuterCostUnit(symbolIndex, unitIndexWithinSymbol)

PaymentAllocationV1(
    target,
    resource: ManaResourceRefV1
)

SourceActivationV2(
    sourceId,
    manaAbilityKey,
    productionChoice,
    activationCostOrder,
    activationCostAllocation: List<PaymentAllocationV1>
)
```

`activationCostOrder` contains each published activation-cost component exactly once. In the first
slice, `ManaComponent` covers the ordinary activation mana cost and
`DeterministicNonManaComponent(index=0)` covers `TapSelf`. The domain publishes a list of legal
`ActivationCostOrderV1` values; the plan carries the one selected value. If the order-characterization
gate proves equivalence, the list has one Rules-approved canonical order. If order is observable, it
contains all legal orders that the supported contract can represent.

For this slice, the ordinary mana portion is one cost component even when it expands into multiple
atomic units; atomic units provide unambiguous allocation targets and do not invent additional
cost-component ordering choices. The V5 atomic representation is separate from the historical V4
`symbolIndex`/`amount` representation: V4 continues to encode `{2}` as its existing aggregate row.

`AtomicManaCostUnitV1` is the normative cost identity. `symbolIndex` identifies the original mana symbol in the ordered cost, and `unitIndexWithinSymbol` expands a symbol's amount into atomic units. For `{2}{B}{B}` the domain publishes:

```
(symbolIndex=0, unitIndexWithinSymbol=0, kind=GENERIC)
(symbolIndex=0, unitIndexWithinSymbol=1, kind=GENERIC)
(symbolIndex=1, unitIndexWithinSymbol=0, kind=COLORED, allowedColors=[BLACK])
(symbolIndex=2, unitIndexWithinSymbol=0, kind=COLORED, allowedColors=[BLACK])
```

The same atomic identity is used for an activation cost. For the first slice, each `PaymentAllocationV1` targets exactly one atomic mana unit and carries exactly one resource reference. There is no hidden “consume enough resources” rule.

The V3 plan contains the ordered activation nodes and outer allocations. The wire may group activation-cost allocations inside each node and outer allocations under `outerAllocation` for readability, but validation MUST concatenate both groups into one logical ledger. There is no independent nested ledger for each activation.

Each fixed production profile exposes stable output indices. Activation outputs are deliberately individual resources because a fixed `B,G` bundle contains two differently typed outputs:

```
ActivationOutputUnit(activationIndex=0, outputIndex=0)
ActivationOutputUnit(activationIndex=0, outputIndex=1)
```

Initial pool mana is different. It is fungible within a published bucket and is represented by a bucket reference plus capacity, not artificial per-mana IDs. A bucket reference may occur in multiple allocations until the ledger's aggregate `consumedAmount` reaches its published `availableAmount`.

For Golgari Signet, the V5 domain entry exposes:

```
atomicActivationManaCostUnits = [
    AtomicManaCostUnitV1(symbolIndex=0, unitIndexWithinSymbol=0, kind=GENERIC)
]
deterministicNonManaCosts = [TapSelf]
activationCostOrderOptions = [
    [ManaComponent, DeterministicNonManaComponent(index=0)]
]
productionChoice = FixedOutputBundle([Black, Green])
```

The single order shown above is valid only after the order characterization has proved that the
supported state class makes the two cost-component orders semantically equivalent. If the order is
observable, the domain instead publishes every legal order, including the reverse order when legal,
and the plan carries the selected order. The domain never hides an order choice in the executor.

The plan may select a Forest activation first. The Signet node then explicitly assigns the Forest's output to `ActivationCostUnit(activationIndex=1, symbolIndex=0, unitIndexWithinSymbol=0)`. The Signet's output units become available only after its complete selected cost order has succeeded.

### One global resource ledger

V3 validation constructs one ledger containing:

```
InitialPoolBucket(bucketKey, availableAmount, consumedAmount)  available aggregate
ActivationOutputUnit(activationIndex, outputIndex)             unavailable until node succeeds
ActivationCostUnit(activationIndex, symbolIndex, unitIndex)    unfilled atomic target
OuterCostUnit(symbolIndex, unitIndex)                          unfilled atomic target
```

The ledger MUST enforce:

1. Every resource has exactly one origin: one published initial-pool bucket capacity or one activation output coordinate.
2. A resource has at most one consumer.
3. Every activation cost unit is filled exactly once.
4. Every outer cost unit is filled exactly once.
5. An activation output is unavailable before its producer node completes.
6. A bucket's aggregate consumption cannot exceed its published `availableAmount`; a resource already consumed for an activation cost cannot also pay the outer cost.
7. A source ID occurs at most once in the activation list for this first slice.
8. No allocation references a resource or target outside the published domain.
9. Unconsumed output is not silently reallocated or discarded by the validator; any post-action floating-mana semantics remain Rules-owned.

The ledger is the single source of truth for both inner and outer payment. This is the required single global resource ledger. A validator MUST NOT validate activation allocations and outer allocations in separate passes that can disagree about ownership.

### Ordered acyclic rules

The `activations` list is the execution order. It is not an unordered set and it does not carry arbitrary graph edges.

For node position `i`:

- its activation-cost allocations MAY reference published initial-pool buckets;
- they MAY reference outputs of nodes at positions `0 .. i-1`;
- they MUST NOT reference its own outputs;
- they MUST NOT reference outputs of positions `i+1 .. end`;
- they MUST NOT reference an output already consumed by another target;
- the node's source MUST be available and legal at the current working state;
- the node's `activationCostOrder` MUST contain each published cost component exactly once;
- each ordered mana component consumes its already assigned ledger resources, and each ordered deterministic non-mana component is applied by its Rules-owned side-effect authority;
- only after every component in the selected order succeeds are the node's output units added to the ledger.

The outer allocation is evaluated after the selected activation list. It MAY consume remaining capacity
from published initial-pool buckets and outputs from completed nodes, subject to the same one-consumer rule.

This ordering makes the graph property constructive:

- self-funding is impossible because a node's outputs do not exist while its own cost is paid;
- forward references are rejected by the earlier-node rule;
- cycles cannot be represented because every dependency points strictly backward in the list;
- no general DAG algorithm is needed;
- a plan with a later-output-to-earlier-cost reference is rejected, even if a general graph could otherwise topologically sort it.

The list order is part of the action carrier and replay input. The validator MUST execute the submitted order exactly; it MUST NOT reorder nodes or infer a different order. Source and ability candidate order is different: it is Rules-owned domain data, not a policy tie-break. A public policy may choose an activation subset and its semantic execution order, but it MUST consume the published source/ability option order and MUST NOT recreate that order from `EntityId`, runtime ability IDs, collection iteration, or an ad hoc tie-break.

### Signet execution example

For a Forest, Signet, and outer `{2}{B}{B}` payment:

```
initial pool
    |
    +--> Forest node
    |        produces G
    |
    +--> Signet node
             selected activationCostOrder:
               ManaComponent: {1} <- Forest output G
               TapSelf (when this component is next)
             produces B and G
                      |
                      +--> outer cost consumes exact output units
```

The exact accepted program has the following logical ledger entries (the two generic outer units
are distinct because their `unitIndexWithinSymbol` values differ):

```
Forest output 0 -> ActivationCostUnit(activationIndex=1, symbolIndex=0, unitIndexWithinSymbol=0)
Signet output 0 -> OuterCostUnit(symbolIndex=0, unitIndexWithinSymbol=0)
Signet output 1 -> OuterCostUnit(symbolIndex=0, unitIndexWithinSymbol=1)
other legal resources -> remaining outer units
```

The Signet output cannot appear as an input to its own activation-cost allocation. The same output cannot appear in both the activation-cost and outer-cost allocations. A later node cannot pay a Forest node's cost.

---

## 4. First support slice and explicit non-goals

V5 initially supports only a mana ability whose complete, effective activation cost is:

```
fixed ordinary mana cost
+
deterministic source-local non-mana cost
```

For the first slice, the deterministic non-mana cost set is exactly `TapSelf`. The support kind is therefore `FixedManaAndTapSelf`. The source object tapped by `TapSelf` is not an ML choice: once the source and ability are selected, Rules deterministically taps that source. The position of `TapSelf` among the cost components remains an explicit plan choice whenever the order characterization finds that position semantically observable.

The effective-cost authority is the Rules-owned `ActivatedAbilityCostCalculator.calculate(...)` path,
applied after resolving the source, stable ability key, current state, and any relevant target/equip
context. `ManaSource.colorActivationManaCost` and related discovery fields are qualification hints only;
they MUST NOT be treated as the final effective Rules cost. Cost increases, reductions, and other current
Rules modifications therefore participate in V5 qualification and validation through the same effective
cost calculation path used by legal-action enumeration and trusted activation handling.

The cost-component order is an explicit contract seam. The canonical source is the
[official Wizards Comprehensive Rules page](https://magic.wizards.com/en/rules), whose currently linked
rules are effective August 7, 2026. The rules permit a player to pay non-random cost components in any
order (601.2h); activated-ability payment follows that procedure (602.2b); and mana abilities may be
activated while paying a mana cost (605.3a).
V5 therefore MUST NOT silently impose `ManaComponent` before `TapSelf`. `activationCostOrderOptions`
publishes the legal order values, and `SourceActivationV2.activationCostOrder` carries the selected
value. If an order characterization proves the supported state class makes the legal orders equivalent,
the domain may publish one Rules-approved canonical order and the plan still carries that explicit value.
If order is observable, the domain MUST publish every supported legal order and the policy's selected
order MUST be replayed exactly.

Before implementation proceeds, the RED characterization MUST determine which of these outcomes holds
for `{1}, {T}: Add {B}{G}` in the supported state class:

1. order is observable, so both legal component orders are externalized wherever legal;
2. order is provably equivalent, so one canonical order is published and the Rules proof is recorded;
3. the engine cannot preserve a meaningful legal order choice, in which case the result is
   `CORE_RULE/ENGINE_GAP` and #106 implementation stops rather than choosing an order implicitly.

No V5 executor or validator implementation may hard-code `ManaComponent`-then-`TapSelf` before this
characterization gate is resolved.

### 601.2g pre-generation versus nested-mana timing

The cost-order gate above is not the only timing question. Under 601.2f, an activated ability's total
cost is determined before its 601.2g mana-ability window; 602.2b applies that process to activated
abilities, and 605.3a permits a mana ability to be activated while another activated ability is being
paid. Therefore V5 MUST characterize both legal shapes for a paid mana source:

```text
A: prerequisite mana ability resolves
   -> paid source is announced and its activation cost is locked

B: paid source is announced and its activation cost is locked
   -> prerequisite mana ability is activated during its 601.2g window
   -> prerequisite resolves
   -> paid source cost components are paid
```

The backward-only ordered program represents A directly. It cannot represent B when the paid source's
activation-cost allocation consumes an output of a later node, because allowing that forward reference
would remove the constructive acyclicity guarantee. `PAY106-MANA-WINDOW-01` MUST therefore establish
one of the following before V5 implementation:

1. A and B are semantically equivalent for the certified V5 first slice. In that case the backward-only
   program remains the normative representation and the characterization is recorded as a pass.
2. A and B are observably different for a represented source/state class. In that case the current V5
   program is insufficient; classify `CORE_RULE/ENGINE_GAP` and stop #106 implementation.

No implementation may assume that pre-generation and nested-mana timing are equivalent. V5 may publish
a paid source only when this timing distinction has been certified irrelevant for that represented
source, effective cost, production profile, and supported state class. This is an internal qualification
precondition, not a hidden policy choice or a request to add a forward edge to V3.

The first slice MUST reject the entire action domain as unsupported when a discovered legal source or legal ability option requires any of the following:

- a choice-bearing secondary cost;
- tap another permanent;
- sacrifice a chosen permanent or another permanent;
- discard, forage, or another hand/graveyard choice;
- dynamic `X`, target-dependent, context-dependent, or unresolved activation mana;
- a hidden or ambiguous production profile;
- an unrepresented restriction, rider, or side effect. `NoSideEffect` is supported directly. A
  `PaymentManaSideEffectCertificate.FixedSelfDamage` shape is supported only when the shared
  Rules-owned execution-stability certificate also proves that its life mutation cannot change
  any later payment fact in the certified ordered-program state class;
- a non-deterministic non-mana cost not represented by a public contract.

The source MUST NOT be omitted merely because one legal option is unsupported. If the complete discovered source set cannot be represented, the whole action domain remains `PAYMENT_DOMAIN_UNSUPPORTED`. This is the same fail-closed completeness rule that makes V4 safe for A5.

The first slice does not attempt to solve every paid mana ability in Magic. It creates a reusable seam that can later add a separately versioned support kind for another fully characterized deterministic cost. Fixed self-damage is not treated as a boolean `NoSideEffect` exception: the Rules-owned certificate must account for the source tap, the controller's life total, the canonical damage/life-history facts that later payment discovery could observe, and the relevant live damage environment. Prevention, redirection, replacement, amplification, protection, lifelink, dynamic, or otherwise additional side effects outside that certificate remain unsupported. The contract does not add a fake generic life field to the mana domain; life costs remain Rules-owned and only enter a future public contract when their exact deterministic semantics are explicitly represented.

---

## 5. Domain-generation contract

A V5 builder MUST follow this sequence:

1. Resolve the legal action and its exact outer cost.
2. Compute only the existing action-source exclusion. For an `ActivateAbility` whose action cost pays `TapSelf`, exclude the action's source. Do not exclude unrelated sources.
3. Discover all currently legal mana sources and all legal mana-ability options visible to the acting perspective.
4. Re-resolve each source/ability option's effective activation cost through the Rules-owned
   `ActivatedAbilityCostCalculator.calculate(...)` path and classify the result against the V5 support
   kind. `ManaSource.colorActivationManaCost` is not sufficient authority for this step.
5. Return `null` for the whole action if any discovered, non-excluded source or legal option cannot be represented.
6. Order the published source candidates by Rules-owned public object order: the battlefield
   `objectIdentityStamp` is primary, and the bounded `BattlefieldEntryTimestampComponent.timestamp`
   is the fallback. Missing or duplicate ranks make the whole action domain unsupported. `EntityId`
   order, collection iteration, and policy-generated source sorting are forbidden.
   This follows the existing Rules-owned `CombatObjectOrder` precedent; V5 should reuse that semantic
   rank policy or a shared primitive rather than copy its historical builder sort.
7. Publish each supported source, stable ability identity in its existing Rules-owned provenance/order,
   production choice, exact effective activation-cost units, deterministic support kind, and legal
   activation-cost order options. Missing stable ability ordering is unsupported; a runtime ability ID
   cannot be promoted into a wire order. Verify that `manaAbilityKey` is unique among that source's
   distinct legal options; a collision makes the whole action domain unsupported.
   The paid-source timing certification from `PAY106-MANA-WINDOW-01` is also required before such an
   option is published. Its side-effect certificate must be `NoSideEffect`, or `FixedSelfDamage`
   paired with a successful life-mutation execution-stability certificate for the complete
   discovered source set; a bare side-effect-type check is insufficient.
8. Publish complete initial-pool bucket capacities and outer atomic cost units.

The builder MUST NOT:

- call `filterNot` to hide an unsupported discovered source;
- select the cheapest ability or a solver-preferred colour on behalf of the policy;
- produce a precomputed plan or a Gym-domain hash;
- read private card definitions or hidden opponent state into the public DTO;
- invoke `ManaSolver.solve()`;
- fall back to `AutoPay`, native AI, a legacy source-only carrier, or an `EntityId`/runtime-ID tie-break.

The current V4 `supportsPaymentPlanV1()` predicate MUST remain historical. V5 uses a new support predicate because “paid activation cost is present” is no longer an automatic rejection if that cost is within the V5 support kind. V4 and V5 are not two names for the same DTO.

---

## 6. Validator and executor contract

### `PaymentPlanValidator.validateV3()`

The V3 validator is a Rules-owned deep module with a small external interface analogous to `validateV2()`:

```
validateV3(
    state,
    playerId,
    outerCost,
    plan: PaymentPlanV3,
    spellContext,
    excludeSources
): PaymentPlanValidation
```

It MUST:

- reject the wrong plan version or an activation list whose indices are not exactly its contiguous
  zero-based list positions;
- reject duplicate source IDs in the activation list;
- re-resolve each source and stable `manaAbilityKey` against the current authoritative state;
- require that the submitted key resolves to exactly one current legal mana-ability option for that
  source; zero matches or a key collision is rejection, never a runtime-ID tie-break;
- re-resolve the effective activation cost through `ActivatedAbilityCostCalculator.calculate(...)` and
  compare the exact canonical cost and supported cost kind;
- re-resolve and compare the exact production profile and output indices;
- reject a source that is currently tapped, absent, illegal, excluded, or otherwise stale;
- reject a source/ability whose activation cost or identity changed since publication;
- reject a source/ability whose `PAY106-MANA-WINDOW-01` timing certification no longer holds for the
  current effective-cost/state class;
- reject an `activationCostOrder` that is not in the current Rules-owned legal order set represented
  by the V5 domain and validate each selected cost component exactly once; this is a fresh
  re-resolution, not a Gym-domain hash comparison;
- normalize all inner and outer allocations into the single global ledger;
- reject missing, duplicate, out-of-domain, forward, self, or already-consumed resource references;
- check every fixed atomic activation-cost unit and every outer atomic cost unit exactly once;
- check every initial-pool bucket's aggregate consumption against its published capacity;
- validate colour, generic, restriction, rider, provenance, and side-effect rules without making a hidden payment choice;
- return a normalized accepted program only after the complete preflight succeeds.

It MUST NOT:

- call `ManaSolver.solve()`;
- choose a source, ability, production option, activation order, resource, or allocation;
- use AutoPay/native heuristics to repair an incomplete plan;
- mutate `GameState`, components, mana pools, sources, or events.

The validator may use Rules-owned source-discovery/profile code to re-resolve current facts. That is a read-only authority check, not a solver search. Public policy never receives that private implementation dependency.

### Ordered-program executor

A new V3 executor, preferably a focused `OrderedPaymentProgramExecutor` that wraps the existing explicit executor rather than changing V1/V2 behavior, MUST:

1. run the complete V3 preflight;
2. retain the original immutable state and empty event result until preflight succeeds;
3. process activation nodes in submitted list order;
4. process each node's submitted or Rules-approved canonical `activationCostOrder`, consuming the
   assigned mana resources and applying each deterministic source-local component at its ordered
   position;
5. use `ManaAbilitySideEffectExecutor` for `TapSelf` and any supported deterministic non-mana
   component; do not assume that it is always a final cost tail;
6. create the node's fixed output units only after the activation has succeeded;
7. process the outer allocation against the same ledger;
8. continue into the existing action effect only after all payment stages succeed;
9. return the original state with no events for any rejected or stale plan.

For Golgari Signet, the executor sequence is explicitly driven by the selected
`activationCostOrder`:

```
process the selected Signet cost order:
  consume the Forest output for Signet's atomic {1} target when `ManaComponent` occurs
  tap Signet through the deterministic side-effect authority when `TapSelf` occurs
apply Signet's fixed B/G production
consume selected output units for the outer cost
resolve the action
```

If the order-characterization gate proves only one canonical order for this state class, the first
two lines execute in that canonical order. If both orders are legal and observable, the submitted order
controls those lines exactly. There is no point at which the executor asks AutoPay to “make the remaining
payment work.” If any stage fails, no partial tap, mana, life, sacrifice, event, or action effect is
observable.

### Rejected/stale-plan atomicity

The implementation MUST preflight the entire ledger and current source facts before any mutation. An execution attempt with:

- a tapped/stale source;
- changed ability key;
- changed activation cost;
- changed output profile;
- forward/self/cyclic reference;
- duplicate resource;
- incomplete target allocation;
- an order value or effective source cost that is no longer legal;

returns rejection with the exact original `GameState` and no new events. Immutable intermediate values MAY be used internally, but they MUST NOT be returned on a failed execution.

---

## 7. Audit consequences for the existing modules

### `PaymentDomainV4`

V4 remains the historical mana-only contract. Its source completeness gate, fixed production profile, and fail-closed `null` behavior remain unchanged. V5 is not a relaxation of V4 and must not be backported into its serialized shape.

### `PaymentSourceActivationDomainV2`

This is the public capability entry for one source/ability option. It adds:

- exact `atomicActivationManaCostUnits`;
- `activationSupportKind`, initially `FixedManaAndTapSelf`;
- the legal `activationCostOrderOptions` for the published cost components;
- all production choices for the stable ability key.

Publication also requires the source-local key to be collision-free and the
`PAY106-MANA-WINDOW-01` timing distinction to be certified irrelevant for the represented case. These
are qualification invariants, not policy-selected fields. No `ManaAbilityIdentityV2` is required for
#106; a collision remains fail-closed until a separately reviewed identity contract exists.

It does not include policy-selected allocations or a nested plan.

### `PaymentPlanV3` and `SourceActivationV2`

These are the explicit action carrier. They contain the chosen ordered nodes, the selected
`activationCostOrder`, and concrete resource references. Node identity is the list index; there is no
arbitrary activation ID. They do not inherit or reinterpret V1/V2 plans.

### `PaymentPlanValidator.validateV2()`

V2 continues to validate only the historical V2 shape. No optional activation-cost field is added and no V2 payload is interpreted as V3.

### `ManaAbilitySideEffectExecutor`

This remains the authority for deterministic source-local side effects. It must not grow a hidden call to `ManaSolver`. The V3 executor processes the selected cost components in their explicit or Rules-approved canonical order and delegates deterministic non-mana application to the existing authority or a narrowly versioned extension.

### `ManaSolver`

The solver's existing automatic first-level handling is not a public contract for recursive paid-source payment. V5 uses explicit domain data and a ledger. The solver may still support legacy native/AI paths where those paths are authorized, but those paths are not available to the public B0 policy.

### Action-source exclusion semantics

Exclusion is an action-cost reservation, not a source capability filter:

- an action source paying `TapSelf` is excluded from the action's own payment-source set;
- a source not used by the action remains discoverable and must be published if legal and representable;
- a source that is discovered but unsupported cannot be silently removed;
- an activation node cannot select an excluded source;
- the same source cannot be selected twice in one V3 program.

This preserves the existing Signet-action behavior while allowing Signet to be a payment source for an unrelated spell.

---

## 8. Alternative comparison

### A. Nested activation-payment plan

Shape:

```
outer plan
  -> activation plan for Signet
       -> nested plan paying {1}
            -> source activations
```

Strengths:

- mirrors the rules-language nesting;
- can be extended recursively to more nested costs;
- each activation looks locally self-contained.

Costs:

- each nested plan tends to create its own ledger, making double-spend and provenance reconciliation cross-ledger;
- recursive validation and execution make self-funding and cycle errors harder to reject constructively;
- replay contains a recursive carrier whose exact ordering and resource ownership must be preserved at every level;
- public policy learns multiple plan interfaces and multiple failure modes;
- a later arbitrary-depth shape is overpowered for the #106 support slice.

Decision: **not selected** for V5. The ordered program may model the same dependency without recursive ledgers.

### B. Flat ordered payment program / dependency graph

Shape:

```
ordered activations + one allocation vocabulary
  -> one global resource ledger
  -> outer allocation
```

Strengths:

- one resource origin and one consumer rule covers inner and outer payment;
- order plus backward-only references make self-funding and cycles impossible by construction;
- validation and replay are deterministic without a general graph algorithm;
- the public domain and plan remain separate;
- all source, ability, production, order, and allocation choices stay external;
- the interface is deep: callers provide a small set of typed references while Rules owns ledger correctness, source re-resolution, and execution;
- it directly represents Forest output paying Signet's `{1}`.

Costs:

- the plan can be longer than a nested notation;
- independent activations still need an explicit stable order;
- future arbitrary choice-bearing costs require new support kinds or a different continuation seam.

Decision: **selected and approved**, refined to ordered acyclic execution with one global ledger, not a
free-form graph, subject to the `PAY106-MANA-WINDOW-01` certification gate. A timing-observable case
cannot be published through this V5 interface.

### Ordered-program execution stability certificate

The V3 validator performs one complete preflight before the executor applies any mutation. Because
an earlier node may tap a different permanent, the executor MUST NOT rely on an initial-state
legality check for an uncertified later node. V5 therefore requires a Rules-owned
`PaymentProgramExecutionStabilityCertifier` for every published payment source. The publisher and
`validateV3()` MUST consume the same certificate path.

The certificate proves that each published mana-ability candidate remains legal, retains the same
effective activation cost and production profile, and remains within the deterministic side-effect
contract throughout any ordered program made from the certified V5 slice. For
`FixedSelfDamage`, the certificate additionally probes the complete deterministic mutation footprint
(source tap, life total, damage/life-history markers, and source-specific canonical trackers) for
every positive cumulative loss up to a conservative upper bound across the discovered painful
sources. It re-discovers the remaining payment sources and compares their availability, ordering,
production, side-effect certificates, restrictions, and effective-cost facts; any difference makes
the complete domain unsupported. This legality proof
includes the authoritative external activation-permission closure: printed and durationally
granted `PlayersCantActivateAbilities` / `PreventActivatedAbilities` shapes are read at activation
time and can change when an earlier `TapSelf` node changes live state. The first slice therefore
rejects every recognized external activation-permission shape until a Rules-owned permission
closure certificate exists; it must never let the executor bypass `CastPermissionUtils`. This
first-slice decision is deliberately conservative: a source is unsupported when its mana ability
has an activation restriction, dynamic or otherwise non-fixed activation cost, an applicable
activated-ability cost
modifier exists on the battlefield, or another represented legality/cost/production fact cannot be
proved stable. The complete action domain is then `null`; the source MUST NOT be silently omitted.

The executor may rely on this certificate instead of revalidating later nodes after mutation only
because full preflight rejects every source outside the certified stability class. A future class
whose legality or cost can change during the program requires an expanded certificate, a nested
payment carrier, or a continuation-based contract. It MUST NOT silently relock a cost or fall back
to a solver after execution has begun.

### C. Explicit continuation-based subpayment

Shape:

```
submit outer action
  -> Rules selects/records Signet activation
  -> continuation opens a separate explicit payment decision for {1}
  -> resume activation
  -> produce mana
  -> resume outer action
```

Strengths:

- aligns with the engine's existing continuation architecture;
- naturally supports interactive secondary costs and future dynamic choices;
- each subpayment can use an established payment-window protocol.

Costs:

- one apparent action becomes a sequence of pending decisions and resumptions;
- public legality becomes stateful across continuation frames;
- replay must record every subpayment response and continuation identity;
- privacy pairing and exact continuation/domain freshness become more difficult;
- the current #106 contract needs one atomic submitted carrier, not a new multi-turn UI protocol.

Decision: **reserved for genuinely interactive or choice-bearing activation costs**, not the first Signet slice.

---

## 9. Required properties and proof obligations

The implementation and RED suite MUST demonstrate all of these:

| Property | Required contract |
|---|---|
| Complete legal source domain | Every legal, non-excluded source and legal ability option is published when representable; otherwise the entire action domain is unsupported. |
| Ability-key uniqueness | Within one source, every published legal mana-ability option has a unique stable `manaAbilityKey`; collisions fail closed without a runtime-ID tie-break. |
| Paid-source timing | A paid source is publishable only after `PAY106-MANA-WINDOW-01` certifies pre-generation and nested 601.2g mana timing equivalent for its represented case. |
| Ordered-program execution stability | Every V5 source is published only after the Rules-owned execution-stability certificate proves later-node legality, effective cost, production, supported side effects, and external activation-permission stability across earlier certified nodes; recognized printed or granted activation-permission statics are unsupported in the first slice, and publisher and validator use the same certificate, otherwise the complete domain is unsupported. |
| No filtering of legal sources | Unsupported sources are never silently omitted to make a domain pass. |
| No AutoPay/native fallback | Public V5 policy and environment accept only a complete explicit V3 plan. |
| External choices | Source, ability, production, activation subset, order, resource, and allocation choices come from the plan. |
| Deterministic ordering | Rules publishes source/ability candidates in an authoritative stable order; activation list order and any observable cost-component order are serialized and executed exactly, with no policy sort or Rules reordering during validation. |
| No self-funding | A node cannot reference its own output for its activation cost. |
| No cyclic dependencies | Only earlier list positions may be referenced; cycles are unrepresentable. |
| Perspective safety | Domain DTO contains only public information available to the observing seat; no Gym-domain hash is required by the Rules plan. |
| Zero mutation on rejection/staleness | Full preflight precedes mutation; rejected plans return the original state and no events. |
| Replay exactness | V3 node order, ability keys, production choices, and every resource reference are persisted and replayed exactly. |
| Historical wire preservation | V1/V2/V4 plan/action/replay carriers remain decodable under their historical versions and are never reinterpreted as V3. |

The single ledger invariant is:

> Each allocated mana unit has exactly one origin and at most one consumer.

For an initial pool, the origin is a published fungible bucket capacity rather than an artificial
wire-level unit ID; the ledger charges that bucket in aggregate. For an activation, the origin is an
individual `ActivationOutputUnit`. “At most one consumer” permits a legal activation to leave an output
unconsumed when the resulting action semantics permits floating mana; it never permits double-spend.

---

## 10. Replay and Gym versioning

### CompactReplay v5

The new `PaymentStrategy.ExplicitV3(PaymentPlanV3)` changes the serialized `GameAction` carrier. Therefore the replay format MUST advance from the current v4 to v5.

Required rules:

```
CompactReplay.CURRENT_VERSION = 5

version >= 5
    OR actions.none(GameAction::usesPaymentPlanV3)
```

The guard is analogous to the existing V4/`ExplicitV2` guard:

- `ExplicitV3` under replay v4 is rejected;
- replay v4 plus `ExplicitV3` is rejected before execution and before a future-version migration can reinterpret it;
- replay v5 round-trips the complete plan exactly;
- v1-v4 retain their historical decode and payment semantics;
- unknown versions greater than 5 fail closed before deserialization/reconstruction;
- an old V4 `ExplicitV2` action remains an `ExplicitV2` action, even when carried by a v5 replay;
- no optional-field trick is used to make V2 mean V3.

The v5 replay action must preserve:

- `PaymentStrategy.ExplicitV3`;
- `PaymentPlanV3`;
- ordered `SourceActivationV2` nodes;
- stable mana-ability keys;
- production choices;
- activation-cost allocations;
- outer allocations;
- initial-pool and activation-output references.

It does not serialize a Gym-domain hash or observation schema identity inside `PaymentPlanV3`.
Replay v5 is required because `ExplicitV3` is a new persisted action carrier with ordered activation
nodes and ledger references, not because Rules consumes a Gym DTO identity. This keeps replay's
historical contract independent of the Gym v1.23 observation schema.

The replay reconstruction path must invoke the V3 executor with the recorded plan. It must not regenerate a plan with `ManaSolver`, infer a dependency order, or treat a rejected V3 action as a legacy action.

### Gym schema

Changing the current `LegalActionView.paymentDomain` from V4 to V5 changes the public Gym DTO and requires a schema bump:

```
argentum-gym-contract@v1.23-paid-mana-source-payment
```

The exact suffix may follow a repo naming convention, but it MUST be unique and clearly identify paid-mana-source payment. V4 remains a historical DTO and is not mutated in place.

Current V5 observations MUST:

- publish `PaymentDomainV5` for every representable payable action;
- publish no domain for an action whose complete source set is unsupported;
- expose no private provenance or hidden source state;
- reject AutoPay, FromPool, source-only, or incomplete V3 submissions;
- preserve the existing strict `PAYMENT_DOMAIN_UNSUPPORTED` boundary.

Privacy-paired states with equivalent public information MUST produce equivalent V5 domain content and
published ordering. Differences visible only to the private seat MUST NOT alter the public domain.

---

## 11. RED/acceptance matrix

These are the required RED and acceptance tests. The implementation status and the separately
reported verification commands are recorded in the current-turn boundary below and in the PR.

| ID | Scenario | Expected result |
|---|---|---|
| PAY106-01 | Forest + Golgari Signet + outer `{2}{B}{B}` | `PaymentDomainV5` exists. |
| PAY106-02 | Inspect the Signet source option | Exact atomic activation cost is `{1}`; support kind is `FixedManaAndTapSelf`; production is fixed `B,G`; legal cost-order options are explicit. |
| PAY106-ORDER-01 | Characterize `{1}, {T}: Add {B}{G}` payment-component order | If order is observable, both legal orders are published and replayed exactly; if provably equivalent, one canonical order is published with the Rules proof; if neither can be represented, classify `CORE_RULE/ENGINE_GAP` and stop #106 implementation. |
| PAY106-MANA-WINDOW-01 | Characterize prerequisite mana timing: prerequisite resolves before paid-source announcement versus paid source cost locks before prerequisite activation in its 601.2g window | If equivalent for the certified first slice, backward-only V3 remains valid; if observably different, classify `CORE_RULE/ENGINE_GAP` and stop #106 implementation. No implementation may assume equivalence. |
| PAY106-KEY-01 | Two distinct legal mana abilities on one source produce the same structural `manaAbilityKey` | Whole action domain is unsupported; no runtime `AbilityId`, description, or collection-order tie-break is allowed. |
| PAY106-EXECUTOR-SEQ-01 | A later mana ability is initially legal but its activation restriction becomes false after an earlier node changes state | The stability certificate makes the complete V5 domain unsupported, or `validateV3()` rejects before the first mutation; original state and events are unchanged. |
| PAY106-EXECUTOR-SEQ-02 | A later mana ability's effective activation cost changes after an earlier node changes state | The stability certificate makes the complete V5 domain unsupported, or `validateV3()` rejects before the first mutation; original state and events are unchanged. |
| PAY106-EXECUTOR-STABILITY-03 | An earlier `TapSelf` activates an external `PlayersCantActivateAbilities` or `PreventActivatedAbilities` permission lock for a later mana source | The complete V5 domain is unsupported, or `validateV3()` rejects before the first mutation; the executor must not bypass authoritative activation permission, and original state/events remain unchanged. |
| PAY106-SIDEEFFECT-01 | Locked-deck-shaped `Battlefield Forge`/`Llanowar Wastes` and generic fixed-self-damage sources are discovered beside an ordinary payable action | Every representable source publishes its `FixedSelfDamage`/`NoSideEffect` profile; the complete `PaymentDomainV5` exists. |
| PAY106-SIDEEFFECT-02 | Select a painful colored activation from a fixed-self-damage source | The ordered V3 executor produces the selected mana, applies exactly one canonical self-damage mutation and its events, completes payment, and never uses native fallback. |
| PAY106-SIDEEFFECT-03 | A painful source is ordered before a later node whose legality observes life history | The shared life-mutation stability certificate rejects the complete domain, or `validateV3()` rejects before mutation; no later illegal node executes. |
| PAY106-SIDEEFFECT-04 | A later node is invalid after the represented pain mutation | The full program is rejected transactionally with the original `GameState` and an empty event list. |
| PAY106-SIDEEFFECT-05 | Select the pain-free colorless output of a mixed pain source | The source produces the selected colorless mana and applies no damage or life-change event. |
| PAY106-SIDEEFFECT-06 | A live damage replacement is present beside a fixed-self-damage source | The damage-environment certificate closes the complete V5 domain before execution; no replacement is approximated and no state/event mutation occurs. |
| PAY106-03 | Forest output pays Signet's atomic `{1}` target; Signet output pays outer atomic cost units | Complete `ExplicitV3` is accepted and resolves with the selected outputs. |
| PAY106-04 | Signet output is assigned to its own `{1}` | Rejected; original state and event list are unchanged. |
| PAY106-05 | A later activation output is assigned to an earlier activation cost | Rejected by the forward-reference rule; zero mutation. |
| PAY106-06 | One Signet output is assigned to both an activation cost and the outer cost | Rejected by the global ledger; zero mutation. |
| PAY106-07 | The same mana source appears in two activation nodes | Rejected; zero mutation. |
| PAY106-08 | One initial-pool bucket is allocated beyond its published capacity | Rejected by aggregate bucket accounting; zero mutation. |
| PAY106-09 | A source becomes tapped/stale after the domain was published | Rejected; zero mutation. |
| PAY106-10 | Ability identity or activation cost changes after publication | Rejected as stale; zero mutation. |
| PAY106-11 | A discovered paid source has an unsupported choice-bearing activation cost | Whole action domain is unsupported; the source is not silently omitted. |
| PAY106-12 | Perspective-paired states expose equivalent public information | Equivalent V5 public domain content and published ordering are produced. |
| PAY106-13 | `ExplicitV3` is encoded in CompactReplay v5 and decoded/replayed | Exact round-trip, exact ordered nodes and allocations. |
| PAY106-14 | CompactReplay v4 contains `ExplicitV3` | Rejected by the version guard; no legacy reinterpretation. |
| PAY106-15 | Exact B0 step 1546 state | `POST_TRANSITION_PAYABLE_ACTIONS=9`; `COMPLETE_PAYMENT_DOMAINS=9`; `MISSING_PAYMENT_DOMAINS=0`. Resume the same seed only until the first new independent gap. |

The last test is not satisfied by “the eight spells now have domains.” It must count all nine payable actions, including the Signet and War Room activations.

---

## 12. Implementation handoff map

No file in this section is modified by this turn. The list defines the later implementation seam and prevents V3 logic from leaking into historical contracts.

### Files and responsibilities

**Rules contract and plan**

- Create `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PaymentPlanV3.kt`: serializable `PaymentPlanV3`, `SourceActivationV2`, `ManaResourceRefV1`, `PaymentTargetV1`, and `PaymentAllocationV1`.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameAction.kt`: add `PaymentStrategy.ExplicitV3` without changing `Explicit` or `ExplicitV2`.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.kt`: add `validateV3()` and the one-ledger preflight; preserve `validate()` and `validateV2()`.
- Create `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/OrderedPaymentProgramExecutor.kt`: execute validated V3 nodes in list order and delegate deterministic side effects.
- Modify the action payment dispatchers under `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/`: add explicit V3 branches; reject V3 in action kinds that do not publish a V5 domain; leave AutoPay as a non-public legacy path.

**Gym public contract**

- Modify or split `gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt`: add `PaymentDomainV5`, `PaymentSourceActivationDomainV2`, support-kind DTOs, and the V5 builder; retain V1-V4 serializers.
- Modify `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt`: route current payable action publication to V5 and preserve action-source exclusion.
- Modify `gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt`: accept complete `ExplicitV3` plans for V5 domains and preserve strict no-fallback behavior.
- Modify `gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt`: carry the current V5 domain while retaining historical V4 decoding where required.
- Modify `gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt`: publish the unique v1.23 paid-source identifier.
- Add `gym/src/test/kotlin/com/wingedsheep/gym/contract/PaymentDomainV5ContractTest.kt` and `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaidManaSourcePaymentTest.kt`.

**Replay**

- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt`: set current version to 5 and add the V3 action-carrier guard.
- Modify replay codec/reconstruction call sites under `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/`: preserve v1-v4 dispatch, reject future versions, and replay V3 exactly.
- Add `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/CompactReplayV5PaymentTest.kt`.

**Documentation**

- Update `docs/data-contracts.md` in the implementation change with the V5 DTO, explicit V3 carrier, Gym v1.23 schema, and CompactReplay v5 rules.
- Keep `docs/architecture-principles.md` aligned with the ordered program's atomic execution and continuation boundary if the implementation adds a continuation only for future choice-bearing costs.

### Later bite-sized execution sequence

Each task is independently reviewable. The task list is the normative implementation sequence; the
current execution boundary is recorded in §14.

#### Task 1: Freeze the V5 domain and ledger RED tests

**Files:**

- Create `gym/src/test/kotlin/com/wingedsheep/gym/contract/PaymentDomainV5ContractTest.kt`.
- Create `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaidManaSourcePaymentTest.kt`.
- Create `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV3ValidatorTest.kt`.

- [ ] Add the exact B0 Signet fixture and assert `PAY106-01`, `PAY106-02`, and `PAY106-15`. The B0 evidence fixture records entity `e165`, the four exact lands, the outer cost `{2}{B}{B}`, and the captured action count 1546. Generic contract tests must locate Golgari Signet semantically by its card and ability shape and must not require `EntityId == e165`.
- [ ] Add the `PAY106-ORDER-01` characterization before implementing the V3 executor: exercise both legal component orders through the current Rules path, record the observable/equivalent/`CORE_RULE/ENGINE_GAP` outcome, and do not bake in a canonical order before that result.
- [ ] Add the `PAY106-MANA-WINDOW-01` characterization before implementing V5 publication: compare prerequisite mana resolving before paid-source announcement with prerequisite mana activated during the paid source's 601.2g window after cost lock; stop at `CORE_RULE/ENGINE_GAP` if the certified first slice is not equivalent.
- [ ] Add a semantic key-collision fixture for `PAY106-KEY-01`; two distinct legal options on one source that serialize to the same structural key must make the whole domain unsupported, without using runtime IDs or collection order.
- [ ] Add ledger-only fixtures for `PAY106-04` through `PAY106-08`. Each fixture must assert the original immutable state and an empty event list after rejection.
- [ ] Add stale source/key/cost fixtures for `PAY106-09` and `PAY106-10`.
- [ ] Run the focused classes with `just test-class PaymentDomainV5ContractTest`, `just test-class GameGymEnvPaidManaSourcePaymentTest`, and `just test-class PaymentPlanV3ValidatorTest`. Expected result before production implementation: the new V5 assertions are RED; existing V1/V2/V4 tests remain unaffected.
- [ ] Commit only the characterization tests if the repository's approved RED workflow calls for a test commit; do not change B0 policy, decks, or card definitions.

#### Task 2: Add the versioned V5 DTOs and explicit V3 carrier

**Files:**

- Create `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PaymentPlanV3.kt`.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameAction.kt`.
- Modify `gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt`.

- [ ] Add `PaymentDomainV5` with outer atomic cost units, initial-pool bucket capacities, and Rules-ordered source activation options; add `PaymentSourceActivationDomainV2` with exact atomic activation units, legal cost-order options, and `FixedManaAndTapSelf`. Do not add a Gym-domain hash.
- [ ] Add `PaymentPlanV3`, `SourceActivationV2`, resource references, atomic targets, and allocations. Require one ordered activation list whose position is the activation identity; preserve list order during serialization and add no arbitrary activation ID.
- [ ] Add `PaymentStrategy.ExplicitV3` as a new serializable discriminator. Keep `ExplicitV2` and all historical plan types byte-compatible.
- [ ] Add round-trip contract tests for the new DTOs and tests that V2 payloads do not decode as V3.
- [ ] Run `just test-class PaymentDomainV5ContractTest` and the existing payment contract classes. Expected result: DTO shape tests pass; domain construction and V3 execution tests remain RED.

#### Task 3: Implement complete-source V5 domain publication

**Files:**

- Modify `gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt` or the V5 builder file selected in Task 2.
- Modify `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt`.
- Modify `gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt`.
- Modify `gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt`.

- [ ] Implement a V5 support predicate that accepts the exact fixed-mana-plus-TapSelf profile and rejects unsupported legal shapes. Make the source-level failure all-or-null.
- [ ] Preserve action-source exclusion before the complete-source gate. Verify that Signet activation excludes `e165`, while spell actions retain `e165` as a required published source.
- [ ] Resolve effective activation costs through `ActivatedAbilityCostCalculator.calculate(...)`, then publish all production choices and exact fixed activation costs without choosing a source, order, or allocation.
- [ ] Publish source candidates in Rules-owned object order (`objectIdentityStamp`, then bounded battlefield-entry timestamp fallback); missing/duplicate ranks fail closed. Characterize and preserve existing Rules-owned stable ability option order; never use `EntityId`, runtime ability IDs, collection iteration, or a policy tie-break. Reject per-source `manaAbilityKey` collisions fail-closed.
- [ ] Gate paid-source publication on the `PAY106-MANA-WINDOW-01` timing certification; do not publish a source whose pre-generation versus nested-601.2g distinction is observably different for the represented state class.
- [ ] Update current observation/environment dispatch to V5 and to Gym v1.23; reject AutoPay/native fallback for V5.
- [ ] Run `just test-class PaymentDomainV5ContractTest` and `just test-class GameGymEnvPaidManaSourcePaymentTest`. Expected result: PAY106-01, PAY106-02, PAY106-11, and the domain-count assertion pass; ledger execution cases remain RED.

#### Task 4: Implement the global-ledger validator

**Files:**

- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.kt`.
- Complete `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV3ValidatorTest.kt`.

- [ ] Normalize activation-cost and outer allocations into one ledger before checking any state mutation; use aggregate initial-pool bucket capacities and atomic cost targets.
- [ ] Enforce earlier-only output references, unique sources, list-index activation identity, exact targets, one-consumer resources, stable ability keys, current effective activation costs, current production profiles, and the selected legal activation-cost order.
- [ ] Resolve each submitted stable ability key to exactly one current legal option and reject zero/multiple matches; re-check the paid-source timing certification before accepting the node.
- [ ] Ensure `validateV3()` contains no `ManaSolver.solve()` call and makes no hidden source/production/allocation choice.
- [ ] Add the accepted Forest-to-Signet ledger fixture and the rejected self-funding, forward-reference, double-spend, duplicate-source, and stale-source fixtures.
- [ ] Run `just test-class PaymentPlanV3ValidatorTest`. Expected result: PAY106-03 through PAY106-10 pass at the validator seam; executor integration remains RED.

#### Task 5: Implement atomic ordered-program execution

**Files:**

- Create `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/OrderedPaymentProgramExecutor.kt`.
- Modify explicit action payment dispatchers under `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/`.
- Complete `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaidManaSourcePaymentTest.kt`.

- [ ] Preflight the entire V3 plan before applying any tap, mana, life, sacrifice, event, or action effect.
- [ ] Execute each node's submitted or Rules-approved canonical cost-component order, consuming assigned mana resources and applying deterministic `TapSelf` through the side-effect authority; expose fixed output units only after every component succeeds.
- [ ] Require the Rules-owned execution-stability certificate for every discovered V5 source in both publication and `validateV3()`; reject the complete domain or plan before mutation when a later node's legality, effective cost, production, or supported side effects could change after an earlier node.
- [ ] Support `FixedSelfDamage` only through the same life-mutation stability certificate used by publication and `validateV3()`; prove the canonical damage/life-history footprint, exact selected output behavior, later-node stability, and transactional rejection evidence. Do not widen support with a bare side-effect-type check.
- [ ] Preserve the certified timing model from `PAY106-MANA-WINDOW-01`; do not emulate a nested 601.2g activation with an illegal forward resource reference or silently relock the activation cost.
- [ ] Pay the outer allocation from the same ledger and continue into the action only after all payment stages succeed.
- [ ] Return the original state and no events for every rejected/stale path.
- [ ] Run `just test-class GameGymEnvPaidManaSourcePaymentTest`. Expected result: PAY106-03 through PAY106-10 pass end-to-end, the sequential stability regressions fail closed before mutation, and there is no AutoPay/native fallback.

#### Task 6: Add CompactReplay v5 and schema/replay acceptance

**Files:**

- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt`.
- Modify the replay codec/reconstructor call sites under `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/`.
- Add `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/CompactReplayV5PaymentTest.kt`.
- Update `docs/data-contracts.md`.

- [ ] Set current replay version to 5 and guard `ExplicitV3` from versions below 5. Do not serialize or validate a Gym-domain hash in the V3 plan.
- [ ] Preserve v1-v4 decoding and reject unknown future versions before partial deserialization or action reinterpretation.
- [ ] Add exact V5 `ExplicitV3` round-trip/reconstruction coverage and the V4-plus-V3 rejection.
- [ ] Run `just test-class CompactReplayV5PaymentTest` and the existing replay contract classes. Expected result: PAY106-13 and PAY106-14 pass without changing historical V1/V2/V4 behavior.

#### Task 7: Re-run exact B0 and stop at the first independent gap

**Files:**

- No deck or card-definition files.
- No B0 policy changes.
- Only the V5 implementation/test files listed above and the documentation contract.

- [ ] Run the repository's existing exact B0 seed/episode harness against the same base/deck inputs and record the exact head, seeds, action count, and closure status.
- [ ] Assert `POST_TRANSITION_PAYABLE_ACTIONS=9`, `COMPLETE_PAYMENT_DOMAINS=9`, and `MISSING_PAYMENT_DOMAINS=0` at the reproduced step.
- [ ] Continue the identical seed only until the first new independent public-domain or execution gap, then stop and classify it. Do not broaden the scope into an unrelated second repair.
- [ ] Run the applicable repository verification recipe through `just`, record focused and full-gate results separately, and do not promote skipped or partial soak coverage to acceptance.

---

## 13. Decision record

| Contract | Decision |
|---|---|
| `PaymentDomainV5` | **Required.** New public domain for complete Rules-ordered source options, atomic outer-cost units, fungible initial-pool buckets, and activation-cost metadata. |
| `PaymentSourceActivationDomainV2` | **Required.** New source capability entry with effective atomic activation units, legal cost-order options, and support kind. |
| `PaymentPlanV3` | **Required.** New explicit ordered payment carrier with one logical resource ledger and no Gym-domain hash. |
| `SourceActivationV2` | **Required.** Ordered list-indexed node with stable ability identity, production choice, selected activation-cost order, and activation-cost allocation. |
| `PaymentStrategy.ExplicitV3` | **Required.** New action discriminator; V2 is never reinterpreted. |
| `PaymentPlanValidator.validateV3()` | **Required.** Complete explicit preflight without `ManaSolver.solve()`. |
| `OrderedPaymentProgramExecutor` | **Required.** Executes each selected cost order before exposing node outputs and preserves atomic failure. |
| Paid-source mana-window qualification | **Required before publication.** `PAY106-MANA-WINDOW-01` must certify that the backward-only program is sufficient for the represented case. |
| Stable mana-ability key uniqueness | **Required before publication.** Per-source structural-key collisions fail closed; no new identity V2 is needed for #106. |
| `CompactReplay v5` | **Required.** The action carrier now stores V3 plans. |
| Gym schema v1.23 | **Required.** Current public observation shape changes from V4 to V5. |
| General arbitrary dependency graph | **Rejected.** Backward-only references in an ordered list provide the needed acyclic structure. |
| AutoPay/native fallback | **Forbidden.** |
| B0 policy/deck/card changes | **Forbidden.** |

The result is the smallest reusable deep module that solves #106 and remains auditable: a public capability domain, an explicit ordered program, a single ledger, and one Rules-owned validation/execution seam. It does not pretend that arbitrary paid abilities are solved, and it does not spend the historical V4/V2/V1 contracts to keep the version number unchanged.

---

## 14. Current-turn verification boundary

The current Task 5 delta covers only the generic `FixedSelfDamage` side-effect qualification and
execution evidence. It does not change B0 policy, locked decks, card definitions, historical V1/V2/V4
contracts, CompactReplay v5, Gym schema migration, `ManaSpentEvent`, or outer non-mana-cost atomicity.
Task 6 and exact-B0 resumption remain out of scope until the remaining Task 5 review questions are
closed.

Current handoff status:

```
SPEC_WRITE=PASS
SPEC_REVIEW_DELTA=APPLIED
WRITTEN_SPEC_APPROVED=YES
GO_TO_RED=COMPLETE
PAY106_SIDEEFFECT_RED=CONFIRMED
PAY106_SIDEEFFECT_DELTA=IMPLEMENTED
PAY106_SIDEEFFECT_EVIDENCE=FOCUSED_PASS
PRODUCTION_CHANGES=TASK5_SIDEEFFECT_ONLY
TASK5_FINAL_REVIEW=PENDING
GO_TO_TASK6=NO
DECKS=UNCHANGED
COMPACT_REPLAY=UNCHANGED
MANA_SPENT_EVENT=NOT_CLASSIFIED
OUTER_NON_MANA_ATOMICITY=NOT_CLASSIFIED
B0_READY_TO_RESUME=NO
DATA_TRUSTED=NO
```

The later RED phase may begin only after this spec is reviewed as the normative contract.

# A5 Public Blocker Declaration Domain V1

## Goal

Publish a complete, perspective-safe, deterministic blocker-declaration domain for strict Gym.
The Rules engine remains the only authority for blocking legality. Gym receives a canonical
projection of a Rules-owned certificate, an external controller fills the existing
`DeclareBlockers.blockers` carrier, and the trusted boundary validates that choice before the
existing Rules processor executes it.

This change closes issue #102 at the first reachable `DeclareBlockers` boundary without changing
B0 policy semantics, adding card-specific logic, adding a second combat engine, or storing the
observation domain in replay.

## Baseline and provenance

This work is isolated from the dirty user checkout and all existing recovery/B0 worktrees.

- repository: `https://github.com/chrismaghuhn/argentum-engine.git`
- base `origin/main`: `adf8516b36d24819ee815ae254e858f3ba995425`
- live `upstream/main`: `e3708751f4769627ddccd732225185a86c049dcb`
- pinned upstream reference for the current roadmap/B0 cycle:
  `d66a5d7f1b46b0ed8891c34ccfe163d491c4ff3d`
- branch: `agent/a5-public-blocker-domain`
- worktree: `C:\argentum-engine-a5-blocker-domain`
- issue: #102
- parent: #98

`origin/main` and `upstream/main` are fetched before implementation. The pinned upstream reference
is reported for provenance only and is not moved to the live upstream head.

## Rules authority

Normative authority:

- authority page: `https://magic.wizards.com/en/rules`
- resolved current rules: Comprehensive Rules effective **August 7, 2026**
- relevant starting section: CR 509, especially 509.1a–i

CR 509.1 treats declaring blockers as one turn-based action. 509.1a chooses blockers and their
attacking creatures; 509.1b checks restrictions; 509.1c checks requirements against the maximum
simultaneously satisfiable set; 509.1d–f determine and pay blocking costs. This design therefore
separates the assignment domain from any later cost or mana decision.

The implementation must inspect the current source for every blocker check actually used by
`BlockPhaseManager` and add no new Magic semantics unless a RED characterization proves a Rules
gap. If the current stateful validator consults a constraint that cannot be represented in the
certificate, the strict path fails closed as unsupported/incomplete.

## Current gap and ownership audit

The current producer/consumer path is:

```text
BlockPhaseManager / CombatManager
  -> CombatEnumerator
  -> LegalAction(validBlockers, blockerMaxBlockCounts,
                mandatoryBlockerAssignments)
  -> ObservationBuilder / LegalActionView
  -> ActionPayloadRequirements(required field: blockers)
  -> GameGymEnv strict submission
  -> ActionProcessor / BlockPhaseManager
```

The Rules owner is `BlockPhaseManager.declareBlockers`. `CombatManager` and `TurnManager` expose
Rules queries, while `CombatEnumerator` creates the `LegalAction` template. The existing flat
fields are compatibility projections and are not a complete external decision domain.

The current authoritative declaration checks include, at minimum:

- blocker existence, creature status, controller, untapped status, current blocking status, and
  face-down handling;
- attacker existence, attacking status, and whether the attacker is attacking the defending
  player/team;
- blocker-level `CantBlock`/projected `cantBlock`/`CantBlockUnless` restrictions;
- all registered `BlockEvasionRule` pair restrictions;
- per-blocker maximum attacker count, including `CanBlockAnyNumber`, projected additional-block
  count, and face-down behavior;
- menace and `CantBeBlockedByFewerThan` minimum blocker counts per attacker;
- printed, granted, conditional, and projected `CantBeBlockedByMoreThan` limits per attacker;
- active `BlockerCountLimit` global declaration caps;
- `CantBlockUnlessCoBlocker` requirements resolved against the full selected blocker set;
- `MustBeBlockedByAll`/Lure-style requirements;
- `MustBeBlockedIfAble` requirements and their declaration-wide maximum matching;
- floating Provoke/`MustBlockSpecificAttacker` assignments; and
- projected `mustBlock` requirements.

The new certificate must either represent every supported one of these constraints or return a
typed unsupported result. It must never turn the current flat fields into a claim of completeness.

## Authority boundary and shared validation

The intended one-way flow is:

```text
Rules blocker certificate
        -> perspective-safe public projection
        -> BlockerDeclarationDomainV1
        -> external blockers map
        -> Rules-owned certificate constraint evaluator
        -> existing stateful object/cost validation
        -> ActionProcessor / BlockPhaseManager
```

The blocker-domain validator is a Rules-owned constraint seam, not a Gym combat engine. The
preferred implementation extracts or reuses the common 509.1a–c certificate constraint evaluator
so that `BlockPhaseManager` delegates the same declaration-wide checks where practical. Stateful
checks that require the live `GameState` (object presence, current zone/incarnation, projected
characteristics, and cost calculation) remain in `BlockPhaseManager` as defense in depth.

If that consolidation would be too invasive, the Rules processor may retain its final checks, but
the implementation must add differential contract tests for every characterized fixture:

```text
RulesBlockerDeclarationDomainValidator(declaration)
    accepts iff
BlockPhaseManager's 509.1a-c validation accepts
```

No Gym-side interpretation of Menace, Provoke, Lure, evasion, or any other blocking rule is
allowed. A mismatch is an implementation defect or an unsupported certificate shape, never a
reason to weaken the public domain.

## Rules-owned certificate

Add a Rules-only `RulesBlockerDeclarationDomain` under `rules-engine`'s legal-action package,
parallel to `RulesAttackDeclarationDomain`. The exact Kotlin names may be refined during RED if
the current validator exposes a different generic primitive, but the certificate must carry these
semantic categories:

```kotlin
data class RulesBlockerDeclarationDomain(
    val blockerToAttackers: Map<EntityId, List<EntityId>>,
    val maxAttackersByBlocker: Map<EntityId, Int>,
    val blockerMustBlockOneOf: Map<EntityId, List<EntityId>>,
    val blockerMustBlockSpecific: Map<EntityId, List<EntityId>>,
    val minBlockersByAttacker: Map<EntityId, Int>,
    val maxBlockersByAttacker: Map<EntityId, Int>,
    val globalMaxBlockers: Int?,
    val coBlockerRequirements: Map<EntityId, List<RulesCoBlockerRequirement>>,
    val mustBeBlockedByAllAttackers: List<EntityId>,
    val mustBeBlockedIfAbleAttackers: List<EntityId>,
    val mustBlockIfAbleBlockers: List<EntityId>,
    val canDeclareZeroBlockers: Boolean,
)

data class RulesCoBlockerRequirement(
    val anyOf: List<EntityId>,
)
```

The names are semantic rather than copied blindly from the legacy fields:

- `blockerToAttackers` contains exactly the current public blocker candidates and, for each, the
  current attacking creatures that the same Rules pair authority permits it to block. The
  defending-player/team relation is resolved here, not inferred by Gym.
- `maxAttackersByBlocker` has an explicit entry for every blocker candidate. A normal blocker is
  represented by `1`; `CanBlockAnyNumber` and the current supported unbounded shape use the
  Rules-defined unbounded value. Projected additional-block capacity is already resolved.
- `blockerMustBlockOneOf` represents Lure/`MustBeBlockedByAll` obligations after resolving each
  eligible blocker to concrete attacker IDs. The declaration must select the blocker and at least
  one applicable attacker from its choice set.
- `blockerMustBlockSpecific` represents concrete Provoke/`MustBlockSpecificAttacker` pins. A
  selected pinned blocker must include its pinned attacker; the field is separate so a Lure choice
  cannot accidentally satisfy a Provoke pin.
- `minBlockersByAttacker` and `maxBlockersByAttacker` represent the resolved per-attacker
  declaration restrictions, including Menace and the supported printed/granted/conditional forms.
- `globalMaxBlockers` represents the smallest active Rules global cap, or `null` when none applies.
- `coBlockerRequirements` resolves each supported `CantBlockUnlessCoBlocker` filter to one or more
  concrete `anyOf` sets of other public blocker candidates. Every group is an all-of requirement;
  one selected member of each group is required.
- `mustBeBlockedByAllAttackers` identifies attackers whose all-able-blocker requirement is active.
- `mustBeBlockedIfAbleAttackers` identifies attackers participating in the Rules maximum-matching
  requirement. The validator uses the published relation and published Provoke/Lure constraints
  to reproduce the current matching semantics; it does not reduce this to independent booleans.
- `mustBlockIfAbleBlockers` contains concrete projected `mustBlock` blockers that must be selected
  when the published relation shows they are able to block.
- `canDeclareZeroBlockers` is the direct result of the Rules-owned 509.1a–c pre-cost evaluation of
  an empty declaration. It is not derived from the absence of a list or from
  `mandatoryBlockerAssignments.isEmpty()`.

The certificate builder is responsible for resolving all filters, conditions, projections,
conditional abilities, team/defender relationships, and matching inputs needed by these fields.
It must not publish raw filters, `GameState`, `CardRegistry`, evaluator instances, hidden
provenance, or private card data.

### Certificate invariants

The Rules certificate is complete only when all of the following hold:

- every blocker key has a non-empty legal attacker list;
- every attacker relation is duplicate-free and only names a current attacking entity;
- max counts are non-negative and cover every blocker key;
- all requirement references are concrete relation members;
- co-blocker groups are non-empty, exclude the owning blocker, and contain only blocker keys;
- per-attacker min/max bounds are non-negative and mutually consistent;
- the global cap is null or non-negative;
- the required/matching lists are duplicate-free and refer to published entities; and
- the certificate can be consumed by the shared Rules-owned validator without consulting hidden
  state or re-evaluating a private filter.

If any current supported validator path produces a shape outside these invariants, the result is
`RulesBlockerDeclarationDomainResult.Unsupported` with a stable reason. A partial certificate is
never registered as a supported legal action.

## Zero-block declaration and blocking costs

`canDeclareZeroBlockers` is calculated by the Rules-owned 509.1a–c evaluator for
`DeclareBlockers(playerId, emptyMap())`. Blocking costs are intentionally excluded from that
calculation. A domain-valid assignment may still require a later externally controlled blocking-
cost decision before the declaration completes:

```text
BLOCKER_DECLARATION (509.1a-c)
  -> optional existing BLOCKING_COST_PAYMENT continuation (509.1d-f)
  -> committed blocks (509.1g)
```

The implementation must characterize the CR 509.1c interaction where a blocker can block only if a
cost is paid. If the current engine incorrectly treats that blocker as mandatory merely because it
would increase the number of requirements obeyed, classify the smallest generic Rules gap before
changing behavior. Do not hide the issue in the DTO or create a Gym payment approximation.

## Canonicalization and ordering

Blocking assignments are semantically unordered at the map/key and per-blocker attacker-list
levels unless the current Rules validator proves otherwise. Producer-owned canonicalization is
required, but `EntityId.value` is **not** presumed to be the ordering authority.

Before implementation chooses an ordering key, add a characterization over equivalent Rules states
whose map/set insertion and construction orders differ. The candidate key must be demonstrated to
be both:

1. semantically stable for the relation being published; and
2. perspective-safe and reproducible across forks, snapshots, and replay.

The implementation may reuse an already-proven public observation canonical order or a Rules-owned
stable object rank if the characterization proves it has those properties. It may not introduce a
new durable identity framework, sort by allocation order, hash iteration, `toString()`, UUID, or
silently sort by `EntityId.value`. If no existing key passes the characterization, stop with a
canonical-order/engine-gap finding and leave the strict blocker domain unsupported.

The public DTO does not make collection order a gameplay choice. The mapper and semantic
canonicalizer must use the producer's certified order consistently, and the tests must prove that
equivalent insertion-order variants produce identical domain semantics.

## Public DTO and projection

Add the versioned Gym DTO:

```kotlin
const val BLOCKER_DECLARATION_DOMAIN_VERSION: Int = 1

@Serializable
data class BlockerDeclarationDomainV1(
    val version: Int = BLOCKER_DECLARATION_DOMAIN_VERSION,
    val blockerToAttackers: Map<EntityId, List<EntityId>>,
    val maxAttackersByBlocker: Map<EntityId, Int>,
    val blockerMustBlockOneOf: Map<EntityId, List<EntityId>>,
    val blockerMustBlockSpecific: Map<EntityId, List<EntityId>>,
    val minBlockersByAttacker: Map<EntityId, Int>,
    val maxBlockersByAttacker: Map<EntityId, Int>,
    val globalMaxBlockers: Int?,
    val coBlockerRequirements: Map<EntityId, List<BlockerCoBlockerRequirementV1>>,
    val mustBeBlockedByAllAttackers: List<EntityId>,
    val mustBeBlockedIfAbleAttackers: List<EntityId>,
    val mustBlockIfAbleBlockers: List<EntityId>,
    val canDeclareZeroBlockers: Boolean,
)

@Serializable
data class BlockerCoBlockerRequirementV1(
    val anyOf: List<EntityId>,
)
```

The final field names may change only to improve clarity without reducing the represented
semantics. `LegalActionView.blockerDeclarationDomain` is non-null for a supported
`DeclareBlockers` action and absent for non-combat actions or unsupported/legacy entries. Unknown
future versions fail closed before use.

The pure mapper:

1. verifies the Rules certificate's complete structural invariants;
2. collects every blocker, attacker, and requirement reference;
3. checks every reference with the existing perspective-safe
   `Visibility.isEntityReferenceAddressableTo` authority;
4. applies the already-proven producer ordering key; and
5. returns the complete V1 DTO.

It never filters hidden IDs into a smaller domain, reconstructs a card predicate, exposes raw
engine state, or silently drops an unaddressable relation. A failed projection returns the stable
unsupported diagnostic for the whole trusted observation.

## Required payload and strict submission seam

`ActionPayloadRequirements` already requires `blockers` for `DeclareBlockers`. The contract matrix
must enforce:

```text
requiredPayloadFields contains "blockers"
    iff the current action contract requires the blockers payload

requiredPayloadFields contains "blockers"
    -> complete Rules certificate exists
    -> complete BlockerDeclarationDomainV1 is published
```

Add a Rules-owned `BlockerDeclarationDomainValidator` (or a shared equivalent) that receives the
registered certificate snapshot and a `DeclareBlockers` action, never `GameState`. It validates the
complete payload shape and membership, including:

- actor equality and action type;
- unknown blocker/attacker rejection;
- blocker keys and non-empty attacker lists;
- every selected blocker→attacker edge;
- each per-blocker max count;
- minimum and maximum blockers per selected attacker;
- global blocker cap;
- concrete mandatory one-of and specific assignments;
- every co-blocker all-of group;
- Lure all-able coverage;
- `mustBeBlockedIfAble` maximum matching using the certificate's resolved relation and pins;
- projected must-block blockers; and
- empty declaration legality from `canDeclareZeroBlockers`.

Malformed certificates and unknown future DTO versions are rejected. The validator must not call a
Gym policy, read hidden state, or choose missing assignments.

`GameGymEnv.step(actionId, payload)` invokes this seam after required-field/decoding checks and
before `GameEnvironment.stepFromCandidateStrict`. The existing current-candidate/freshness guard
remains in force. A valid certificate submission then enters the existing `ActionProcessor`; the
Rules processor remains authoritative for final stateful validation, blocking-cost continuations,
events, and commitment.

Rejected submissions are not transitions. The pre-processor path must not consume RNG, mutate the
pending/current legal domain, advance continuation or replay state, alter turn/priority/combat,
increment semantic external-decision or accepted-transition counters, create block assignments,
or change payment state. Diagnostics may differ only as non-authoritative metadata.

## Privacy boundary

The domain publishes only public attacking entities, public defending-player/team relationships,
public blocker identities, public projected effects/restrictions, and concrete IDs already
addressable to the acting defender. It must not publish or depend on hidden opponent hand/library,
hidden exile, face-down identities not legally known, evaluator internals, CardRegistry metadata,
future policy choices, or hidden-state-dependent identifiers.

A paired-state privacy test must hold the defender's legal information set constant while changing
only hidden opponent information. The projected blocker domain, its semantic canonical form, and
its public action view must remain equivalent.

## Replay

Do not add the domain to `CompactReplay`. Replay continues to store the existing semantic action
carrier:

```text
DeclareBlockers.blockers
```

On reconstruction, the deterministic Rules state regenerates the blocker certificate and validates
the recorded declaration against that regenerated domain before normal Rules execution. A replay
test must prove action encode/decode equality, exact reconstruction, and identical semantic combat
result. If the audit discovers that current replay cannot preserve a blocker declaration, stop and
classify that finding as an A6 replay blocker rather than inventing a B0-only wire shape.

## RED characterization

Before production implementation, add and run the focused failing characterization against this
baseline. It must prove that a reachable `DeclareBlockers` action currently has:

```text
requiredPayloadFields = ["blockers"]
LegalActionView.blockerDeclarationDomain = absent
```

The RED matrix includes:

- simple one-blocker/one-attacker choice;
- one blocker with multiple legal attackers;
- multiple blockers with insertion-order variants;
- a multi-blocking-capability blocker when supported and reachable;
- mandatory Provoke assignment;
- Lure/`MustBeBlockedByAll` choice;
- Menace or another supported minimum-blocker restriction;
- a supported attacker maximum-blocker restriction;
- a supported global blocker cap;
- a supported `CantBlockUnlessCoBlocker` relation;
- `MustBeBlockedIfAble` matching and conflicting requirements;
- projected must-block;
- an explicit legal no-block declaration;
- blocking-cost interaction; and
- privacy-equivalent paired states.

For small characterized fixtures only, test code may enumerate candidate declarations and compare
the shared Rules certificate predicate with `BlockPhaseManager`'s 509.1a–c result. This is a
differential test, not a production full-enumeration strategy.

## Execution and regression gates

The implementation is accepted only when focused tests demonstrate:

- an external chooser builds a valid blocker payload from `TrainingObservation` and
  `BlockerDeclarationDomainV1` alone;
- no-block, multiple-choice, mandatory/restriction, and supported multi-block declarations reach
  `ActionProcessor` and create the expected authoritative block state;
- malformed, unknown, stale, out-of-domain, over-cap, mandatory-violating, and global-constraint-
  violating submissions are rejected with zero authoritative mutation;
- canonicalization is independent of map/set insertion order and does not rely on an unproven
  `EntityId.value` order;
- paired hidden-information states publish equivalent domains;
- replay codec and reconstruction preserve the accepted declaration exactly; and
- the issue #102 seed-0 episode crosses the former blocker boundary using the public contract.

Run focused tests first, then relevant Rules combat, Gym contract/strict, privacy, replay, and
Environment V1 exact-pair tests. `just` remains the required project gate; if Windows raises
`WinError 193` before Gradle starts, record that gate as `BLOCKED` and label any native
`gradlew.bat` run as fallback evidence. Do not rebless unrelated snapshots or claim the complete
B0 corpus from this PR.

The exact seed-0 follow-up must report separately:

```text
FORMER_BLOCKER_REACHED=YES
FORMER_BLOCKER_PUBLIC_DOMAIN_PRESENT=YES
FORMER_BLOCKER_EXTERNAL_RESPONSE_CONSTRUCTED=YES
FORMER_BLOCKER_ACCEPTED=YES
POST_BLOCKER_PROGRESS=<evidence or next independent blocker>
```

If the next independent trust gap appears, stop at that boundary and report it separately. Do not
expand this change into the next issue.

## Documentation, schema, and scope

Update the public data/decision contract documentation with:

- `BlockerDeclarationDomainV1` and its version;
- Rules ownership and the certificate/projection/validation flow;
- all represented declaration-wide constraints;
- explicit empty declaration semantics;
- canonicalization authority and fail-closed ordering behavior;
- privacy boundary;
- blocking-cost continuation after a domain-valid assignment; and
- replay's unchanged action carrier and regenerated domain.

Bump the Gym schema hash because `LegalActionView` changes shape. Keep replay version unchanged
only after the replay-wire audit passes. Do not change the B0 corpus, decks, cards, native AI,
heuristic assignment, B1 performance, B2 trajectories, or unrelated combat semantics.

The design does not claim global A5 acceptance. It establishes only the issue #102 implementation,
hosted-CI, code-review, and final-acceptance statuses after their respective gates close. Final
acceptance cannot be reported YES before merge and independent final review.


# C0_04A Environment V1 tie-totality characterization

## 1. Status / authority

```text
TASK=C0_04A_ENVIRONMENT_V1_TIE_TOTALITY_CHARACTERIZATION
DATE=2026-09-12
STATUS=CHARACTERIZATION_PASS_SOURCE_BACKED_NON_TOTAL
BASE=5b4c1ab741e2c098febd83003b32770743c34954
SOURCE_HEAD_AT_AUDIT=5b4c1ab741e2c098febd83003b32770743c34954
BRANCH=chris/c0-04a-environment-v1-tie-totality-20260912
ORIGIN_MAIN=5b4c1ab741e2c098febd83003b32770743c34954
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
PR_178=MERGED
PR_178_MERGE_PARENTS=57a22d64711c760c274b7f366a58ac56bdda7b09,3c8104eff4835494db2eadc49e712c28b1f6b7ce
```

`origin` and `upstream` were fetched before the audit. `origin` is the writable fork
`https://github.com/chrismaghuhn/argentum-engine.git`; `upstream` is the reference-only
`https://github.com/wingedsheep/argentum-engine.git`. Upstream was not integrated.

The accepted entry state remains Environment V1, Phase A/B0/B1/B2 complete, `DATA_TRUSTED=YES`,
and C0-01/C0-02/C0-03 accepted. C0-04 is still an intermediate merge with its deterministic
baseline blocked by `TIE_BREAK_IDENTITY_GAP`.

## 2. Scope

This is a source audit of deterministic exact-score tie resolution for the accepted fixed
Akiri-vs-Chevill Environment V1 surface. It covers root action candidates, folded decisions,
typed structured domains, and every policy-owned structured prefix that the accepted C0-01/B2
closure marks reachable.

The exact curriculum inputs were not changed:

* [`akiri-v0.1.txt`](curriculum/akiri-v0.1.txt), including 17 `Plains` and 10 `Mountain` entries.
* [`chevill-v0.1.txt`](curriculum/chevill-v0.1.txt), including 15 `Forest` and 13 `Swamp` entries.

This characterization does not implement policy RNG, stochastic inference, a model, training,
RL, self-play, a checkpoint, a Rules change, a Gym semantic change, or a trajectory-schema
change. It does not reopen any accepted gate and does not make a universal-Magic claim.

## 3. Accepted C0 dependencies

The audit treats these accepted contracts as normative:

* C0-01 admits complete domains and distinct source candidates, but excludes raw `EntityId`,
  candidate row position, routing IDs, allocation order, and other incidental identity from model
  features or preference keys. See [`c0-model-facing-sample-and-candidate-scoring-contract-v1.md`](c0-model-facing-sample-and-candidate-scoring-contract-v1.md), §§5-9.
* C0-02 freezes split/evaluation identity and does not turn collection or batch order into policy
  semantics.
* C0-03 isolates recurrent state; prior hidden state is not an arbitrary identity oracle.
* C0-04 defines finite deterministic argmax and fail-closed unresolved ties, but explicitly leaves
  Environment V1 totality unestablished. See [`c0-checkpoint-identity-and-deterministic-inference-contract-v1.md`](c0-checkpoint-identity-and-deterministic-inference-contract-v1.md), §§18-21.

The accepted B2 family closure is the reachability inventory, not a proof that a bounded corpus
observed every possible collision. See [`b2-a8-exact-pair-decision-family-closure-2026-09-05.md`](b2-a8-exact-pair-decision-family-closure-2026-09-05.md), §§4-6, and
[`b2-a9-decision-family-closure-audit-2026-09-06.md`](b2-a9-decision-family-closure-audit-2026-09-06.md).

## 4. Exact blocker definition

For a legal domain `D`, let `T` contain all executable alternatives with the same maximal finite
policy score. A deterministic selector is total only if it can choose exactly one member of `T`
using a discriminator that is unique, source-authoritative, policy-admissible, permutation
invariant, runtime-ID-renaming invariant, perspective-safe, and not a hidden policy.

The following remain forbidden:

```text
candidate row or producer iteration position
raw EntityId or any hash of raw EntityId
actionId, decisionId, nonce, generated abilityId, allocation stamp
object allocation order, map/filesystem/batch/worker/PID order, wall clock
hidden library position or raw GameState ordering
recurrent state used as an identity oracle
```

`UNRESOLVED_DETERMINISTIC_TIE=FAIL_CLOSED` is the current valid behavior. It is not a totality
proof and it does not collapse source-distinct alternatives.

## 5. Model-equivalence relation

Two alternatives are `MODEL_EQUIVALENT` when their C0-01-admitted representations agree after a
consistent bijective renaming of relational handles, while preserving typed relations, declared
semantic order, and multiplicity. A raw handle is retained for exact binding, but its literal is
not a learned feature or tie preference.

This distinction is visible in the current code:

* [`LegalActionView`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt#L235-L306)
  retains source/target handles and the complete action payload.
* [`ObservationBuilder`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt#L651-L721)
  binds `sourceEntityId` and `actionSemantics` for every legal action.
* [`ObservationCanonicalizer`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt#L215-L269)
  includes `sourceEntityId` and relational arrays in the durable semantic fingerprint.
* C0-01 classifies those relations as binding-only and explicitly admits that distinct source
  choices can have identical features.

Therefore:

```text
UNIQUE_SOURCE_CANDIDATE != UNIQUE_POLICY_SEMANTIC_TIE_KEY
```

## 6. Deterministic totality criterion

The accepted source key may be either:

1. a gameplay-semantic key, such as a visible card definition, boolean, color, or complete
   source-semantic action variant; or
2. a source-declared local semantic slot/order, such as an option slot, mode slot, or the explicit
   current top-first order of a reorder-library domain.

It may not be a candidate's physical list position. Canonical serialization order is not a policy
preference. For structured decoding the criterion applies at every prefix: choosing the next
source, production mode, target, ordered object, edge, or allocation must itself be total.

## 7. Reachable decision-family inventory

The accepted B2 closure marks these policy-owned families reachable for the fixed pair:

```text
PRIORITY / ACTION_CANDIDATES
PassPriority, PlayLand, CastSpell, ActivateAbility, CastSpellMode,
CastWithFlashback, CastWithKicker, CycleCard, DeclareAttackers, DeclareBlockers
DECISION folded proxy
CHOOSE_TARGETS, SELECT_CARDS, YES_NO, CHOOSE_COLOR, CHOOSE_OPTION,
ORDER_OBJECTS, REORDER_LIBRARY, COMBAT_RESOLUTION, SELECT_MANA_SOURCES
```

The current typed structured-domain versions are targets v2, card-selection v1, mode-selection
v1, distribution v1, ordering v1, split-piles v1, search-library v1, reorder-library v1,
combat-resolution v1, mana-sources v3, replacement v1, and budget-modal v1. The latter generic
types are not silently admitted as reachable merely because a DTO exists.

The accepted exact-pair distinctions are important: Outpost Siege is `CHOOSE_OPTION`, not a
runtime `CHOOSE_MODE` pending decision; exact library searches use `SELECT_CARDS`; and Read the
Bones supplies `REORDER_LIBRARY`.

## 8. Candidate / root-action audit

`PlayLandEnumerator` emits one `PlayLand(playerId, cardId)` action for each land in the hand
(`rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/enumerators/PlayLandEnumerator.kt#L19-L33`).
`ObservationBuilder.actionSourceEntityId` maps that action to the card instance
(`#L1847-L1862`), while C0-01 excludes that instance handle from model preference semantics.

The exact Akiri deck has 17 `Plains`. A legal hand containing two of them therefore produces two
source-distinct `PlayLand` alternatives with identical visible definition/state features. The
same repeated-permanent construction applies on the battlefield to the intrinsic mana abilities
of two untapped Plains; `ActivatedAbilityEnumerator` emits one action per battlefield entity.

At the root level, exact non-basic card definitions are singleton entries in each fixed deck, so
the visible `cardDefinitionId` is a semantic discriminator for `CastSpell`, `CastWithFlashback`,
`CastWithKicker`, and `CycleCard` root candidates. This conclusion is root-only: a unique spell
candidate does not make its nested target, payment, sacrifice, or mode prefixes total.

`CastSpellMode` and the kicker/mode variants retain source-declared mode/cost slots. Those slots
are legitimate local semantics when they are the actual Rules choice, not a derived candidate-row
rank.

## 9. Targets audit

`GenerousGift` is in the locked Akiri deck and its definition targets a permanent
([`GenerousGift.kt`](../../mtg-sets/2008-2016/src/main/kotlin/com/wingedsheep/mtg/sets/definitions/mh1/cards/GenerousGift.kt#L16-L35)).
After two identical visible Plains are on the battlefield, both are legal members of its
single-target domain.

`ActionTargetDomainMapper` copies the complete target requirement and sorts its candidate handles
for producer-canonical transport (`ActionTargetDomainMapper.kt#L45-L76`). That sort is a stable
serialization convention, not a semantic preference. `TargetRequirementDomain` contains the
candidate handles and constraint flags but no per-target semantic instance key or public card
metadata. `ChosenSemanticInput.validateTargets` preserves exact membership and cardinality; it
does not select one equivalent target.

The target prefix therefore has a reachable unresolved symmetry. A future equivariant scorer is
allowed to assign equal scores to the two Plains, and neither row order nor the lower raw handle
is admissible.

## 10. Card-selection audit

`FaithlessLooting` is in the locked Akiri deck and its definition draws two cards and then discards
two (`FaithlessLooting.kt#L11-L27`). With two Plains and another card in the hand, the complete
discard domain can contain the distinct source choices `{Plains-A, X}` and `{Plains-B, X}`.
The choices have the same admitted card-definition/state features and differ only in the card
instance handles.

The current `CardSelectionDomain` requires distinct option handles and preserves `cardInfo`,
cardinality, orderedness, and constraints. `ChosenSemanticInput.validateCardSelection` checks
membership, bounds, and semantic constraints but does not provide an instance discriminator. For
single-select unordered decisions, `ObservationBuilder` folds one response per option into the
`DECISION` proxy; this does not make identical instances semantically equal.

## 11. Payment audit

`PaymentDomainV5.sourceActivationOptions` is keyed by `(sourceId, manaAbilityKey)`. Its constructor
rejects duplicate keys, so two Plains with the same intrinsic white-mana ability are deliberately
retained as distinct source options rather than collapsed
([`PaymentDomain.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt#L361-L405)).
The V5 builder discovers every source, orders them through `CombatObjectOrder`, and publishes each
option with its `sourceId` and `manaAbilityKey` (`PaymentDomain.kt#L576-L613`, `#L752-L842`).

`PaymentPlanV3.SourceActivationV2` then requires the exact source handle in the submitted program
([`PaymentPlanV3.kt`](../../rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PaymentPlanV3.kt#L209-L235)).
For a generic payment with two equivalent Plains, plan A activates Plains-A and plan B activates
Plains-B. They are distinct exact programs and are both valid, but no admitted field distinguishes
the sources semantically.

`CombatObjectOrder` uses state-owned object ranks/timestamps for engine serialization order and
explicitly does not make EntityId or collection iteration authoritative. That rank is not a
policy tie key. The payment source prefix is therefore non-total, as are any later allocation
prefixes whose only distinction is which equivalent source supplied the resource.

## 12. Ordering / reorder audit

`ORDER_OBJECTS` has two different cases:

* Trigger objects with genuinely different source/ability semantics can use the stable tagged
  trigger label. `TriggerProcessor` replaces runtime trigger handles with normalized ordinal
  handles and labels (`TriggerProcessor.kt#L247-L321`), and chosen-response validation rejects
  indistinguishable semantic aliases (`ChosenSemanticInput.kt#L1171-L1280`).
* The duplicate-label suffix (`simultaneous instance n of m`) is occurrence identity generated by
  the producer. This audit does not treat it as a gameplay-semantic preference or prove that every
  reachable duplicate-trigger group is total. The family is consequently `INSUFFICIENT_EVIDENCE`
  for a universal exact-pair verdict, even though the distinct-label cases are total.

`REORDER_LIBRARY` is different. `ReorderLibraryDomain` explicitly defines its list as current
top-first, and the chosen order is a Rules-significant result. The current source slot relation is
therefore a legitimate semantic order for resolving an exact tie, including when two cards share a
definition. This is not permission to read an unexposed library coordinate or use an EntityId.

## 13. Combat audit

The locked Akiri deck contains Kemba, Kha Regent and multiple Equipment. Kemba's Rules definition
creates one 2/2 white Cat for each Equipment attached to it
([`KembaKhaRegent.kt`](../../mtg-sets/2008-2016/src/main/kotlin/com/wingedsheep/mtg/sets/definitions/som/cards/KembaKhaRegent.kt#L10-L33)); the existing scenario proves that two attached Equipment create two Cats
(`KembaKhaRegentScenarioTest.kt#L71-L78`). Those tokens are distinct entities with identical
public characteristics.

Consequently, the attacker and blocker declaration surfaces can contain two symmetric legal
entity choices. `AttackDeclarationDomainV2` and `BlockerDeclarationDomainV1` preserve relation
maps, capacities, and requirement multiplicity, but their object IDs are relational handles. The
declaration surface has no source-semantic instance key beyond those handles.

The accepted B2 closure also establishes the exact trample/combat-resolution producer path. In a
reachable blocked-trample state containing equivalent Cat blockers or equivalent token attackers,
the combat edge/source assignment alternatives differ only by those handles. `CombatResolutionDomain`
and its validator preserve exact edges and bounds; they do not provide an invariant tie key.
This is classified as `NON_TOTAL_REACHABLE_SYMMETRY` by source reachability plus the existing
Kemba/trample witnesses, not by absence in the bounded trajectory corpus.

## 14. Simple scalar / option audit

The following values are semantically self-identifying and total for the currently reachable
shapes:

* `YES_NO`: `true` and `false` are distinct gameplay values.
* `CHOOSE_COLOR`: the `Color` enum value is semantic.
* Numeric choices, although not reachable as a named pending family in this pair, would use the
  source numeric value rather than row order.

`CHOOSE_OPTION` uses `OptionChosenResponse.optionIndex` over the Rules-owned `options.indices`.
That index is a source-local option slot, not a candidate batch position; the source option's
meaning and execution slot legitimately distinguish it. The exact Outpost Siege path has two
source options and is total by source semantic order.

## 15. Structured-prefix audit

The audit was applied at each policy-owned prefix rather than only to a completed response:

| Prefix | Exact identity retained | Tie result |
| --- | --- | --- |
| target requirement → next target | target EntityId and typed relation | non-total for equivalent Plains/creatures |
| card-selection → next selected card | card instance EntityId and `cardInfo` | non-total for equivalent card instances |
| folded single-select card response | selected card EntityId | non-total for equivalent sacrifice/card instances |
| payment → next source activation | source EntityId + mana ability key | non-total for equivalent Plains |
| payment → production/cost/allocation | typed production and unit slots plus source refs | source-slot cases can be total; source-ref symmetry remains non-total |
| ordering → next object | tagged trigger semantic alias or entity ref | distinct trigger aliases total; duplicate-label case insufficient |
| reorder-library → next object | explicit current top-first source slot | total by source semantic order |
| combat → next edge/amount | source/target edge relation and amount bounds | non-total for symmetric entity relations |

No complete response is collapsed. A full response can be unique under exact source binding while
an intermediate prefix remains symmetric; that still blocks deterministic policy totality.

## 16. Existing corpus evidence

The accepted B2 A8/A9 artifacts establish the exact Environment V1 family closure, including
reachable `PlayLand`, `ActivateAbility`, `CHOOSE_TARGETS`, `SELECT_CARDS`, `ORDER_OBJECTS`,
`REORDER_LIBRARY`, `COMBAT_RESOLUTION`, and `SELECT_MANA_SOURCES`. They also record the bounded
72-episode/64-cell evidence and its targeted rare-family witnesses.

That corpus is witness evidence only. `NO_OBSERVED_SYMMETRY != TOTALITY_PROOF`: a bounded run's
absence of a duplicate-feature collision cannot exclude a legal two-Plains or two-token state.

Relevant inspected existing tests include:

* `gym/src/test/kotlin/com/wingedsheep/gym/contract/CandidateDomainDigestTest.kt` for action-ID
  exclusion, candidate canonicalization, and duplicate full-candidate rejection.
* `gym/src/test/kotlin/com/wingedsheep/gym/A9DecisionFamilyClosureAudit.kt` for the accepted
  source-derived family matrix.
* `GenerousGiftScenarioTest`, `FaithlessLooting` source/selection paths,
  `KembaKhaRegentScenarioTest`, `GarrukRelentlessScenarioTest`, and the accepted payment-domain
  tests for the relevant Rules producers.

No new executable characterization test was needed: the source contracts and fixed-deck witnesses
already prove a reachable non-total case. No test file, golden, deck, or production source was
changed.

## 17. Focused characterization evidence

### Source proof: candidate identity is not a tie key

`CompleteLegalDomainV1` preserves the complete candidate list and rejects exact duplicate canonical
fingerprints (`CandidateDomainDigest.kt#L312-L317`). Its semantic action fingerprint contains
`sourceEntityId` (`ObservationCanonicalizer.kt#L215-L269`). Thus two card instances with different
handles are valid distinct source candidates. C0-01 separately classifies those handles as
relational/binding data, not semantic preference features.

This proves the required separation:

```text
DUPLICATE_CANDIDATE_REJECTION=source-domain integrity
TIE_RESOLUTION=policy selection identity
DUPLICATE_CANDIDATE_REJECTION != TIE_RESOLUTION
```

### Source-backed fixed-deck witnesses

The fixed decks contain repeated basic-land definitions, and the exact card definitions provide
legal roots and structured decisions that address those instances. The witnesses are structural
reachability proofs, not random trajectory observations.

## 18. Family-by-family verdict table

| Family | Reachable | Model-equivalent distinct alternatives possible? | Valid invariant discriminator | Verdict | Evidence |
| --- | --- | --- | --- | --- | --- |
| `PassPriority` root | YES | No multi-instance choice at one boundary | action kind / singleton domain | `TOTAL_BY_SEMANTIC_KEY` | SOURCE_PROOF |
| `PlayLand` root | YES | Yes: two Plains in hand | None; card instance is R-only | `NON_TOTAL_REACHABLE_SYMMETRY` | SOURCE_PROOF + STATIC_INFERENCE |
| `CastSpell` root only | YES | Not for fixed non-basic singleton card definitions | visible `cardDefinitionId` plus cast semantics | `TOTAL_BY_SEMANTIC_KEY` | SOURCE_PROOF |
| `ActivateAbility` root | YES | Yes: two identical basic-land mana abilities | None; source instance is R-only | `NON_TOTAL_REACHABLE_SYMMETRY` | SOURCE_PROOF + STATIC_INFERENCE |
| `CastSpellMode` / mode prefix | YES | Mode slots can share presentation | source-declared mode slot | `TOTAL_BY_SOURCE_SEMANTIC_ORDER` | SOURCE_PROOF |
| `CastWithFlashback` root only | YES | Not for fixed singleton card definitions | card definition + alternative-cost variant | `TOTAL_BY_SEMANTIC_KEY` | SOURCE_PROOF |
| `CastWithKicker` root only | YES | Not for fixed singleton card definitions | card definition + `KICKED` slot | `TOTAL_BY_SOURCE_SEMANTIC_ORDER` | SOURCE_PROOF |
| `CycleCard` root only | YES | Not for fixed singleton card definitions | visible card definition | `TOTAL_BY_SEMANTIC_KEY` | SOURCE_PROOF |
| `DeclareAttackers` | YES | Yes: identical Kemba Cat tokens | None beyond entity refs | `NON_TOTAL_REACHABLE_SYMMETRY` | SOURCE_PROOF + FOCUSED_TEST_WITNESS |
| `DeclareBlockers` | YES | Yes: identical Kemba Cat tokens | None beyond entity refs | `NON_TOTAL_REACHABLE_SYMMETRY` | SOURCE_PROOF + FOCUSED_TEST_WITNESS |
| `DECISION` folded scalar | YES | No for boolean/color values | boolean/color semantic value | `TOTAL_BY_SEMANTIC_KEY` | SOURCE_PROOF |
| `DECISION` folded card instance | YES | Yes: one-of sacrifice/card choices | None beyond card instance ref | `NON_TOTAL_REACHABLE_SYMMETRY` | SOURCE_PROOF + STATIC_INFERENCE |
| `CHOOSE_TARGETS` | YES | Yes: two same-state Plains as legal targets | None beyond target EntityId | `NON_TOTAL_REACHABLE_SYMMETRY` | SOURCE_PROOF + STATIC_INFERENCE |
| `SELECT_CARDS` | YES | Yes: equivalent Plains/card instances | None beyond option EntityId | `NON_TOTAL_REACHABLE_SYMMETRY` | SOURCE_PROOF + STATIC_INFERENCE |
| `YES_NO` | YES | No; values are boolean | `choice` | `TOTAL_BY_SEMANTIC_KEY` | SOURCE_PROOF |
| `CHOOSE_COLOR` | YES | No; values are enum colors | `color` | `TOTAL_BY_SEMANTIC_KEY` | SOURCE_PROOF |
| `CHOOSE_OPTION` | YES | Presentation may collide | source option slot/index | `TOTAL_BY_SOURCE_SEMANTIC_ORDER` | SOURCE_PROOF |
| `ORDER_OBJECTS` | YES | Distinct-label cases total; duplicate-label occurrence case not proved total | stable semantic alias when distinct; suffix not accepted as preference | `INSUFFICIENT_EVIDENCE` | SOURCE_PROOF + STATIC_INFERENCE |
| `REORDER_LIBRARY` | YES | Yes: same-definition cards can be permuted | explicit current top-first source slot | `TOTAL_BY_SOURCE_SEMANTIC_ORDER` | SOURCE_PROOF |
| `COMBAT_RESOLUTION` | YES | Yes: symmetric source/target edges | None beyond relational edge refs | `NON_TOTAL_REACHABLE_SYMMETRY` | SOURCE_PROOF + STATIC_INFERENCE |
| `SELECT_MANA_SOURCES` | YES | Yes: two equivalent Plains payment programs | None beyond source/ability ref | `NON_TOTAL_REACHABLE_SYMMETRY` | SOURCE_PROOF + STATIC_INFERENCE |
| target-payment nested relation | YES where emitted | Instance symmetry not separately materialized here | no unproven fallback | `INSUFFICIENT_EVIDENCE` | INSUFFICIENT_EVIDENCE |

Unreachable generic families (`GENERIC` actor sentinel, `BatchYesNoDecision`, `CHOOSE_MODE` pending
type, `CHOOSE_NUMBER`, `DISTRIBUTE`, `SPLIT_PILES`, `CHOOSE_REPLACEMENT`, named `SEARCH_LIBRARY`,
`ASSIGN_DAMAGE`, and `BUDGET_MODAL`) are excluded from the Environment V1 totality quantifier by
the accepted B2 closure.

## 19. Counterexamples

### Primary root witness: duplicate Plains `PlayLand`

```text
DECISION_FAMILY=PlayLand root / ACTION_CANDIDATES

REACHABILITY_EVIDENCE=
Akiri v0.1 contains 17 Plains; PlayLandEnumerator emits one PlayLand(player, cardId)
per land in hand; the exact domain preserves both instances.

SOURCE_ALTERNATIVE_A=
PlayLand(SELF, plainsEntityA), affordable=true, sourceEntityId=plainsEntityA,
actionSemantics={type=PlayLand, cardId=plainsEntityA, playerId=SELF}

SOURCE_ALTERNATIVE_B=
PlayLand(SELF, plainsEntityB), affordable=true, sourceEntityId=plainsEntityB,
actionSemantics={type=PlayLand, cardId=plainsEntityB, playerId=SELF}

SOURCE_CHOICES_DISTINCT=YES
C0_01_MODEL_EQUIVALENT=YES
DIFFERING_FIELDS=sourceEntityId and the same source/card relational handle in actionSemantics
DIFFERING_FIELDS_CLASS=FORBIDDEN_RUNTIME_IDENTITY / RELATIONAL_HANDLE_ONLY
PERMITTED_INVARIANT_DISCRIMINATOR=NONE
ROW_ORDER_CAN_RESOLVE=NO
RAW_ENTITY_ID_CAN_RESOLVE=NO
DETERMINISTIC_TOTALITY_FOR_THIS_CASE=NO
```

The two `PlayLand` actions may lead to an observationally equivalent position, but C0-01 still
requires retaining both exact source choices for binding/replay. “Either is fine” is not an exact
identity contract.

### Structured target witness: `GenerousGift` targeting either Plains

```text
DECISION_FAMILY=CHOOSE_TARGETS prefix of CastSpell(GenerousGift)
REACHABILITY_EVIDENCE=GenerousGift is locked in Akiri; two Plains are legal permanents in its
single target requirement.
SOURCE_ALTERNATIVE_A=TargetsResponse({requirement 0 -> [plainsEntityA]})
SOURCE_ALTERNATIVE_B=TargetsResponse({requirement 0 -> [plainsEntityB]})
SOURCE_CHOICES_DISTINCT=YES
C0_01_MODEL_EQUIVALENT=YES
DIFFERING_FIELDS=selected target handle only
DIFFERING_FIELDS_CLASS=FORBIDDEN_RUNTIME_IDENTITY / RELATIONAL_HANDLE_ONLY
PERMITTED_INVARIANT_DISCRIMINATOR=NONE
ROW_ORDER_CAN_RESOLVE=NO
RAW_ENTITY_ID_CAN_RESOLVE=NO
DETERMINISTIC_TOTALITY_FOR_THIS_CASE=NO
```

### Structured payment witness: two equivalent Plains sources

```text
DECISION_FAMILY=SELECT_MANA_SOURCES payment-source prefix
REACHABILITY_EVIDENCE=two untapped Plains can be on the battlefield; PaymentDomainBuilder emits
both intrinsic white-mana source options for a payable fixed ordinary cost.
SOURCE_ALTERNATIVE_A=PaymentPlanV3 with SourceActivationV2(sourceId=plainsEntityA,
manaAbilityKey=intrinsic:WHITE, productionChoice={W})
SOURCE_ALTERNATIVE_B=PaymentPlanV3 with SourceActivationV2(sourceId=plainsEntityB,
manaAbilityKey=intrinsic:WHITE, productionChoice={W})
SOURCE_CHOICES_DISTINCT=YES
C0_01_MODEL_EQUIVALENT=YES
DIFFERING_FIELDS=SourceActivationV2.sourceId; all production/cost semantics equal
DIFFERING_FIELDS_CLASS=FORBIDDEN_RUNTIME_IDENTITY / RELATIONAL_HANDLE_ONLY
PERMITTED_INVARIANT_DISCRIMINATOR=NONE
ROW_ORDER_CAN_RESOLVE=NO
RAW_ENTITY_ID_CAN_RESOLVE=NO
DETERMINISTIC_TOTALITY_FOR_THIS_CASE=NO
```

### Repeated-token declaration/combat witness

Kemba's exact source path creates two identical Cats when two Equipment are attached. After the
tokens are eligible to attack/block, choosing Cat-A versus Cat-B in an otherwise symmetric
declaration, or assigning an otherwise symmetric combat edge, has the same shape: source choices
remain distinct, C0-01 features are equal up to handle renaming, and no semantic instance key is
available. The same conclusion applies to the folded one-card sacrifice choice after Garruk has
created two identical Wolf tokens.

## 20. Global totality conclusion

```text
MODEL_EQUIVALENT_DISTINCT_CHOICE_WITNESS=YES
FORBIDDEN_IDENTITY_REQUIRED_TO_BREAK_WITNESS=YES
VALID_INVARIANT_DISCRIMINATOR_FOR_WITNESS=NO
DUPLICATE_CANDIDATE_REJECTION_EQUALS_TIE_TOTALITY=NO
HASHING_FORBIDDEN_IDENTITY_DOES_NOT_SANITIZE_IT=YES
STRUCTURED_PREFIX_TIE_TOTALITY_AUDITED=YES
PAYMENT_TIE_TOTALITY_AUDITED=YES
TARGET_TIE_TOTALITY_AUDITED=YES
ORDERING_TIE_TOTALITY_AUDITED=YES
COMBAT_TIE_TOTALITY_AUDITED=YES
DETERMINISTIC_POLICY_TOTALITY=NO
TIE_BREAK_IDENTITY_GAP=CONFIRMED
```

The exact question is answered `NO` for the accepted fixed Environment V1 policy surface. The
counterexample does not depend on a trained model or observed floating-point equality; exact equal
scores are structurally permitted whenever the admitted representations are equivalent.

## 21. C0-04 consequence

```text
C0_TIE_BREAK_CONTRACT=PASS
C0_DETERMINISTIC_SELECTION_CONTRACT=BLOCKED_BY_REACHABLE_SYMMETRY
C0_DETERMINISTIC_INFERENCE_CONTRACT=BLOCKED_BY_REACHABLE_SYMMETRY
C0_04_DETERMINISTIC_BASELINE_READY=NO
STOCHASTIC_POLICY_INFERENCE_SUPPORTED=NO
C0_04_FINAL_ACCEPTANCE_PASS=NO
```

The fail-closed tie behavior is accepted and unchanged. Deterministic inference is not total, and
the C0-04 baseline cannot be called ready. No C0-01 relaxation, candidate collapse, row-order
fallback, or card-specific repair is justified.

## 22. Next required task

Recommend, but do not start:

```text
C0_04B_POLICY_RNG_AND_SYMMETRY_RESOLUTION_CONTRACT
```

That follow-up must choose and freeze the smallest accepted symmetry-resolution policy. If RNG is
chosen, it must separately specify the exact algorithm, seed representation, per-policy stream,
cursor/draw consumption, tie-class sampling, replay provenance, batch-order independence,
cross-player isolation, and episode reset. A narrow alternative contract could instead define a
new source-semantic symmetry resolution, but it must satisfy C0-01 and must not smuggle in runtime
identity.

## 23. Verification / stop condition

This branch changes documentation only. No production, Rules, Gym, model, trajectory, deck, golden,
or test file was changed. `git diff --check` is the required local gate. Full Gym and Rules suites
are not required for a docs-only characterization; no new executable characterization test was
added. Hosted CI is reported separately from this local document gate.

The audit stops at the exact Environment V1 answer. It does not implement C0-04B, C0-05, C1,
training, or self-play.

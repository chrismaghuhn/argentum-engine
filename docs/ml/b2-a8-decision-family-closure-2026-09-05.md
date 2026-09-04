# B2 A8 — Decision-Family Closure

Date: 2026-09-05

Status: **BLOCKED_GENERIC_GAP**. This is a fail-closed characterization, not an A8
implementation pass, dataset run, or trust claim.

## Source boundary

```text
ORIGIN_MAIN=48eef62213a44ff7d4fe33dd18bc2376a574a923
UPSTREAM_MAIN=4f09fec7e23723997c2e433f22c9a8e5681013b3
BASE=48eef62213a44ff7d4fe33dd18bc2376a574a923
BRANCH=chris/b2-a8-decision-family-closure-20260905
```

The dedicated worktree was created directly from `origin/main` at `BASE`. The locked deck
artifacts were read but not changed. Their current acceptance fixture hashes are:

```text
AKIRI_SHA256=0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6
CHEVILL_SHA256=D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2
```

## Current registry audit

The exact baseline does not contain symbols named `PendingDecision.Kind`,
`StructuredDecisionRegistry`, or `StructuredDecisionDomainBuilder`. Its equivalent current
contracts are:

- `PendingDecisionKind` in `gym/.../TrainingObservation.kt`;
- `SemanticDecisionKindV1` in `gym/.../ChosenSemanticInput.kt`;
- the exhaustive `when (decision)` projection in
  `gym/.../ObservationBuilder.kt`.

Direct enumeration produced:

```text
PENDING_DECISION_KIND_COUNT=18
PENDING_DECISION_KINDS=
GENERIC,CHOOSE_TARGETS,SELECT_CARDS,YES_NO,CHOOSE_MODE,CHOOSE_COLOR,
CHOOSE_NUMBER,DISTRIBUTE,ORDER_OBJECTS,SPLIT_PILES,CHOOSE_OPTION,
CHOOSE_REPLACEMENT,SEARCH_LIBRARY,REORDER_LIBRARY,ASSIGN_DAMAGE,
COMBAT_RESOLUTION,SELECT_MANA_SOURCES,BUDGET_MODAL

SEMANTIC_DECISION_KIND_COUNT=19
SEMANTIC_DECISION_KINDS_MINUS_PRIORITY=
GENERIC,CHOOSE_TARGETS,SELECT_CARDS,YES_NO,CHOOSE_MODE,CHOOSE_COLOR,
CHOOSE_NUMBER,DISTRIBUTE,ORDER_OBJECTS,SPLIT_PILES,CHOOSE_OPTION,
CHOOSE_REPLACEMENT,SEARCH_LIBRARY,REORDER_LIBRARY,ASSIGN_DAMAGE,
COMBAT_RESOLUTION,SELECT_MANA_SOURCES,BUDGET_MODAL

STRUCTURED_DECISION_REGISTRY_SYMBOL=ABSENT
STRUCTURED_REGISTRY_KIND_COUNT=0
SUPPORTED_STRUCTURED_DOMAIN_SERIALIZER_COUNT=12
PENDING_TO_SEMANTIC_NAME_PARITY_COUNT=18
PENDING_TO_SEMANTIC_NAME_PARITY=YES
REGISTRY_EXACT_MATCH=NO
```

The 18-way name parity is useful evidence, and the new focused test keeps it fail-closed, but it
is not a substitute for the requested `StructuredDecisionRegistry.ALL_KINDS`. The actual complete
domain code has 12 structured-domain serializers; the rest are folded choices or, for legacy
`ASSIGN_DAMAGE`, no current structured domain. The mandated registry-to-kind comparison therefore
cannot pass on this baseline. This is an independent `DECISION_REGISTRY_GAP` and already requires
the task to stop.

`PRIORITY` is the separate durable semantic family. The prompt's historical names such as
`MULLIGAN`, `PAY_MANA`, `CHOOSE_ATTACKERS`, and `CHOOSE_BLOCKERS` are not distinct current
`PendingDecisionKind` values: on this baseline they are public priority-action candidates or
payload domains. They cannot be silently added as invented registry rows.

## First generic blocker

`SELECT_MANA_SOURCES` is reachable from the locked pair. The exact Akiri card **Mentor of the
Meek** uses `MayPayManaEffect("{1}")`; an exact qualifying locked card, **Fervent Champion**,
causes its trigger. The focused A8 characterization creates that real engine decision, then reads
it through `GameGymEnv` and the public `CompleteLegalDomainV1` producer.

The resulting actor-facing domain is `ManaSourcesDomain`, not a complete `PaymentDomainV5`:

```text
PendingDecisionKind        = SELECT_MANA_SOURCES
Public structured domain   = ManaSourcesDomain
Exact locked source        = Battlefield Forge
Published color set        = {RED, WHITE}
Durable response payload   = type, selectedSources, waterbendPermanents, and declined; the only
                             source/production selector is selectedSources, with no production
                             choice, cost-unit allocation, or PaymentPlanV3 witness
Engine fallback surface    = autoPaySuggestion remains in the published domain
```

The Rules resumer creates `ManaSourceOption` from the source's color set and resolves the
ability with `source.manaAbilityFor(source.producesColors.firstOrNull())`. `ManaSourcesDomain`
then preserves one `manaAbilityKey` and a color set, but its response type has no field for a
selected production or allocation. A3 can normalize a source-ID response, but that is not a
complete, externally chosen payment program. Recording it as trusted A8 closure would hide the
policy decision behind engine behavior.

`UnpayableDomainV1` is not a type in this exact source tree. Its functional equivalent for this
audit is the reachable legacy `ManaSourcesDomain` path above: it cannot satisfy the required
complete representable payment-domain invariant. No fake candidate list or AutoPay fallback was
introduced.

Classification:

```text
FIRST_BLOCKER=DECISION_REGISTRY_GAP
ADDITIONAL_REACHABLE_BLOCKER=DECISION_DOMAIN_GAP:SELECT_MANA_SOURCES
KNOWN_REACHABLE_UNPAYABLE_DOMAIN_COUNT=1
REACHABLE_UNPAYABLE_DOMAIN_COUNT=NOT_FINALIZED_AFTER_FIRST_BLOCKER; AT_LEAST_1
SMALLEST_MISSING_PRIMITIVE=
complete public pending-payment domain carrying explicit source activation/production,
pool provenance, cost-unit allocation, and a matching durable response
```

The required corrections are generic: an explicit closed structured-decision registry and a
complete pending-payment domain. Both are outside this task's authorization. A8 therefore stops
before creating replay, trajectory, publication, or reader evidence for this incomplete choice.

## Commander-zone trace

`CommanderZoneChoiceCheck` is enabled by the locked `Format.Commander()` configuration, whose
`alwaysDivertToCommand` default is `false`. For a commander placed in a graveyard or exile, that
check calls `DecisionHandler.createYesNoDecision` and pushes
`CommanderZoneChoiceContinuation`. The public builder maps `YesNoDecision` to `YES_NO`.

```text
COMMANDER_ZONE_CHOICE_IMPLEMENTATION=
CommanderZoneChoiceCheck -> DecisionHandler.createYesNoDecision -> CommanderZoneChoiceContinuation
COMMANDER_ZONE_DECISION_FAMILY=YES_NO
COMMANDER_ZONE_REACHABLE=YES
COMMANDER_ZONE_E2E_EVIDENCE=NOT_RUN_AFTER_SELECT_MANA_SOURCES_BLOCKER
```

The locked pair contains ordinary destruction and board-wipe effects, so a commander can reach
the graveyard/exile choice surface. This record does not claim it has passed the A3-A7 trajectory
chain; work stopped at the earlier generic payment-domain gap.

## A8 closure matrix at the stop boundary

`B0 observed` is historical positive evidence only. `NO` never means unreachable. Rows not
evaluated after the first generic blocker explicitly remain `BLOCKED_GENERIC_GAP`; they are not
claimed unreachable or verified.

In the `Current registry present?` column, `yes` means the current enum/mapper recognizes the
family. It does not override the absent named `StructuredDecisionRegistry` reported above.

| Family | Semantic decision kind | Structured domain type | Current registry present? | Reachable in locked pair? | Reachability authority/reason | B0 observed? | A3 semantic fold/membership | A4 replay | A5 trajectory | A6 publication | A7 reader | A8 positive test | Final status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Priority action | `PRIORITY` | action candidates | yes | yes | Generic turn-priority path | yes | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Generic masked decision | `GENERIC` | no actor-owned domain | yes | no | `ObservationBuilder` emits it only for a non-owning perspective | no | no actor-owned sample exists | not run after blocker | not run after blocker | not run after blocker | not run after blocker | source audit | `PROVEN_UNREACHABLE_IN_LOCKED_CURRICULUM` |
| Choose targets | `CHOOSE_TARGETS` | `TargetsDomain` | yes | yes | Locked definitions and B0 both expose targets | yes | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Select cards | `SELECT_CARDS` | `CardSelectionDomain` or folded options | yes | yes | Locked draw, discard, and search cards; B0 observed it | yes | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Yes/no | `YES_NO` | folded options | yes | yes | Locked may effects and Commander zone choice | yes | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | commander mapping audited; no full E2E artifact | `BLOCKED_GENERIC_GAP` |
| Choose mode | `CHOOSE_MODE` | folded options or `ModeSelectionDomain` | yes | not finalized after blocker | Static locked-card audit has modal definitions | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Choose color | `CHOOSE_COLOR` | folded options | yes | yes | B0 observed the public color-choice path | yes | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Choose number | `CHOOSE_NUMBER` | folded options | yes | not finalized after blocker | No unreachable conclusion made after stop | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Distribute | `DISTRIBUTE` | `DistributionDomain` | yes | not finalized after blocker | No unreachable conclusion made after stop | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Order objects | `ORDER_OBJECTS` | `OrderingDomain` | yes | not finalized after blocker | Trigger-order path requires exact witness after blocker removal | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Split piles | `SPLIT_PILES` | `SplitPilesDomain` | yes | not finalized after blocker | No unreachable conclusion made after stop | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Choose option | `CHOOSE_OPTION` | folded options | yes | not finalized after blocker | No unreachable conclusion made after stop | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Choose replacement | `CHOOSE_REPLACEMENT` | `ReplacementDomain` | yes | not finalized after blocker | No unreachable conclusion made after stop | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Search library | `SEARCH_LIBRARY` | `SearchLibraryDomain` | yes | yes | Locked tutors and ramp effects | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Reorder library | `REORDER_LIBRARY` | `ReorderLibraryDomain` | yes | yes | Locked scry effects; current static exact-pair gate derives it | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Legacy assign damage | `ASSIGN_DAMAGE` | missing on current projection | yes | no | `AssignDamageDecision` documents that modern gameplay never emits or evaluates it | no | no reachable actor-owned sample exists | not run after blocker | not run after blocker | not run after blocker | not run after blocker | source audit | `PROVEN_UNREACHABLE_IN_LOCKED_CURRICULUM` |
| Combat resolution | `COMBAT_RESOLUTION` | `CombatResolutionDomain` | yes | yes | Commander combat plus locked creature/equipment package | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |
| Select mana sources | `SELECT_MANA_SOURCES` | `ManaSourcesDomain` | yes | yes | Locked Mentor of the Meek + Fervent Champion + Battlefield Forge characterization | no | source-ID membership only; incomplete payment semantics | not run; prohibited by gap | not run; prohibited by gap | not run; prohibited by gap | not run; prohibited by gap | `TrajectoryDecisionFamilyClosureTest` | `BLOCKED_GENERIC_GAP` |
| Budget modal | `BUDGET_MODAL` | `BudgetModalDomain` | yes | not finalized after blocker | No unreachable conclusion made after stop | no | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not run after blocker | not added after blocker | `BLOCKED_GENERIC_GAP` |

## Cross-check status

```text
A_CURRENT_ENUM_REGISTRY=PARTIAL: enum-name parity passes, requested structured registry is absent
B_STATIC_EXACT_PAIR_REACHABILITY=STOPPED_AT_KNOWN_GENERIC_GAP
C_B0_DYNAMIC_FAMILIES_SUBSET_OF_REACHABLE=NO_FINAL_ASSERTION_AFTER_STOP
D_A8_A3_TO_A7_SERIALIZED_COVERAGE=NOT_RUN_AFTER_BLOCKER

NO_PARTIAL_OR_FORGED_EVIDENCE=YES
NO_HIDDEN_POLICY=YES
NO_RAW_GAMESTATE_LEARNER_PATH=YES
NO_GYM_TRAINER_GAME_SERVER_PRODUCTION_DEPENDENCY=YES
LOCKED_DECKS_CHANGED=NO
PRODUCTION_CODE_CHANGED=NO
TEST_ONLY_HARNESS_CHANGED=YES
DOCS_CHANGED=YES
A9_IMPLEMENTED=NO
DATASET_GENERATION_RUN=NO
ML_CODE_CHANGED=NO
```

## Verification

```text
TARGETED_A8_TEST=
TrajectoryDecisionFamilyClosureTest: PASS by native Gradle fallback
  - durable PendingDecisionKind/SemanticDecisionKindV1 vocabulary parity
  - reachable locked Mentor pending-payment characterization

JUST_WRAPPER=
BLOCKED before Gradle: WinError 193 from scripts/gradle-locked/test-class and a missing
/bin/bash from just test-gym

RED_CHARACTERIZATION=
PASS: the focused test proves that the current reachable domain omits an explicit production,
cost-unit allocation, and PaymentPlanV3 witness. This is RED against A8's complete representable
domain requirement, not a green implementation of the missing primitive.

TEST_SCAFFOLD_COMPILE_FENCE=
The first native compile correctly showed that ScenarioTestBase was absent from :gym's test
classpath. Adding only testFixtures(project(":rules-engine")) made the characterization runnable;
no production dependency was added.

FULL_GYM_TEST=PASS by serial native Gradle fallback:
`gradlew.bat :gym:test --no-daemon --no-build-cache --no-configuration-cache --console=plain`
The earlier non-serial attempts displayed passing test bodies but failed while writing shared
`gym/build/test-results/test/binary` artifacts. Their cause is not asserted; they are not used as
final evidence.
GYM_TRAINER_TEST=PASS by native Gradle fallback
GAME_SERVER_TEST=PASS by native Gradle fallback
COVERAGE=NOT_RUN_LOCALLY
```

## Required next authority

Do not add a card-name exception, fake `PaymentDomainV5`, AutoPay path, or trajectory-only
normalization. A separately authorized generic decision-contract change must first define the
closed registry and make pending mana payments complete and explicitly selectable. Only then may
a resumed A8 task classify and prove the remaining matrix through A3, A4, A5, A6, and A7.

```text
A8_IMPLEMENTATION_PASS=NO
A8_FINAL_ACCEPTANCE_PASS=NO
B2_FINAL_PASS=NO
DATA_TRUSTED=NO
```

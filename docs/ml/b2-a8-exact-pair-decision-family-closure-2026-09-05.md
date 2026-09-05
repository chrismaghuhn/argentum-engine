# B2 A8 — Exact-Pair Decision-Family Closure

Date: 2026-09-05
Task: `B2_A8_EXACT_PAIR_DECISION_FAMILY_CLOSURE_02`

Status: **implementation pass; independent review and final acceptance pending**.

This is characterization/evidence only. It does not start A9, generate dataset shards, train a
model, change payment or replay production code, change the locked decks, or re-bless goldens.

## Source and acceptance boundary

```text
repository=chrismaghuhn/argentum-engine
BASE=11806c789c37b6347781f9fc8904c58db19faa88
ORIGIN_MAIN=11806c789c37b6347781f9fc8904c58db19faa88
UPSTREAM_MAIN=5021faf88093a93091e4de7914fbe0f411499d58
branch=chris/b2-a8-exact-pair-decision-family-closure-20260905-rerun
worktree=C:/Users/chris/.config/superpowers/worktrees/argentum-engine/b2-a8-decision-family-closure-20260905-rerun
```

PR #130 was verified against the fork before this audit:

```text
PR_130_MERGED=YES
PR_130_HEAD=82fcc60dab11521feab04f30ece5a9511bf6aa5f
PR_130_MERGE_COMMIT=11806c789c37b6347781f9fc8904c58db19faa88
HOSTED_CI_COMPLETED=YES
HOSTED_CI_PASS=YES
```

Required Hosted jobs (`backend`, `frontend`, `test (content)`, `test (engine)`, all scenario jobs,
`test (server)`, and `test (tools)`) passed. The workflow-configured `coverage` job was
`skipping`; it is not counted as an individual pass.

The locked deck files were unchanged and independently hashed from this worktree:

```text
Akiri: 0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6
Chevill: D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2
```

The existing exact-pair setup resolved 146 unique cards from two exact 100-card Commander lists.
Its current definition digest was `3C3C2DF4993D875D1239F49D4D3DACF059D8842BC2A6E0D03DDF31CDB7901E23`.

## Current decision surfaces

The source-derived `PendingDecisionKind` enum currently contains 18 values:

```text
GENERIC, CHOOSE_TARGETS, SELECT_CARDS, YES_NO, CHOOSE_MODE, CHOOSE_COLOR,
CHOOSE_NUMBER, DISTRIBUTE, ORDER_OBJECTS, SPLIT_PILES, CHOOSE_OPTION,
CHOOSE_REPLACEMENT, SEARCH_LIBRARY, REORDER_LIBRARY, ASSIGN_DAMAGE,
COMBAT_RESOLUTION, SELECT_MANA_SOURCES, BUDGET_MODAL
```

`PRIORITY` is not a `PendingDecisionKind`; it is the separate action-candidate semantic family.
`SemanticDecisionKindV1` has the same 18 non-priority names plus `PRIORITY`.

The current typed `StructuredDecisionDomain` forms are:

```text
TargetsDomain(v2)
CardSelectionDomain(v1)
ModeSelectionDomain(v1)
DistributionDomain(v1)
OrderingDomain(v1)
SplitPilesDomain(v1)
SearchLibraryDomain(v1)
ReorderLibraryDomain(v1)
CombatResolutionDomain(v1)
ManaSourcesDomain(v3, wrapping PaymentDomainV5)
ReplacementDomain(v1)
BudgetModalDomain(v1)
```

The three complete-domain shapes are `ACTION_CANDIDATES`, `FOLDED_DECISION_OPTIONS`, and
`STRUCTURED_DECISION`. A folded decision is still a complete domain: the concrete response
options are retained as semantic candidates, not inferred from prompt text or `toString()`.

There are two important source-level corrections to the historical family labels:

* `ChoiceType.MODE` on locked **Outpost Siege** is emitted by the Rules entry-replacement path as
  `ChooseOptionDecision`, therefore its current pending family is `CHOOSE_OPTION`. Modal spell
  choices are likewise exposed as `CastSpellMode` action candidates. The `CHOOSE_MODE` enum value
  remains a supported future/legacy shape, but is not the emitted pending type for this pair.
* Locked search and scry definitions use `SelectFromCollectionDecision`/`SelectCardsDecision` and
  the scry pipeline. `SearchLibraryDecision` is a declared compatibility shape, not a current
  producer in this exact path. `ReorderLibraryDecision` is nevertheless reachable through the
  kept-card ordering leg of **Read the Bones**.

## Four evidence sets

### STATIC

The current 146-definition scan derives these exact-pair action families:

```text
ActivateAbility, CastSpell, CastSpellMode, CastWithFlashback, CastWithKicker,
CycleCard, DECISION, DeclareAttackers, PassPriority, PlayLand
```

The runtime-normalized static pending families are:

```text
CHOOSE_COLOR, CHOOSE_OPTION, CHOOSE_TARGETS, COMBAT_RESOLUTION,
ORDER_OBJECTS, REORDER_LIBRARY, SELECT_CARDS, SELECT_MANA_SOURCES, YES_NO
```

The existing definition scan reports `CHOOSE_MODE` as a semantic `ChoiceType.MODE` tag. The
source trace above is the authoritative normalization of that tag to `CHOOSE_OPTION` at the
actual Rules-to-pending boundary. The scan also does not need to invent `COMBAT_RESOLUTION`,
`ORDER_OBJECTS`, or `SELECT_MANA_SOURCES`: their generic producers are reachable with exact-pair
cards and are listed explicitly in the closure matrix below.

Concrete locked-card reachability witnesses are present in current definitions:

* **Outpost Siege** has two `EntersWithChoice(ChoiceType.MODE)` options.
* **Open the Armory**, **Steelshaper's Gift**, **Stoneforge Mystic**, **Armored Skyhunter**, and
  the scry/search cards exercise explicit card-selection domains.
* **Read the Bones** exercises both the scry selection and a subsequent kept-card ordering.
* **Akiri**, **Fervent Champion**, **Morbid Opportunist**, **Harvester of Souls**, **Moldervine
  Reclamation**, and other exact cards provide simultaneous triggered-ability or target/may
  surfaces; the trigger processor owns the `ORDER_OBJECTS` boundary.
* **Loxodon Warhammer**, **Embercleave**, **Shadowspear**, and **Garruk's Uprising** provide exact
  trample grants. A legal blocked trample attack reaches the generic combat-resolution board.
* **Mentor of the Meek**, **Fervent Champion**, and **Battlefield Forge** provide the exact
  pending-payment witness used below.

### PUBLIC

The public producers are the current `ObservationBuilder` and `CompleteLegalDomainV1` path:

```text
LegalActionView.actionSemantics + ActionTargetDomainV1
  + AttackDeclarationDomainV2 / BlockerDeclarationDomainV1
  + PaymentDomainV5 / TargetPaymentDomainV1 / public mana-color fields

PendingDecisionView
  + folded ActionRegistry candidates
  + the typed StructuredDecisionDomain forms listed above
```

An acting structured decision without a typed domain emits
`STRUCTURED_DECISION_DOMAIN_MISSING` and fails closed. Non-owners receive the privacy-only
`GENERIC` placeholder with no decision ID or domain.

### DYNAMIC

The required current exact-pair command was run from this worktree:

```text
.\gradlew.bat :gym:environmentV1AcceptanceTest --rerun-tasks --console=plain
```

It completed successfully. The accepted corpus result was:

```text
episodesStarted=72
terminalEpisodes=9
truncatedEpisodes=63
totalExternalTransitions=139740
maxEpisodeTransitions=2000
firstFailure=none
```

Selected externally controlled action counts:

```text
ActivateAbility=47816
CastSpell=685
CastSpellMode=75
CastWithKicker=6
CycleCard=46
DECISION=2681
DeclareAttackers=383
PassPriority=85307
PlayLand=2402
```

Externally actionable pending counts in the accepted corpus were:

```text
CHOOSE_COLOR=108
CHOOSE_TARGETS=72
SELECT_CARDS=2733
YES_NO=107
```

`PRIORITY=136720` was tracked separately. The corpus did not randomly hit every rare family;
zero dynamic count is therefore not used as unreachability evidence. The targeted witnesses and
static producer traces below cover the rare exact-pair families.

The current dynamic failure/rejection ledger was:

```text
UNSUPPORTED_DIAGNOSTICS=0
PUBLIC_CHOICE_REJECTIONS=0
NATIVE_FALLBACKS=0
diagnosticCountsByKind=
  {UNSUPPORTED_CARD=0, UNSUPPORTED_DECISION=0,
   UNSUPPORTED_RULE_OR_MECHANIC=0, NATIVE_POLICY_FALLBACK=0}
```

### TRAJECTORY

The accepted replay gate captured complete `PlayerObservationV1`, `CompleteLegalDomainV1`, and
`CandidateDomainDigestV1` values at every boundary and independently reconstructed two public
cases:

```text
cases=2
authoritativeCompactReplay=2
failures=0
each case: frames=2001, decisions=2000, truncated=true,
           CompactReplay v6, codecRoundTrip=true, fidelity=EXACT, checkpoints=100
```

The chosen-input chain is represented by `ChosenSemanticActionV1`/
`ChosenSemanticResponseV1`, `SemanticReplayInputV1`, `SemanticDecisionIdentityV1`,
`ReplayChosenInputBindingV1`, and the additive `ReplayTrajectoryBindingV1`. The chosen-input
binding covers the complete replay action range, strips only live routing IDs, and shares the A4
replay-content identity. `TrajectoryV1Validator` requires a complete domain, a matching digest,
exactly one chosen semantic action/response, a matching semantic decision identity, and chosen
membership in the stored domain. This was exercised in-memory/test-level; no dataset shards were
created.

## SELECT_MANA_SOURCES after PR #130

Final status: `REACHABLE_AND_VERIFIED`.

The existing `PendingManaPaymentDomainTest` was run on this post-payment head (14 tests, 0
failures, 0 skips). It uses the locked-card Mentor/Fervent Champion/Battlefield Forge witness and
proves:

```text
pending family                 SELECT_MANA_SOURCES
public domain                  ManaSourcesDomain(version=3)
inner domain                   complete PaymentDomainV5
semantic response               ManaSourcesSelectedResponse(paymentPlan=PaymentPlanV3(...))
production color               explicit RED or WHITE Battlefield Forge choice
strict membership               source, production, allocation, and cost are checked
nonce behavior                 decision ID is routing freshness only
AutoPay                        rejected
source-only legacy selection   rejected
replay binding                 preserves the semantic PaymentPlanV3 program
```

The same test suite deliberately records the boundaries that remain outside this exact pair:
waterbend tap-to-reduce and composite Ward payment are rejected rather than given a partial domain.
Those are known fail-closed generic limitations, not exact-pair blockers, because neither shape is
produced by the locked Akiri/Chevill card definitions used by Environment V1.

## Generic/privacy distinction

The current public-actionability predicate is based on the public actor, terminal/truncated state,
perspective, pending owner, and non-null decision ID. It does not special-case `GENERIC`.

```text
GENERIC_PRIVACY_SENTINEL_COUNTED_AS_POLICY_FAMILY=NO
ACTIONABLE_GENERIC_WOULD_COUNT=YES
ACTIONABLE_GENERIC_FAILS_CLOSED_IF_UNSUPPORTED=YES
```

The dedicated harness tests prove both halves: a truncated/non-owner generic placeholder is not
actionable, while a generic pending view with an acting player and a decision ID is actionable and
is not filtered by family name. The current exact-pair privacy gate inspected 8,004 observations
from both player perspectives with zero failures.

## Closure matrix

`Dynamic count` is the current accepted-corpus count where available. `0/static` means a real
producer is proven from the locked definitions or generic Rules path but the weak 72-case policy
did not happen to choose/reach it. `—/unreachable` is a source/configuration proof, never a
random-observation claim.

| FAMILY | STATIC_PRODUCER | LOCKED_PAIR_REACHABILITY | PUBLIC_DOMAIN | EXTERNAL_RESPONSE | CHOSEN_IN_DOMAIN | REPLAY_BINDING | TRAJECTORY_REPRESENTATION | DYNAMIC_COUNT | TARGETED_WITNESS / EVIDENCE | FINAL_STATUS |
| --- | --- | --- | --- | --- | --- | --- | --- | ---: | --- | --- |
| `PRIORITY` | Generic turn-priority enumerator | Real priority boundary | `ACTION_CANDIDATES` | `ChosenSemanticActionV1` | Yes | Yes | Yes | 136720 | 72-episode corpus + exact replay | `REACHABLE_AND_VERIFIED` |
| `GENERIC` privacy sentinel | `ObservationBuilder` non-owner projection only | Not actor-actionable in V1 | No actor-owned domain | None | N/A | N/A | N/A | 0 | Harness/privacy predicate | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `CHOOSE_TARGETS` | Locked target effects, `SelectTarget` | Real | `TargetsDomain(v2)` | `TargetsResponse` / explicit action targets | Yes | Yes | Yes | 72 | Corpus; structured-domain contract | `REACHABLE_AND_VERIFIED` |
| `SELECT_CARDS` | Locked discard, sacrifice, search, scry, Skyhunter paths | Real | `CardSelectionDomain(v1)` or folded candidates | `CardsSelectedResponse` | Yes | Yes | Yes | 2733 | Corpus; locked card scenario coverage | `REACHABLE_AND_VERIFIED` |
| `YES_NO` ordinary | Locked may/pay/zone triggers | Real | Folded decision candidates | `YesNoResponse` | Yes | Yes | Yes | 107 | Corpus | `REACHABLE_AND_VERIFIED` |
| `BatchYesNoDecision` subtype | `TriggerProcessor.batchKeyOf` requires a top-level targeted may trigger | No exact locked definition has that batch shape; Mentor is `MayPayMana` without targets and Skyhunter's may is nested | Folded `YES_NO` would omit peel-off, but no exact producer exists | `BatchYesNoResponse` | N/A for V1 exact pair | N/A | N/A | 0 | Exact-definition shape scan + processor preconditions | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `CHOOSE_MODE` pending type | Only a legacy/factory `ChooseModeDecision` path; locked mode sources do not call it | No; exact mode sources normalize to `CHOOSE_OPTION` or `CastSpellMode` | `ModeSelectionDomain(v1)` exists for the supported generic shape | `ModesChosenResponse` | Yes for supported generic shape | Yes | Yes | 0 | `Outpost Siege` → `EntersWithChoice(MODE)` → `ChooseOptionDecision` | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `CHOOSE_COLOR` | Locked color-choice effects (including Mother of Runes path) | Real | Folded color candidates / public mana-color field | `ColorChosenResponse` / explicit color field | Yes | Yes | Yes | 108 | Corpus; color-domain contract | `REACHABLE_AND_VERIFIED` |
| `CHOOSE_NUMBER` | Generic Rules number-choice producers only | No exact card/effect/`ChoiceType.NUMBER` or X-cost producer in the locked definitions | Folded numeric candidates | `NumberChosenResponse` | Yes for supported generic shape | Yes | Yes | 0 | 146-definition scan has no `xValue` field; no exact number-choice producer | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `DISTRIBUTE` | Generic divided-damage/counter producers | No locked divided-damage, free-division, or distribute-counter effect | `DistributionDomain(v1)` | `DistributionResponse` | Yes for supported generic shape | Yes | Yes | 0 | Locked definition scan; Rules `DividedDamage` paths are not in pair | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `ORDER_OBJECTS` | `TriggerProcessor.raiseTriggerOrdering` and library/attachment ordering | Real via simultaneous exact triggers and exact Read the Bones path | `OrderingDomain(v1)` with stable trigger aliases | `OrderedResponse` | Yes | Yes | Yes | 0/static | Exact Fervent/Akiri/Harvester/Moldervine trigger interactions; ordering contract tests | `REACHABLE_AND_VERIFIED` |
| `SPLIT_PILES` | Generic `SplitPilesDecision` factory only | No Fact-or-Fiction/`ChoosePile` producer in locked definitions | `SplitPilesDomain(v1)` | `PilesSplitResponse` | Yes for supported generic shape | Yes | Yes | 0 | Locked definition scan has no pile-split producer | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `CHOOSE_OPTION` | `PermanentEntryReplacements`, modal/option executors; exact Outpost Siege | Real | Folded decision candidates with option metadata | `OptionChosenResponse` | Yes | Yes | Yes | 0/static | Outpost mode is a real `ChooseOptionDecision`; folded membership contract | `REACHABLE_AND_VERIFIED` |
| `CHOOSE_REPLACEMENT` | Text-changing replacement executors only | No exact text-changing card | `ReplacementDomain(v1)` | `ReplacementChosenResponse` | Yes for supported generic shape | Yes | Yes | 0 | No `ChangeWordInText`/`ChangeCreatureTypeText` exact producer | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `SEARCH_LIBRARY` | Declared compatibility DTO; no current Rules constructor in this path | Named pending type unreachable; exact searches become `SELECT_CARDS` | `SearchLibraryDomain(v1)` exists for generic/future use | `CardsSelectedResponse` | Yes for supported generic shape | Yes | Yes | 0 | `SelectFromCollectionExecutor` emits `SelectCardsDecision`; Open the Armory/Steelshaper's Gift scenarios | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `REORDER_LIBRARY` | `MoveCollectionExecutor` controller-order path | Real via Read the Bones kept-card ordering | `ReorderLibraryDomain(v1)` | `OrderedResponse` | Yes | Yes | Yes | 0/static | Read the Bones scenario; `MoveCollectionExecutor.pauseForOrderDecision` | `REACHABLE_AND_VERIFIED` |
| `ASSIGN_DAMAGE` legacy | Decode-only legacy class; modern combat manager replaced its chain | Proven absent from current combat gameplay | No current structured domain | Legacy `DamageAssignmentResponse` is not accepted as a current public domain | N/A | N/A | N/A | 0 | `CombatDamageManager` explicitly emits `CombatResolutionDecision` instead | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `COMBAT_RESOLUTION` | `CombatDamageManager.checkCombatResolutionBoard` | Real: exact trample grants plus legal blockers | `CombatResolutionDomain(v1)` | `CombatResolutionResponse` | Yes | Yes | Yes | 0/static | Exact trample definitions; combat board and semantic-membership tests | `REACHABLE_AND_VERIFIED` |
| `SELECT_MANA_SOURCES` | `ManaPaymentWindow` / payment continuation; exact Mentor witness | Real | `ManaSourcesDomain(v3)` → `PaymentDomainV5` | explicit `PaymentPlanV3` | Yes | Yes | Yes | targeted | `PendingManaPaymentDomainTest` 14/14; Battlefield Forge red/white | `REACHABLE_AND_VERIFIED` |
| `BUDGET_MODAL` | Generic `BudgetModalEffectExecutor` only | No budget-modal card/effect in locked definitions | `BudgetModalDomain(v1)` | `BudgetModalResponse` | Yes for supported generic shape | Yes | Yes | 0 | Locked definition scan has no `BudgetModalEffect` producer | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |
| `CastSpell` | Locked nonland spells | Real | `ACTION_CANDIDATES` + payment/target/X fields as required | `ChosenSemanticActionV1` | Yes | Yes | Yes | 685 | Corpus | `REACHABLE_AND_VERIFIED` |
| `ActivateAbility` | Locked activated abilities/equip/mana | Real | `ACTION_CANDIDATES` + target/payment/color/cost fields | `ChosenSemanticActionV1` | Yes | Yes | Yes | 47816 | Corpus + payment tests | `REACHABLE_AND_VERIFIED` |
| `CastSpellMode` | Locked modal spells | Real | Complete candidate/action semantics | `ChosenSemanticActionV1` | Yes | Yes | Yes | 75 | Corpus; modal payment tests | `REACHABLE_AND_VERIFIED` |
| `CastWithFlashback` | Locked flashback definitions, including Sevinne's Reclamation | Real static producer | Complete candidate/action semantics | `ChosenSemanticActionV1` | Yes | Yes | Yes | 0/static | Static definition scan; generic action contract | `REACHABLE_AND_VERIFIED` |
| `CastWithKicker` | Locked Tear Asunder kicker | Real | Complete candidate/action semantics + payment | `ChosenSemanticActionV1` | Yes | Yes | Yes | 6 | Corpus + Tear Asunder payment tests | `REACHABLE_AND_VERIFIED` |
| `CycleCard` | Locked Barren Moor and cycling cards | Real | Complete candidate/action semantics + payment | `ChosenSemanticActionV1` | Yes | Yes | Yes | 46 | Corpus + cycle payment tests | `REACHABLE_AND_VERIFIED` |
| `DeclareAttackers` | `CombatEnumerator` | Real | `AttackDeclarationDomainV2` | explicit attackers/bands | Yes | Yes | Yes | 383 | Corpus + attack-domain tests | `REACHABLE_AND_VERIFIED` |
| `DeclareBlockers` | `CombatEnumerator` | Real generic combat boundary | `BlockerDeclarationDomainV1` | explicit blockers | Yes | Yes | Yes | 0 selected | Blocker-domain strict/privacy tests; not inferred from absent selection | `REACHABLE_AND_VERIFIED` |
| `PassPriority` | Priority enumerator | Real | `ACTION_CANDIDATES` | `ChosenSemanticActionV1` | Yes | Yes | Yes | 85307 | Corpus | `REACHABLE_AND_VERIFIED` |
| `DECISION` folded action handle | `ActionRegistry.ofDecisionResponses` | Real when a simple pending decision is exposed | Folded decision candidates | response-specific `DecisionResponse` | Yes | Yes | Yes | 2681 | Corpus and folded-membership tests | `REACHABLE_AND_VERIFIED` |
| `TypecycleCard` | No typed-cycling card in either locked deck | Proven absent for Environment V1 | Not published by exact pair | N/A | N/A | N/A | 0 | Exact deck inventory | `PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1` |

Therefore:

```text
ALL_CURRENT_FAMILIES_CLASSIFIED=YES
UNCLASSIFIED=[]
REAL_BLOCKERS=[]
CHOSEN_IN_DOMAIN_ALL_REACHABLE=YES
REPLAY_BINDING_ALL_REACHABLE=YES
TRAJECTORY_REPRESENTATION_ALL_REACHABLE=YES
PRIVACY_BOUNDARY_ALL_REACHABLE=YES
DECISION_FAMILY_CLOSURE=PASS
```

## Verification performed

The native Windows Gradle commands were used because the repository's `just` wrapper is blocked on
this host before Gradle starts (`WSL ... execvpe(/bin/bash) failed`, equivalent to the known
WinError 193 limitation). The raw commands are separately labeled native fallback evidence.

```text
just test-gym
  BLOCKED before Gradle: WSL /bin/bash unavailable

.\gradlew.bat :gym:environmentV1AcceptanceTest --rerun-tasks --console=plain
  PASS: 44 tests, 0 skipped, 0 failures; BUILD SUCCESSFUL in 21m47s

.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.PendingManaPaymentDomainTest" --rerun-tasks --console=plain
  PASS: 14 tests, 0 skipped, 0 failures; BUILD SUCCESSFUL in 3m04s

.\gradlew.bat :gym:test --rerun-tasks --console=plain
  PASS: 553 tests, 6 skipped, 0 failures; BUILD SUCCESSFUL in 3m03s

.\gradlew.bat :gym-trainer:test --rerun-tasks --console=plain
  PASS: 137 tests, 1 skipped, 0 failures; BUILD SUCCESSFUL in 2m00s

.\gradlew.bat :game-server:test --rerun-tasks --console=plain
  PASS: 609 tests, 13 skipped, 0 failures; BUILD SUCCESSFUL in 5m16s

.\gradlew.bat :rules-engine:test --rerun-tasks --console=plain
  PASS: 3617 tests, 0 skipped, 0 failures; BUILD SUCCESSFUL in 4m44s

git diff --check
  PASS
```

Configured skips remain explicit:

```text
gym: B1MultiEnvIsolationTest, B1PerformanceBaselineTest, B1ResetHeavyMeasurementTest,
     B1ScalingContractTest, B1ScalingMeasurementTest, B1StructuredLatencyMeasurementTest
gym-trainer: TrajectoryV1ReaderTest symlinked-shard-ancestor test (Windows)
game-server: AIBenchmark plus 9 Flyway/database integration tests
rules-engine: none
```

## Scope and remaining limitations

* No production source changed. No locked deck, golden, or frozen baseline changed.
* The exact-pair corpus is a bounded reachability corpus, not an exhaustive proof by random
  sampling. Rare families are covered by source reachability plus focused Rules/Gym/domain tests.
* The generic waterbend and composite-Ward pending payment shapes still fail closed; they are not
  in the locked pair and were not silently approximated.
* The existing static acceptance helper retains a semantic `CHOOSE_MODE` label for
  `ChoiceType.MODE`; this report records the actual runtime family (`CHOOSE_OPTION`) so that the
  current source surface is not misreported. No production fix was made in this audit.
* Hosted CI for this new characterization branch and independent code review were intentionally
  not started. A8 final acceptance remains pending those checkpoints.

```text
PRODUCTION_CODE_CHANGED=NO
PRODUCTION_CHANGE_REQUIRED=NO
FROZEN_BASELINE_REBLESSED=NO
GOLDENS_CHANGED=NO
LOCKED_DECKS_CHANGED=NO
A9_STARTED=NO
DATASET_GENERATION_RUN=NO
DATA_TRUSTED=NO
C0_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

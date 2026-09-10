# Paused Spell Resolution Disposition — Rules Characterization 05

## Scope and provenance

This is a test-only characterization of the paused spell-resolution boundary. It does not change
Rules, History-A/B/C/D, replay, Gym, decks, performance, or ML behavior.

```text
TASK=PAUSED_SPELL_RESOLUTION_DISPOSITION_RULES_CHARACTERIZATION_05
BASE=4e06d8679d4d95420eee4b7a422b56da8942f4f5
CURRENT_ORIGIN_MAIN=32cac5f2f99eda863414fe9881df0ca93dfff537
CURRENT_UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
BRANCH=chris/paused-spell-resolution-disposition-rules-characterization-05-20260910
```

The base is Fix-04, whose non-paused copied-spell disposition is already accepted as a partial
fix. The test below characterizes the remaining paused-resolution behavior; it is not a production
fix and does not reclassify Fix-04's unrelated non-paused behavior.

## Rules authority

The current official source checked on 2026-09-10 was the [Wizards Comprehensive Rules page](https://magic.wizards.com/en/rules).
The page linked the current TXT rules document at
[MagicCompRules 20260819.txt](https://media.wizards.com/2026/downloads/MagicCompRules%2020260819.txt),
which states that the rules are effective as of August 7, 2026.

The relevant rules are:

* CR 117.2e: resolving a spell may ask for choices, but no player receives priority during
  resolution. CR 117.5 performs state-based actions when a player would receive priority.
* CR 608.2b: if all targets of a spell are illegal, it does not resolve; it is removed from the
  stack and, if it is a spell, put into its owner's graveyard.
* CR 608.2d: choices required by the resolving effect are made while applying the effect.
* CR 608.2m: an instant, sorcery, or ability that can legally resolve continues to resolve if it
  leaves the stack after resolution starts.
* CR 608.2n: putting an instant or sorcery spell into its owner's graveyard is the final part of
  that spell's resolution. CR 608.2p handles triggers after the resolution steps complete.
* CR 400.6 applies replacement effects to a zone-change event before the event moves the object;
  CR 400.7 makes the destination object new.
* CR 704.3 and 704.4 defer state-based actions until the priority boundary and make them
  inapplicable as an intermediate resolution operation.
* CR 112.1a and CR 707.10 define a spell copy as a spell even without a card, and CR 704.5e / 707.10a
  make a spell copy cease to exist when it is in a zone other than the stack.

The rules therefore support a normal disposition (including replacements and the destination
object) followed by copy disappearance at the next state-based-action check. They do not support
finishing that disposition while an effect-owned resolution choice is still pending.

## Current production flow

The relevant control flow is:

```text
StackResolver.resolveTop
  -> read top entity
  -> popFromStack()
  -> resolveSpell(...)
  -> resolveNonPermanentSpell(...)
  -> effect executor returns a pending decision
  -> ordinary paused branch removes SpellOnStackComponent and TargetsComponent
  -> addToZone(graveyard/exile)
  -> directly creates ZoneChangeEvent
  -> returns paused
  -> resolveSpell adds ResolvedEvent and returns paused
```

In the current ordinary paused branch, `resolveNonPermanentSpell` uses
`ZoneMovementUtils.checkZoneChangeRedirect` as a redirect probe. For the ordinary
graveyard/exile case it then performs `updateEntity` + `addToZone` and constructs a
`ZoneChangeEvent` directly. The event has no `fromZone` in this branch. Hand/library destinations
use `ZoneTransitionService.moveToZoneWithReplacements`, but that disposition is still started
before the original effect decision is answered.

`resolveTop` only re-adds the item to `state.stack` for a pending replacement continuation when
the entity still has `SpellOnStackComponent`. The ordinary pending-effect branch has already
removed that component, so the test observes an empty stack.

The continuation boundary is:

```text
PassPriorityHandler.resolveTopOfStack
  -> returns immediately when resolveTop is paused
  -> no SBA check while pending

SubmitDecisionHandler
  -> clears the pending decision
  -> ContinuationHandler.resume(...)
  -> on a completed public action, invokes StateBasedActionChecker
  -> PhantomCardCopiesCheck removes a copy that is already in a non-stack zone
```

The current paused result also contains `DecisionRequestedEvent`, the early
`ZoneChangeEvent`, and `ResolvedEvent` in that order for the minimal fixture. `ResolvedEvent` is
therefore emitted while the effect-owned decision is still unresolved.

## Characterization evidence

The test file is:

```text
rules-engine/src/test/kotlin/com/wingedsheep/engine/event/
PausedSpellResolutionDispositionRulesCharacterizationTest.kt
```

It creates a minimal instant/sorcery-like stack entity with a `MayEffect` and a fixed starting
object stamp. The copy variant adds the existing stack-copy `CopyOfComponent`. It records only
structural, zone, component, event-class, and stamp facts; no raw identifiers are printed.

The focused test has 5 passing cases:

| Case | Facts observed before the decision is answered |
| --- | --- |
| ordinary spell pauses | `state.stack` is empty; entity is in owner's graveyard; `SpellOnStackComponent` is absent; stamp advances from 101 to 102; pending decision and continuation remain; event classes are `DecisionRequestedEvent`, `ZoneChangeEvent`, `ResolvedEvent`; zone event is `null -> GRAVEYARD` |
| copied spell pauses | same early destination transition; `CopyOfComponent` remains; copy entity is present in graveyard with stamp 102; pending decision and continuation remain |
| direct continuation answer, ordinary spell | no later `ZoneChangeEvent`; the entity remains in graveyard; this direct continuation API does not run the action-boundary SBA |
| direct continuation answer, copy | no later `ZoneChangeEvent`; the copy remains in graveyard; the direct continuation API does not run `PhantomCardCopiesCheck` |
| public `SubmitDecision` answer, copy | continuation stack is empty; `SubmitDecisionHandler` reaches the normal SBA boundary; `PhantomCardCopiesCheck` removes the copy; no second `ZoneChangeEvent` is produced |

The last case uses `PRECOMBAT_MAIN` so the normal post-resolution SBA branch is exercised rather than
the unrelated turn-advance handling for an `UNTAP` fixture.

Existing Fix-04 controls were also run. They cover non-paused copied fizzle, non-paused successful
copied resolution, ordinary non-copy fizzle, one replacement redirect, generic stack-to-zone
incarnation, existing phantom-copy SBA cleanup, and countered copied spells.

## Required versus current lifecycle

The supported models resolve as follows:

* Model A (direct disappearance) is not Rules-correct for a copied spell. It is the current
  pre-Fix-04 shortcut that Fix-04 removed for non-paused copy paths.
* Model B is required: the spell copy follows the normal stack-to-destination disposition, with
  replacement processing and a zone change; it is then removed by the existing copy SBA before
  priority is received. The copy has no physical card, but it is still a spell under CR 112.1a.

The required generic timeline is:

```text
T0  copied or ordinary spell is the resolving stack object, E/S0
T1  effect asks for a decision; resolution remains in flight
T2  decision is answered; remaining resolution continuations finish
T3  final stack-to-destination event is replacement-processed
T4  destination object is created as E/S1, S1 != S0, and ZoneChangeEvent is emitted
T5  at the next priority boundary, PhantomCardCopiesCheck removes a spell copy
```

For the current paused implementation, T3/T4 happen before T2. The engine reuses the runtime
`EntityId` while advancing its object-identity stamp, so the runtime ID is not itself the Rules
object identity. The copy is finally absent only after the SBA boundary.

## Required result matrix

```text
PAUSED_SPELL_CURRENTLY_LEAVES_STACK_EARLY=YES
RULES_REQUIRE_SPELL_TO_REMAIN_RESOLVING_UNTIL_DECISION_COMPLETES=YES
SPELL_ON_STACK_COMPONENT_DURING_PAUSE_REQUIRED=YES
ZONE_CHANGE_EVENT_DURING_PAUSE_CORRECT=NO
REPLACEMENT_PROCESSING_DURING_PAUSE_CORRECT=NO
SBA_COPY_CLEANUP_DURING_PAUSE_CORRECT=NO
ORDINARY_SPELL_AFFECTED=YES
SPELL_COPY_AFFECTED=YES
```

`SBA_COPY_CLEANUP_DURING_PAUSE_CORRECT=NO` means the current lifecycle is incorrect: the copy has
already been moved into an SBA-eligible zone while resolution is still pending. The absence of an
SBA during the pause is itself the correct timing; the object should instead still be a resolving
stack object at that point.

```text
RULES_REQUIRED_COPY_FIZZLE_LIFECYCLE=
  normal stack-to-destination disposition, including replacement processing and a ZoneChangeEvent,
  followed by CR 704.5e / 707.10a copy disappearance at the next SBA check

COPY_FIZZLE_ZONE_CHANGE_REQUIRED=YES
ZONE_CHANGE_EVENT_EXPECTED=YES for an actual destination move
COPY_DESTINATION_OBJECT_EXISTS=YES transiently between the move and the SBA
COPY_DESTINATION_STAMP_REQUIRED=YES
COPY_CEASES_TO_EXIST_STAGE=after destination move, at the next SBA check before priority
ENTITY_ID_AFTER_DISPOSITION=runtime EntityId is reused by the engine with a new object stamp
FINAL_COPY_ENTITY_EXISTS=NO after PhantomCardCopiesCheck

COPY_FIZZLE_REPLACEMENT_PIPELINE_REQUIRED=YES
CURRENT_COPY_BRANCH_BYPASSES_REPLACEMENTS=YES for the generic paused graveyard/exile branch;
  the hand/library branch enters the existing pipeline but starts it too early

CURRENT_DIRECT_REMOVAL_SKIPS_RULES_SEMANTICS=PARTIAL
  it skips/deforms the final-resolution boundary, full generic disposition timing, and the
  destination event's stack origin; it does not mean every possible trigger would fire for a copy

EXISTING_GENERIC_COPY_CLEANUP_MECHANISM=YES
REUSABLE_MECHANISM=PhantomCardCopiesCheck registered by ZoneSbaModule
```

The current direct removal is therefore not correct for paused ordinary spells or paused copies.
It is also inconsistent with the current non-paused Fix-04 path, which already produces a
destination incarnation and lets the existing SBA remove the copy.

## Controls

```text
COPY_SUCCESSFUL_RESOLUTION_LIFECYCLE=
  current non-paused path creates a stack-to-graveyard ZoneChangeEvent, advances the object stamp,
  leaves the copy transiently in the graveyard, then the existing phantom-copy SBA removes it

COPY_FIZZLE_LIFECYCLE=
  current non-paused path has the same disposition shape; current paused path performs that shape
  before the pending effect is complete

COPY_SUCCESSFUL_RESOLUTION_CONSISTENT_WITH_CR=YES for the non-paused control
COPY_COUNTERED_CONTROL=PASS
```

The existing replacement redirect control passes for a non-paused copy. It does not establish that
the paused branch defers replacement processing correctly; that timing is part of the blocker.

## Classification and smallest future boundary

```text
DEFECT_CLASSIFICATION=CORE_RULE_ENGINE_GAP
```

The manifestation is at the StackResolver/continuation seam, but the defect is generic Rules
state/lifecycle timing rather than History behavior. The smallest future production boundary is:

```text
StackResolver + resolution continuation completion seam:
  preserve the resolving spell and SpellOnStackComponent as an in-flight stack object while an
  effect-owned decision is pending;
  defer final destination selection, replacement processing, SpellOnStackComponent removal,
  ZoneChangeEvent, and ResolvedEvent until all resolution continuations complete;
  then use the existing ZoneTransitionService path and the existing public SBA boundary, including
  PhantomCardCopiesCheck.
```

This must reuse the existing disposition/replacement and copy-cleanup machinery. It must not add a
History special case, a second copy-cleanup system, or a card-specific branch. The exact future
representation of an in-flight popped item requires its own implementation task; this report does
not choose or implement it.

## Impact on Fix-04

```text
COPY_FIZZLE_NON_PAUSED=STILL_VALID
COPY_SUCCESSFUL_NON_PAUSED=STILL_VALID
COPY_PAUSED_RESOLUTION=INVALID
SPELL_COPY_STACK_DISPOSITION_RULES_FIX_04=NEEDS_REMEDIATION
```

The non-paused parts of Fix-04 remain useful and were not reverted. The paused expectation in the
existing Fix-04 characterization is evidence of current behavior, not proof that early disposition
is Rules-correct.

## Verification and scope

```text
FOCUSED_CHARACTERIZATION=PASS (5/5)
FOCUSED_SURROUNDING_RULES=PASS (78/78, 0 failures, 0 skipped)
FULL_RULES_TEST=NOT_RUN (optional for this characterization)
JUST=UNAVAILABLE (Windows WSL launcher failed with WinError 193)
GRADLE_FALLBACK=PASS
GIT_DIFF_CHECK=PASS

PRODUCTION_FILES_CHANGED=0
TEST_FILES_CHANGED=1
DOC_FILES_CHANGED=1

RULES_PRODUCTION_CHANGED=NO
HISTORY_A_CHANGED=NO
HISTORY_C_CHANGED=NO
HISTORY_D_CHANGED=NO
REPLAY_CHANGED=NO
GYM_CHANGED=NO
DECKS_CHANGED=NO
PERFORMANCE_CHANGED=NO
ML_CHANGED=NO
TRAINING_STARTED=NO
```

The 78 focused surrounding tests were:

```text
PausedSpellResolutionDispositionRulesCharacterizationTest              5
SpellCopyFizzleZoneLifecycleCharacterizationTest                        9
SpellFizzledEmitterAuthorityCharacterizationTest                       1
ResolvingSpellCopyPayloadTest                                           1
StormIllegalTargetFizzleTest                                            2
ContinuationDiagnosticsPropagationTest                                   1
ContinuationSystemTest                                                   4
GatedEffectScenarioTest                                                 11
PhantomCardCopiesCheckTest                                               6
CommanderZoneReplacementTest                                             38
TOTAL                                                                    78
```

No performance corpus was run. No production file was changed. The next appropriate task is a
separate implementation/review slice for this generic paused-resolution lifecycle:

```text
NEXT_RECOMMENDED_TASK=PAUSED_SPELL_RESOLUTION_DISPOSITION_RULES_FIX_06
```

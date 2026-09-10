# Spell-copy fizzle zone-lifecycle Rules characterization 03

```text
TASK=SPELL_COPY_FIZZLE_ZONE_LIFECYCLE_RULES_CHARACTERIZATION_03
CHARACTERIZATION_BASE=8d5b1c8615394ac0c897cd8717ad34b7fe153c3b
CHARACTERIZATION_PARENT=1f9acf4a0ba691c73994948fb52d023d4d840ac3
NEW_COMMIT_PARENT=8d5b1c8615394ac0c897cd8717ad34b7fe153c3b
CURRENT_ORIGIN_MAIN=32cac5f2f99eda863414fe9881df0ca93dfff537
CURRENT_UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
BRANCH=chris/spell-copy-fizzle-zone-lifecycle-rules-characterization-03-20260910
CHARACTERIZATION_SHA_ANCESTOR_OF_CURRENT_BRANCH=YES
```

This is test-only Rules evidence. It does not implement the lifecycle fix and does not change
History-A, History-C, History-D, replay, decks, performance, or ML.

## Rules authority

At execution time the [Wizards Comprehensive Rules page](https://magic.wizards.com/en/rules)
linked [MagicCompRules 20260819.txt](https://media.wizards.com/2026/downloads/MagicCompRules%2020260819.txt).
That document says it is effective as of **August 7, 2026**.

The relevant current rules are:

- CR 112.1a: a copy of a spell is also a spell even without an associated card.
- CR 112.2: a copy's owner is the player under whose control it was put onto the stack.
- CR 400.7: moving an object to another zone creates a new object.
- CR 608.2b: when all targets are illegal, the spell is removed from the stack and put into its
  owner's graveyard.
- CR 608.2c: replacement effects may modify actions performed during resolution.
- CR 608.2n: an instant or sorcery spell is put into its owner's graveyard as the final part of
  resolution.
- CR 614.1, 614.4, and 614.6: replacement effects apply to events before they happen, and the
  modified event occurs instead.
- CR 704.3 and 704.5e: state-based actions are checked after resolution/when priority would be
  received, and a spell copy in a zone other than the stack ceases to exist.
- CR 707.10 and 707.10a: copying puts a spell copy onto the stack, and the copy ceases to exist
  only after it is in a non-stack zone.

These rules select the normal-disposition lifecycle, not direct disappearance from the stack.

## Complete current emitter inventory

The current fork has four `SpellFizzledEvent` constructor sites in two logical production
routines. The event declaration is `SpellFizzledEvent(spellEntityId, cardName, reason)`.

| Emitter | Preconditions and subject | Current event/state behavior |
| --- | --- | --- |
| `StackResolver.fizzleSpell`, `CopyOfComponent` branch | All targets are illegal and `spellId` is the resolving copy spell on the stack before disposition. | `resolveTop` has already popped the stack; `removeEntity(spellId)` is called; only `SpellFizzledEvent` is returned. No zone transition, destination object, or replacement scan occurs. |
| `StackResolver.fizzleSpell`, pending replacement-destination completion | A non-copy fizzle is redirected toward a destination that uses the pending zone-change pipeline, such as hand or library. | The pending transition completes through `ZoneTransitionService`; the returned event list contains `SpellFizzledEvent` and the transition events. A pause resumes through the continuation routine below. |
| `StackResolver.fizzleSpell`, ordinary destination branch | A non-copy fizzle reaches the direct destination path after the redirect probe. | The spell is added to the destination zone and `SpellFizzledEvent` plus `ZoneChangeEvent` are returned. |
| `ReplacementContinuationResumer`, `StackSpellDispositionZoneChangeCompletion` | A pending stack-spell disposition has completed after replacement/commander decisions. | `performPendingZoneChange` completes the physical move, then the continuation constructs the fizzle disposition event and passes it onward with the transition events. |

Non-emitter uses are polymorphic event serialization and the client display adapter. No separate
replay or History authority was found.

## Exact current control flow

For a copied spell whose targets are all illegal:

```text
StackResolver.resolveTop(state)
  -> read top copy entity
  -> pop copy from GameState.stack
  -> resolveSpell(...)
  -> targetValidator returns no legal targets
  -> fizzleSpell(...)
  -> CopyOfComponent branch
  -> state.removeEntity(spellId)
  -> return [SpellFizzledEvent]
```

The copy branch returns before `checkZoneChangeRedirect`, before
`ZoneTransitionService.moveToZoneWithReplacements`, and before any destination-zone event is
constructed. `removeEntity` removes the container from the entity map and zones, but the current
`objectIdentityStamps` entry is retained.

The same direct-removal pattern appears after successful non-permanent copy resolution in
`resolveNonPermanentSpell`: effects complete, then the `CopyOfComponent` branch removes the copy
without routing the final instant/sorcery disposition through a zone transition. Successful
permanent spell copies are a separate control path: `resolvePermanentSpell` turns them into token
permanents, as required by CR 707.10f.

## Test evidence for current behavior

`SpellCopyFizzleZoneLifecycleCharacterizationTest` uses a minimal real `StackResolver` fixture and
has eight passing focused tests:

1. A hand-built copied instant/sorcery fizzle returns exactly `SpellFizzledEvent`, leaves no copy
   entity in any zone, and leaves the old stamp-map entry unchanged.
2. A copy produced through `StackResolver.putSpellCopy` follows the same direct-removal branch.
3. An ordinary non-copy fizzle is the control: it returns `SpellFizzledEvent` and
   `ZoneChangeEvent`, places the spell in the graveyard, and receives a new destination stamp.
4. A successful non-permanent copy is also directly removed in the current engine and emits no
   zone-change event.
5. A generic battlefield `RedirectZoneChange` would redirect stack-to-graveyard, but the current
   copied-fizzle result ignores it and has no destination event.
6. Calling the existing generic `ZoneTransitionService` directly on the same copy produces a
   stack-to-graveyard `ZoneChangeEvent`, retains the copy entity in the destination, and assigns a
   new object stamp.
7. Calling the existing `StateBasedActionChecker` on that non-stack copy invokes the existing
   phantom-copy cleanup and removes it without a second zone-change event.
8. `counterSpell` is a control path: a countered copy uses the existing zone transition and
   `ZoneChangeEvent`, then `PhantomCardCopiesCheck` removes it at the SBA boundary.

This separates observed implementation behavior from the Rules-required result; the test is a
characterization of the current implementation, not an assertion that direct removal is legal.

## Required Magic lifecycle

The required lifecycle is Model B from the task alternatives:

```text
T0: copied spell E is a spell on the stack with stamp S0
T1: all targets are illegal; resolution does not happen
T2: CR 608.2b removes E from the stack and puts the spell copy into its owner's graveyard,
    unless an applicable replacement changes the destination
T3: the zone move creates a new destination object for E with a new stamp S1
    and the engine's generic move emits ZoneChangeEvent
T4: the next SBA check sees a spell copy in a non-stack zone
    and CR 704.5e/707.10a makes it cease to exist
```

The final copy entity does not exist. The same EntityId may name the transient destination object
between T3 and T4; it is not a stable cross-zone identity. The current direct-removal branch
instead makes the entity absent at T2 and retains only the old stamp-map entry.

```text
RULES_REQUIRED_COPY_FIZZLE_LIFECYCLE=
  stack copy -> owner destination via normal spell disposition/replacement pipeline
  -> destination ZoneChangeEvent/new object incarnation
  -> SBA phantom-copy disappearance

COPY_FIZZLE_ZONE_CHANGE_REQUIRED=YES
ZONE_CHANGE_EVENT_EXPECTED=YES
COPY_DESTINATION_OBJECT_EXISTS=YES (transiently)
COPY_DESTINATION_STAMP_REQUIRED=YES
COPY_CEASES_TO_EXIST_STAGE=the first applicable SBA check after the copy enters a non-stack zone
FINAL_COPY_ENTITY_EXISTS=NO
```

The final destination is conditional because replacement effects can modify the move. The physical
stack-to-destination transition itself is not optional for a copied spell.

## Replacement and generic semantic consequences

```text
COPY_FIZZLE_REPLACEMENT_PIPELINE_REQUIRED=YES
CURRENT_COPY_BRANCH_BYPASSES_REPLACEMENTS=YES
```

CR 608.2c and CR 614 require the engine to evaluate applicable replacement effects before the
zone-change event occurs. The focused generic redirect fixture proves that the existing
replacement vocabulary can match a stack-to-graveyard event, while `fizzleSpell` returns before
that check for copies. Applicability of a particular effect remains effect-specific: a replacement
whose wording is limited to cards need not apply to a cardless spell copy, while a generic
object/zone replacement can. The copy branch currently cannot make that determination because it
does not enter the pipeline at all. Commander-specific replacement is not expected for a copy that
has no commander identity, but it must likewise be allowed to resolve to no match through the
normal pipeline.

```text
CURRENT_DIRECT_REMOVAL_SKIPS_RULES_SEMANTICS=PARTIAL
```

It bypasses the generic zone-transition atom, replacement evaluation, destination object
incarnation, `ZoneChangeEvent`, and any generic triggers or consumers that depend on that physical
move. It does not create a battlefield-exit LKI case because the subject starts on the stack; the
relevant loss is the missing spell zone-transition evidence, not a missing battlefield snapshot.
It also bypasses the existing SBA cleanup because the copy never reaches a scanned non-stack zone.

## Copy cleanup and controls

```text
EXISTING_GENERIC_COPY_CLEANUP_MECHANISM=YES
REUSABLE_MECHANISM=
  ZoneSbaModule -> PhantomCardCopiesCheck -> remove non-stack stack-style copies
```

`PhantomCardCopiesCheck` scans hand, graveyard, library, and exile for stack-style copies whose
`CopyOfComponent.originalCardComponent` is null. `ZoneSbaModule` registers it with
`StateBasedActionChecker`. It removes the phantom copy without emitting a second movement event;
the movement event belongs to the preceding stack-to-zone transition.

```text
COPY_SUCCESSFUL_RESOLUTION_LIFECYCLE=
  current non-permanent copy executes effects, then direct entity removal; no ZoneChangeEvent

COPY_FIZZLE_LIFECYCLE=
  current direct entity removal before any destination/replacement pipeline; no ZoneChangeEvent

CONSISTENT_WITH_CR=NO
```

The countered-copy control uses the existing ordinary spell-disposition path and is therefore
useful evidence that the generic transition plus later phantom cleanup is already representable.

```text
COPY_COUNTERED_CONTROL=PASS
```

## Classification and future fix boundary

```text
DEFECT_CLASSIFICATION=CORE_RULE_ENGINE_GAP
```

The confirmed defect is one generic Rules/lifecycle gap: a stack-style spell copy is removed
directly instead of receiving the normal spell disposition before the copy-specific SBA cleanup.
The successful non-permanent copy path exhibits the same lifecycle omission, so this is not a
card-specific or History-specific defect.

```text
SMALLEST_GENERIC_FIX_BOUNDARY=
  route copied-spell fizzle and the corresponding non-permanent completion through the existing
  generic stack-spell disposition/replacement transition; then let PhantomCardCopiesCheck perform
  the already-existing CR 704.5e/707.10a cleanup
```

Expected ownership is primarily `rules-engine/.../StackResolver.kt`, with focused Rules tests.
`ZoneTransitionService`, `ReplacementContinuationResumer`, and `PhantomCardCopiesCheck` should be
reused rather than given a second copy-specific cleanup system. This characterization does not
authorize those changes.

## Impact on Characterization-02

```text
SPELL_ENTITY_ID_EVENT_TIME_SEMANTICS=
  STILL_VALID: the event identifies the pre-disposition resolving spell stack object

BEFORE_OBJECT_UNIVERSAL=
  NEEDS_RECHARACTERIZATION_AFTER_RULES_FIX: the copy emitter currently has invalid lifecycle

ZONE_CHANGE_AFTER_FIZZLE=
  NEEDS_RECHARACTERIZATION_AFTER_RULES_FIX: destination is conditional, but the copy cannot be
  treated as a valid no-zone-change exception

ENTITY_ID_SURVIVES=
  NEEDS_RECHARACTERIZATION_AFTER_RULES_FIX: it should name a transient destination incarnation,
  then disappear at SBA; current code removes it immediately

INCARNATION_STAMP_CHANGES=
  NEEDS_RECHARACTERIZATION_AFTER_RULES_FIX: a correct destination entry must create S1 from S0

EVENT_OWNED_INCARNATION_AUTHORITY_REQUIRED=
  STILL_VALID: the committed before-state witness remains the authority for the fizzle event;
  this Rules gap does not justify a new event field

RULES_METADATA_DEPENDENCY=
  STILL_VALID: no new History metadata is established by this task

HISTORY_C_DEPENDENCY=
  STILL_VALID: later binding still needs the committed before-state stack witness
```

Remaining unknowns are limited to the post-fix behavior of every supported replacement and
continuation shape. The current evidence is sufficient to authorize a separate generic Rules fix;
it is not sufficient to authorize the SpellFizzled History-A/C implementation yet.

## Verification

Executed on this characterization branch:

```text
just test-class SpellFizzledEmitterAuthorityCharacterizationTest
UNAVAILABLE: Windows WinError 193 before Gradle

.\gradlew.bat :rules-engine:test --tests "*SpellFizzledEmitterAuthorityCharacterizationTest" --max-workers=1
PASS: 1 test, 0 failures

.\gradlew.bat :rules-engine:test --tests "*SpellCopyFizzleZoneLifecycleCharacterizationTest" --max-workers=1
PASS: 8 tests, 0 failures

.\gradlew.bat :rules-engine:test --tests "*SpellCopyFizzleZoneLifecycleCharacterizationTest" --tests "*SpellFizzledEmitterAuthorityCharacterizationTest" --tests "*StormIllegalTargetFizzleTest" --tests "*ResolvingSpellCopyPayloadTest" --tests "*TargetedProliferateTest" --tests "*PartialIllegalTargets608Test" --tests "*CommanderZoneReplacementTest" --max-workers=1
PASS: 128 tests, 0 failures, 0 skipped

.\gradlew.bat :gym:test --tests "*SpellFizzledHistoryAFirstBlockerCharacterizationTest" --max-workers=1
PASS: 1 test, 0 failures

git diff --check
PASS
```

The full Rules suite and performance corpus were not run for this test-only characterization.

```text
PRODUCTION_FILES_CHANGED=0
TEST_FILES_CHANGED=1
DOC_FILES_CHANGED=1

RULES_PRODUCTION_CHANGED=NO
HISTORY_A_PRODUCTION_CHANGED=NO
HISTORY_C_PRODUCTION_CHANGED=NO
HISTORY_D_CHANGED=NO
REPLAY_CHANGED=NO
OBSERVATION_CHANGED=NO
GYM_CHANGED=NO
CARD_DEFINITIONS_CHANGED=NO
LOCKED_DECKS_CHANGED=NO
PERFORMANCE_CHANGED=NO
ML_CHANGED=NO
TRAINING_STARTED=NO
```

Recommended next task:

```text
SPELL_COPY_FIZZLE_ZONE_LIFECYCLE_RULES_FIX_04
```

It must add a RED regression for the required stack-to-destination transition, implement only the
generic Rules/lifecycle fix, and independently revalidate this characterization before any
SpellFizzled History-A/C production binding is attempted.

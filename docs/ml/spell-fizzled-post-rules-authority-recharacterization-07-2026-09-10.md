# SpellFizzledEvent post-rules authority recharacterization 07

```text
TASK=SPELL_FIZZLED_POST_RULES_AUTHORITY_RECHARACTERIZATION_07
BASE=630be6f5ade5ca67ff3b49dd5229290c1ad0d9b1
ORIGIN_MAIN=630be6f5ade5ca67ff3b49dd5229290c1ad0d9b1
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
```

This is a test/documentation-only recharacterization after PR #171. It does not implement the
SpellFizzled History-A/C binding and does not change Rules, event, replay, Gym, deck, performance,
C1, or training behavior.

## Result

The corrected non-permanent spell disposition now gives ordinary spells and spell copies the same
pre-disposition subject boundary. `SpellFizzledEvent.spellEntityId` names the resolving stack
object before the final disposition. The event is placed before the final `ZoneChangeEvent` in the
returned event list. The zone transition advances the object identity stamp; for a copy, the
destination incarnation exists until the existing `PhantomCardCopiesCheck` state-based action
removes it.

```text
SPELL_FIZZLED_SUBJECT_AUTHORITY=pre-disposition resolving stack object
SPELL_FIZZLED_BEFORE_OBJECT_WITNESS_VALID=YES
SPELL_FIZZLED_EVENT_PRECEDES_ZONE_CHANGE=YES
DESTINATION_OBJECT_IS_NEW_INCARNATION=YES
COPY_DESTINATION_THEN_SBA_CLEANUP=YES
HISTORY_C_EXISTING_VOCABULARY_SUFFICIENT=YES
NEW_RULES_METADATA_REQUIRED=NO
NEW_EVENT_SCHEMA_REQUIRED=NO
```

## Current emitter inventory

The declaration is `SpellFizzledEvent(spellEntityId, cardName, reason)` in `GameEvent.kt`.

The current production constructor sites are three expressions in two logical routines:

| Site | Meaning and timing |
| --- | --- |
| `StackResolver.fizzleSpell`, direct completion of a redirected pending move | The fizzle event is prepended to the completed `ZoneTransitionService` events. |
| `StackResolver.fizzleSpell`, ordinary/direct disposition | The event is returned immediately before the final zone-change event. |
| `ReplacementContinuationResumer`, `StackSpellDispositionZoneChangeCompletion` | The pending transition is completed through `ZoneTransitionService`; the returned event list is disposition event followed by transition events. |

`GameEvent` serialization and `ClientEvent` display mapping are consumers, not additional emitters.
The former copy-specific direct-removal constructor path is gone. There is no materially different
current lifecycle among the three sites: the pending replacement site differs only by suspension
and resumption timing.

## Lifecycle evidence

The focused test uses a committed state with the spell on the stack and a pre-disposition stamp
`S0=101`. The returned transition is inspected before any SBA is applied.

```text
T0  committed before-state:
    stack contains E; objectIdentityStamps[E] = S0

T1  SpellFizzledEvent:
    spellEntityId = E; no state mutation is carried by the event value

T2  final disposition:
    ZoneChangeEvent follows the fizzle event; E enters its destination
    objectIdentityStamps[E] = S1; S1 != S0; SpellOnStackComponent is removed

T3  copy-only SBA:
    E is still present transiently in the destination, then PhantomCardCopiesCheck removes it
```

The three cases are asserted separately:

1. An ordinary all-illegal-target spell moves to the graveyard and changes its stamp.
2. A copied all-illegal-target spell also emits `SpellFizzledEvent` and `ZoneChangeEvent`, receives a
   new destination stamp, and is then removed by `PhantomCardCopiesCheck`.
3. A replacement redirect to exile retains the same event ordering and pre-disposition subject,
   while changing only the destination zone.

The existing pending replacement control remains consistent with this result: if the replacement
pipeline pauses, the transition and its fizzle event are deferred together; no after-state
inference is needed for the subject authority.

## History-C conclusion

No Rules-owned event metadata or event-schema extension is needed. The existing generic History-C
vocabulary remains sufficient:

```text
raw subject = SpellFizzledEvent.spellEntityId
role        = EVENT_SUBJECT
kind        = STACK_OBJECT
endpoint    = BEFORE_OBJECT
witness     = committed transition.beforeState witness
```

`AFTER_OBJECT` is not used. The destination stamp is deliberately not the subject witness, and the
copy's later SBA disappearance does not remove the pre-disposition stack witness from the committed
transition. History-A/C binding remains a separate, not-yet-implemented follow-up.

## Verification

```text
New focused test:
  :rules-engine:test --tests "*SpellFizzledPostRulesAuthorityRecharacterizationTest"
  3 tests, 0 failures

Existing Rules controls:
  :rules-engine:test --tests "*SpellFizzledEmitterAuthorityCharacterizationTest" \
                    --tests "*SpellCopyFizzleZoneLifecycleCharacterizationTest"
  10 tests, 0 failures

```

The `just test-class` wrapper was attempted but is unavailable on this Windows host because its
WSL-backed `scripts/gradle-locked` entry cannot start `/bin/bash` (`WinError 193`). The native
Gradle wrapper is the separately labelled fallback. Full Rules/Gym suites and the performance
corpus were not run for this small characterization.

```text
CHARACTERIZATION_RESULT=PASS
```

## Scope

```text
PRODUCTION_FILES_CHANGED=0
TEST_FILES_CHANGED=1
DOC_FILES_CHANGED=1

RULES_PRODUCTION_CHANGED=NO
HISTORY_A_CHANGED=NO
HISTORY_C_PRODUCTION_CHANGED=NO
HISTORY_D_CHANGED=NO
REPLAY_CHANGED=NO
OBSERVATION_CHANGED=NO
GYM_CHANGED=NO
DECKS_CHANGED=NO
PERFORMANCE_CHANGED=NO
CARD_DEFINITIONS_CHANGED=NO
ML_CHANGED=NO
TRAINING_STARTED=NO
```

Recommended next task:

```text
NEXT_RECOMMENDED_TASK=SPELL_FIZZLED_HISTORY_A_C_BINDING_FIX_08
```

This characterization does not authorize that implementation.

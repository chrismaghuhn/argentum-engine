# SpellFizzledEvent History-C authority characterization 02

```text
TASK=SPELL_FIZZLED_HISTORY_C_AUTHORITY_CHARACTERIZATION_02
BASE=1f9acf4a0ba691c73994948fb52d023d4d840ac3
BASE_PARENT=32cac5f2f99eda863414fe9881df0ca93dfff537
ORIGIN_MAIN=32cac5f2f99eda863414fe9881df0ca93dfff537
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
```

This is a test/audit artifact only. No Rules, History-A, History-C, History-D, replay, Gym, deck,
observation, performance, or ML production code was changed.

## Result

The current fork has four `SpellFizzledEvent` constructor call sites in two production routines.
Control-flow inspection shows that every one identifies the resolving spell **before its fizzle
disposition**. The current committed transition already supplies the authoritative `beforeState`
object witness, so a new event-owned endpoint or incarnation field is not required for the current
emitter inventory.

The smallest future History-C contract is therefore:

```text
raw SpellFizzledEvent.spellEntityId
+ current committed transition.beforeState witness
+ explicit History-C event mapping:
     EVENT_SUBJECT / STACK_OBJECT / BEFORE_OBJECT
```

This is not the same as accepting an EntityId alone. A standalone EntityId is not a stable object
identity, and the C mapping must not use the existing `opaqueCandidate` default of `AFTER_OBJECT`.

## Emitter inventory

```text
SPELL_FIZZLED_EVENT_DECLARATION_FOUND=YES
SPELL_FIZZLED_EMITTER_INVENTORY_COMPLETE=YES
EMITTER_COUNT=4 constructor call sites / 2 logical production emitter routines
```

| Emitter | Preconditions and object meaning | Event ordering and post-state |
| --- | --- | --- |
| `StackResolver.fizzleSpell`, copy branch | Resolving `SpellOnStackComponent` has `CopyOfComponent`; `spellId` is the copy spell object on the stack before resolution. | Emits `SpellFizzledEvent` only; removes the copy entity. No `ZoneChangeEvent` and no after-object entity. |
| `StackResolver.fizzleSpell`, replacement-destination branch | All spell targets are invalid; a replacement can redirect the eventual stack disposition to hand/library. `spellId` is still the pre-disposition stack spell. | On an immediate completion, emits `SpellFizzledEvent` followed by pending transition events. If a replacement decision pauses, the later continuation emits it after the physical move. The destination is conditional. |
| `StackResolver.fizzleSpell`, ordinary branch | All spell targets are invalid; the ordinary destination is the owner's graveyard unless a replacement changes it. `spellId` is the resolving stack spell before movement. | Builds the after-state destination object and returns `[SpellFizzledEvent, ZoneChangeEvent]` in that event order. |
| `ReplacementContinuationResumer`, `StackSpellDispositionZoneChangeCompletion` | A paused stack disposition has completed. `pending.entityId` is the same spell object that was on the stack before the pending zone change. | Emits the disposition event before `transition.events`, which contain the completed physical transition. The destination is conditional. |

The remaining references are non-emitter uses: polymorphic event serialization registers the event,
`ClientEvent` maps it to the existing spell-countered display shape, and Rules tests assert fizzle
behavior. No replay-specific `SpellFizzledEvent` authority or existing History-A/C projection case
was found.

## Event-time lifecycle

The locked RED witness is `engineSeed=1`, `startingPlayerIndex=0`, with the existing B1 policy-seed
derivation and a nonblank History-D episode ID:

```text
successfulChoices=638
committedStep=639
rawEvents=[SpellFizzledEvent, ZoneChangeEvent]
History-D failure=HISTORY_A_PROJECTION_INCOMPLETE
```

For the ordinary locked path:

```text
T0 before committed transition:
   spellEntityId is the resolving spell on the stack with stamp S0

T1 event value:
   SpellFizzledEvent carries spellEntityId, cardName, reason
   no state mutation occurs inside the event value

T2 committed after-state:
   the same EntityId is in the destination zone with stamp S1
   S1 != S0 because the zone entry creates a new object

T3 event list:
   ZoneChangeEvent follows SpellFizzledEvent in the returned event order
```

The pure engine transition can already have built the after-state when it returns the ordered event
list; event-list order must not be treated as a substitute for the Rules-owned before witness.

The Rules copy control test additionally proves:

```text
copy spell on stack before fizzle = YES
SpellFizzledEvent = emitted
ZoneChangeEvent = absent
copy entity after fizzle = absent
old stamp map value = retained but not an after-object witness
```

Therefore:

```text
SPELL_ENTITY_ID_EVENT_TIME_SEMANTICS=
  the pre-fizzle resolving spell stack object; not a stable identity by itself

ZONE_CHANGE_AFTER_FIZZLE=CONDITIONAL
ENTITY_ID_SURVIVES=CONDITIONAL
INCARNATION_STAMP_CHANGES=
  YES for zone-disposition emitters; no after entity exists for the copy-removal branch
```

## Universal endpoint analysis

The four current construction sites all receive a stack spell identity from either `fizzleSpell`
or its pending disposition completion. The transition boundary retains the spell in
`beforeState`:

- ordinary fizzle: `resolveTop` starts from a stack spell and the committed transition's before
  state is before the stack disposition;
- copy fizzle: the copy is removed only after the resolving stack object has been selected;
- replacement destination: the pending move is resumed from a state that still identifies the
  spell before the physical disposition;
- continuation completion: `performPendingZoneChange` is applied to a pending stack disposition,
  so the committed transition before-state is the pre-move spell object.

The copy test and the locked real-emitter test cover the two materially different after-state
shapes. The other two branches differ in destination/pause timing, not in the identity of the
pre-disposition spell. Thus the current-fork result is:

```text
BEFORE_OBJECT_UNIVERSAL=YES
  qualification: proven for all four current constructor sites by control flow;
  not a claim about future emitters
```

A History-C implementation must nevertheless select `BEFORE_OBJECT` explicitly. The existing
candidate helper defaults to `AFTER_OBJECT`, which is wrong for a moved spell and has no usable
after witness for a removed copy.

## Authority-shape analysis

| Candidate | Result for current emitters |
| --- | --- |
| EntityId only | Rejected. It cannot distinguish the pre-fizzle stack object from a later same-ID incarnation. |
| EntityId + event-owned incarnation stamp | Sufficient, but redundant with the committed transition's before witness for the current four emitters. |
| EntityId + event-owned endpoint | Endpoint alone does not prove the incarnation; not sufficient as a standalone identity contract. |
| EntityId + event-owned stamp + endpoint | Sufficient, but larger than the current need and would change the Rules event schema. |
| Existing committed transition context + explicit C event mapping | Smallest sufficient current contract. It binds the raw ID to `beforeState` and fails closed when that witness is absent. |

```text
EVENT_OWNED_INCARNATION_AUTHORITY_REQUIRED=NO
EXPLICIT_ENDPOINT_AUTHORITY_REQUIRED=
  NO as a new Rules event field;
  YES as an explicit History-C mapping to BEFORE_OBJECT
RULES_METADATA_DEPENDENCY=NO
  qualification: no new Rules event field; existing committed beforeState witness is required
HISTORY_C_DEPENDENCY=YES
SMALLEST_SUFFICIENT_AUTHORITY=
  spellEntityId + committed beforeState witness + explicit
  EVENT_SUBJECT / STACK_OBJECT / BEFORE_OBJECT C binding
```

The `NO` result is scoped to the current emitter inventory and the existing
`CommittedRulesTransition`. If a future emitter cannot guarantee that its committed
`beforeState` contains the pre-fizzle spell, it needs a separate characterization rather than a
neighbor-event inference.

## Existing generic History-C vocabulary

The existing vocabulary is sufficient and reusable:

```text
HistoryCReferenceSlotRole.EVENT_SUBJECT
HistoryCReferenceKind.STACK_OBJECT
HistoryCReferenceEndpointAuthority.BEFORE_OBJECT
HistoryCObjectWitness
opaqueCandidate(... endpointAuthority=BEFORE_OBJECT)
```

This matches the event's semantics better than `SOURCE`: the event describes what happened to the
spell object itself. `STACK_OBJECT` is required because CR 707.10 permits a spell copy with no
physical card; a card-only reference kind would not cover that path. The accepted History-C
validator already validates before witnesses and object incarnations without exposing them to the
model.

```text
EXISTING_GENERIC_VOCABULARY_REUSABLE=YES
```

Rejected alternatives:

- `AFTER_OBJECT` or the helper default: follows the new destination incarnation and fails for a
  removed copy;
- deriving the endpoint from a neighboring `ZoneChangeEvent`: not event-owned and not needed;
- a new SpellFizzled-specific reference kind: duplicates existing stack-object authority;
- runtime EntityId in A: violates the model-facing semantic boundary.

## History-A scalar and privacy analysis

The raw event declaration is exactly:

```text
SpellFizzledEvent(
    spellEntityId: EntityId,
    cardName: String,
    reason: String,
)
```

The smallest proposed A projection is:

```text
family=SPELL_FIZZLED
payload={ type: "spell_fizzled", reason: <stable semantic value> }
```

`spellEntityId` belongs to C. `cardName` is not unconditionally safe in A. It is used by the
client display adapter, but the raw event has no perspective-specific identity disclosure and
face-down/copy paths make unconditional model emission unsafe.

```text
CARD_NAME_HISTORY_A_STATUS=CONDITIONAL_ONLY
A_FORBIDDEN_FIELDS=spellEntityId, cardName, raw runtime identity, object stamp
```

All current production constructors use the literal `All targets are invalid`, but `reason` is a
free-form `String` and the pending completion field is nullable. It is safe as a current observed
fact, not yet a versioned semantic enum suitable for unconstrained long-term schema evolution:

```text
REASON_MODEL_PAYLOAD_SAFE=PARTIAL
```

The future A closure should either freeze/allowlist the current semantic reason or introduce a
generic reason vocabulary only if broader evidence requires it. This characterization does not
make that change.

## Rules authority

The current official source is the [Wizards Rules page](https://magic.wizards.com/en/rules), whose
linked [Comprehensive Rules TXT](https://media.wizards.com/2026/downloads/MagicCompRules%2020260819.txt)
states that the rules are effective August 7, 2026.

Relevant rules for this characterization are:

- CR 112.1 and 112.1a: a spell is on the stack, and a copy can be a spell without a card;
- CR 400.7: moving to another zone creates a new object with no memory of the previous existence;
- CR 608.2b: all illegal targets prevent the spell from resolving and a spell is put into its
  owner's graveyard;
- CR 707.10: copying a spell puts a copy on the stack and does not itself cast the copy;
- CR 708.4, 708.5 and 708.9: face-down spell characteristics/visibility and reveal-on-movement
  behavior remain separate from an unconditional card-name payload.

These Rules establish the game meaning of resolution/fizzle/copy/lifecycle. The exact
`SpellFizzledEvent` timing and field semantics above are established by Argentum's current
production control flow and tests.

## Test evidence

The prerequisite RED test remains unchanged and passed:

```text
gradlew.bat :gym:test --tests "*SpellFizzledHistoryAFirstBlockerCharacterizationTest" --max-workers=1
PASS — 1 test, 0 failures
```

The added copy-emitter control passed:

```text
gradlew.bat :rules-engine:test --tests "*SpellFizzledEmitterAuthorityCharacterizationTest" --max-workers=1
PASS — 1 test, 0 failures
```

Relevant surrounding tests passed:

```text
Gym:
  CommittedPerspectiveEventSourceTest       17 passed
  PerspectiveHistoryCompositionTest         10 passed
  SpellFizzledHistoryAFirstBlocker...        1 passed

Rules:
  SpellFizzledEmitterAuthority...            1 passed
  StormIllegalTargetFizzleTest               2 passed
  TargetedProliferateTest                   14 passed
  PartialIllegalTargets608Test              64 passed
```

The `just` wrapper was attempted for the prerequisite class and was unavailable before Gradle on
Windows (`WinError 193`). Native Gradle was used as the separately labeled fallback. No full
Rules/Gym or performance corpus was run for this characterization.

## Required evidence matrix

```text
SPELL_FIZZLED_EVENT_DECLARATION_FOUND=YES
SPELL_FIZZLED_EMITTER_INVENTORY_COMPLETE=YES
EMITTER_COUNT=4 constructor sites / 2 logical routines
SPELL_ENTITY_ID_EVENT_TIME_SEMANTICS=pre-fizzle resolving spell stack object
EVENT_SEQUENCE=conditional; ordinary/redirect [SpellFizzled, ZoneChange], copy [SpellFizzled]
ZONE_CHANGE_AFTER_FIZZLE=CONDITIONAL
ENTITY_ID_SURVIVES=CONDITIONAL
INCARNATION_STAMP_CHANGES=YES when moved; no after object for copy removal
BEFORE_OBJECT_UNIVERSAL=YES for all current emitters by control flow
EVENT_OWNED_INCARNATION_AUTHORITY_REQUIRED=NO for current emitter inventory
EXPLICIT_ENDPOINT_AUTHORITY_REQUIRED=NO as a new event field; YES as explicit C mapping
SEMANTIC_ROLE=EVENT_SUBJECT
REFERENCE_KIND=STACK_OBJECT
EXISTING_GENERIC_VOCABULARY_REUSABLE=YES
CARD_NAME_HISTORY_A_STATUS=CONDITIONAL_ONLY
REASON_MODEL_PAYLOAD_SAFE=PARTIAL
RULES_METADATA_DEPENDENCY=NO
  qualification: existing committed beforeState witness is required
HISTORY_C_DEPENDENCY=YES
SMALLEST_SUFFICIENT_AUTHORITY=
  spellEntityId + committed beforeState witness + explicit
  EVENT_SUBJECT / STACK_OBJECT / BEFORE_OBJECT C mapping
```

## Design conclusion and next task

```text
SpellFizzledEvent
Rules-owned authority:
    current committed transition.beforeState contains the pre-fizzle
    resolving spell object; no additional event field is required for
    the four current emitters

History-C consumes:
    raw spellEntityId paired with that beforeState witness
    under an explicit BEFORE_OBJECT endpoint contract

History-A emits:
    family SPELL_FIZZLED
    scalar reason only after its semantic vocabulary is frozen/allowlisted
    no cardName or runtime identity

Rules-event/display only unless independently visibility-authorized:
    cardName; raw EntityId and internal object stamps never cross the model boundary
```

The next production slice, if separately authorized, is:

```text
NEXT_RECOMMENDED_TASK=SPELL_FIZZLED_HISTORY_A_C_BINDING_FIX_03
```

It should add the minimal A family/reason contract and the explicit C
`EVENT_SUBJECT / STACK_OBJECT / BEFORE_OBJECT` binding, with tests for ordinary, copy, replacement,
face-down, and fail-closed authority cases. It must not add a redundant Rules event field unless a
new emitter proves the existing committed before-witness contract insufficient.

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
PERFORMANCE_CHANGED=NO
CARD_DEFINITIONS_CHANGED=NO
LOCKED_DECKS_CHANGED=NO
ML_CHANGED=NO
TRAINING_STARTED=NO

CHARACTERIZATION_RESULT=PASS
STOP_FOR_EXACT_SHA_REVIEW=YES
```

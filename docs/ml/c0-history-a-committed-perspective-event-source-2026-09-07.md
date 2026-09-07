# C0-HISTORY-A — Committed Perspective Event Source

Task: `C0_HISTORY_A_COMMITTED_PERSPECTIVE_EVENT_SOURCE_01`

This is one implementation slice. It establishes a committed-only, one-transition event source and
an additive perspective-safe event batch. It does not implement the complete perspective history,
known-information ledger, stable semantic aliases, Trajectory V1 binding, or any learner.

## 1. Identity and scope

```text
REPOSITORY=chrismaghuhn/argentum-engine
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
BRANCH=chris/c0-history-a-committed-perspective-events
BASE=a3c2fb3137e50ee56de7a03933fd10ce29ac59fe
SOURCE_AUDIT_HEAD=a3c2fb3137e50ee56de7a03933fd10ce29ac59fe
UPSTREAM_SHA=5021faf88093a93091e4de7914fbe0f411499d58
```

`BASE` is the fetched `origin/main` used for this worktree. The upstream repository was fetched for
reference only. The accepted characterization authority is
`docs/ml/c0-perspective-history-known-information-characterization-2026-09-07.md`.

The implementation boundary is:

```text
successful strict Gym Rules transition
    → internal committed transition token
    → raw ordered GameEvent batch
    → perspective projection
    → immutable PerspectiveEventBatchV1
```

Raw engine state and raw events remain internal inputs. They are never fields of the new public
batch. This slice deliberately stops before a persistent multi-transition history stream.

## 2. Source integration point

### 2.1 Successful strict transition

`GameEnvironment.processAndCommit` is the only producer of the new internal
`CommittedRulesTransition` token. It captures the pre-state, the accepted post-state, the ordered
`ExecutionResult.events`, and an internal post-step count only after:

1. the existing `ActionProcessor` returns;
2. unsupported execution diagnostics have been rejected;
3. an execution error has been rejected;
4. the environment installs the new state and advances its existing counters.

`consumeCommittedTransition` is a one-shot internal handoff. It does not expose a raw state or raw
event list to the model-facing contract.

### 2.2 Gym-only capture

`GameGymEnv.commitStrict` wraps all existing strict external submission paths:

- action-ID-only legal actions;
- structured action payloads;
- direct structured decision responses.

The wrapper calls the existing strict environment operation first, consumes the resulting committed
token, and then hands it to `CommittedPerspectiveEventSource`. A thrown validation, stale-action,
unsupported-path, or engine exception reaches the existing failure classification before a new
source capture is made. The last retrievable projection is invalidated on an external failure so a
failed call cannot be mistaken for a newly committed batch.

### 2.3 Explicit exclusions

The source is not called for:

- `GameEnvironment` legacy simulator execution;
- `GameEnvironment.fork()` rollouts;
- snapshot restore;
- reset initialization events;
- rejected or failed actions;
- replay reconstruction performed only for verification;
- candidate/search/MCTS branches.

Forked `GameGymEnv` instances construct the source with capture disabled. Reset and restore clear
the source. A legacy `GameEnvironment.step` never reaches `GameGymEnv.commitStrict`.

The source retains at most the most recent successful strict transition. That is intentional: A is a
source seam, not `PerspectiveHistoryV1` accumulation.

## 3. `PerspectiveEventBatchV1`

### 3.1 Version and schema

```text
PERSPECTIVE_EVENT_BATCH_V1_VERSION=1
PERSPECTIVE_EVENT_BATCH_V1_SCHEMA_IDENTITY=argentum-gym-perspective-event-batch@v1
```

The additive DTO is in `gym` and does not change `TrainingObservation`, `PlayerObservationV1`,
`CompleteLegalDomainV1`, `TrajectoryV1`, `CommanderPublicStateV1`, or the existing `SchemaHash`.
The top-level `perspectivePlayerId` scopes which player information set the batch represents. It is
not an event-object identity and is not repeated inside event payloads.

```text
PerspectiveEventBatchV1 {
    version
    schemaIdentity
    perspectivePlayerId
    entries[]
}

PerspectiveEventV1 {
    perspectiveEventOrdinal
    eventFamily
    semanticPayload
}
```

`semanticPayload` is a typed JSON object containing only scalar facts and perspective-relative
roles such as `SELF`, `OTHER`, or `DRAW`. It does not contain card/object/player IDs, names that
could be hidden identities, routing IDs, prompts, continuation values, or source coordinates.

### 3.2 Ordinal contract

```text
AUTHORITATIVE_SOURCE_COORDINATE
    = internal provenance only

PERSPECTIVE_HISTORY_ORDINAL
    = 0,1,2,... assigned after event projection
```

The projector first collects emitted semantic entries. It then assigns
`perspectiveEventOrdinal` with `mapIndexed`. Intentionally hidden and unsupported raw events never
consume an ordinal. Consequently:

```text
raw: 0 PUBLIC_A, 1 HIDDEN, 2 PUBLIC_B
P1:  0 PUBLIC_A, 1 PUBLIC_B
```

No raw transition step, raw event ordinal, private decision count, hidden-event count, or hidden
event position is serialized or included in the batch digest. A global action/decision coordinate is
not part of this DTO. It may be retained in a future internal audit binding only after a separate
hidden-state non-interference proof.

### 3.3 Classification and diagnostics

Every input raw event is mapped to exactly one operational disposition:

```text
EMITTED
INTENTIONALLY_HIDDEN
UNSUPPORTED_FOR_PERSPECTIVE_HISTORY
```

`INTENTIONALLY_HIDDEN` requires an explicit rationale in the operational classification. The current
reasons are:

- no visible source or destination endpoint for a `ZoneChangeEvent` under the shared `Visibility`
  service;
- a `HandLookedAtEvent` viewed by a player other than its `viewingPlayerId`;
- a `LookedAtCardsEvent` for a player other than its `playerId`;
- a `CardsRevealedEvent` whose explicit `revealToSelf=false` excludes the revealing player.

`UNSUPPORTED_FOR_PERSPECTIVE_HISTORY` produces an operational diagnostic with the raw event type
and one of the B/C/event-time/uncharacterized reasons. It is not converted into an empty successful
classification. The result contains safe emitted entries plus `isComplete=false`; a future consumer
must call the internal complete gate before treating the batch as a complete source unit.

The `else` branch of the raw sealed `GameEvent` match is deliberately unsupported. A future event
family therefore cannot silently disappear from coverage.

## 4. Projection rules

The projector deliberately emits facts, not a second Rules or visibility model.

### 4.1 Reused visibility authority

`ZoneChangeEvent` projection uses the existing `Visibility.isZoneVisibleTo` service against the
committed pre- and post-states. It emits only a structural owner-role/from-zone/to-zone fact when
at least one endpoint is visible to the perspective. It never emits `entityId`, `entityName`, a
library position, or a physical object alias. If pre/post state is absent, the event is
unsupported with `EVENT_TIME_STATE_REQUIRED` rather than guessed.

This handles these cases conservatively:

- public battlefield/graveyard/stack/exile/command movement: structural event may be emitted;
- a player's own hand endpoint: structural event may be emitted because the existing Visibility
  contract exposes that hand;
- opponent hand/library-only movement: intentionally hidden;
- face-down public-zone movement: only the structural zone fact can be emitted; identity is absent;
- any hidden physical identity: never reconstructed from the raw event name or ID.

### 4.2 Identity-free public facts

The following event families currently have an emitted path that omits raw references:

| Raw family | Emitted semantic facts |
| --- | --- |
| `PhaseChangedEvent`, `StepChangedEvent` | New phase/step enum |
| `TurnChangedEvent`, `PriorityChangedEvent`, `DayNightChangedEvent` | Turn/timing value and relative role; no player ID |
| `LifeChangedEvent` | Relative player role, old/new life, reason, first-this-turn flag |
| `DamageDealtEvent` when `targetIsPlayer` | Relative target role, amount, combat flag; permanent targets defer to C |
| `SpellCastEvent` | Relative caster role only; card, mana, X, mode, and payment details are omitted to avoid face-down characteristic leaks |
| `AbilityActivatedEvent` | Relative controller role and tap/mana/exhaust flags |
| `LandPlayedEvent` | Relative controller role; card reference and origin are omitted |
| `CardsDrawnEvent`, `CardsDiscardedEvent`, `DiscardRequiredEvent` | Relative player role and public count; card IDs/names are omitted |
| `CardRevealedFromDrawEvent` | Relative player role and revealed creature flag; card ID/name omitted |
| `DrawFailedEvent` | Relative player role; diagnostic reason omitted |
| `LibraryShuffledEvent`, `LibrarySearchedEvent` | Relative player role; no knowledge epoch or card content |
| `ScriedEvent`, `SurveiledEvent` | Relative player role and count; looked-at cards omitted |
| `HandRevealedEvent`, `CardsRevealedEvent` | Public reveal fact and count; revealed IDs/names omitted |
| `HandLookedAtEvent`, `LookedAtCardsEvent` for the authorized viewer | Perspective-private fact and count; card IDs/names omitted |
| `AttackersDeclaredEvent`, `BlockersDeclaredEvent`, `DamageAssignedEvent` | Public role/count/aggregate damage facts; object references omitted |
| `CreatureTypeChosenEvent` | Relative player role and chosen type |
| `GameEndedEvent`, `PlayerLostEvent`, `PlayerLeftGameEvent` | Terminal/loss/leave reason and perspective-relative role |
| `TurnFaceUpEvent`, `TurnedFaceDownEvent`, `TransformedEvent` | Public turn/transform fact and relative controller role; hidden identity omitted |
| `SpellCopiedEvent`, `ResolvedEvent` | Public copy/resolve fact and safe aggregate values; object references omitted |

The table is intentionally conservative. A public fact may be emitted without claiming that all
identity or knowledge continuity associated with that event is already available.

### 4.3 No stable semantic aliases

The batch never serializes:

```text
raw EntityId
objectIdentityStamp
allocation order / UUID
runtime action handle
decision ID / continuation ID
```

Card names or face names are not used as cross-step identity. A public card-definition fact may be
added by a later slice only where the event-time visibility proof is explicit. Cross-step physical
object relationships remain C territory.

## 5. Current event-family inventory

The current `GameEvent.kt` hierarchy contains 104 concrete `GameEvent` data classes at this base.
The helper data classes `DamageRecipientKindSet` and `DeclaredAttack` are not events and are not
counted.

The following counts are primary classification counts and sum to 104. The intentionally-hidden
count is reported separately because some emitted families have an explicit hidden branch.

```text
CURRENT_EVENT_FAMILIES_TOTAL=104
EMITTED_SAFE_FAMILIES=36
INTENTIONALLY_HIDDEN_FAMILIES=4 (overlaps emitted families with conditional hidden paths)
DEFERRED_TO_HISTORY_B=1
DEFERRED_TO_HISTORY_C=46
DEFERRED_TO_BOTH=9
UNCHARACTERIZED=12
EVENT_HISTORY_COMPLETE=NO
```

### 5.1 Emitted-safe raw families (36)

```text
PhaseChangedEvent
StepChangedEvent
TurnChangedEvent
DayNightChangedEvent
PriorityChangedEvent
LifeChangedEvent
DamageDealtEvent                 (only player targets)
SpellCastEvent
AbilityActivatedEvent
LandPlayedEvent
ZoneChangeEvent                  (visible endpoint and committed states required)
CardsDrawnEvent
CardRevealedFromDrawEvent
DrawFailedEvent
CardsDiscardedEvent
DiscardRequiredEvent
LibraryShuffledEvent
LibrarySearchedEvent
ScriedEvent
SurveiledEvent
HandLookedAtEvent                (authorized viewer only)
HandRevealedEvent
CardsRevealedEvent               (authorized audience branch)
LookedAtCardsEvent               (authorized viewer only)
AttackersDeclaredEvent           (attacking player is present)
BlockersDeclaredEvent
DamageAssignedEvent
CreatureTypeChosenEvent
GameEndedEvent
PlayerLostEvent
PlayerLeftGameEvent
TurnFaceUpEvent
TurnedFaceDownEvent
TransformedEvent
SpellCopiedEvent
ResolvedEvent
```

### 5.2 Explicit hidden paths (4, overlapping 5.1)

```text
ZoneChangeEvent
HandLookedAtEvent
CardsRevealedEvent
LookedAtCardsEvent
```

The hidden path is selected only from existing zone visibility or explicit event audience data. It
does not infer hidden knowledge from card names, final state, or call-site conventions.

### 5.3 Deferred to C: semantic reference dependency (46)

These raw families carry object identity, target identity, or an object relationship that A does not
replace with an invented alias:

```text
DamagePreventedEvent
CardPlayedFromPermissionEvent
StatsModifiedEvent
KeywordGrantedEvent
RingTemptedEvent
EvidenceCollectedEvent
PermanentExploredEvent
ManifestedDreadEvent
CreatureTypeChangedEvent
SpellCounteredEvent
AbilityCounteredEvent
SpellFizzledEvent
AbilityResolvedEvent
SagaChapterResolvedEvent
ReflexiveAbilityTriggeredEvent
TappedEvent
ExertedEvent
BecameSaddledEvent
PermanentAttachedEvent
PermanentUnattachedEvent
LandTappedForManaEvent
UntappedEvent
CreaturesPairedEvent
CreaturesUnpairedEvent
PhasedOutEvent
PhasedInEvent
CountersAddedEvent
CountersRemovedEvent
LoyaltyChangedEvent
PermanentsSacrificedEvent
ExploitedEvent
TrainedEvent
ClassLevelChangedEvent
CreatureDestroyedEvent
ControlChangedEvent
BecomesTargetEvent
CardCycledEvent
CrewOrSaddleContributionEvent
CardPlottedEvent
CardExiledWithMadnessEvent
GiftGivenEvent
CoinFlipEvent
TurnHijackedEvent
RoomFullyUnlockedEvent
DoorUnlockedEvent
DoorLockedEvent
```

### 5.4 Deferred to B and C: knowledge plus semantic reference (9)

```text
AbilityTriggeredEvent
TargetReselectedEvent
AbilityAutoAnsweredEvent
CommitCrimeEvent
TargetsChosenEvent
BlockerOrderDeclaredEvent
AttackerOrderDeclaredEvent
DecisionRequestedEvent
DecisionSubmittedEvent
```

Routing IDs, prompts, continuation details, and source/target object handles do not become event
features. A later implementation must separately establish event-time audience and semantic object
reference rules.

### 5.5 Deferred as uncharacterized (12)

```text
ManaAddedEvent
ManaSpentEvent
BendPerformedEvent
DiscoveredEvent
MaximumHandSizeRemovedEvent
MaximumHandSizeReducedEvent
CitysBlessingGainedEvent
EnduringStoryGainedEvent
SpeedChangedEvent
AbilityFizzledEvent
CreatureGoadedEvent
CreatureNoLongerGoadedEvent
```

These are not silently hidden. They produce `UNSUPPORTED_FOR_PERSPECTIVE_HISTORY` with an
`UNCHARACTERIZED` diagnostic until their public semantic payload and audience are separately
characterized.

### 5.6 Deferred to B: knowledge-ledger dependency (1)

```text
LibraryReorderedEvent
```

The current event reports that a reorder occurred but does not provide a historical knowledge epoch
or safe persistent card references. A future B/C composition must decide what each perspective knew
before and after the reorder.

## 6. Pre-state, post-state, and event-time boundary

The source captures both committed pre- and post-states internally. A does not reconstruct a hidden
intermediate state. Current behavior is:

| Case | A behavior |
| --- | --- |
| scalar public event with self-contained event payload | Emit identity-free facts |
| public structural zone move | Use pre/post `Visibility` endpoint checks, then emit zones only |
| hidden-to-hidden zone move | Intentionally hide when the existing visibility contract proves neither endpoint visible |
| missing pre/post state for zone projection | Unsupported `EVENT_TIME_STATE_REQUIRED` |
| public reveal/look event | Emit only the public/private occurrence and count; do not persist knowledge |
| event needing last-known object or target relationship | Defer to C, or B+C if knowledge audience also matters |
| face-down or hidden identity in raw event | Omit identity; no card-name/ID heuristic |

This is not a complete event-time state contract. A later family can remain unsupported if correct
projection requires a state between the raw pre- and post-states that the engine does not supply.

## 7. Canonicalization and digest

`PerspectiveEventBatchV1.canonicalJson()` uses the existing strict `A3SemanticJson` serializer and
canonical object-key ordering. The entries array retains producer-projected sequence order. The
semantic digest is SHA-256 over those canonical UTF-8 bytes.

The batch serializer/validator enforces:

- version and schema identity are exact;
- perspective identity is nonblank;
- emitted ordinals are exactly `0 until entries.size`;
- every payload has a matching `type` and event-family value;
- opaque trigger-order handles are rejected;
- nested runtime identity keys (`entityId`, `sourceId`, `targetId`, `cardId`, player/controller/
  owner IDs, raw/source ordinals, replay/action/decision coordinates, and equivalent ID/coordinate
  suffixes) are rejected;
- unknown future schema values fail closed through constructor validation;
- diagnostics are not serialized into model-facing batch bytes.

The digest must not depend on:

```text
raw event order before projection
hidden event count or position
global action/decision coordinate
raw EntityId/object stamp/allocation order
map iteration order
decision nonce/action handle/prompt
wall clock/PID/host/debug text
```

`PerspectiveEventBatchV1` has its own schema/digest identity. It does not reuse
`TrainingObservation.stateDigest`, `PlayerObservationV1.observationDigest`, or a Trajectory V1
semantic decision identity.

## 8. Test coverage

Focused tests live in
`gym/src/test/kotlin/com/wingedsheep/gym/CommittedPerspectiveEventSourceTest.kt`.

```text
HISTA-01 strict committed action → one repeatable immutable batch
HISTA-02 failed action → no new authoritative batch
HISTA-03 forked Gym transition → no committed capture
legacy GameEnvironment simulation → no committed capture
HISTA-04 hidden opponent hand/card mutation → identical P1 bytes and digest
HISTA-05 hidden opponent library movement → identical P1 bytes and intentional hiding
HISTA-06 hidden event insertion → no ordinal gap or digest change
HISTA-07 private P2 hand look → absent from P1, present only for P2
HISTA-08 public life event → emitted for both perspectives
HISTA-09 face-down identity → absent from serialization
HISTA-10 raw EntityId/source-coordinate fields → absent; DTO rejects identity keys
HISTA-11 uncharacterized event → unsupported diagnostic, not silent drop
HISTA-12 identical seed/config/decision → identical bytes and digest
HISTA-13 reset → source cleared
public zone movement → structural projection without object identity/name
owner-visible hand movement → Visibility-controlled owner projection, opponent hidden
```

The focused native Gradle run completed successfully after the RED compile characterization:

```text
.\gradlew.bat :gym:test \
  --tests com.wingedsheep.gym.CommittedPerspectiveEventSourceTest \
  --console=plain

16 tests completed, 0 failed
BUILD SUCCESSFUL
```

The repository `just test-class CommittedPerspectiveEventSourceTest` wrapper was attempted first but
is blocked on this Windows host before Gradle with `WinError 193`. The native Gradle run is reported
separately and is not presented as wrapper success.

## 9. Non-regression boundary

No Rules production source, card, deck, replay format, or learner source was changed. The only
existing observation source change is the visibility of `ObservationBuilder.cardRegistry` from
private to module-internal so the Gym-owned projector can reuse the same registry-backed
`Visibility` authority. It does not change `TrainingObservation` fields, wire bytes, canonical
observation semantics, or `SchemaHash`.

Unchanged contracts:

```text
PLAYER_OBSERVATION_V1_SEMANTICS_UNCHANGED=YES
TRAINING_OBSERVATION_WIRE_CONTRACT_UNCHANGED=YES
COMPLETE_LEGAL_DOMAIN_V1_UNCHANGED=YES
TRAJECTORY_V1_UNCHANGED=YES
COMMANDER_PUBLIC_STATE_V1_UNCHANGED=YES
COMPACT_REPLAY_SEMANTICS_UNCHANGED=YES
RULES_SEMANTICS_UNCHANGED=YES
LOCKED_DECKS_UNCHANGED=YES
```

No event history is added to `ObservationResult`, `TrainingObservation`, `PlayerObservationV1`, or
`TrajectoryV1`. The raw capture/projector remain internal; `GameGymEnv` exposes only the
non-serialized projection result (batch plus operational diagnostics) pending a later reviewed
history binding.

## 10. Limitations and next dependencies

`EVENT_HISTORY_COMPLETE=NO` is intentional. A does not claim:

- a complete public event vocabulary;
- historical known-card continuity;
- knowledge acquisition or invalidation epochs;
- safe cross-step physical-card aliases;
- full event-time replay projection;
- trajectory or Commander binding;
- model/learner readiness.

The next generic slices remain:

```text
C0-HISTORY-B = per-player known-information ledger and invalidation epochs
C0-HISTORY-C = perspective-scoped semantic object/incarnation references
C0-HISTORY-D = persistent PerspectiveHistoryV1 and derived sequence binding
```

Replay may validate or reproduce transitions, but it remains distinct from model-information
authority. A future replay-backed source must call this same projection contract and must not pass
raw reconstructed `GameState` or raw replay actions into a learner.

## 11. Completion decision table

```text
TASK=C0_HISTORY_A_COMMITTED_PERSPECTIVE_EVENT_SOURCE_01

BASE=a3c2fb3137e50ee56de7a03933fd10ce29ac59fe
HEAD=BOUND_BY_FINAL_IMPLEMENTATION_COMMIT
PARENT=a3c2fb3137e50ee56de7a03933fd10ce29ac59fe
REMOTE_HEAD=BOUND_AFTER_PUSH
UPSTREAM_SHA=5021faf88093a93091e4de7914fbe0f411499d58

FILES_CHANGED=8
PRODUCTION_FILES_CHANGED=6
TEST_FILES_CHANGED=1
DOC_FILES_CHANGED=1

PRODUCTION_CODE_CHANGED=YES
RULES_SEMANTICS_CHANGED=NO
TRAJECTORY_V1_CHANGED=NO
PLAYER_OBSERVATION_V1_CHANGED=NO
COMPLETE_LEGAL_DOMAIN_CHANGED=NO
COMMANDER_PUBLIC_STATE_CHANGED=NO

COMMITTED_ONLY_CAPTURE=PASS
SPECULATIVE_FORK_REJECTION=PASS
PERSPECTIVE_EVENT_PROJECTION=PASS
PERSPECTIVE_LOCAL_ORDINALS=PASS
HIDDEN_EVENT_NON_INTERFERENCE=PASS
RAW_ENTITY_ID_SERIALIZED=NO
RAW_SOURCE_COORDINATE_SERIALIZED=NO

CURRENT_EVENT_FAMILIES_TOTAL=104
EMITTED_SAFE_FAMILIES=36
INTENTIONALLY_HIDDEN_FAMILIES=4
DEFERRED_TO_HISTORY_B=1
DEFERRED_TO_HISTORY_C=46
DEFERRED_TO_BOTH=9
UNCHARACTERIZED=12
EVENT_HISTORY_COMPLETE=NO

KNOWN_INFORMATION_LEDGER_IMPLEMENTED=NO
STABLE_SEMANTIC_ALIAS_IMPLEMENTED=NO
PERSPECTIVE_HISTORY_V1_IMPLEMENTED=NO
TRAJECTORY_BINDING_IMPLEMENTED=NO

FOCUSED_TESTS=PASS__16_of_16
RULES_TESTS=NOT_RUN__no_Rules_production_code_changed
GYM_TESTS=PASS__native_gradle_full_module
GYM_TRAINER_TESTS=PASS__native_gradle_full_module
GAME_SERVER_TESTS=PASS__native_gradle_full_module
GIT_DIFF_CHECK=PASS

P1_FINDINGS=NONE
P2_FINDINGS=NONE

C0_HISTORY_A_IMPLEMENTATION_PASS=YES
C0_HISTORY_A_CODE_REVIEW_PASS=NO
C0_HISTORY_A_FINAL_ACCEPTANCE_PASS=NO

C0_HISTORY_B_AUTHORIZED=NO
C0_HISTORY_C_AUTHORIZED=NO
C0_HISTORY_D_AUTHORIZED=NO
C0_FINAL_ACCEPTANCE_PASS=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

`C0_HISTORY_A_IMPLEMENTATION_PASS=YES` means this authorized implementation slice and its focused
contract tests are present. It does not mean the event history is complete, the code review is
closed, or any later C0/C1 gate is authorized.

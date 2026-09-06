# C0 Perspective-Safe History / Known-Information Continuity

Task: `C0_HISTORY_PERSPECTIVE_SAFE_CONTINUITY_01`

Scope: source characterization and future contract design only. This document does not implement a
history pipeline, a knowledge ledger, a public alias primitive, a recurrent learner, or any
Trajectory V1 binding.

## 0. Result at a glance

There is no complete accepted model-history source at this head.

The current sources have different authority and safety properties:

| Source | Current result | Why it is not yet the model-history source |
| --- | --- | --- |
| `GameEnvironment.events` / `lastStepEvents` | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Raw `GameEvent` values contain internal IDs, card names, hidden-zone-sensitive fields, and events from every player. Fork and restore intentionally clear the event list. |
| `TrainingObservation` | `DERIVABLE_FROM_ACCEPTED_PUBLIC_SOURCE` | It is a perspective-safe current snapshot, not a history. It contains no event stream or knowledge continuity. |
| `PlayerObservationV1` | `DERIVABLE_FROM_ACCEPTED_PUBLIC_SOURCE` | It is a durable current observation projection only. It has no history field and cannot recover events or prior knowledge. |
| `TrajectoryV1` decision records | `AVAILABLE_AUTHORITATIVELY` | Records preserve actor-perspective observations, complete domains, and one chosen semantic input per replay action. They are authoritative for those records but do not preserve all public events or knowledge changes. |
| `GymReplayFrameSource` / verified replay frames | `AVAILABLE_AUTHORITATIVELY` | It rebuilds perspective-safe observations at replay action boundaries, but does not retain a perspective event stream or known-information ledger. |
| `CompactReplay` / `ReplayReconstructor` | `AVAILABLE_AUTHORITATIVELY` | Replay inputs and exact checkpoints can reproduce state, but reconstruction exposes raw state internally and is not an information-set contract. |
| `GameSession.gameLogs` / `ClientEvent` | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Per-player masking exists, but the log is presentation-oriented, lossy, not a complete semantic event stream, and contains runtime references. |
| `RevealedToComponent` | `DERIVABLE_ONLY_WITH_MISSING_METADATA` | It records current per-entity visibility, not when or why knowledge was acquired, its historical epochs, or a complete invalidation timeline. |
| Complete real-perspective history source | `NOT_AVAILABLE` | No accepted source currently combines committed event order, event-time audience projection, known-information continuity, and safe cross-step references. |

Therefore:

```text
CURRENT_HISTORY_SOURCE=AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE
FULL_REAL_PERSPECTIVE_HISTORY_AVAILABLE=NO
PRIOR_SAME_PERSPECTIVE_DECISIONS_AVAILABLE=AVAILABLE_AUTHORITATIVELY
PUBLIC_EVENT_HISTORY_AVAILABLE=AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE
OWN_KNOWN_INFORMATION_CONTINUITY_AVAILABLE=DERIVABLE_ONLY_WITH_MISSING_METADATA
REPLAY_IS_MODEL_INFORMATION_AUTHORITY=NO
KNOWN_INFORMATION_METADATA_DEPENDENCY=OPEN
STABLE_CROSS_STEP_ALIAS_STATUS=STABLE_ALIAS_METADATA_DEPENDENCY_OPEN
```

The safe future direction is an engine-owned, immutable perspective-history source produced at
committed transition boundaries. It must project raw events and knowledge changes before anything
crosses into a learner-facing adapter. The later learner sequence/window view must remain a derived
consumer of that source.

## 1. Audit identity and primary sources

The audit was performed after fetching both writable-fork and upstream references.

```text
REPOSITORY=chrismaghuhn/argentum-engine
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
BRANCH=chris/c0-perspective-history-characterization
BASE=417c6a19061d62ed8031cecadce2b5ee4afd8c2b
AUDIT_HEAD=417c6a19061d62ed8031cecadce2b5ee4afd8c2b
AUDIT_PARENT=97856d97e31c0bea9301388f931bfcfcd47df090
AUDIT_MERGE_SECOND_PARENT=5a584da7a054f4b9d2fa9dd3e457384546684a58
ORIGIN_MAIN=417c6a19061d62ed8031cecadce2b5ee4afd8c2b
UPSTREAM_MAIN=5021faf88093a93091e4de7914fbe0f411499d58
```

The authoritative rules reference used for the information-set audit is the current Wizards
[Rules page](https://magic.wizards.com/en/rules). At audit time that page links the current
[Comprehensive Rules TXT](https://media.wizards.com/2026/downloads/MagicCompRules%2020260819.txt),
which states that the rules are effective August 7, 2026. The relevant rules state, in substance:

- `400.2`: library and hand are hidden zones; graveyard, battlefield, stack, exile, and command are
  public zones, while a public zone can still contain specifically face-down cards.
- `401.2` and `401.5`: library order is hidden and only explicitly permitted top-card knowledge is
  available.
- `402.3`: a player may look at their own hand but not an opponent's hand.
- `400.7`: an object changing zones becomes a new object without memory or relation to its former
  existence, subject to the listed exceptions.
- `701.20a`, `701.20d`, and `701.20e`: revealing is an information event, reordering a revealed
  library card ends that reveal and creates a new object, and looking is limited to the specified
  player.
- `701.23a` and `701.23e`: searching a hidden zone gives the searching player access during the
  search, but a found card is not revealed unless instructed.
- `701.24a`: shuffling randomizes a library or face-down pile so no player knows its order.
- `903.3`: Commander designation belongs to the card and survives zone changes; that designation
  does not make a hidden-zone location public.

The repository sources audited directly include:

| Source | Verified responsibility |
| --- | --- |
| `gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt:61-230` | Current Gym observation, per-player summaries, masked zone cards, and stack view. |
| `gym/src/main/kotlin/com/wingedsheep/gym/contract/PlayerObservationV1.kt:13-94` | Durable transport-free current observation projection. |
| `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt:149-215,395-486,585-630` | Current observation construction, shared visibility use, and stack projection. |
| `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt:13-106,167-211` | Canonical current-observation semantics; no GameState or history access. |
| `rules-engine/src/main/kotlin/com/wingedsheep/engine/view/Visibility.kt:22-164` | Single source for zone and identity visibility decisions. |
| `gym/src/main/kotlin/com/wingedsheep/gym/GameEnvironment.kt:99-117,198-212,379-417,481-526` | Immutable state wrapper, raw event accumulation, action advancement, fork, and restore behavior. |
| `gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt:86-288` | Trusted observation/action seam and current-observation cache; no history state. |
| `gym/src/main/kotlin/com/wingedsheep/gym/StepResult.kt:11-52` | Legacy step result exposing raw `GameState` and raw events. |
| `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameEvent.kt:19-74,639-712,1396-1473,1554-1572,1697-1767` | Raw event vocabulary, including zone, cast, draw, shuffle, decision, reveal, and reorder events. |
| `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/PendingDecision.kt:19-66,800-1075` | Pending decision context and typed response hierarchy. |
| `rules-engine/src/main/kotlin/com/wingedsheep/engine/state/components/identity/ZonePermissionComponents.kt:7-24` | Current per-entity `RevealedToComponent` metadata. |
| `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/RevealedInHandTracker.kt:15-121` | Event-driven hand-reveal lifecycle and its explicit limitations. |
| `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/library/LibraryRevealUtils.kt:9-72` | Reveal marking and shuffle/per-card clearing. |
| `rules-engine/src/main/kotlin/com/wingedsheep/engine/state/GameState.kt:37-125,319-339,480-523,843-884` | Full state, internal continuation/identity state, zones, object stamps, and stack. |
| `mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/model/EntityId.kt:6-29` | Universal runtime identifier with no semantic incarnation contract. |
| `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt:383-565,837-1116` | Episode metadata, `DecisionRecordV1`, trajectory validation, and closure. |
| `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/SemanticReplayInput.kt:41-145` | Ordered semantic action/response prefix and its digest. |
| `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1StorageFrames.kt:14-129` | Physical start/decision/end storage frames; no event-history frame. |
| `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Writer.kt` and `TrajectoryV1Reader.kt` | Trusted storage admission/publication and validated streaming; both consume unchanged Trajectory V1. |
| `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt:40-193` | Replay setup, ordered actions, yields, pins, and checkpoints. |
| `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayReconstructor.kt:88-175,189-385,443-528` | Replay fold, public frame callbacks, raw state reconstruction, and transient event omission. |
| `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/GymReplayFrameSource.kt:52-350` | Verified public observation/domain boundaries and chosen-input binding. |
| `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprint.kt:11-81` | Full transition-semantic state fingerprints, not information-set history. |
| `game-server/src/main/kotlin/com/wingedsheep/gameserver/session/GameSession.kt:246-279,899-910,1354-1495,1640-1644` | Per-player UI logs and separate replay input recording. |
| `rules-engine/src/main/kotlin/com/wingedsheep/engine/view/ClientEvent.kt:785-844,862-982,1145-1182,1326-1360` | Client event transformation and masking/presentation behavior. |
| `ai/src/main/kotlin/com/wingedsheep/ai/training/DecisionRecordFactory.kt:18-61` and `DecisionTrainingRecord.kt` | Legacy optional collector that builds a different mask and simulates candidate worlds. |

## 2. Current authority and boundary model

### 2.1 Current Gym observation

`TrainingObservation` is a current information-set snapshot. It includes timing, players, zone
summaries, visible `EntityFeatures`, stack items, the current pending decision, and current legal
actions. It does not include an event array, prior observations, a knowledge ledger, a reveal epoch,
or a history cursor (`TrainingObservation.kt:61-109`).

`ZoneView.size` retains public count information while hidden members are omitted from
`ZoneView.cards`; visible cards carry `EntityFeatures.entityId`, `cardDefinitionId`, and current
zone (`TrainingObservation.kt:141-230`). `StackItemView` similarly carries a current stack object
view. These are current-view references, not stable cross-step aliases.

`ObservationBuilder` applies the shared `Visibility` service while constructing the current zones
and stack (`ObservationBuilder.kt:395-486,585-630`). `Visibility` distinguishes zone visibility,
identity visibility, per-card reveals, top-library permission, and face-down identity
(`Visibility.kt:22-164`). This is the correct authority for a future projection, but it is not a
historical source by itself.

`PlayerObservationV1.from` accepts only an already projected `TrainingObservation` and copies its
current fields (`PlayerObservationV1.kt:13-94`). `ObservationCanonicalizer` explicitly accepts no
`GameState`, makes no visibility decision, and canonicalizes the current DTO (`ObservationCanonicalizer.kt:13-20,43-106`).
Neither type can reconstruct a prior event or what was known before the current snapshot.

### 2.2 Live Gym state and events

`GameEnvironment` stores a mutable wrapper around immutable `GameState`. It exposes:

```text
state          = current full GameState
events         = cumulative raw GameEvent list since reset
lastStepEvents = raw events from the most recent step
```

This is explicit in `GameEnvironment.kt:99-117`. `reset` replaces the state and event list with
initializer output (`GameEnvironment.kt:198-212`). Both legacy simulation and strict processing
append raw engine events after a committed transition (`GameEnvironment.kt:379-417`).

This source is authoritative for engine occurrence order, but it is not perspective-safe:

- raw `ZoneChangeEvent` carries `entityId`, `entityName`, `fromZone`, and `toZone`;
- raw `CardsDrawnEvent` carries all drawn IDs and names;
- raw `CardsDiscardedEvent`, `SpellCastEvent`, `DamageDealtEvent`, and target/combat events carry
  internal references and names;
- raw reveal/look events carry card IDs and sometimes names;
- raw pending-decision events carry routing IDs and prompts;
- raw event lists do not carry a model-facing audience/knowledge classification.

The legacy `StepResult` also exposes the full `GameState` alongside raw events
(`StepResult.kt:17-52`). It is therefore not a permitted model-history seam.

The event list is not a stable history for forks or snapshots. `GameEnvironment.fork()` copies the
current state but sets `events` and `lastStepEvents` to empty; `restore()` does the same
(`GameEnvironment.kt:481-526`). There is no live-vs-hypothetical provenance marker on the resulting
environment. A future collector must not accept an arbitrary `GameEnvironment` or `GameGymEnv`
fork as an authoritative episode continuation.

`GameGymEnv` returns an `ObservationResult` from `observe`/`step` and caches only the latest current
observation and action registry (`GameGymEnv.kt:86-288`). The cache is not a history store. Its
action-ID remapping is specifically an environment-local handle operation; it does not create a
semantic object alias.

### 2.3 Raw events versus client events

`ClientEventTransformer.transform(events, viewingPlayerId)` is explicitly a client/UI adapter
(`ClientEvent.kt:785-844`). It masks some information, for example opponent draw names and the
fact that a private hand was looked at, but it is not a complete semantic history projection.

The source has three independent reasons not to use `ClientEvent` as model history:

1. The transform drops events that the UI does not render. Its ignored branch includes phase/step/
   priority, decision-request, look-at-card, library-reorder, scry, and other events
   (`ClientEvent.kt:1326-1360`).
2. It is presentation-shaped and has no immutable action/event coordinate or knowledge epoch.
3. The `ZoneChangeEvent` branch forwards the event's runtime card ID and name for hand and library
   destinations (`ClientEvent.kt:947-982`). That branch is not a general proof that a hidden-zone
   card's identity is visible to the viewer. It must be independently fixed or excluded before any
   model-history use.

`GameSession` stores one mutable `gameLogs` list per player, but appends transformed UI events and
filters noisy events before persistence (`GameSession.kt:246-279,899-910`). The logs are therefore
per-player but lossy, not a complete Rules event record. They are persisted as `ClientEvent` values
(`GameSession.kt:1640-1644`), not as an accepted learner contract.

**Decision:** `ClientEvent` and `gameLogs` are not the future history authority. A new
engine-owned perspective event projection is required.

### 2.4 Legacy AI/training encoder is out of scope and not an authority

`DecisionRecordFactory` is an optional offline collector, not the accepted C0 source. It calls
`TrainingRecordEncoding.observation(state, actingPlayer)` and simulates each legal candidate with
`GameSimulator` (`DecisionRecordFactory.kt:18-61`). The resulting candidate observations are
hypothetical/search states, not real episode history.

The legacy `TrainingRecordEncoding` also constructs visible cards directly from `CardComponent`
without the shared `ObservationBuilder`/`Visibility` path. Its public-zone card helper can emit
names for graveyard, battlefield, and stack objects, including face-down objects, while its
opponent-hand masking is a separate ad-hoc rule. This path is not safe for future model history and
must not be revived as a shortcut.

## 3. Current capability matrix

The statuses below describe source capability, not authorization to use the source as model input.
They use the requested classification vocabulary: `AVAILABLE_AUTHORITATIVELY` means that the
named source is authoritative for the facts it stores; it does not imply that the source is a
complete or perspective-safe history. `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` means that a source
exists but cannot cross the model boundary without a new projection. `DERIVABLE_FROM_ACCEPTED_PUBLIC_SOURCE`
means that the accepted public source can provide the fact at its current boundary. `DERIVABLE_ONLY_WITH_MISSING_METADATA`
means that a derivation needs an unimplemented authoritative capability. `NOT_AVAILABLE` means no
accepted source supplies the requested capability.

| History category | Current source | Status | Characterization |
| --- | --- | --- | --- |
| Current public board/timing | `TrainingObservation` | `DERIVABLE_FROM_ACCEPTED_PUBLIC_SOURCE` | Safe current snapshot only; no prior context. |
| Previous accepted choices by the same player | `TrajectoryV1.decisions`, `DecisionRecordV1.perspectivePlayerId`, semantic chosen input | `AVAILABLE_AUTHORITATIVELY` | A record can identify the acting perspective, but the contract does not provide a complete per-perspective history stream or live Gym history API. |
| Previous opponent public actions | raw `GameAction`/`GameEvent`, replay actions, client log | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Publicness is event-specific; raw actions and events have no audience contract. |
| Spell casts | `SpellCastEvent`, chosen semantic action, client event | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Raw event includes runtime spell ID/name and cast details; no event-time perspective projection. |
| Land plays | `LandPlayedEvent`, zone events, chosen action | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | The event is global/raw; the action may be actor-specific and the UI log is presentation-only. |
| Activated abilities | `AbilityActivatedEvent`, chosen action, stack view | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Source IDs and ability handles are runtime references; generated ability semantics need a separate public alias policy. |
| Combat declarations | `AttackersDeclaredEvent`, `BlockersDeclaredEvent`, combat actions/domains | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Event maps contain raw entity IDs and producer order; no history DTO binds them to a perspective. |
| Targets, modes, X, and payments | `ChosenSemanticActionV1`, `ChosenSemanticResponseV1`, event fields | `AVAILABLE_AUTHORITATIVELY` | Actor choice is durable when recorded; resolution side effects and opponent visibility are not captured as a safe event stream. |
| Structured decision responses | `DecisionResponse`, `ChosenSemanticResponseV1`, `DecisionRecordV1` | `AVAILABLE_AUTHORITATIVELY` | Each accepted response can be a decision input, but the same payload may contain actor-private card/source references and cannot be replayed as public history for another player. |
| Opponent-private decision responses | `SemanticReplayPrefixV1`, trajectory chosen response | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | The prefix has `kind` and semantic value but no audience/perspective field; a search/library selection can contain hidden IDs. |
| Zone movements | `ZoneChangeEvent`, current zones | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Raw from/to and entity name are global; current snapshots do not encode the transition. |
| Life-total changes | `LifeChangedEvent`, current `PlayerView.lifeTotal` | `AVAILABLE_AUTHORITATIVELY` | Raw changes exist; current observations only show the latest total and no event rationale/history. |
| Damage | `DamageDealtEvent`, commander ledger, current life/damage fields | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Raw event includes source/target IDs and LKI; a future projection must preserve only public semantic facts. |
| Commander public events/state | `CommanderPublicStateV1` current sidecar, raw commander events/state | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Current accepted Commander context is not bound into Trajectory V1 and has no historical event stream. |
| Public reveals | `CardsRevealedEvent`, `HandRevealedEvent`, `RevealedToComponent` in some paths | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Events identify reveals, but persistent and historical audience semantics are not one complete contract. |
| Own-card reveals / known cards | `RevealedToComponent`, current own observation, look/search decisions | `DERIVABLE_ONLY_WITH_MISSING_METADATA` | Current visibility may be known; acquisition time, source, expiry, and historical continuity are missing. |
| Cards seen then moved to a hidden zone | reveal/look events plus current `RevealedToComponent` | `DERIVABLE_ONLY_WITH_MISSING_METADATA` | No generic immutable knowledge timeline proves what remained known after each move. |
| Shuffle events | `LibraryShuffledEvent`, `LibraryRevealUtils.clearLibraryReveals` | `AVAILABLE_AUTHORITATIVELY` | The shuffle and current clearing behavior exist, but no historical knowledge epoch is persisted for a learner. |
| Search events | `SearchLibraryDecision`, `CardsRevealedEvent`, `LibrarySearchedEvent` | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Actor-facing domains expose hidden card metadata; raw event/action data cannot be reused for all perspectives. |
| Reorder-library events | `ReorderLibraryDecision`, `LibraryReorderedEvent`, current library order | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | The acting player may know the ordered cards; opponent history must not inherit that order. |
| Mulligan/setup events | `GameAction` mulligan actions, initialization events, `CompactReplay.setup` | `AVAILABLE_AUTHORITATIVELY` | Inputs and setup are recordable; a per-perspective knowledge timeline across opening draws and mulligan shuffles is not. |
| Turn/phase/step/priority progression | current observation fields and raw turn events | `DERIVABLE_FROM_ACCEPTED_PUBLIC_SOURCE` | Current boundary state is public and durable; every internal transition is not retained in learner-facing history. |
| Persistent yields | `GameState.yieldsByPlayer`, `ReplayYieldEntry` | `AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE` | Yields are player-specific state affecting auto-answer behavior and are outside the ordinary chosen-action stream. |
| Hypothetical/search branches | `GameEnvironment.fork`, legacy candidate simulation, MCTS callers | `NOT_AVAILABLE_AS_TRUSTED_HISTORY` | Forked state has no live-history provenance; candidate rollouts are explicitly hypothetical. |

## 4. Why Trajectory V1 decision rows are insufficient

### 4.1 What a decision row does preserve

`DecisionRecordV1` stores:

```text
decisionIndex
replayActionIndex / replayFrameIndex
perspectivePlayerId
decisionKind
semanticDecisionId
observationBefore: PlayerObservationV1
completeLegalDomain: CompleteLegalDomainV1
candidateDomainDigest
exactly one chosen semantic action or response
```

This is the correct durable contract for one actor-facing decision boundary. The validator checks
contiguous coordinates, perspective/observation agreement, current nonterminal status, schema,
observation digest, complete domain, chosen membership, and semantic decision identity
(`TrajectoryV1.kt:936-1001,1016-1082`). It does not store `GameEvent` values, a previous observation
sequence, known-card facts, or an audience for prior choices.

### 4.2 Why the semantic replay prefix is not perspective history

`SemanticReplayPrefixV1` is an ordered list of `SemanticReplayInputV1` action/response values
(`SemanticReplayInput.kt:41-145`). An input contains only a kind and semantic JSON. It does not
carry `perspectivePlayerId`, audience, visibility class, or knowledge provenance.

The prefix is global replay order, not one player's information history. It is used to bind semantic
decision identity, not to tell a player what they were allowed to know. In particular, an opponent's
structured library search response can contain a selected hidden card entity reference. A P1
history adapter cannot include the raw global prefix without either leaking that choice or inventing
a rule that it was public.

### 4.3 What replay-frame verification adds, and what it does not

`GymReplayFrameSource` replays the input stream and calls `ObservationBuilder` at each replay action
boundary. It resolves the acting perspective, builds a perspective-safe `TrainingObservation`, and
derives `PlayerObservationV1`, `CompleteLegalDomainV1`, and its domain digest
(`GymReplayFrameSource.kt:229-300`). It also validates recorded responses/actions against the public
domain, Rules decision validators, target domains, and payment domains (`GymReplayFrameSource.kt:303-350`).

This is a strong future raw material because the raw reconstructed `GameState` stays inside the
replay adapter until the `PlayerObservationV1` boundary. It is still not a history contract:

- `VerifiedReplayFrame` stores one current observation/domain boundary, not event entries or
  knowledge deltas (`VerifiedReplayFrame.kt:51-93`);
- the source selects the current acting perspective at each boundary rather than producing a
  complete parallel per-player history;
- event lists from replay execution are not retained by `ReplayEngine.applyAction`;
- CommanderPublicStateV1 is not part of the verified frame or Trajectory V1;
- a frame snapshot cannot, by itself, distinguish a fact currently visible from a fact known earlier
  and later invalidated.

**Decision:** Trajectory V1 may remain an actor-decision source. It is not sufficient as the sole
source for `REAL-PERSPECTIVE-HISTORY-DERIVED`.

## 5. Replay authority is not model-information authority

`CompactReplay` persists setup, ordered `GameAction`s, persistent yields, pinned cards, and
checkpoints (`CompactReplay.kt:40-102,145-193`). It does not persist a perspective-safe event
stream, per-player current observations, reveal audiences, or a knowledge ledger.

`ReplayReconstructor` can rebuild the initial state and fold the action stream. Its public replay
output is spectator snapshots/deltas, while `reconstructStateAt` intentionally returns a full
unmasked `GameState` for the share-frame path (`ReplayReconstructor.kt:52-60,365-385`). The private
replay fold's `StepResult` records the next state, failure/checkpoint status, and diagnostics, not
the transient event list (`ReplayReconstructor.kt:473-528`).

`ReplayFingerprint` and `TransitionSemanticGameStateCanonicalizer` deliberately fingerprint broad
transition state, including hidden/internal state needed to detect replay drift
(`ReplayFingerprint.kt:11-81`). Equality of such a fingerprint is replay evidence, not proof that
the corresponding state is part of a player's legal information set.

The exact replay path is therefore constrained as follows:

```text
REPLAY_AUTHORITY
    = authoritative input/state-reconstruction and integrity evidence

MODEL_INFORMATION_AUTHORITY
    = no, unless a separate adapter projects every historical item through
      the perspective's event-time visibility and knowledge contract
```

A future replay-backed history adapter would need all of the following before it can be trusted:

1. exact replay fidelity and closure evidence;
2. a fixed perspective for the entire derived history, not merely the actor at each frame;
3. event-time public/ private audience projection before model output;
4. a knowledge ledger that records acquisition and invalidation at each historical point;
5. semantic aliases that are valid for the perspective and object incarnation;
6. a fail-closed policy for any event or state that cannot be classified.

No such adapter is implemented here.

## 6. Known-information continuity

### 6.1 Current metadata

`RevealedToComponent` is a set of player IDs attached to an entity. `Visibility.isCardRevealedTo`
consults it (`ZonePermissionComponents.kt:7-24`, `Visibility.kt:56-60`). This is useful current
state, but it has no timestamp, source event, knowledge epoch, reason, or distinction between
"looked at", "revealed to all", "owner naturally knows", and "known because it was public".

`LibraryRevealUtils` marks cards and clears all library reveals on shuffle, with a per-card clearing
operation for other invalidation paths (`LibraryRevealUtils.kt:9-72`). `RevealedInHandTracker` runs
after action events and handles a narrow hand lifecycle: public-zone returns and explicit reveals
can mark cards, same-named hand plays and hand exits can clear knowledge, while ordinary library to
hand moves remain hidden (`RevealedInHandTracker.kt:15-121`).

These rules are valuable engine behavior, but they are not a historical ledger. At a later point a
consumer cannot ask the current state:

```text
which player learned this card at which event ordinal?
which effect granted the knowledge?
which knowledge epoch was invalidated by which shuffle/reorder?
what did the player know immediately before the next decision?
```

The source also exposes a concrete completeness gap. `RevealCollectionExecutor` emits a
`CardsRevealedEvent` but returns the original state without adding `RevealedToComponent`
(`RevealCollectionExecutor.kt:44-76`). Persistent continuity for such a reveal therefore depends
on a separate upstream path; the event itself is not a generic durable knowledge update.

Battlefield exits strip `RevealedToComponent` as part of new-object cleanup
(`ZoneMovementUtils.kt:380-440`), which is consistent with CR 400.7 for that object. It does not
provide a general history of what a player knew about the prior object or how a public reveal should
be represented after a hidden-zone transition.

### 6.2 Required future knowledge semantics

The later engine capability must be event-driven and immutable. At minimum it needs a per-player
ledger with:

```text
    perspectivePlayerId
    knowledgeEpoch
    known semantic card/object fact
    acquiredAt internal committed action/event coordinate
audience / reason class
invalidation coordinate and invalidation reason, when applicable
```

The ledger must be updated by the authoritative transition path, not reconstructed from final
`GameState` or card names. It must define:

| Situation | Required future behavior |
| --- | --- |
| Public reveal | Add a public fact at that internal event coordinate; after projection, emit only the contiguous perspective-local ordinal and never rewrite earlier entries. |
| Private look/search | Add only to the acting player's ledger; no opponent entry. |
| Known card enters a hidden zone | Preserve only the knowledge that the Rules/visibility policy actually preserves; do not infer location or position. |
| Shuffle/randomization | Advance/invalidate the affected library knowledge epoch according to the authoritative rule path. |
| Reorder of known cards | Preserve only explicitly permitted knowledge; invalidate positions/references that no longer survive. |
| Face-down transition | Remove identity from viewers not authorized by the current visibility contract. |
| Future reveal | Append a later fact; never backfill the earlier history. |
| Object zone change | Use CR 400.7 incarnation semantics; do not reuse a previous public object alias automatically. |

## 7. Cross-step identity and alias audit

The repository has four different identity concepts that must not be conflated:

| Identity | Current meaning | Future history consequence |
| --- | --- | --- |
| Card definition identity | Printed/registry definition, e.g. `cardDefinitionId` | Can identify a definition, not one physical card instance. |
| `EntityId` | Universal runtime ID for cards, players, tokens, abilities, and emblems | Not a semantic incarnation identity; the public contracts carry it in current views but do not version it. |
| `objectIdentityStamps` | Rules-side CR 400.7 incarnation stamp per current entity object | Authoritative internal identity evidence, but not present in `TrainingObservation`, `PlayerObservationV1`, or `ZoneChangeEvent`. |
| Public semantic alias | No general current primitive | Required later for model references; must be perspective-scoped and incarnation-aware. |

`GameState` increments a state-owned object stamp for every zone entry, including hidden zones, and
stores the current stamp by `EntityId` (`GameState.kt:319-328,480-488`). This proves the Rules layer
does not treat raw `EntityId` as sufficient for all object-incarnation checks. `EntitySnapshot`
checks stamps for selected Rules relationships, but that information is internal and not a public
cross-step alias.

`EntityId` itself is a universal value class and may be generated from UUIDs or deterministic `eN`
values (`EntityId.kt:6-29`). The type has no owner, zone, incarnation, visibility, or semantic alias
meaning.

The repository does have limited aliasing:

- pending decision IDs are explicitly routing nonces and are rebound during replay;
- generated `AbilityId` handles have replay canonicalization aliases;
- stable `abilityKey` values are used for some action semantics.

Those mechanisms do not solve physical card/object aliases. A future history consumer must classify
each reference as one of:

```text
definition identity
public current object reference
perspective-local known-card reference
incarnation-scoped public alias
not referenceable
```

For the requested movement cases:

| Movement | Safe current conclusion |
| --- | --- |
| battlefield → graveyard | Current public object is visible, but the new graveyard object is a CR 400.7 incarnation. No general learner alias binds the two. |
| graveyard → hand | The source may be public and `RevealedInHandTracker` may mark the returned card; the new hand object still needs a future knowledge/alias rule. |
| hand → battlefield | The owner knows the card; opponents may learn it from the public battlefield. The card's raw ID is not a stable model alias. |
| library → hand | The owner may know the card, but an opponent must not infer membership or identity unless the current visibility contract exposes it. |
| exile → battlefield | Face-up public identity can be projected; face-down or previously hidden identity requires the event-time visibility decision. |
| blink | The returning object is new under CR 400.7; a previous alias cannot be reused without a Rules-approved exception. |
| bounce/recast | The hand object and stack/battlefield object are distinct incarnations; history needs explicit relationship semantics, not raw ID equality. |
| Commander zone changes | `CommanderPublicStateV1` provides current public designation facts, but does not provide a physical-card alias or Trajectory binding. |
| token disappearance | A missing token is not a hidden card; its terminal alias must not be reused for a future object. |

**Status:**

```text
STABLE_ALIAS_PRIMITIVE_ALREADY_EXISTS=NO
STABLE_ALIAS_CAN_BE_DERIVED_SAFELY=NO
STABLE_ALIAS_METADATA_DEPENDENCY_OPEN=YES
```

## 8. Perspective isolation and hypothetical-state boundary

The future recurrent/history key must be:

```text
(semanticEpisodeId, perspectivePlayerId)
```

`DecisionRecordV1` binds each stored current observation to its `perspectivePlayerId`, and the
validator requires that it match the observation and the actor at that boundary
(`TrajectoryV1.kt:436-473,1016-1030`). This is sufficient to freeze the required isolation key as
a design invariant.

It is not sufficient to use one global history sequence:

- opponent actions may be public, but opponent private responses are not automatically public;
- a global semantic replay prefix has no audience field;
- `GameEnvironment.fork()` keeps state but clears event history and has no hypothetical marker;
- `DecisionRecordFactory` candidate simulations are explicitly non-real branches;
- `StepResult.state` is raw full state and has no model-facing privacy guarantee.

Required future invariants:

```text
NO_CROSS_EPISODE_STATE_CARRY=YES
NO_CROSS_PLAYER_STATE_CARRY=YES
NO_OPPONENT_HIDDEN_HISTORY=YES
NO_FUTURE_LEAKAGE=YES
NO_SEARCH_STATE_HISTORY=YES
NO_DEBUG_REVEAL_HISTORY=YES
NO_RAW_GAMESTATE_MODEL_INPUT=YES
```

Changing only hidden opponent hand/library facts while preserving the acting player's information
history must produce semantically equivalent model-history input. Conversely, a legitimate public
reveal or a perspective-private look must change only the histories whose audiences received that
knowledge.

## 9. Decision-boundary semantics

The accepted causal unit is:

```text
H_k       = perspective-safe observation at committed boundary k
D_k       = complete public legal domain at boundary k
I_k       = chosen semantic action or response accepted at boundary k
E_k       = ordered engine events caused by the committed transition
K_k       = knowledge/visibility delta caused by E_k and the Rules transition
H_(k+1)   = next perspective-safe observation
```

The future history source must retain `E_k`/`K_k` only after they have been projected for the
perspective. It must not treat `GameEvent` construction order alone as publicness.

### 9.1 Action boundaries

`GameEnvironment.step` may simulate a submitted action and auto-resolve trivial decisions before
returning (`GameEnvironment.kt:214-225,379-397`). The resulting `StepResult.events` is a batch for
that API step, not necessarily one user-visible Magic event or one learner decision.

`GameGymEnv` exposes the next current observation after the committed step. This provides a safe
boundary for `H_k` and `H_(k+1)`, but does not expose the internal ordered event batch as a typed
history contract.

### 9.2 Structured continuations

`PendingDecision` is typed and the game state remains frozen until a matching `DecisionResponse` is
submitted (`PendingDecision.kt:19-66`). The response hierarchy includes targets, card selections,
yes/no, modes, colors, numbers, distributions, orderings, searches, reorderings, payment sources,
combat resolution, and cancellation (`PendingDecision.kt:800-1075`).

Each externally submitted structured response is a real decision input and must retain its
authoritative order. It must not be collapsed into an informal "move" if Argentum exposes multiple
responses at separate boundaries.

At the same time, internal continuation frames, prompts, decision IDs, and subject/routing details
are not automatically model history. Runtime decision IDs are deliberately rebound during replay;
they are not semantic events. An adapter must emit the chosen semantic response only after domain
membership has been validated, and must project any resulting event/knowledge deltas separately.

### 9.3 Accepted trajectory alignment

`GymReplayFrameSource` verifies a replay action against the preceding public boundary and then emits
one `ReplayChosenInputV1` at the replay action coordinate (`GymReplayFrameSource.kt:303-350`).
`ReplayChosenInputBindingV1` and `TrajectoryV1` therefore provide a reliable action/response order
for accepted replay inputs. They do not provide all internal events between those inputs.

**Design status:**

```text
DECISION_BOUNDARY_HISTORY_CONTRACT=PASS
STRUCTURED_CONTINUATION_HISTORY_CONTRACT=PASS
```

These statuses freeze the required causal alignment only; implementation remains a later slice.

## 10. Future history contract recommendation

The smallest reusable model-facing contract should be a new, additive, versioned source rather than
a mutation of `TrainingObservation`, `PlayerObservationV1`, or `TrajectoryV1`.

### 10.1 Model-facing shape (design only)

```text
PerspectiveHistoryV1 {
    version
    schemaIdentity
    semanticEpisodeId
    perspectivePlayerId
    entries[]
}

PerspectiveHistoryEntryV1 {
    perspectiveHistoryOrdinal
    eventFamily
    visibilityClass
    semanticPayload
    knowledgeEpoch
    references[]
}
```

Recommended schema identity:

```text
argentum-gym-perspective-history@v1
```

This exact name is a recommendation, not an implementation authorization.

### 10.2 Entry rules

Coordinate boundary:

```text
AUTHORITATIVE_SOURCE_COORDINATE
    = internal provenance / replay binding only

PERSPECTIVE_HISTORY_ORDINAL
    = contiguous coordinate assigned after perspective projection
```

`PerspectiveHistoryEntryV1` must satisfy:

- `perspectiveHistoryOrdinal` is contiguous in the serialized stream and is assigned only after the
  event/knowledge projection for this perspective; omitted events cannot leave gaps;
- raw source action/decision coordinates and raw event ordinals are internal provenance/replay
  bindings only and are not model-facing fields;
- a global action/decision coordinate may be serialized only after a separate proof that its
  existence and value are invariant under hidden-only mutations for this perspective;
- entries retain the projected producer order, but are never sorted by runtime ID;
- `visibilityClass` is source-owned, for example `PUBLIC`, `PERSPECTIVE_PRIVATE`, or an omitted
  event that is not observable to this perspective;
- `semanticPayload` contains only public or perspective-private facts valid at that historical point;
- raw `GameState`, raw `GameEvent`, `GameAction`, `PendingDecision`, continuation, prompt, runtime
  decision ID, PID, host, timestamp, and framework artifacts are excluded;
- `knowledgeEpoch` changes only when an authoritative reveal/look/invalidation transition changes
  this perspective's knowledge; a hidden-only transition cannot advance the serialized epoch;
- a future reveal appends an entry and cannot mutate an earlier serialized entry;
- an entry referencing an object uses a separately validated semantic reference, never an unqualified
  raw `EntityId` as a stable alias;
- unknown event/visibility/reference versions fail closed.

### 10.3 Source-side engine capability

The model-facing DTO should be downstream of a source-side engine capability with three internal
responsibilities:

1. **Committed transition event capture**
   - receive the exact ordered event list produced by the committed Rules transition;
   - retain the source action/event coordinates only as internal provenance and replay bindings;
   - project visibility and knowledge first, then assign contiguous `perspectiveHistoryOrdinal`
     values to the emitted entries;
   - never let hidden-event count or position, or a private decision boundary, alter the
     perspective-facing coordinates or digest;
   - never capture candidate-simulation or fork-only transitions as live history.
2. **Perspective knowledge ledger**
   - maintain per-player known facts and invalidation epochs;
   - reuse the Rules-owned visibility/reveal decisions;
   - record explicit reveal/look/search/shuffle effects at event time;
   - fail closed when a producer does not supply enough audience or identity metadata.
3. **Perspective semantic references**
   - issue aliases only for objects/incarnations that are legally referenceable to the perspective;
   - preserve CR 400.7 new-object semantics;
   - avoid linking hidden physical positions or unobserved card identities.

This is the smallest generic follow-up set. It is intentionally not a `GameState` serializer and
not an extension of the current Commander contract.

### 10.4 Canonicalization and digest

`PerspectiveHistoryV1` should use the repository's strict canonical JSON convention and a dedicated
schema/digest identity. The digest must include:

```text
version/schema identity
semantic episode identity
perspective identity
contiguous perspective-local entry order
visibility class
knowledge epoch
semantic payload/reference values
```

It must not include:

```text
map iteration order
raw runtime allocation order
raw EntityId unless explicitly part of a validated public reference
raw source action/event coordinates
global action/decision coordinates without a hidden-state non-interference proof
decision nonce
action handle
prompt/presentation text
final winner/outcome on a pre-terminal prefix
```

This history digest is a future source/adapter identity. It must not be silently reused as the
existing `PlayerObservationV1.observationDigest` or `TrajectoryV1` semantic decision identity.

## 11. Source history versus derived learner sequence

The source history and the recurrent learner view must remain separate:

```text
authoritative PerspectiveHistoryV1
    → fixed perspective episode stream
    → recurrent SequenceViewV1
    → windows / burn-in / padding / masks
```

The derived sequence may:

- select a finite context window;
- add burn-in entries that are excluded from loss;
- pad only with explicit padding masks;
- truncate only under an explicit sequence policy;
- include current `PlayerObservationV1`, current complete domain, and current chosen input at the
  decision boundary.

It may not:

- mutate or rewrite source history;
- recover hidden information from replay state;
- fill missing events with inferred card-name or zone heuristics;
- carry state between `semanticEpisodeId` values;
- carry state between perspective IDs;
- treat an omitted hidden event as a negative fact;
- use final closure/outcome to populate earlier entries.

## 12. Required future RED characterization matrix

No new tests were added in this characterization-only slice. The following tests are the required
future RED tests at the first implementation seam. `BLOCKED_ON_GENERIC_METADATA` means the test must
remain blocked until the engine supplies the missing authoritative metadata; it does not authorize a
fixture-only inference.

| ID | Future test | Expected contract assertion | Current status |
| --- | --- | --- | --- |
| `HIST-01` | Same-perspective prior accepted choices | P1 history retains P1 semantic actions/responses in committed order, including structured responses. | `BLOCKED_ON_FUTURE_HISTORY_SEAM` |
| `HIST-02` | Opponent hidden-hand mutation | Changing only opponent hidden hand contents leaves P1 history bytes unchanged. | `BLOCKED_ON_FUTURE_HISTORY_SEAM` |
| `HIST-03` | Opponent library permutation | Hidden opponent library permutation leaves P1 history unchanged unless a public knowledge fact differs. | `BLOCKED_ON_FUTURE_HISTORY_SEAM` |
| `HIST-04` | Public reveal | A public reveal appends a public entry for every authorized perspective, with a perspective-local ordinal assigned after projection. | `BLOCKED_ON_GENERIC_METADATA` |
| `HIST-05` | Reveal followed by shuffle | Shuffle/reorder invalidates knowledge only through the authoritative epoch policy; no stale position remains. | `BLOCKED_ON_GENERIC_METADATA` |
| `HIST-06` | Face-down identity | Face-down identity is absent for unauthorized perspectives in every history entry and reference. | `BLOCKED_ON_FUTURE_HISTORY_SEAM` |
| `HIST-07` | Future reveal non-retroactivity | A later reveal changes later history only; serialized earlier history bytes remain unchanged. | `BLOCKED_ON_GENERIC_METADATA` |
| `HIST-08` | P1/P2 isolation | P1 and P2 maintain independent history streams; a P2-private fact never appears in P1 history. | `BLOCKED_ON_FUTURE_HISTORY_SEAM` |
| `HIST-09` | Episode reset | Reset starts a new semantic episode and clears prior recurrent/history state. | `BLOCKED_ON_FUTURE_HISTORY_SEAM` |
| `HIST-10` | Structured continuation order | Multiple typed responses remain separate and ordered by committed transition order, with any emitted perspective ordinals assigned after projection. | `BLOCKED_ON_FUTURE_HISTORY_SEAM` |
| `HIST-11` | Replay-only hidden facts | A replay adapter cannot emit hidden replay state or private opponent response facts into model history. | `BLOCKED_ON_REPLAY_PROJECTION_SEAM` |
| `HIST-12` | Raw EntityId alias rejection | A raw `EntityId` without an incarnation/public-reference witness is rejected as a stable history alias. | `BLOCKED_ON_FUTURE_ALIAS_SEAM` |
| `HIST-13` | Hidden event insertion/removal | Executions differing only by an event or decision invisible to P, with otherwise identical visible events, produce identical `PerspectiveHistoryV1(P)` semantic bytes; no hidden count, position, or source-coordinate difference is serialized. | `BLOCKED_ON_FUTURE_HISTORY_SEAM` |

Additional required canaries for the known source gaps:

- reveal-only collection emits a public reveal event but does not update current `RevealedTo`; the
  future ledger must not silently assume persistence;
- a `ZoneChangeEvent` to hand/library is not emitted as a public named-card history entry merely
  because the raw event contains `entityName`;
- inserting or removing a hidden-only event does not create a perspective-ordinal gap or digest
  difference;
- a `GameEnvironment.fork()` transition is rejected by the live-history source;
- a legacy `DecisionRecordFactory` candidate simulation is rejected as history;
- hidden opponent changes preserve the actor's current `PlayerObservationV1` and history bytes;
- a CommanderPublicStateV1 current fact can be composed later without mutating Trajectory V1.

## 13. Smallest reusable follow-up primitives

The next implementation work should be split into separately reviewed slices:

### C0-HISTORY-A — committed perspective event source

Create an engine-owned, immutable per-transition event sidecar. It should receive only committed
Rules output, retain source action/event coordinates only as internal provenance, project for one
perspective, then assign contiguous perspective-local ordinals. It must not change `GameEvent` wire
semantics or expose raw events/source coordinates to learners.

### C0-HISTORY-B — known-information ledger

Add the smallest generic event-driven knowledge state needed to record per-player reveal/look/search
facts and shuffle/reorder invalidation. First resolve the `RevealCollectionExecutor` persistence gap
and define audience/epoch semantics. Do not add card-specific or Commander-specific heuristics.

### C0-HISTORY-C — perspective semantic references

Define a public/known-card reference that binds object incarnation and perspective scope without
persisting raw runtime IDs as learned identities. Reuse existing Rules object-stamp authority
internally; do not expose the stamp itself as an unreviewed model feature.

### C0-HISTORY-D — derived sequence view

Only after A–C are accepted, define `PerspectiveHistoryV1` binding and the recurrent sequence/window
view. Keep this separate from Trajectory V1 binding and from any learner implementation.

The Commander-public context remains compositional at C0-HISTORY-D:

```text
PlayerObservationV1
+ CommanderPublicStateV1 (current additive source)
+ PerspectiveHistoryV1 (future history source)
+ CompleteLegalDomainV1
```

No change to `CommanderPublicStateV1` is required by this characterization, and no Commander data
is bound into Trajectory V1 here.

## 14. Explicit non-goals

This slice does not:

- modify Rules gameplay, cards, locked decks, Gym semantics, Visibility, replay semantics, or
  Trajectory V1;
- add a public event stream, knowledge ledger, or stable alias primitive;
- bind `CommanderPublicStateV1` into HTTP, `PlayerObservationV1`, or Trajectory V1;
- change `TrainingObservation` or its wire/canonical digest;
- implement recurrent state, sequence windows, burn-in, padding, or masking code;
- implement BC, value learning, RL, self-play, MCTS, search, or a world model;
- use raw `GameState` or CompactReplay reconstruction as model input;
- generate or modify a dataset;
- treat UI logs, legacy training records, candidate simulations, or framework artifacts as trusted
  model history;
- declare C0 final acceptance or authorize C1/training.

## 15. Gate decision

The semantic isolation requirements can be frozen now, but the complete real-perspective history
source cannot. The open dependencies are generic engine contracts, not permission to implement them
in this task.

```text
HIDDEN_STATE_NON_INTERFERENCE_DESIGN=PASS
PERSPECTIVE_ISOLATION_CONTRACT=PASS
DECISION_BOUNDARY_HISTORY_CONTRACT=PASS
STRUCTURED_CONTINUATION_HISTORY_CONTRACT=PASS
P1_SOURCE_COORDINATE_NON_INTERFERENCE=PASS
P2_CURRENT_CR_REFERENCE=PASS

KNOWN_INFORMATION_METADATA_DEPENDENCY=OPEN
PUBLIC_EVENT_PROJECTION_DEPENDENCY=OPEN
HYPOTHETICAL_STATE_PROVENANCE_DEPENDENCY=OPEN
STABLE_CROSS_STEP_ALIAS_STATUS=STABLE_ALIAS_METADATA_DEPENDENCY_OPEN

TRAJECTORY_BINDING_IMPLEMENTED=NO
COMMANDER_TRAJECTORY_BINDING_IMPLEMENTED=NO
```

The source integrity/trust status is unchanged:

```text
DATA_TRUSTED=YES
B2_SOURCE_INTEGRITY_TRUSTED=YES
C0_AUTHORIZED=YES
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

## 16. Completion decision table

```text
TASK=C0_HISTORY_PERSPECTIVE_SAFE_CONTINUITY_01

BASE=417c6a19061d62ed8031cecadce2b5ee4afd8c2b
HEAD=BOUND_BY_FINAL_DOCUMENTATION_COMMIT
PARENT=417c6a19061d62ed8031cecadce2b5ee4afd8c2b
REMOTE_HEAD=BOUND_AFTER_PUSH
UPSTREAM_SHA=5021faf88093a93091e4de7914fbe0f411499d58

FILES_CHANGED=1
PRODUCTION_CODE_CHANGED=NO
TEST_ONLY_CODE_CHANGED=NO
DOC_FILES_CHANGED=1

CURRENT_HISTORY_SOURCE=AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE
FULL_REAL_PERSPECTIVE_HISTORY_AVAILABLE=NO
PRIOR_SAME_PERSPECTIVE_DECISIONS_AVAILABLE=AVAILABLE_AUTHORITATIVELY
PUBLIC_EVENT_HISTORY_AVAILABLE=AVAILABLE_BUT_NOT_PERSPECTIVE_SAFE
OWN_KNOWN_INFORMATION_CONTINUITY_AVAILABLE=DERIVABLE_ONLY_WITH_MISSING_METADATA

REPLAY_IS_MODEL_INFORMATION_AUTHORITY=NO

KNOWN_INFORMATION_METADATA_DEPENDENCY=OPEN
STABLE_CROSS_STEP_ALIAS_STATUS=STABLE_ALIAS_METADATA_DEPENDENCY_OPEN

HIDDEN_STATE_NON_INTERFERENCE_DESIGN=PASS
PERSPECTIVE_ISOLATION_CONTRACT=PASS
DECISION_BOUNDARY_HISTORY_CONTRACT=PASS
STRUCTURED_CONTINUATION_HISTORY_CONTRACT=PASS

FUTURE_HISTORY_CONTRACT=PerspectiveHistoryV1_DESIGN_ONLY_NOT_IMPLEMENTED
TRAJECTORY_BINDING_IMPLEMENTED=NO
COMMANDER_TRAJECTORY_BINDING_IMPLEMENTED=NO

FOCUSED_TESTS=PASS_BASELINE_ONLY__CommanderPublicObservationTest_14_of_14__CommanderGymContractTest_5_of_5
GIT_DIFF_CHECK=BOUND_AFTER_DOCUMENTATION_EDIT

P1_FINDINGS=NONE_IN_THIS_REMEDIATION__future_event_projection_and_knowledge_metadata_remain_open_dependencies
P2_FINDINGS=NONE_IN_THIS_REMEDIATION__future_stable_alias_metadata_remains_an_open_dependency

C0_HISTORY_CHARACTERIZATION_IMPLEMENTATION_PASS=YES
C0_HISTORY_CODE_REVIEW_PASS=NO
C0_HISTORY_FINAL_ACCEPTANCE_PASS=NO

C0_FINAL_ACCEPTANCE_PASS=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

## 17. Verification scope

No production or test source was changed. The existing focused baseline was run from the new clean
worktree:

```text
gradlew.bat :gym:test \
  --tests com.wingedsheep.gym.contract.CommanderPublicObservationTest \
  --tests com.wingedsheep.gym.CommanderGymContractTest

BUILD SUCCESSFUL
CommanderGymContractTest: 5 passed
CommanderPublicObservationTest: 14 passed
0 failures, 0 errors
```

No B0/B2 soak, corpus generation, dataset generation, training, learner implementation, or history
implementation was run. The final `git diff --check` and exact commit/remote values are reported in
the completion response after this document is committed and pushed.

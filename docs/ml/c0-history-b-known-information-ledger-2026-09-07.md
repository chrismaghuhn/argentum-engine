# C0-HISTORY-B — Perspective-Safe Known-Information Ledger

Task: `C0_HISTORY_B_KNOWN_INFORMATION_LEDGER_01`

This is one bounded Rules-side implementation slice. It adds an immutable, perspective-scoped
known-information state to `GameState`. It does not implement `PerspectiveHistoryV1`, semantic
object aliases, Trajectory V1 binding, or a learner sequence.

## 1. Identity, source, and scope

```text
REPOSITORY=chrismaghuhn/argentum-engine
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
BRANCH=chris/c0-history-b-known-information-ledger
BASE=caf688015a35712c07d6fc489319f48c71285dc4
PARENT=COMPLETION_COMMIT_SHA_REPORTED_AFTER_PUSH
HEAD=COMPLETION_COMMIT_SHA_REPORTED_AFTER_PUSH
REMOTE_HEAD=COMPLETION_COMMIT_SHA_REPORTED_AFTER_PUSH
UPSTREAM_SHA=5021faf88093a93091e4de7914fbe0f411499d58
```

`BASE` is the fetched current `origin/main`. `UPSTREAM_SHA` is the fetched upstream reference and
is not an implementation base. The implementation is intentionally limited to the Rules-owned
information boundary described below.

```text
Rules-owned information authority
    → immutable per-player ledger in GameState
    → later History-C semantic references
    → later History-D perspective history
```

No data was generated, no trajectory was regenerated, and no training or learner code was added.

## 2. Current Comprehensive Rules authority

The current official source was checked before implementing the information transitions:

```text
CURRENT_CR_VERIFIED=YES
CR_SOURCE=https://magic.wizards.com/en/rules
CR_CURRENT_TXT=https://media.wizards.com/2026/downloads/MagicCompRules%2020260819.txt
CR_EFFECTIVE_DATE=August 7, 2026
```

The TXT linked by the official page states the effective date above. The audit used the current
rules for public/hidden zones and library/hand information (CR 400–402), new-object semantics
(CR 400.7), and reveal/look/search/shuffle/reorder procedures (CR 701.20, 701.22, 701.23,
701.24, 701.25). The implementation follows the existing Argentum visibility authority rather
than duplicating a second rules table.

## 3. Source audit and integration point

The relevant current sources are:

| Source | Role in this slice |
| --- | --- |
| `GameState.objectIdentityStamps` / `nextObjectIdentityStamp` | Rules-owned CR 400.7 incarnation witness. It is internal ledger key material, never a learner alias. |
| `Visibility` | Existing zone/identity visibility authority, including face-down and per-object reveal permissions; its identity predicate now also covers the separately stored public stack. |
| `RevealedToComponent` | Current visibility permission used by client/AI projections; it is not itself a historical ledger. |
| `LibraryRevealUtils` | Shared producer seam for public/private reveal metadata and ledger acquisition. |
| `RevealedInHandTracker` | Existing narrow public hand-reveal lifecycle; now records the same ledger facts through the shared utility. |
| `GatherCardsExecutor` | Records private library look/search knowledge supplied by the typed effect audience. Filtered searches record the whole searched library to the authorized viewer, not just the selected subset. |
| `SelectFromCollectionExecutor` | Records exactly the cards exposed in a private typed selection decision when no preceding look marker exists. |
| `LibraryAndZoneContinuationResumer` / `ReplacementContinuationResumer` | Record exact order only at producer-owned reorder completion, where the ordered card list is still available. |
| `ZoneTransitionService` | Clears `RevealedToComponent` at the CR 400.7 zone-change atom; public/private producers re-establish current visibility when authorized. |
| `ActionProcessor` | Final authoritative post-action seam. Failed actions return before ledger finalization; successful results are finalized once after existing hand tracking. |
| `GameState` serialization / Gym `SnapshotCodec` | Preserve the immutable component through state serialization and snapshot/restore. |

The central call is `ActionProcessor.process`: after Rules validation/execution and the existing
`RevealedInHandTracker`, `KnownInformationLedger.applyAfterAction` receives the accepted result.
It returns errors unchanged. Candidate simulation, legacy `GameEnvironment` stepping, forks used
as hypothetical branches, replay-only reconstruction, and debug state are not model-information
producers. A fork may carry an immutable ledger internally, but it cannot mutate its parent.

The `GameState` serializer receives an additive polymorphic component registration. No
`CompactReplay` field, version, action, trajectory, or public observation wire field was changed.
Replay/checkpoint state serialization therefore preserves the ledger, while the existing replay
tests remain the authority for replay behavior.

## 4. `KnownInformationLedgerComponentV1`

The new Rules-state contract is:

```text
KNOWN_INFORMATION_LEDGER_V1_VERSION=1
KNOWN_INFORMATION_LEDGER_V1_SCHEMA_IDENTITY=argentum-rules-known-information-ledger@v1
```

`KnownInformationLedgerComponentV1` is attached to the player entity whose perspective it
represents:

```text
KnownInformationLedgerComponentV1 {
    version
    schemaIdentity
    knowledgeEpoch
    activeFacts[]
}
```

The component contains active facts only. It is not an append-only historical event log and is not
a model-facing DTO. A future History-D source must compose committed event entries with this state
without exposing its internal witnesses.

Each `KnownInformationFactV1` carries:

| Field | Meaning | Boundary |
| --- | --- | --- |
| `subjectEntityId` | Current engine object witness | Rules-internal only; not a semantic alias. |
| `objectIdentityStamp` | Current CR 400.7 incarnation witness | Rules-internal only; not model-facing. |
| `factKind` | `IDENTITY`, `ZONE_MEMBERSHIP`, or `POSITION_OR_ORDER` | Explicitly separated dimensions. |
| `cardDefinitionId` | Identity known to this perspective | Stored only in the perspective-scoped Rules component. |
| `knownZone` | Zone known for this incarnation | Never inferred for an unauthorized hidden object. |
| `knownPosition` | Zero-based library position when explicitly supplied | Only emitted by order-aware/top-of-library producer paths; ordinary reveal does not imply position. |
| `audience` | `PUBLIC` or `PERSPECTIVE_PRIVATE` | Supplied by the authoritative producer. |
| `acquisitionReason` | Typed Rules reason, such as public reveal, private look, search, or visible transition | Provenance for the Rules ledger; not UI text. |
| `acquiredAtEpoch` | Epoch at which this active fact was acquired | Internal provenance; not a source/action coordinate. |

Fact lists are canonically ordered by semantic subject value, incarnation stamp, fact kind, zone,
position, definition, audience, reason, and acquisition epoch. No map iteration or allocation
order determines the serialized state order.

## 5. Fact acquisition and invalidation semantics

### 5.1 Public and private audience

`CardsRevealedEvent` and `HandRevealedEvent` add identity and current-zone facts for every roster
player. `CardsRevealedEvent.revealToSelf` remains a client overlay flag, not an information
audience flag. A reveal with `revealToSelf=false` still gives the revealing player the public fact.

`HandLookedAtEvent` adds facts only for `viewingPlayerId`. `LookedAtCardsEvent` adds facts only for
its `playerId`. No `source` string, card name, or UI behavior is used to infer audience.

`GatherCardsExecutor` records private library knowledge only when its typed `LookAudience` grants
it. A filtered `FromZone(LIBRARY, ...)` search records the complete searched library to the
authorized searcher because CR 701.23a requires looking through the hidden zone; it does not give
the opponent the result. A selected card becomes public only through a separate public reveal path.

`SelectFromCollectionExecutor` records exactly the card identities present in the typed decision
payload for the deciding player. It does not infer the rest of the source zone.

### 5.2 Identity, membership, and position are independent

Normal `recordCards` records identity and current zone membership. It does not record a library
index merely because a card was revealed. `recordLibraryOrder` is the explicit order-aware
producer operation: it invalidates previous positions and records the exact ordered card list for
the authorized perspective. Top-of-library look/reveal paths opt in to position recording because
their source semantics explicitly identify the top slice.

This avoids the invalid inference:

```text
card identity was revealed
    ⇒ exact hidden library position is known
```

### 5.3 Shuffle and reorder

`LibraryShuffledEvent` invalidates only `POSITION_OR_ORDER` facts for the affected current library.
It retains identity/membership facts, so a known card does not become unknown solely because its
library order was randomized. A shuffle with no tracked affected position does not bump the
perspective epoch.

`LibraryReorderedEvent` itself contains only count/source presentation data and is not treated as
an information authority. Exact ordered IDs are captured at the current continuation producer
before the event is returned. No post-state/card-count/source-name reconstruction is used.

### 5.4 Zone changes and face-down objects

Every zone entry already advances `GameState.objectIdentityStamps`. `ZoneTransitionService` also
clears the old `RevealedToComponent` at the transition atom. The ledger drops facts tied to the old
incarnation, then may record a new fact only when the existing `Visibility` authority proves that
the old pre-transition identity or the new post-transition identity was available to that
perspective. This permits safe cases such as:

```text
known/public object → new hidden-zone incarnation
known card drawn into its owner's hand
hidden card → public face-up battlefield
```

without turning `EntityId` into a cross-step alias or publishing a hidden index. The old and new
incarnation witnesses are never represented as a learner relationship.

For a `TurnedFaceDownEvent`, identity facts are removed from perspectives for which the current
`Visibility` authority no longer permits identity. Public zone membership is retained. A
perspective-specific reveal/look permission is honored only because the existing visibility service
explicitly authorizes it; the ledger does not invent a second face-down policy. `TurnFaceUpEvent`
records public identity only when current identity visibility is established.

### 5.5 Epoch rule

For one accepted transition, the ledger compares semantic active facts before and after the
transition. A perspective's `knowledgeEpoch` increments exactly once when its set of semantic facts
changes, regardless of the number of cards changed in that transition. Audience/reason-only changes
do not create a new semantic epoch. The epoch is monotonic within an episode.

Therefore:

```text
P2-private look       → P2 epoch changes; P1 epoch unchanged
hidden opponent edit  → P1 facts/epoch unchanged
public reveal         → each receiving perspective changes
unaffected shuffle    → no artificial epoch change
invalidation          → affected perspective changes once
```

## 6. Knowledge transition inventory

The following is the History-B semantic scope. “Supported” means the accepted producer path has a
Rules-owned acquisition/invalidation behavior. Standalone presentation events without card/order
metadata do not create invented facts; the actual producer path owns the fact.

| Transition | Producer/authority | Result |
| --- | --- | --- |
| Public card reveal | `CardsRevealedEvent` + shared reveal utility / final action pass | `SUPPORTED` |
| Hand reveal | `HandRevealedEvent` + `RevealHandEffectExecutor` | `SUPPORTED` |
| Private hand look | `HandLookedAtEvent` + `LookAtTargetHandExecutor` | `SUPPORTED` |
| Private face-down/card look | `LookedAtCardsEvent` + `LookAtFaceDownExecutor` | `SUPPORTED` |
| Private library look/search | typed `GatherCardsEffect.lookAudience` and selection boundary; `LibrarySearchedEvent` only upgrades the reason | `SUPPORTED` |
| Shuffle | `LibraryShuffledEvent` | `SUPPORTED` |
| Reorder/scry/surveil order | exact continuation producer + `recordLibraryOrder`; raw reorder event is not sufficient alone | `SUPPORTED` |
| Visible → hidden zone move | pre-transition `Visibility` plus new incarnation | `SUPPORTED` |
| Hidden → visible zone move | post-transition `Visibility` plus new incarnation | `SUPPORTED` |
| Face-down transition | `Visibility`-controlled identity invalidation | `SUPPORTED` |
| Zone-change new incarnation | `objectIdentityStamps` and stale-fact removal | `SUPPORTED` |
| Reset/new episode | new immutable `GameState` has no old component | `SUPPORTED` |
| Fork | immutable state copy; parent is not mutated | `SUPPORTED` |
| Snapshot/restore | additive `GameState` component serialization and Gym snapshot | `SUPPORTED` |

```text
KNOWLEDGE_TRANSITIONS_TOTAL=16
SUPPORTED=16
INTENTIONALLY_NO_PERSISTENT_KNOWLEDGE=0
DEFERRED_TO_HISTORY_C_REFERENCE=0
UNSUPPORTED_MISSING_METADATA=0
UNCHARACTERIZED=0
```

This count does not claim that the complete model-facing history exists. It is scoped to the
listed, currently reachable acquisition/invalidation producer paths. `PerspectiveHistoryV1`,
semantic references, and a historical acquisition/invalidation event log remain future work.

## 7. RevealCollection gap

The current source still had the characterized generic gap: `RevealCollectionExecutor` emitted a
public `CardsRevealedEvent` but returned state without durable reveal metadata. The minimal generic
repair routes the collection through `LibraryRevealUtils.markRevealed`, which updates both the
existing current visibility marker and the Rules ledger. It is not card-, commander-, or source-name
specific.

## 8. Lifecycle, persistence, and replay boundary

The ledger is immutable state attached to each perspective player. It participates in ordinary
state copies, so advancing a fork produces a new ledger without mutating the parent. Reset creates
a new state and therefore cannot carry facts or epochs from an earlier episode.

`GameState.serializer()` round-trips the component through the existing polymorphic component
registration. Gym `SnapshotCodec` snapshots the whole `GameEnvironment` state; restoring a snapshot
restores the ledger exactly. No separate mutable `GameGymEnv` cache exists.

`CompactReplay` and replay reconstruction remain validation/state-reconstruction authorities, not
model-information authorities. This slice does not add a replay history field and does not allow a
replay adapter to expose the full ledger or raw state as model input. A future replay-backed history
adapter must use the same per-perspective projection and fail closed when an event-time fact is not
available.

## 9. Deliberate non-goals and remaining boundaries

Not implemented here:

- `PerspectiveHistoryV1` or a durable append-only knowledge/event stream;
- `PerspectiveHistoryEntryV1`, `SequenceViewV1`, recurrent state, windows, burn-in, padding, or masks;
- stable public or learner-facing semantic aliases;
- Trajectory V1 or Commander trajectory binding;
- changes to `PlayerObservationV1`, `TrainingObservation`, `CompleteLegalDomain`, or candidate data;
- `CompactReplay` history serialization or replay protocol changes;
- public action/event history, known-information acquisition timestamps as model coordinates, or
  hidden-zone history reconstruction;
- card-name heuristics, Commander-specific branches, raw `GameState` model input, or raw runtime
  identity in a learned representation;
- BC, value learning, RL, self-play, search, MCTS, world models, or corpus generation.

The component's internal `EntityId`, `objectIdentityStamp`, library index, and acquisition epoch are
not model-facing. History-C remains responsible for a perspective-safe semantic reference. History-D
remains responsible for binding current observations, committed events, and knowledge deltas to a
future perspective history/sequence view.

## 10. Focused tests

New focused coverage:

```text
rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/KnownInformationLedgerTest.kt
    22 tests: HISTB-01 through HISTB-17 plus explicit public-stack/destination, face-down, and
    library-position privacy cases

rules-engine/src/test/kotlin/com/wingedsheep/engine/handlers/effects/library/RevealCollectionExecutorTest.kt
    1 test: reveal-only persistence RED → generic fix

gym/src/test/kotlin/com/wingedsheep/gym/KnownInformationLedgerSnapshotTest.kt
    1 test: Gym snapshot/restore preserves the ledger
```

The focused tests cover public/private audience isolation, `revealToSelf=false`, private search,
shuffle position invalidation, exact producer-owned reorder, hidden-state non-interference, future
reveal non-retroactivity, CR 400.7 incarnation separation, face-down identity invalidation,
fork/reset isolation, serialization, and deterministic evolution.

## 11. Verification record

The repository's `just` Rules wrapper was attempted but is unavailable on this Windows host because
WSL cannot start `/bin/bash`; this is reported as `BLOCKED`, not as a passing test.

Native Gradle was used as an explicitly labeled fallback:

```text
FOCUSED_RULES_TESTS=PASS
RULES_ENGINE_FULL_TEST=PASS
GYM_FULL_TEST=PASS
GYM_TRAINER_FULL_TEST=PASS
GAME_SERVER_FULL_TEST=PASS
REPLAY_SNAPSHOT_TESTS=PASS
GIT_DIFF_CHECK=PASS
```

No B0/B2 soak, corpus generation, training, or learner implementation was run.

## 12. Completion status

```text
TASK=C0_HISTORY_B_KNOWN_INFORMATION_LEDGER_01

BASE=caf688015a35712c07d6fc489319f48c71285dc4
HEAD=COMPLETION_COMMIT_SHA_REPORTED_AFTER_PUSH
PARENT=caf688015a35712c07d6fc489319f48c71285dc4
REMOTE_HEAD=COMPLETION_COMMIT_SHA_REPORTED_AFTER_PUSH
UPSTREAM_SHA=5021faf88093a93091e4de7914fbe0f411499d58

FILES_CHANGED=COMPLETION_COUNT_REPORTED_AFTER_COMMIT
RULES_PRODUCTION_FILES_CHANGED=COMPLETION_COUNT_REPORTED_AFTER_COMMIT
GYM_PRODUCTION_FILES_CHANGED=0
TEST_FILES_CHANGED=3
DOC_FILES_CHANGED=1

CURRENT_CR_VERIFIED=YES
CR_EFFECTIVE_DATE=August 7, 2026

RULES_INFORMATION_SEMANTICS_CHANGED=YES__ADDITIVE_LEDGER_AND_GENERIC_REVEAL_VISIBILITY_METADATA
CARD_DEFINITION_CHANGED=NO
LOCKED_DECKS_CHANGED=NO

LEDGER_AUTHORITY=RULES-owned immutable KnownInformationLedgerComponentV1 finalized at ActionProcessor
LEDGER_PERSPECTIVE_SCOPED=YES
LEDGER_IMMUTABLE_STATE=YES
SNAPSHOT_RESTORE_PRESERVED=YES
FORK_ISOLATION=YES

PUBLIC_REVEAL_KNOWLEDGE=PASS
REVEAL_TO_SELF_UI_ONLY=PASS
PRIVATE_HAND_LOOK_ISOLATION=PASS
PRIVATE_CARD_LOOK_ISOLATION=PASS
PRIVATE_SEARCH_ISOLATION=PASS
REVEAL_COLLECTION_PERSISTENCE=PASS

IDENTITY_KNOWLEDGE_DISTINGUISHED=YES
ZONE_MEMBERSHIP_KNOWLEDGE_DISTINGUISHED=YES
POSITION_ORDER_KNOWLEDGE_DISTINGUISHED=YES

SHUFFLE_INVALIDATION=PASS
SHUFFLE_NO_FALSE_EPOCH_BUMP=PASS
REORDER_KNOWLEDGE_SEMANTICS=PASS
ZONE_CHANGE_INCARCINATION_BOUNDARY=PASS
FACE_DOWN_INVALIDATION=PASS

KNOWLEDGE_EPOCH_MONOTONIC=PASS
HIDDEN_STATE_NON_INTERFERENCE=PASS
FUTURE_REVEAL_NON_RETROACTIVE=PASS
DETERMINISTIC_LEDGER_EVOLUTION=PASS

RAW_ENTITY_ID_MODEL_FACING=NO
OBJECT_IDENTITY_STAMP_MODEL_FACING=NO
SEMANTIC_ALIAS_IMPLEMENTED=NO
PERSPECTIVE_HISTORY_V1_IMPLEMENTED=NO
TRAJECTORY_BINDING_IMPLEMENTED=NO
COMMANDER_HISTORY_BINDING_IMPLEMENTED=NO

KNOWLEDGE_TRANSITIONS_TOTAL=16
SUPPORTED=16
INTENTIONALLY_NO_PERSISTENT_KNOWLEDGE=0
DEFERRED_TO_HISTORY_C_REFERENCE=0
UNSUPPORTED_MISSING_METADATA=0
UNCHARACTERIZED=0

FOCUSED_TESTS=PASS
RULES_TESTS=PASS
GYM_TESTS=PASS
GYM_TRAINER_TESTS=PASS
GAME_SERVER_TESTS=PASS
REPLAY_SNAPSHOT_TESTS=PASS
GIT_DIFF_CHECK=PASS

P1_FINDINGS=0
P2_FINDINGS=0

C0_HISTORY_A_FINAL_ACCEPTANCE_PASS=YES
C0_HISTORY_B_AUTHORIZED=YES
C0_HISTORY_B_IMPLEMENTATION_PASS=YES
C0_HISTORY_B_CODE_REVIEW_PASS=NO
C0_HISTORY_B_FINAL_ACCEPTANCE_PASS=NO

C0_HISTORY_C_AUTHORIZED=NO
C0_HISTORY_D_AUTHORIZED=NO

C0_FINAL_ACCEPTANCE_PASS=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

The agent reports implementation evidence only. Code review, final acceptance, History-C/D
authorization, C1, and training remain separate gates.

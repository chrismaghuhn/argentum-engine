# C0 History-C Perspective Semantic References Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. This document is a future implementation plan; it is not authorization to implement History-C.

**Goal:** Define the smallest reusable, perspective-safe capability that converts committed Rules object witnesses into deterministic semantic references without exposing runtime identity, and stage its future implementation behind the final History-B gate.

**Architecture:** Use a hybrid design: an immutable, internal per-episode/per-perspective witness registry owned by the committed-history adapter, plus a pure perspective projection that validates every candidate through History-A, `Visibility`, and the merged History-B ledger. Allocate compact aliases only for committed, legally referenceable candidates; never reuse an alias across a CR 400.7 incarnation. Keep optional cross-incarnation relationships separate from aliases and emit them only when an authoritative Rules transition witness proves that the perspective can know the relation.

**Tech Stack:** Kotlin/JDK 21, immutable `GameState`, `CommittedPerspectiveEventSource`, `PerspectiveEventBatchV1`, `Visibility`, `KnownInformationLedgerComponentV1`, kotlinx.serialization, strict canonical JSON, Kotest, Gradle/`just` verification.

---

This is a plan-only artifact for `C0_HISTORY_C_PERSPECTIVE_SEMANTIC_REFERENCES_PLAN_01`. It intentionally creates no History-C implementation, no tests, no observation changes, no Trajectory V1 changes, no Rules-semantics changes, and no History-D DTO.

## 1. Executive decision

### Decision

Choose architecture **D: hybrid internal witness registry plus perspective projection**.

The future History-C module will have one deep external interface: accept one successful committed transition, its event-time before/after witnesses, the complete History-A perspective classification, and History-B evidence for one perspective; return either a fully classified semantic-reference result plus the next immutable registry state, or a typed fail-closed diagnostic. The module hides the mapping from the internal witness `(EntityId, objectIdentityStamp)` to the learner-safe alias.

One semantic object alias identifies exactly:

```text
(semanticEpisodeId, perspectivePlayerId, one Rules object incarnation)
```

The object incarnation is represented internally by the exact pair `(EntityId, objectIdentityStamp)`. The pair never crosses the semantic seam. The compact model-facing value is a perspective-local ordinal such as `o0`, `o1`, `o2`; it has no meaning outside its episode, perspective, and schema identity.

The non-negotiable decisions are:

- allocate only after the committed event/reference candidate is proven referenceable for the perspective;
- preserve one alias for one unchanged incarnation while the legal continuity evidence remains valid;
- retire an alias when the incarnation changes, individual continuity is lost, or a token reaches its terminal existence;
- never reuse a retired alias within an episode;
- allow a face-down public object to have an opaque alias without a printed identity;
- add printed identity to a later occurrence of the same alias only when the same incarnation is still authoritative; do not rewrite earlier bytes;
- represent a known relationship between old and new incarnations with two aliases plus optional relation evidence, never with alias reuse;
- treat missing event-time witness, missing History-B evidence, unordered symmetry, missing episode identity, and unknown schema versions as typed failures;
- emit no aliases for ordinary opponent hidden-hand membership or any hidden-only mutation that is not referenceable to that perspective;
- keep History-C out of `PerspectiveHistoryV1`, history windowing, recurrent state, Trajectory V1, teacher data, and training.

### Implementation start gate

No future implementation slice may start until both conditions are true on the exact implementation base:

```text
C0_HISTORY_B_FINAL_ACCEPTANCE_PASS=YES
HISTORY_B_MERGED_IN_CURRENT_MAIN=YES
```

At this revalidation the gate is open on the exact merged current-main authority below. This commit remains docs-only; HISTC-A is eligible for separate production authorization after plan review, while no History-C implementation is performed here.

## 2. Source authority / exact SHAs

### Git identity at authorization/audit

| Authority | Exact value | Use |
| --- | --- | --- |
| Repository | `chrismaghuhn/argentum-engine` | Writable fork only |
| `origin` | `https://github.com/chrismaghuhn/argentum-engine.git` | Branch base and push target |
| `upstream` | `https://github.com/wingedsheep/argentum-engine.git` | Read-only reference |
| `PLAN_BASE_ORIGIN_MAIN` | `b270855aabf5afc7639010e921855918cf160b8b` | Original dedicated plan branch base |
| `CURRENT_ORIGIN_MAIN` | `a7d73f7bf0e1061a696516b86246bcffb08eae8b` | Current merged-main authority after History-B PR #151 |
| `UPSTREAM_MAIN` | `5021faf88093a93091e4de7914fbe0f411499d58` | Rules/source reference only |
| `HISTORY_B_IMPLEMENTATION_HEAD` | `86d3c065a6dacd9c12e5959a8cf055f84d522062` | Final History-B implementation head contained in current `origin/main` |
| `HISTORY_B_MERGE_SHA` | `a7d73f7bf0e1061a696516b86246bcffb08eae8b` | Merge of History-B into current `origin/main` |
| historical accepted History-B review head | `995b683d0887f20f1ffebb33199c0c1c4de7cdb8` | Pre-merge audit reference only |
| historical live History-B branch head | `8560a88007486568fcd758e3e4fd2d2f1def0ce0` | Pre-merge lineage reference only |
| plan branch | `chris/c0-history-c-semantic-reference-plan` | Docs-only revalidation branch containing current-main merge |

History-B is now final and merged: `HISTORY_B_IMPLEMENTATION_HEAD=86d3c065...` and `HISTORY_B_MERGE_SHA=a7d73f7...` are both ancestors of current `origin/main`. The earlier `995b683d...` and `8560a880...` values remain historical audit refs only. The formerly WIP reveal-preservation change is now accepted current-main authority:

- `ZoneTransitionService.ZoneEntryOptions.reestablishRevealToPlayerIds` re-establishes an already authorized reveal audience on a new object;
- `MoveCollectionExecutor` supplies that existing audience across collection moves;
- the History-B document records the accepted continuity behavior and the `Expensive Taste` regression;
- generic hidden-zone continuity remains restricted to producer-authorized evidence and is not inferred from ownership, names, or raw IDs.

This behavior is classified as `ACCEPTED_HISTORY_B_AUTHORITY`; no plan rule treats it as a generic cross-zone identity relation.

### Repository authorities audited

The current `origin/main` History-A and observation sources are:

| Source | Authority established by audit |
| --- | --- |
| `docs/ml/c0-perspective-history-known-information-characterization-2026-09-07.md` | Accepted design input; History-D shape remains design-only; current history source is not complete and not perspective-safe. |
| `docs/ml/c0-history-a-committed-perspective-event-source-2026-09-07.md` | History-A boundary, event-family inventory, fail-closed classification, and committed/fork/reset/restore exclusions. |
| `gym/src/main/kotlin/com/wingedsheep/gym/CommittedPerspectiveEventSource.kt` | Internal committed transition token; one-transition source; no accumulation; projection requires before/after state. |
| `gym/src/main/kotlin/com/wingedsheep/gym/contract/PerspectiveEventBatchV1.kt` | Versioned ordered perspective batch; runtime identity keys and source coordinates are rejected from semantic payloads. |
| `gym/src/main/kotlin/com/wingedsheep/gym/contract/PerspectiveEventProjector.kt` | Event-time `Visibility` projection and explicit emitted/hidden/unsupported classification. |
| `gym/src/main/kotlin/com/wingedsheep/gym/GameEnvironment.kt` | Strict commit token production, raw event accumulation, fork/restore clearing, and no token for legacy simulation. |
| `gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt` | Strict Gym seam, fork capture disablement, reset/restore source clear, and current observation cache. |
| `rules-engine/src/main/kotlin/com/wingedsheep/engine/view/Visibility.kt` | Current zone visibility, object addressability, and identity visibility authority. |
| History-B at `86d3c065...`, merged by `a7d73f7...` | Current-main Rules-owned immutable known-information facts, epochs, object-incarnation invalidation, search/reorder/shuffle semantics, producer-authorized reveal preservation, and snapshot participation. |

### Official Rules authority

The official page was fetched on 2026-09-07:

- Rules page: <https://magic.wizards.com/en/rules>
- Exact TXT linked by that page: <https://media.wizards.com/2026/downloads/MagicCompRules%2020260819.txt>
- TXT effective date: **August 7, 2026**

The local audit copy was fetched from the exact TXT URL and checked with `rg`. The design relies on these verified rules:

| Rules reference | Verified semantic used by this plan |
| --- | --- |
| CR 111.7 and 704.5d | A token outside the battlefield ceases to exist; a terminal token alias must not become a reusable object alias. |
| CR 400.2 | Battlefield, graveyard, stack, exile, ante, and command are public zones except for specifically face-down objects; hand and library remain hidden zones even when cards are revealed. |
| CR 400.7 and 403.4 | A zone change creates a new object with no memory/relation except listed exceptions; every battlefield entry is a new object. |
| CR 400.7e, 400.7f, 400.7g–k, 400.7m | Certain Rules/effect relationships may find a new object, but those exceptions are relation evidence and never justify alias reuse. |
| CR 401.2 and 401.5–401.6 | Library order is hidden except for explicit top-card permissions; a revealed top card that stops being revealed becomes a new object. |
| CR 402.3 | A player may see their own hand but not an opponent's hand, except where an effect supplies permission. |
| CR 701.20a, 701.20d, 701.20e | Reveal is public, reordering revealed library cards ends the reveal and creates new objects, and look is limited to the specified player. |
| CR 701.23a and 701.23e | Search grants the searching player access to the hidden zone; found cards are not public unless the effect also instructs a reveal. |
| CR 701.24a | Shuffle destroys knowledge of library order. |
| CR 708.2, 708.5, 708.6, 708.9 | Face-down objects can be addressable without printed identity, authorized players may look at their own face-down objects, distinct face-down objects must be physically distinguishable, and a face-down object leaving its zone is revealed as required. |
| CR 903.3 and 903.3d–e | Commander designation belongs to the card and persists across zones; it is definition/designation continuity, not a physical-object alias. |

## 3. Current History-A and History-B capabilities

### History-A

History-A is a committed source, not a history accumulator:

```text
successful strict Gym transition
    -> CommittedRulesTransition(beforeState, afterState, ordered GameEvent list, sourceStepCount)
    -> PerspectiveEventProjector with event-time before/after state
    -> PerspectiveEventBatchV1 plus complete classification diagnostics
```

The source captures only after `ActionProcessor` has accepted the strict transition. Failed validation, unsupported execution, failed actions, legacy simulation, candidate simulation, fork transitions, reset, restore, and replay-only reconstruction do not produce authoritative History-A capture. `GameGymEnv.fork()` disables capture; reset and restore clear the one-transition source.

The current batch already provides:

- version and schema identity;
- a contiguous producer-ordered `perspectiveEventOrdinal` after projection;
- event family and identity-free semantic payload;
- reuse of `Visibility` for zone endpoint classification;
- explicit intentional hiding versus unsupported metadata;
- canonical JSON and digest that exclude raw object IDs, object stamps, action/decision coordinates, map iteration order, and hidden event count.

It does not provide:

- a stable reference to a card/object across events;
- a before/after semantic object witness in model-facing bytes;
- a knowledge continuity decision;
- a semantic episode identifier;
- a persistent alias registry;
- an authoritative relation classification for every CR 400.7 transition.

The current event-family inventory must remain the source of scope. Families presently marked `REQUIRES_SEMANTIC_REFERENCE_C` or `REQUIRES_BOTH_B_AND_C` may become History-C candidates only when their reference slots have an authoritative producer order and event-time witnesses. `UNCHARACTERIZED` events remain unsupported; History-C must not silently upgrade them.

### Current merged History-B

At implementation head `86d3c065a6dacd9c12e5959a8cf055f84d522062`, merged into current `origin/main` by `a7d73f7bf0e1061a696516b86246bcffb08eae8b`, History-B provides a Rules-owned immutable `KnownInformationLedgerComponentV1` attached to each player. It contains active facts, not an append-only history log:

```text
KnownInformationFactV1(
    subjectEntityId, objectIdentityStamp,
    factKind = IDENTITY | ZONE_MEMBERSHIP | POSITION_OR_ORDER,
    cardDefinitionId?, knownZone?, knownPosition?,
    audience, acquisitionReason, acquiredAtEpoch
)
```

The accepted ledger supplies:

- per-perspective `knowledgeEpoch` with one semantic increment per accepted transition when facts change;
- identity, current-zone membership, and explicit library position/order as independent fact dimensions;
- public reveal, hand reveal, private hand/card/library look, private search, visible zone transition, and continuous top-library acquisition;
- shuffle/order invalidation and CR 701.20d object reincarnation for affected known library objects;
- same-name hand ambiguity invalidation through the existing `RevealedInHandTracker` authority;
- face-down identity invalidation while retaining public zone membership where authorized;
- immutable `GameState` copy semantics, fork isolation, state serialization, and Gym snapshot/restore for the ledger component;
- no learner-facing `EntityId`, `objectIdentityStamp`, library index, or semantic alias.

History-C may query the merged current-main ledger for evidence. It must not duplicate reveal, look, search, shuffle, reorder, same-name, face-down, or continuous-visibility rules. The accepted producer-authorized reveal-preservation path may carry an existing `RevealedToComponent.playerIds` audience onto the new incarnation; it does not infer a new viewer. If a required evidence question cannot be answered by current History-B plus `Visibility`, the result is `BLOCKED_ON_AUTHORITATIVE_METADATA` and the reference is not emitted.

## 4. Identity taxonomy

The following meanings are frozen and must appear as separate concepts in future implementation names, types, tests, and diagnostics.

| Concept | Exact meaning | May cross the History-C semantic seam? |
| --- | --- | --- |
| `DEFINITION_IDENTITY` | Printed/registry definition such as `cardDefinitionId`; many object incarnations can share it. | Only as an independently authorized identity disclosure; never as the object alias. |
| `CURRENT_RULES_OBJECT_IDENTITY` | One current Rules object incarnation, internally keyed by `(EntityId, objectIdentityStamp)`. | No. Internal authority only. |
| `PERSPECTIVE_KNOWN_OBJECT_IDENTITY` | A perspective's active History-B fact that it knows an identity, zone, or explicit order for a current incarnation. | No. It authorizes a projection decision; it is not learner bytes. |
| `MODEL_SEMANTIC_REFERENCE` | The perspective-local, episode-local alias that lets later semantic payloads refer to one authorized Rules incarnation. | Yes, as a versioned typed reference such as `o7`. |
| `CROSS_INCARNATION_RELATIONSHIP` | A separate, perspective-safe statement that an old alias and a new alias are related by one Rules/effect transition. | Yes only when independently authorized; never represented by reusing one alias. |
| `NOT_REFERENCEABLE` | The perspective cannot legally address or distinguish the object at the relevant committed point. | No alias, no inferred identity, no placeholder alias. |

The following distinctions are explicitly invalid:

```text
cardDefinitionId != EntityId
EntityId != (EntityId, objectIdentityStamp)
(EntityId, objectIdentityStamp) != perspective semantic alias
same physical card != same Rules object incarnation
same-name card != identifiable hidden object
public zone != printed identity visibility for a face-down object
```

## 5. Referenceability policy

Referenceability is evaluated in this order:

1. **Committed-source eligibility:** the candidate originates from one successful strict committed transition and has an event-time before/after witness. A current snapshot scan, fork, search state, debug reveal, legacy AI rollout, or arbitrary `GameState` is not eligible.
2. **Object addressability:** use the existing `Visibility.isEntityReferenceAddressableTo` policy for the exact event-time state. This is weaker than identity visibility and permits an opaque public face-down permanent or stack object.
3. **Identity disclosure:** use `Visibility.isEntityIdentityVisibleTo` and the merged History-B facts for the exact current witness. Printed identity is an optional disclosure on a reference, not a prerequisite for an opaque object alias.
4. **Continuity:** for a hidden-zone object, require a current History-B fact combination that binds the same witness and has not been invalidated. Do not use a card name, current zone alone, or raw `EntityId` to recover continuity.
5. **Reference-slot ordering:** allocate only after the candidate has a perspective-visible semantic slot and a stable public order. Unresolved symmetry is fail-closed.

### Sufficient evidence by reference kind

| Requested semantic fact | Sufficient authority | Insufficient authority |
| --- | --- | --- |
| Opaque object reference | Current committed witness plus `Visibility.isEntityReferenceAddressableTo`; a face-down public permanent may qualify without identity. For a hidden object, an active History-B continuity/identity fact and current reveal permission are required. | Zone membership count, raw `EntityId`, card name, public zone label alone, or an uncommitted state. |
| Printed identity reference | Current `Visibility.isEntityIdentityVisibleTo` or an active History-B `IDENTITY` fact for the same current stamp, with any required current-zone/continuity evidence. | `cardDefinitionId` in hidden full state, a prior identity fact on an old stamp, or a same-name match. |
| Zone membership reference | Public current zone through event-time `Visibility`, or History-B `ZONE_MEMBERSHIP` for the current witness. | Hidden zone size, final-state reconstruction, or inferred owner/library membership. |
| Position/order reference | History-B `POSITION_OR_ORDER` for the exact current library stamp and an authoritative order-producing event. | Identity reveal without position, a library list read from full state, a search result without order, or collection iteration. |
| Continued hidden-zone reference | History-B `IDENTITY` plus `ZONE_MEMBERSHIP` on the current stamp, with continuity preserved by the authoritative producer; the merged producer-authorized reveal-preservation path is supported for its exact move. | Old-stamp facts, a same-name card in hand, a known definition without current continuity, or `EntityId` equality across a zone change. |

### Public current objects

Current public objects use one authority path: event-time `Visibility` plus the internal incarnation witness plus the generic History-C reference policy. History-C must not require redundant History-B facts for an inherently public face-up battlefield, graveyard, stack, exile, or command object. History-B remains necessary for hidden-zone continuity, identity acquired by private look/search, explicit order, and invalidation history.

`TrainingObservation.EntityFeatures.entityId`, `StackItemView.entityId`, `ZoneView`, `PlayerObservationV1`, legal-action domains, and `CommanderPublicStateV1` are current public contracts, not History-C aliases. History-C must not copy those IDs into semantic references or use the observation builder as an alias allocator.

## 6. Alias lifecycle

The implementation may use different enum names, but it must preserve these lifecycle meanings:

```text
UNSEEN
    no semantic reference has been allocated for this perspective/witness

REFERENCEABLE
    the current witness is legally addressable; an alias may be allocated when a committed
    reference slot actually needs it

IDENTITY_KNOWN
    the same witness is still addressable and a printed identity is now authorized; this is an
    observation capability on the existing alias, not a new alias

CONTINUITY_KNOWN
    a separate authorized relation can connect this witness to another incarnation; it does not
    merge the aliases

RETIRED
    the witness can no longer be used by this alias because the object changed incarnation,
    individual continuity was invalidated, or the object ceased to exist
```

Frozen transitions:

- The first legally referenceable occurrence allocates the next compact alias, after ordering and symmetry validation.
- The same `(semanticEpisodeId, perspectivePlayerId, EntityId, objectIdentityStamp)` maps to one alias for the episode.
- An identity disclosure upgrade changes only the later reference occurrence; earlier semantic bytes remain unchanged.
- Any new object stamp requires a new alias even when the `EntityId`, definition, name, zone, or physical card is unchanged.
- Losing individual continuity retires the old alias. It does not automatically remove the alias from old history entries.
- A retired alias is never reused within the semantic episode, including after token disappearance or a hidden-zone ambiguity.
- Reset starts a new `semanticEpisodeId` and a new alias namespace.
- Snapshot/restore restores the registry at the saved boundary; it does not replay or backfill earlier references.

Alias allocation is lazy. A perspective-visible current object that never occurs in a semantic reference slot consumes no alias ordinal. This prevents an eager scan of all visible hand/battlefield objects from making alias bytes depend on unrelated hidden or unreferenced objects.

## 7. Perspective isolation

The registry key is exactly:

```text
(semanticEpisodeId, perspectivePlayerId)
```

Required invariants:

```text
NO_CROSS_EPISODE_ALIAS_STATE=YES
NO_CROSS_PLAYER_ALIAS_STATE=YES
NO_SHARED_GLOBAL_OBJECT_ALIAS=YES
NO_OPPONENT_PRIVATE_ALIAS_ALLOCATION=YES
NO_CROSS_PERSPECTIVE_EQUALITY_CHANNEL=YES
```

P1 and P2 may both receive `o0` for their first independently referenceable object. That equality is intentionally meaningless across perspective namespaces. A P2-private look can allocate or stabilize a P2 alias without changing P1's alias counter, registry, bytes, digest, or knowledge epoch.

Player references remain a separate public scope. Use the existing validated player identity/role contract where a history event needs a player; do not route players through the card/object alias registry.

## 8. Hidden-state non-interference

The History-C input allowlist is:

- explicit `semanticEpisodeId` and `perspectivePlayerId`;
- the committed History-A event ordinal and typed reference slot;
- the event producer's semantically authoritative public order;
- event-time `Visibility` decisions for the perspective;
- merged History-B evidence for the exact witness and epoch;
- public semantic descriptors needed to distinguish candidates;
- an explicit Rules-approved cross-incarnation relation witness.

The following are forbidden alias inputs:

```text
raw EntityId ordering
UUID ordering
map or set iteration order
allocation sequence
hidden event count
hidden decision count
hidden zone position
global action ordinal
global event ordinal
decision nonce
action handle
snapshot handle
debug identity
full-state serialization bytes
replay fingerprint or digest
```

If two authoritative executions differ only in opponent-private hand/library contents, private decisions, hidden-only events, or hidden insertion/removal, then for P:

```text
same visible reference candidates
same public producer order
same alias assignments
same retired-alias set
same canonical reference bytes
same digest
```

No hidden event is allowed to consume an ordinal. No hidden-only mutation may create a gap. If hidden information changes whether a candidate is referenceable, the candidate is omitted or the event fails closed according to the authoritative visibility result; the registry must not allocate speculatively and then roll back by renumbering.

## 9. Alias allocation / ordering policy

The future projection must process a committed transition as follows:

1. Reject the input unless it is a successful strict committed transition with a nonblank semantic episode ID.
2. Require the History-A projection to be complete for every raw event. An intentionally hidden event has no reference candidates; an unsupported reference-bearing event remains a typed failure.
3. Build internal reference candidates from the raw event plus event-time before/after state. Each candidate carries an internal witness, reference family, semantic slot, and producer-order evidence; none of those internal fields cross the semantic seam.
4. Ask `Visibility` and History-B whether the candidate is addressable, whether identity is known, and whether hidden continuity survives.
5. Group candidates by the History-A event order and typed semantic slot. Do not iterate raw maps or sets to choose an alias order.
6. Apply the producer/symmetry rules below. If a group has no legal public distinction, return `UNORDERED_SYMMETRY` and emit no partial complete history unit.
7. Allocate aliases lazily in the resulting public order. Existing witness mappings are reused; new witnesses receive the next ordinal. Hidden candidates never call the allocator.
8. Retire stale mappings after validating the post-transition ledger and object stamps. Retirement is append-only state; it never renumbers active aliases.
9. Emit semantic references and any separately validated cross-incarnation relation evidence. Canonicalize only after all aliases are assigned.

### Producer-order decision table

| Producer | Ordering policy | If producer order is not authoritative |
| --- | --- | --- |
| Explicit player-selected list/order | Preserve the accepted choice order when the choice is in P's legal information set. | Fail closed if the choice is private to another player or not bound by History-B. |
| Stack objects | Use the Rules-owned bottom-to-top stack order and the event's typed role order. | Fail closed if only an unordered source set is available. |
| Combat attackers/blockers/assignments | Use the published Rules domain/order already accepted for the event family. | Do not sort by `EntityId`; fail closed on a missing stable public rank. |
| Public zone collection with meaningful order | Use the producer's ordered zone/list position when the position is public to P. | For hidden library order require History-B `POSITION_OR_ORDER`; otherwise do not allocate per-card aliases. |
| Set-like event payload | Canonically sort by a public semantic descriptor containing no runtime ID. | If descriptors tie, apply the indistinguishable-object policy below. |
| Unordered map | Canonically sort key/value semantic pairs using strict canonical JSON shape. | Never use Kotlin map iteration or raw key order. |
| Identical tokens or same-definition cards | Use a legal public distinction such as explicit entry order, public position, controller/role, or a typed selection ordinal. | No per-object alias; fail closed for a reference that requires distinguishing them. |

The allocator is not a general `first EntityId seen` counter. It is a consequence of a validated perspective-visible semantic order.

## 10. Indistinguishable duplicate policy

The frozen generic policy is:

```text
INDISTINGUISHABLE_OBJECT_POLICY=FAIL_CLOSED_NO_PER_OBJECT_ALIAS
```

When two or more current witnesses have identical perspective-visible semantics and the committed source supplies no legally public distinction, History-C must not invent one. It must not use `EntityId`, allocation order, collection iteration, a hash, or a hidden zone position as a tie-breaker. It returns a focused `UNORDERED_SYMMETRY` diagnostic for a reference-bearing producer and emits no complete reference result for that transition.

If the Rules producer supplies a legal distinction, it is used before this policy applies:

| Case | Legal distinction allowed | Result without it |
| --- | --- | --- |
| Two identical creature tokens enter simultaneously | Explicit public entry/order position or a public event slot that players can distinguish | No per-token aliases; reference-bearing event fails closed |
| Two same-name cards revealed together | Producer-supplied ordered reveal/selection slot, if the effect makes that order meaningful | No distinct card aliases |
| Multiple face-down permanents | CR 708.6-required physical distinction represented by accepted producer order/slot | Opaque aliases are not allocated per object |
| Same-definition cards in a known ordered library segment | Merged History-B position/order fact | No per-card order/reference alias |
| Identical visible features with different public controllers/roles | Controller/role is a semantic distinction | If roles also tie, fail closed |

This policy prefers a safe unsupported result over an alias byte that changes with runtime allocation.

## 11. CR 400.7 incarnation policy

Every Rules zone entry or accepted object-reincarnation operation establishes a new internal object stamp. History-C treats the stamp as the incarnation boundary, including for:

```text
battlefield -> graveyard
graveyard -> hand
hand -> battlefield
library -> hand
hand -> library
library -> graveyard
exile -> battlefield
stack -> graveyard
stack -> permanent
blink
bounce -> recast
Commander zone changes
library reveal/reorder/shuffle paths that invoke the accepted object reincarnation primitive
```

Default rule:

```text
new object stamp -> old alias retired for the old witness -> new alias required if referenceable
```

The exception list in CR 400.7 can authorize a relation that an effect can use to find a new object. It does not make the old and new objects one model alias. A `ZoneChangeEvent` with the same `EntityId` is not sufficient by itself; History-C also requires the exact before/after stamps, the committed transition context, the legal visibility/continuity evidence for P, and an allowed relation kind.

Turning a face-down object face up or face down without a zone change preserves the current stamp. The alias remains the same if the object remains referenceable. Identity disclosure can be added on the later occurrence; identity loss does not automatically retire an opaque alias unless individual continuity is also lost.

## 12. Cross-incarnation relationship policy

The optional relation primitive belongs to History-C as validated reference evidence, because it must combine the old and new witness with the perspective-specific referenceability decision at the same committed boundary. History-D may consume the relation but must not infer it from aliases, card names, or current state.

The conceptual relation is:

```text
old alias = o7
new alias = o12
relation = RULES_LOCATABLE_TRANSITION
```

The relation is not a physical-card identity claim, not alias reuse, and not a promise that an arbitrary later object is the same card.

A relation may be emitted only when all of these hold:

- the old and new witnesses are both present in the same committed transition context or in an explicitly linked committed producer context;
- the before and after stamps differ as required by CR 400.7;
- P had a valid old alias before the transition;
- P can reference the new witness after the transition;
- the Rules/event producer supplies a relation kind covered by the accepted exception/effect semantics;
- History-B/`Visibility` proves any hidden-zone identity or continuity needed by P;
- the relation is ordered and canonicalized without a runtime-ID tie-break.

Current History-A and merged History-B supply before/after states, `ZoneChangeEvent`, stamps, visibility, and facts, but they do not provide a complete explicit relation-kind/audience witness for every event family. Therefore:

```text
MISSING_RELATION_AUTHORITY -> NO_LINK
```

The future HISTC-C slice must either consume a separately accepted Rules-owned relation witness or classify the event as `BLOCKED_ON_AUTHORITATIVE_METADATA`. It must not promote raw `EntityId` equality into relation authority.

Examples:

- A visible battlefield permanent entering its owner's graveyard may link old and new aliases when the committed transition and public endpoints establish the allowed relation.
- A known public graveyard card returning to hand may link only for perspectives that knew the old object and are authorized to know the destination card's identity/continuity.
- An ordinary hidden library draw has no old alias for an opponent and therefore no opponent link, even though the owner can see the new hand card.
- A bounce/recast sequence gets a new alias for the hand incarnation, a new alias for the spell, and a new alias for the permanent; each link is independently validated.
- A shuffle/reorder creates a new incarnation where the accepted Rules path says so, but no relation is emitted unless the producer supplies explicit relation evidence. Preserving a definition fact is not relation evidence.
- A token's terminal existence produces no destination alias after CR 111.7; a terminal marker may be recorded internally for retirement tests, but not as a reusable object reference.

## 13. Face-down and hidden-zone policy

| Situation | Alias | Printed identity | Required authority |
| --- | --- | --- | --- |
| Face-down battlefield permanent visible to P | Allocate opaque alias if addressable. | Absent unless `Visibility.isEntityIdentityVisibleTo` or History-B authorizes it. | History-A event-time state plus `Visibility`; B only for acquired/retained identity. |
| Controller/authorized player looks at the same face-down permanent | Reuse the same alias for the unchanged stamp. | Add only to the later reference occurrence. | Current `Visibility` look permission and merged B identity fact. |
| Opponent's ordinary hidden hand | No individual alias. | No identity. | Hand count is not referenceability; CR 402.3 and `Visibility`. |
| Individually known hidden hand card | Alias may remain only while B proves current identity and continuity. | Present only for the authorized perspective. | B `IDENTITY` + `ZONE_MEMBERSHIP`, current reveal permission, and same stamp. |
| Same-name card played from a known/revealed hand | Retire ambiguous hand aliases selected by History-B; do not bind a remaining copy by name. | Do not preserve identity on an unidentifiable remaining card. | `RevealedInHandTracker` plus B invalidation. |
| Face-down exile | Alias only for a perspective authorized to address/know it; no opponent alias from existence alone. | Absent unless explicit permission/reveal. | `Visibility` plus B continuity; the accepted producer-authorized reveal audience may be re-established on the new incarnation for exact library-look paths such as `Expensive Taste`; no generic owner inference. |
| Library card | No alias for arbitrary hidden membership. | No identity. | Only explicit top/reveal/look/search facts; B position/order for order references. |
| Public face-up exile/graveyard/stack | Alias may be allocated on a committed reference. | Identity is public unless the object is specifically face down. | Event-time `Visibility` and current stamp. |
| Future reveal of an earlier opaque alias | Reuse same alias if stamp is unchanged. | Add identity only to the later occurrence. | Public reveal or authorized look plus B/current visibility. |

History-C never reconstructs hidden identity from a card name in `GameEvent`, `CardComponent`, `GameState`, or an observation. History-A's current identity-free event payload remains identity-free until a separately authorized reference disclosure is attached.

## 14. Snapshot / restore / fork policy

Choose **snapshotting the immutable History-C registry state alongside the Gym snapshot**. The registry is not placed in Rules `GameState`, because aliases are model/perspective contract state rather than Rules semantics; it is also not left as an untracked mutable field on `CommittedPerspectiveEventSource`.

The future snapshot state must contain only:

```text
HistoryCSnapshotStateV1 {
    version
    schemaIdentity
    semanticEpisodeId
    perspective registries
    next alias ordinals
    active witness -> alias mappings
    retired alias/witness tombstones required to prevent reuse
}
```

It must not contain model-visible raw witness fields. The snapshot is an internal capability/state record; the public semantic DTO still contains only aliases and authorized semantic disclosures. The `SnapshotCodec.Entry` or an equivalent versioned Gym snapshot envelope must save and restore this state at the same committed boundary as `GameState`, player roster, step count, and projection generation.

Frozen lifecycle behavior:

- **Fork:** `GameGymEnv.fork()` remains non-authoritative. It either has no History-C registry or a private copy that is never emitted; stepping it cannot mutate the parent registry, parent alias ordinal, or parent history. No fork output is accepted as live history.
- **Reset:** require a new nonblank deterministic `semanticEpisodeId`; clear all per-player registries, active mappings, and retired tombstones. Do not derive the ID from wall clock, process ID, or hidden allocation state.
- **Snapshot:** capture the registry after the last committed transition and before any next candidate is processed.
- **Restore:** restore the registry exactly. Clear only the one-transition History-A buffer as the current source already does; the next committed transition continues from the restored alias state without backfilling.
- **Cross-episode restore:** reject a snapshot whose episode/schema identity is not accepted by the current History-C configuration. Do not silently merge namespaces.
- **Unsupported legacy snapshot:** fail closed with `UNKNOWN_HISTORY_C_SNAPSHOT_VERSION` rather than reconstruct aliases from current `GameState` or observations.

The current `SnapshotCodec` stores `GameState` and Gym metadata but not History-C registry state; this is a planned future change, not a current code change.

## 15. Replay / determinism policy

For identical:

```text
engine/card/schema versions
seed and explicit episode identity
semantic external decisions
perspective
```

History-C must reproduce the same alias/reference sequence and canonical bytes.

The replay adapter must feed the same History-C projector with committed event-time before/after witnesses and the same History-B facts. Replay fingerprints remain integrity/replay authority; they are not information authority and must never become alias inputs.

The determinism contract includes:

- different runtime `EntityId` allocation that preserves the same public producer order and visible semantics produces the same alias bytes;
- different map/set insertion order produces the same canonical output for semantically unordered payloads;
- hidden-only event insertion/removal produces no visible alias gap or renumbering;
- a public order change is allowed to change aliases only when the order itself is a public semantic difference;
- if equivalent information sets cannot be aligned because the source omitted a stable public distinction, the projector fails closed instead of selecting a runtime tie-break;
- raw replay digests, decision nonces, action IDs, snapshot handles, and full-state bytes never appear in the semantic reference.

## 16. Canonical schema recommendation

Do not implement this DTO in History-C. Freeze the future minimum shape as:

```text
PerspectiveSemanticReferenceV1 {
    version
    schemaIdentity
    referenceKind
    perspectiveAlias
    identityDisclosure?
    cardDefinitionId?
}
```

Recommended meanings:

- `referenceKind` distinguishes `CARD_OR_RULES_OBJECT`, `STACK_OBJECT`, `TOKEN`, `EMBLEM`, and `ABILITY` where a reference family needs different validation. A `PLAYER` reference uses the existing validated player scope instead of the object alias table.
- `perspectiveAlias` is the compact alias (`o0`, `o1`, …) and is required for object-like references. It is never a raw `EntityId`, stamp, hash, or encrypted runtime ID.
- `identityDisclosure` is a small enum such as `OPAQUE` or `DEFINITION_KNOWN`, allowing the same alias to remain stable while identity disclosure changes.
- `cardDefinitionId` is present only when independently authorized by current visibility/History-B and is payload data, not the alias identity. Card name is not needed for reference identity.

Optional relation evidence is a separate versioned value:

```text
PerspectiveIncarnationRelationV1 {
    version
    schemaIdentity
    relationKind
    oldAlias
    newAlias
}
```

Neither shape contains `EntityId`, `objectIdentityStamp`, raw zone index, source event ordinal, runtime class name, debug text, action ID, decision ID, snapshot handle, or replay digest. Unknown versions and schema identities fail closed. Retired aliases remain tombstoned and are never renumbered.

Future History-D may place these references in `references[]`, but History-C does not own `eventFamily`, `visibilityClass`, `semanticPayload`, `perspectiveHistoryOrdinal`, history windowing, or sequence storage.

## 17. Unsupported / fail-closed policy

The future History-C module must return a typed failure equivalent to `UNSUPPORTED_REFERENCE_METADATA` when a required reference cannot be safely classified. The diagnostic must identify the semantic category, not expose the hidden witness.

Required diagnostic categories:

```text
MISSING_SEMANTIC_EPISODE_ID
UNCOMMITTED_TRANSITION
FORK_OR_SPECULATIVE_SOURCE
MISSING_EVENT_TIME_WITNESS
MISSING_HISTORY_B_REFERENCE_EVIDENCE
MISSING_HISTORY_B_CONTINUITY_EVIDENCE
MISSING_RULES_RELATION_EVIDENCE
UNORDERED_SYMMETRY
UNSUPPORTED_REFERENCE_KIND
RAW_RUNTIME_ID_AT_SEMANTIC_SEAM
UNKNOWN_REFERENCE_SCHEMA_VERSION
UNKNOWN_HISTORY_C_SNAPSHOT_VERSION
```

The implementation must not:

- drop a required reference silently;
- guess from card name, current zone, first matching card, or current full state;
- use raw `EntityId` or `objectIdentityStamp` as a public alias;
- consume and later hide an alias ordinal;
- sort by runtime ID or Kotlin collection iteration;
- fall back to client/UI masking, debug reveal, native AI, or replay full-state heuristics.

Intentionally hidden events remain intentionally hidden and consume no aliases. An event that is visible/reference-bearing but lacks the metadata needed to classify its reference is unsupported and keeps the resulting committed history unit incomplete.

## 18. Movement matrix

Legend:

- `YES` means true for the ordinary face-up/public case.
- `P-CONDITIONAL` means true only when the listed perspective-specific authority exists.
- `NEW` means a new alias is required for the new CR 400.7 object.
- `NO-LINK` means the default when explicit relation evidence is absent.
- `SUPPORTED_EXACT` means merged History-B plus the named producer proves the exact continuity path; it does not generalize to arbitrary hidden-zone movement.
- `BLOCKED` means current A+B metadata does not prove the required generic semantic; no heuristic is permitted.

| Movement | OLD_INCARNATION_REFERENCEABLE? | NEW_INCARNATION_REFERENCEABLE? | NEW_ALIAS_REQUIRED? | CROSS_INCARNATION_LINK_ALLOWED? | KNOWS_IDENTITY_BEFORE? | KNOWS_IDENTITY_AFTER? | HISTORY_B_EVIDENCE_REQUIRED | CURRENT_METADATA_SUFFICIENT? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| battlefield -> graveyard | YES for public face-up; opaque face-down object if addressable | YES for a public face-up destination; face-down departure must obey CR 708.9 | NEW | Only with committed Rules relation evidence; otherwise `NO-LINK` | `Visibility` before, identity conditional for face-down | YES for ordinary public card; face-down exit requires authoritative reveal | B only for hidden/face-down identity or continuity | YES face-up; `BLOCKED` for a missing face-down-exit reveal witness |
| graveyard -> hand | YES for public graveyard object | YES for owner; P-CONDITIONAL for other players when public return/reveal knowledge survives | NEW | P-CONDITIONAL with old/new evidence; otherwise `NO-LINK` | YES for ordinary public graveyard | Owner YES; other P only with B/current reveal authority | B `IDENTITY` + `ZONE_MEMBERSHIP` for non-owner hidden continuity | YES for ordinary public return; accepted producer-authorized reveal preservation applies only to exact supported move paths |
| hand -> battlefield | Owner YES; opponent P-CONDITIONAL for revealed/visible hand | YES, including opaque face-down permanent | NEW | Only if old hand reference and cast/move relation are both authorized; otherwise `NO-LINK` | Owner YES; opponent only if hand known | Face-up YES; face-down identity P-CONDITIONAL | B hand identity/continuity when old hidden card is referenced | YES for public destination aliases; relation is `BLOCKED` without relation evidence |
| library -> hand | P-CONDITIONAL only for known/revealed/top/looked card | Owner YES; other P-CONDITIONAL on reveal | NEW | Only if old position/identity continuity and producer relation exist; ordinary draw has `NO-LINK` for opponents | Only with B identity/order or explicit top knowledge | Owner YES; other P only if public/revealed | B `IDENTITY` + `ZONE_MEMBERSHIP`; `POSITION_OR_ORDER` when old position is referenced | P-CONDITIONAL; ordinary opponent draw link is not sufficient |
| library-look -> hidden destination (producer-authorized, including face-down exile) | P-CONDITIONAL for the looked-at card | P-CONDITIONAL only for the same producer-authorized reveal audience on the new incarnation | NEW | `NO-LINK` by default; a relation still requires separate Rules relation evidence | The looking perspective knows identity before | The existing authorized audience retains identity knowledge after; no new viewer is added | B `IDENTITY` + `ZONE_MEMBERSHIP` plus producer-supplied existing `RevealedToComponent.playerIds` | `SUPPORTED_EXACT` for the merged producer path; generic hidden-zone continuity remains conditional |
| hand -> library | Owner/P-CONDITIONAL for a known card | P-CONDITIONAL; a library object is not generally addressable | NEW when a reference is required | Only for explicit producer-owned order relation; otherwise `NO-LINK` | P-CONDITIONAL | Only with explicit order/reveal/visibility authority | B membership plus `POSITION_OR_ORDER` for position references | `BLOCKED` for generic hidden placement; explicit reorder path is sufficient for its authorized perspective |
| library -> graveyard | P-CONDITIONAL for known top/ordered/revealed card | YES for ordinary public graveyard destination | NEW | Only with old knowledge plus committed relation evidence; otherwise `NO-LINK` | B/top visibility or explicit reveal | YES ordinary public identity | B old identity/order when a prior reference is made | YES for public destination; old-to-new relation conditional |
| face-up exile -> battlefield | YES public face-up | YES public face-up; opaque if face-down entry | NEW | Allowed only with event/effect relation evidence; otherwise `NO-LINK` | YES | Face-up YES; face-down P-CONDITIONAL | B only if an endpoint is hidden/face down | YES for aliases; relation conditional |
| face-down exile -> battlefield | P-CONDITIONAL; no opponent alias from existence alone | YES as opaque battlefield object; identity P-CONDITIONAL | NEW | Only if P knew/ could reference old object and Rules relation evidence exists | Owner/authorized P-CONDITIONAL | Opaque for all; identity only where `Visibility` allows | B current identity/continuity; accepted producer audience when this is the exact supported look-preservation path | `SUPPORTED_EXACT` for producer-authorized continuity; generic owner inference remains `BLOCKED` |
| blink (battlefield -> exile -> battlefield) | First old object YES/opaque if addressable | Exile and returning battlefield are each new incarnations; each may be addressable | NEW for exile and NEW for return | Each link is independently authorized; never one alias across the blink | Before YES/conditional for identity | Public return identity conditional on face-down mode | B for hidden/face-down endpoints | Aliases YES per endpoint; composite relation `BLOCKED` without producer evidence |
| bounce / recast | Battlefield old YES; hand and stack endpoints P-CONDITIONAL by perspective | Hand, stack, and permanent each receive new aliases when referenceable | NEW at every incarnation | Separate links only when cast/move evidence and old reference exist | P-dependent | Stack/permanent public identity conditional on face-down casting | B for hidden hand continuity | Aliases YES for public endpoints; relation `BLOCKED` without explicit causal witness |
| Commander battlefield -> command | YES public/opaque | YES public command object; designation is separate | NEW | Allowed only with committed public transition evidence; otherwise `NO-LINK` | YES unless face-down identity masked | YES for public commander card if identity visible | Usually none beyond public endpoint facts | YES for aliases; commander designation does not create alias reuse |
| Commander command -> hand | YES public command object | Owner YES; others P-CONDITIONAL in hidden hand | NEW | P-CONDITIONAL; no link for a perspective lacking destination continuity | YES public designation/card if visible | Owner YES; other P only if revealed/known | B destination identity/continuity for non-owner | Owner/public case sufficient; hidden-opponent case conditional |
| token battlefield -> graveyard / ceases to exist | YES opaque/public token if addressable | No durable destination alias after CR 111.7/704.5d | No destination alias; retire old | Terminal only; no new object link | Token characteristics are public as authorized | No durable post-SBA object identity | None beyond committed token witness | YES for retirement; no destination alias |
| spell stack -> graveyard | YES public stack object, opaque if face down | YES public graveyard object under ordinary rules | NEW | Allowed only with Rules/effect relation evidence; otherwise `NO-LINK` | P-CONDITIONAL for face-down spell | YES ordinary public identity; face-down departure follows CR 708.9 | B for face-down identity | Public face-up case sufficient; face-down reveal relation conditional |
| spell stack -> permanent | YES public stack object | YES public permanent, opaque if face down | NEW | Allowed only when the committed cast/resolution producer supplies the relation; never by ID equality | P-CONDITIONAL for face-down spell | Face-up YES; face-down P-CONDITIONAL | B for face-down identity and continuity | Alias allocation sufficient for public endpoints; relation `BLOCKED` without producer witness |

The table is intentionally conservative. `CURRENT_METADATA_SUFFICIENT?=NO/BLOCKED` is a real plan dependency, not permission to add a card-specific or state-heuristic repair inside History-C.

## 19. Required RED matrix

Every row below is a future RED characterization. `BLOCKED_ON_HISTORY_C_NOT_IMPLEMENTED` is the current status for the new semantic behavior; rows mentioning History-B or History-A passing do not promote those focused results into History-C acceptance.

| ID | Authoritative setup | Perspective | Internal variation | Expected semantic reference output | Privacy assertion | Determinism assertion | Current status | Required dependency |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `HISTC-01_RAW_ENTITY_ID_REJECTED` | Committed candidate contains a raw object ID in a semantic reference slot/payload | P1 | Change only the raw ID value | Typed `RAW_RUNTIME_ID_AT_SEMANTIC_SEAM`; no reference bytes | No raw ID crosses the seam | Same malformed shape yields the same typed rejection | History-A payload scanner partial; C blocked | History-C schema validator |
| `HISTC-02_SAME_INCARNATION_ALIAS_STABLE` | One public object is referenced in two committed events without a zone/reincarnation change | P1 | Repeat the same witness with different event metadata that does not change semantics | `o0`, then `o0`; one registry mapping | No stamp or ID exposed | Replay and repeated projection produce identical `o0` | Blocked | Committed witness + C registry |
| `HISTC-03_HIDDEN_HAND_MUTATION_NON_INTERFERENCE` | P1 sees the same public object in two executions; P2 hand contents differ | P1 | Replace/add/remove only P2-private hand cards | P1 reference output and digest unchanged | No P2 hand identity or alias | No alias ordinal shift or gap | History-A/B characterization exists; C blocked | B final acceptance + hidden-state property harness |
| `HISTC-04_HIDDEN_EVENT_INSERTION_NO_ALIAS_GAP` | Two executions have the same P1-visible candidate sequence | P1 | Insert a P2-private event between candidates | Both allocate `o0`, then `o1` with no gap | Hidden event absent from P1 output | Canonical bytes/digest equal | Blocked | Complete classification and lazy allocator |
| `HISTC-05_PRIVATE_LOOK_PERSPECTIVE_ISOLATION` | P2 privately looks at a hidden card; no public reveal follows | P1 and P2 | Project each perspective from the same committed transition | P1: no card alias; P2: alias only when its reference slot needs it | P1 receives no P2-private identity | P1 registry/bytes unchanged; P2 deterministic | B private-look coverage; C blocked | B fact/continuity query |
| `HISTC-06_PUBLIC_REVEAL_REFERENCEABLE` | A committed public reveal exposes one card to all players | P1 and P2 | Use different runtime IDs with the same public reveal order | Each namespace emits its own first alias, with authorized identity | Both may know identity; no raw witness | Per-namespace bytes match across equivalent runs | Blocked | A ordered reveal candidate + B public fact |
| `HISTC-07_FACE_DOWN_OPAQUE_REFERENCE` | A public face-down battlefield permanent is referenced without a look | P1 opponent | Change hidden definition while retaining visible face-down characteristics | `o0` with `OPAQUE`; no `cardDefinitionId` | Hidden definition does not appear | Same visible face-down fixture yields same bytes | Visibility has addressability support; C blocked | `Visibility` addressability + schema disclosure field |
| `HISTC-08_AUTHORIZED_FACE_DOWN_LOOK_ADDS_IDENTITY_SAME_REF` | Same face-down permanent remains in the same zone/incarnation; authorized look occurs | Authorized P | Reveal different definitions in separate runs | Earlier ref `{o0, OPAQUE}`; later ref `{o0, DEFINITION_KNOWN}` | Unauthorized P remains opaque | Earlier bytes are byte-for-byte unchanged | B face-down coverage; C blocked | B identity fact and same-stamp lookup |
| `HISTC-09_ZONE_CHANGE_NEW_INCARNATION_NEW_ALIAS` | One public card moves through a committed zone change | P1 | Keep `EntityId`/definition stable while stamp changes | Old `o0`; new `o1`; no alias reuse | Only authorized endpoint facts appear | Runtime ID equality cannot alter ordinals | B incarnation coverage; C blocked | Before/after stamps + lazy allocator |
| `HISTC-10_VISIBLE_ZONE_CHANGE_RELATIONSHIP_POLICY` | Public old and new endpoints are in one committed transition | P1 | Run once with relation witness and once without it | With evidence: `o0`, `o1`, relation `o0 -> o1`; without: aliases only, `NO-LINK` | Relation appears only for P with old/new authority | No raw ID equality can create a link | Blocked | Explicit Rules relation witness |
| `HISTC-11_BLINK_NEW_ALIAS` | Permanent leaves battlefield and returns as a new object | P1 | Use identical definition and runtime ID across blink | Battlefield `o0`, exile `o1` if referenced, return `o2` | Hidden exile identity stays masked as required | No alias reuse across either stamp change | Blocked | Multi-transition committed source and relation policy |
| `HISTC-12_BOUNCE_RECAST_NEW_ALIASES` | Public permanent bounces, is cast from hand, resolves | P1 and opponent | Make the hand hidden to opponent but visible to owner | Owner receives separate aliases for hand/stack/permanent; opponent receives only referenceable endpoints | Opponent gets no hidden hand alias/link | Both perspectives deterministic and isolated | Blocked | Spell/zone reference candidates + B continuity |
| `HISTC-13_SHUFFLE_KNOWLEDGE_INVALIDATION` | P has known library identity/order; accepted shuffle changes the affected order | P | Shuffle an unrelated library versus the known library | Old position alias retires; a later newly referenced incarnation gets the next alias | No old order/position is retained | Unrelated shuffle does not bump P; affected shuffle changes only authorized state | B shuffle invalidation pass; C blocked | B final accepted reincarnation facts |
| `HISTC-14_SAME_NAME_HAND_AMBIGUITY_BREAKS_CONTINUITY` | Two same-name hand cards were known; one same-name card is played | Opponent who knew the hand | Vary which runtime copy is selected | Ambiguous old hand aliases retire; no remaining copy is rebound by name | No card identity is guessed from same name | Runtime selection/allocation cannot choose the surviving alias | B same-name invalidation pass; C blocked | B affected-ID invalidation query |
| `HISTC-15_TOKEN_TERMINAL_ALIAS_NOT_REUSED` | Public token is referenced, then moves off battlefield and ceases to exist | P1 | Create a later token with identical visible characteristics | Old token alias retires; later token receives a fresh alias only when referenceable | No post-terminal object is fabricated | Later token ordinal is independent of old token runtime ID | Blocked | Committed terminal-token witness |
| `HISTC-16_P1_P2_ALIAS_NAMESPACE_ISOLATION` | P1 and P2 reference the same public object | P1 and P2 | Allocate in opposite perspective processing order | Both may independently use `o0` in their own namespace | Cross-player alias equality has no meaning | Processing order of perspectives cannot alter either namespace | Blocked | Per-perspective registry key |
| `HISTC-17_FORK_DOES_NOT_ADVANCE_ALIAS_STATE` | Authoritative parent has a registry with `o0`; fork is created for search/MCTS | Parent and fork | Step the fork through a visible reference | Parent next reference is `o1`; fork emits no authoritative reference | Fork-only information cannot reach parent | Parent bytes/ordinal unchanged | History-A fork exclusion pass; C blocked | Fork-disabled C source |
| `HISTC-18_RESET_CLEARS_ALIAS_NAMESPACE` | Episode has allocated aliases, then reset starts a new episode | Same seat in both episodes | Reuse cards/config/seed but provide a new episode identity | New episode may allocate `o0`; old `(episode,P,o0)` is not addressable | No cross-episode state | Same explicit episode IDs reproduce the same bytes | Blocked | Deterministic semantic episode identity |
| `HISTC-19_SNAPSHOT_RESTORE_CONTRACT` | Snapshot after `o0` allocation; continue and allocate `o1` | P1 | Restore snapshot, then repeat the same post-snapshot committed transition | Restored path produces the same `o1`, not a reset `o0` | Snapshot contains no model-visible witness | Save/restore bytes and output equal | B snapshot pass; C blocked | Versioned History-C snapshot state |
| `HISTC-20_REPLAY_DETERMINISTIC_REFERENCES` | Live committed run and exact replay use same seed/decisions/episode ID | Fixed P | Runtime allocation differs but public order is equal | Identical alias/reference sequence and digest | Replay never exposes full state | Two runs compare equal at every committed reference boundary | Replay is not model authority; C blocked | Replay adapter using same C policy |
| `HISTC-21_IDENTICAL_DUPLICATE_SYMMETRY` | Two identical public tokens/cards appear in one reference-bearing event | P1 | Permute hidden/runtime allocation and collection insertion order | No per-object alias; typed `UNORDERED_SYMMETRY` unless public order exists | No runtime distinction leaks | Equivalent inputs reject identically or use same public order | Blocked | Producer distinction or fail-closed policy |
| `HISTC-22_COLLECTION_ORDER_INVARIANCE` | A semantically unordered set/map contains reference candidates | P1 | Permute map/set insertion order only | Canonically ordered aliases from public descriptors; tied group fails | No iteration-order leak | Canonical bytes/digest equal across permutations | Blocked | Typed semantic descriptor and canonical sorter |
| `HISTC-23_FUTURE_REVEAL_NO_RETROACTIVE_IDENTITY` | Opaque alias is emitted, then same incarnation is later revealed/looked at | P1 | Change only later disclosure timing | Earlier entry remains opaque; later entry carries identity on same alias | No future leakage into prior bytes | Prefix bytes/digest remain unchanged | B non-retroactive pass; C blocked | Append-only semantic reference output |
| `HISTC-24_UNKNOWN_REFERENCE_VERSION_FAILS_CLOSED` | Decode/consume an unknown reference or registry schema version | P1 | Change version/schema only | Typed `UNKNOWN_REFERENCE_SCHEMA_VERSION`; no partial result | No fallback to raw/internal fields | Same unknown version always rejects | Existing versioned DTO pattern; C blocked | Strict schema validator |

The implementation slices must turn these rows RED before adding the smallest generic behavior, then run focused and surrounding regressions. A focused passing test is not History-C final acceptance without the exact-head semantic review and the History-B gate.

## 20. Architecture alternatives

| Alternative | Privacy | Determinism | Snapshot/restore | Fork safety | Replay reproduction | Dependency direction | Rules coupling | History-D usability | Performance | Testability | Schema stability |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A. Rules/GameState-owned semantic alias registry | Risk of model aliases becoming Rules state; hidden state could be serialized too broadly | Strong if state-owned, but allocation policy would be coupled to Rules internals | Natural through `GameState` snapshots | Natural immutable copy, but speculative branches carry model state | Strong state reproduction | History-C concerns leak down into Rules | Too high; Rules would own perspective/model policy | Easy to read but wrong ownership | Bounded but adds alias state to every Rules state | Easy unit tests, harder Rules isolation | Alias schema becomes a Rules-state schema |
| B. Mutable Gym/committed-source registry | Good if never exposed, but mutable source can be bypassed or stale | Depends on call order and careful reset/restore | Requires parallel custom snapshot handling | Must disable/copy explicitly | Replay adapter must reproduce mutable lifecycle | Keeps Rules clean | Low | Usable if snapshot is correct | Bounded per episode | Good source tests, weaker pure replay tests | Risk of unversioned mutable state |
| C. Stateless projection from History-A + History-B | Good boundary privacy | Cannot preserve retired aliases or lazy namespace state from current inputs alone | No registry to restore, but cannot reproduce continuity | Stateless but cannot preserve parent allocation history | Fails after first alias/retirement boundary | Clean | Low | Insufficient for references across entries | Potential history rescan per event | Simple only for trivial first-use cases | No stable lifecycle contract |
| D. Immutable internal witness registry + perspective projection | Strong: raw witnesses stay behind a private seam; B/Visibility authorize each output | Strong: state is explicit, lazy, ordered by public evidence, and canonical | Save a versioned registry with the Gym snapshot | Fork uses no authoritative registry/output; parent state is immutable | Fresh replay adapter consumes the same pure transition function | Rules supplies evidence; Gym/history owns learner alias policy | Low and localized | Directly supplies future `references[]` and relations | O(number of candidates + changed registry facts), bounded per episode/perspective | Pure transition function plus source/replay adapters | Dedicated versioned reference schema |

### Chosen architecture

Choose **D**. It is the smallest design that simultaneously preserves alias lifetime, prevents hidden-state perturbation, supports exact snapshot/restore, keeps Rules semantics free of learner naming, and gives both live committed Gym and replay a common pure projection seam. The registry is internal state, not a model DTO; the only model-facing output is the versioned semantic reference value.

## 21. Reuse inventory and module seam

### Reuse before inventing

| Existing mechanism | Reuse decision | Precise reason |
| --- | --- | --- |
| `GameState.objectIdentityStamps` / `nextObjectIdentityStamp` | **Reuse as internal witness** | It is the accepted CR 400.7 state-owned incarnation authority and already survives immutable copies, replay, and Rules snapshots. Never expose it. |
| `EntitySnapshot` | **Do not use as alias state** | It is frozen last-known characteristics for LKI/trigger resolution, may describe a dead permanent, and is not a complete current hidden-zone incarnation/visibility witness. It can remain a source of event-local Rules facts only. |
| `Visibility` | **Reuse directly** | It already separates `isEntityReferenceAddressableTo` from `isEntityIdentityVisibleTo`, including public face-down and hidden-zone cases. Do not build a second visibility table. |
| `KnownInformationLedger` / `KnownInformationLedgerComponentV1` | **Reuse as authority** | It owns fact acquisition, invalidation, epochs, same-name ambiguity, order, and continuity evidence. History-C reads it; it does not maintain a duplicate ledger. |
| `PerspectiveEventBatchV1` / `PerspectiveEventProjector` | **Reuse committed event order and completeness gate** | A supplies the public event sequence and hidden/unsupported classification. C adds an internal reference envelope; it does not mutate A's model-facing DTO in this slice. |
| `CommittedPerspectiveEventSource` | **Reuse as the committed source seam** | It already rejects legacy/fork/restore paths and retains before/after state for event-time decisions. C must attach only after successful strict capture. |
| `abilityKey` / `AbilityIdentity` | **Reuse for ability references where sufficient** | These represent stable ability semantics, not physical card/object identity. Do not create a second ability alias table. |
| `AbilityIdAliasTable` | **Reuse only as a canonicalization pattern** | It aliases generated ability handles inside replay full-state canonicalization; it does not solve perspective visibility, CR 400.7, hidden continuity, or object lifetime. |
| `DecisionNonceAliasTable` | **Do not reuse for object aliases** | Decision nonces are routing identities and can be rebound; they must never become semantic object identity. |
| `TransitionSemanticGameStateCanonicalizer` / replay canonicalization | **Reuse typed path/canonical ordering lessons only** | It proves replay equivalence and aliases typed ability/decision fields. It cannot be the information authority and must not feed raw state/digests into C aliases. |
| `A3SemanticJson` and canonical JSON helpers | **Reuse** | They provide strict versioned canonical bytes, sorted object keys, and runtime-key rejection patterns. |
| `SnapshotCodec`, `GameEnvironment.fork`, and immutable state copies | **Extend at the future C seam** | Snapshot/fork lifecycle is already explicit; add the versioned C registry state to the snapshot, keep fork output non-authoritative, and preserve parent immutability. |
| `CommanderPublicStateV1` | **Reuse only as public designation context** | It has public commander identity/zone/damage facts but deliberately no physical `EntityId` handle and no history binding. Commander designation continuity is not object-alias continuity. |
| Current observations (`TrainingObservation`, `PlayerObservationV1`, `ZoneView`, `StackItemView`) | **Do not use as alias source** | Their current `EntityId` fields are environment/runtime references; their privacy projection is current-state-only and lacks historical incarnation/lifetime semantics. |

### Future deep-module interface

The future module should present a small interface at `gym`'s committed-history seam, conceptually:

```text
advance(
    registryState,
    semanticEpisodeId,
    perspectivePlayerId,
    committedTransition,
    completePerspectiveEventProjection,
    beforeLedger,
    afterLedger,
    referenceCandidateEnvelope
) ->
    Success(nextRegistryState, semanticReferences, relationEvidence)
    | Failure(typedHistoryCDiagnostic)
```

The exact Kotlin names are intentionally left to the implementation slice, but the interface must not accept an arbitrary `GameState`, a raw observation, a replay fingerprint, or a candidate/simulation marker. The live Gym adapter and replay adapter are the two real adapters at this seam; tests exercise the same interface rather than bypassing it.

## 22. History-C / History-D boundary

### History-C may own

```text
referenceability decision through existing authority
perspective alias allocation
alias lifetime and retired tombstones
incarnation mapping behind an internal witness
optional validated cross-incarnation relation evidence
reference canonicalization
unsupported-reference classification and fail-closed diagnostics
snapshot/fork state contract for the alias registry
```

### History-C must not own

```text
PerspectiveHistoryV1 entries
PerspectiveHistoryEntryV1
perspectiveHistoryOrdinal
eventFamily or visibilityClass ownership
history windowing
recurrent sequence construction
burn-in
padding or masks
TrajectoryV1 binding
history storage/publication format
teacher inputs
learner inputs
training/self-play/search
observation or legal-domain mutation
```

History-D is the first future layer allowed to compose History-A event entries, History-B knowledge deltas, History-C references/relations, current `PlayerObservationV1`, and the future `PerspectiveHistoryV1` shape. History-C remains a reusable reference capability even if History-D changes its history-window representation.

## 23. Future implementation sequencing

The following slices are future work only. The History-B merge/acceptance gate is now open on current `origin/main`; each slice still requires its own explicit authorization and follows `RED -> smallest generic implementation -> focused tests -> surrounding regressions -> standalone semantic commit -> exact-SHA independent review`.

### HISTC-A — reference authority and internal witness contract

**Future files:**

- Create `gym/src/main/kotlin/com/wingedsheep/gym/history/HistoryCReferenceEvidence.kt` for internal before/after witness and typed reference-candidate evidence.
- Create `gym/src/main/kotlin/com/wingedsheep/gym/history/HistoryCFailure.kt` for typed fail-closed diagnostics.
- Create `gym/src/test/kotlin/com/wingedsheep/gym/history/HistoryCReferenceAuthorityTest.kt` for HISTC-01, HISTC-02, HISTC-06, HISTC-07, and HISTC-24.
- Modify only the committed-source adapter seam needed to hand C the captured before/after token; do not change the public `PerspectiveEventBatchV1` fields.

**Future steps:**

1. Write REDs proving raw IDs are rejected at the semantic seam, unknown schemas fail closed, and a committed public object can produce a typed candidate without exposing its witness.
2. Run the focused class and record wrapper/native evidence separately.
3. Implement the smallest internal evidence envelope that carries before/after witnesses, semantic slot, and public producer-order proof.
4. Re-run HISTC-A focused tests plus the existing History-A source tests.
5. Commit only this semantic slice, record the exact SHA, and stop for independent review.

### HISTC-B — alias allocation, lifecycle, and symmetry

**Future files:**

- Create `gym/src/main/kotlin/com/wingedsheep/gym/history/PerspectiveAliasRegistryV1.kt` for immutable per-episode/per-perspective mappings and retirement.
- Create `gym/src/main/kotlin/com/wingedsheep/gym/history/PerspectiveAliasAllocator.kt` for lazy allocation, public-order validation, and indistinguishable-object rejection.
- Create `gym/src/test/kotlin/com/wingedsheep/gym/history/PerspectiveAliasRegistryTest.kt` for HISTC-03, HISTC-04, HISTC-07 through HISTC-09, HISTC-15, HISTC-16, HISTC-21, HISTC-22, and HISTC-23.

**Future steps:**

1. Write REDs for no hidden ordinal consumption, stable same-stamp aliasing, new-stamp aliasing, retired tombstones, P1/P2 isolation, and symmetry failure.
2. Run the focused tests and verify the RED assertions fail for the absent registry.
3. Implement an immutable registry transition with an explicit public candidate order; never sort by runtime ID.
4. Add the opaque/identity-known disclosure transition without rewriting prior output.
5. Run the focused suite and the existing canonical JSON/History-A regressions.
6. Commit the allocation/lifecycle slice separately and stop for exact-head review.

### HISTC-C — History-A/History-B integration and relations

**Future files:**

- Create `gym/src/main/kotlin/com/wingedsheep/gym/history/PerspectiveReferenceProjectorV1.kt` for the pure A+B+registry transition.
- Create `gym/src/main/kotlin/com/wingedsheep/gym/history/PerspectiveIncarnationRelationV1.kt` for optional relation evidence.
- Create `gym/src/test/kotlin/com/wingedsheep/gym/history/PerspectiveReferenceProjectorTest.kt` for HISTC-05, HISTC-10 through HISTC-14, HISTC-17, HISTC-20, and movement-matrix cases.
- Add only internal source/replay adapters required to provide accepted relation/order evidence; no History-D storage or observation fields.

**Future steps:**

1. Write REDs for private/public audience isolation, old/new stamp separation, movement matrix aliases, no-link fallback, exact producer-authorized reveal preservation, and explicit relation evidence.
2. Run the tests against merged History-B `86d3c065...` on current `origin/main` and stop on any missing fact authority; historical `8560a880...` is not a separate dependency.
3. Implement the projector using `Visibility`, merged current-main History-B read access, History-A complete classification, and the immutable registry.
4. Add explicit relation evidence only for producer paths that prove the Rules/effect relationship; all other paths return aliases without a link or fail closed when the relation is required.
5. Run focused History-C, History-A, merged History-B, and relevant Rules regressions with exact source/head labels.
6. Commit the integration slice separately and stop for independent review.

### HISTC-D — snapshot, fork, replay, and privacy closure

**Future files:**

- Modify `gym/src/main/kotlin/com/wingedsheep/gym/service/SnapshotCodec.kt` to carry `HistoryCSnapshotStateV1` in the internal snapshot entry.
- Modify `gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt` and `GameEnvironment.kt` only at the explicit snapshot/fork/reset seams.
- Create `gym/src/test/kotlin/com/wingedsheep/gym/history/HistoryCSnapshotReplayTest.kt` for HISTC-17 through HISTC-20.
- Create `gym/src/test/kotlin/com/wingedsheep/gym/history/HistoryCPrivacyDeterminismTest.kt` for HISTC-03, HISTC-04, HISTC-16, HISTC-21, and HISTC-22.

**Future steps:**

1. Write REDs proving fork does not advance parent aliases, reset starts a new episode namespace, snapshot/restore continues the same ordinal, and identical replay reproduces references.
2. Run the REDs and verify the current snapshot source fails because it does not store C registry state.
3. Implement the versioned internal snapshot state and restore/fork lifecycle without changing public observation or Trajectory V1 wire bytes.
4. Run focused tests twice in fresh processes and compare stdout/stderr/exit status and semantic reference bytes.
5. Run surrounding Gym/replay/snapshot regressions and the repository wrapper/native fallback gates separately.
6. Commit the closure slice separately, record exact local/hosted status, and stop for independent review.

No slice above is implemented by this revalidation. HISTC-A is now eligible for separate explicit production authorization; HISTC-B/C/D remain gated by their own slice reviews. History-D remains a separate future task after all four C slices have exact-head review and final acceptance.

## 24. History-B dependency gate

The current gate is verified against the exact merged History-B authority:

```text
C0_HISTORY_B_FINAL_ACCEPTANCE_PASS=YES
HISTORY_B_MERGED_IN_CURRENT_MAIN=YES
HISTORY_B_IMPLEMENTATION_HEAD=86d3c065a6dacd9c12e5959a8cf055f84d522062
HISTORY_B_MERGE_SHA=a7d73f7bf0e1061a696516b86246bcffb08eae8b
```

Current History-B supports the exact producer-authorized continuity path in which a library-look producer copies existing `RevealedToComponent.playerIds` onto the new incarnation. That authority covers the documented `Expensive Taste`/face-down-exile path without inferring any new viewer. It does not authorize generic arbitrary hidden-zone continuity, alias reuse, or cross-incarnation relation inference.

## 25. Risks and open authoritative metadata dependencies

The following are explicit open dependencies, not implementation invitations:

1. **Semantic episode identity provider:** current Gym reset has no C-owned deterministic episode identity. A future caller/provider must supply a nonblank stable ID; wall-clock/UUID allocation is not acceptable for canonical bytes.
2. **Reference-candidate envelope:** History-A's public batch intentionally omits object references. A future internal envelope must bind each supported event/reference slot to before/after witnesses and producer-order evidence without changing the public A payload.
3. **Cross-incarnation relation authority:** current A+B have stamps and events but no complete typed relation-kind/audience witness. C must receive this authority or emit `NO_LINK`/`BLOCKED_ON_AUTHORITATIVE_METADATA`.
4. **Unordered/symmetric producer order:** every multi-object event family must state whether its order is semantic and public. Missing rank is a fail-closed condition.
5. **Face-down movement reveal evidence:** CR 708.9 requires a departing face-down object to be revealed; the future adapter must consume an authoritative event-time audience/identity witness rather than `ZoneChangeEvent.entityName`.
6. **History-B genericity boundary:** exact producer-authorized reveal preservation is supported; arbitrary hidden-zone continuity, alias reuse, and relation inference remain unsupported and must fail closed.
7. **Ability and non-card reference coverage:** existing `abilityKey`/`AbilityIdentity` is reusable where sufficient, but every event family needing an ability, emblem, token, or stack reference needs a typed supported candidate or remains unsupported.
8. **Commander physical-object history:** CR 903.3 designation and `CommanderPublicStateV1` are public semantic facts, not a physical alias or a History-D binding. Commander movement rows remain subject to ordinary C evidence.
9. **Snapshot schema:** current `SnapshotCodec` does not store C registry state. The future snapshot version must be explicit and unknown versions must fail closed.

## 26. Acceptance criteria

This plan is complete only because it freezes an answer to each required question:

- **What exactly is one semantic alias identifying?** One authorized Rules object incarnation for one perspective in one semantic episode.
- **When is an alias created?** After a committed, perspective-referenceable candidate passes event-time authority, ordering, and symmetry checks.
- **Who owns allocation?** The History-C immutable registry/projector seam, not Rules `GameState`, not observations, not replay fingerprints.
- **What authority makes an object referenceable?** Committed History-A source plus event-time `Visibility`, with merged History-B facts for hidden continuity and identity/order.
- **How is identity knowledge separated from referenceability?** Opaque aliases are allowed; identity is a per-occurrence disclosure upgrade on the same stamp/alias.
- **When is an alias retired?** New object stamp, lost individual continuity, or terminal token existence; retired aliases are never reused.
- **Can aliases survive hidden-zone transitions?** Only for the same incarnation (which a normal zone change does not preserve) or when merged History-B continuity plus a new alias/relation proves the transition; the accepted producer-authorized reveal-preservation path supports its exact move without alias reuse.
- **How are CR 400.7 new objects represented?** New alias per referenceable new stamp, with optional separate relation evidence.
- **How are legally known cross-zone relationships represented?** Two aliases plus a typed, perspective-authorized relation; never raw ID equality or alias reuse.
- **How are identical objects handled?** Use a producer/public distinction; otherwise fail closed with no per-object alias.
- **Why do hidden-only changes not alter visible alias bytes?** Hidden candidates never allocate; the allocator consumes only perspective-visible, ordered semantic candidates and keeps no hidden count/position input.
- **How do fork/reset/snapshot/restore behave?** Fork is non-authoritative and cannot mutate the parent; reset requires a new deterministic episode ID; snapshot/restore includes the versioned C registry state; unknown snapshot versions fail closed.
- **How does replay reproduce aliases?** The replay adapter invokes the same pure C transition function with the same seed, explicit episode ID, decisions, perspective, committed event order, and B evidence.
- **How do History-A and History-B feed History-C?** A supplies committed ordered events/classification and before/after witnesses; merged B supplies post-transition facts/epochs and continuity/invalidation authority, including exact producer-authorized reveal preservation; C validates and allocates.
- **What does History-C leave for History-D?** History entries, event-family/window/ordinal binding, sequence construction, Trajectory V1, and all learner/training concerns.
- **What metadata gaps still block implementation?** Deterministic episode identity, internal reference-candidate/order envelope, typed relation authority, symmetric-object distinctions, and face-down exit evidence.

The plan also satisfies the requested scope controls:

```text
PRODUCTION_CODE_CHANGED=0
TEST_CODE_CHANGED=0
LOCKED_DECKS_CHANGED=0
HISTORY_B_CHANGED=0
HISTORY_B_FINAL_ACCEPTANCE_PASS=YES
HISTORY_B_MERGED_IN_CURRENT_MAIN=YES
HISTORY_B_IMPLEMENTATION_HEAD=86d3c065a6dacd9c12e5959a8cf055f84d522062
HISTORY_B_MERGE_SHA=a7d73f7bf0e1061a696516b86246bcffb08eae8b
HISTORY_D_IMPLEMENTED=NO
TRAJECTORY_CHANGED=NO
OBSERVATION_CHANGED=NO
RULES_POLICY_CHANGED=NO
```

## 27. Plan delivery verification

This revalidation branch is docs-only and its diff against current `origin/main=a7d73f7...` must contain only the plan document:

```text
git diff --check
git status --short
git diff origin/main..HEAD --name-status
git diff origin/main..HEAD --stat
```

Expected scope after the single commit:

```text
one file: docs/superpowers/plans/2026-09-07-c0-history-c-perspective-semantic-references.md
production files: 0
test files: 0
locked decks: 0
History-B files: 0
History-D implementation: no
PR: none
```

The branch may be pushed to `origin` for independent review, but no pull request may be opened and no merge may be performed. Stop after the pushed docs-only commit.

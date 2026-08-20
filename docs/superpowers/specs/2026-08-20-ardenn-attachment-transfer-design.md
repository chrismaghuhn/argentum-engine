# #47 Generic Attachment Transfer for Selected Auras and Equipment

Status: Way A architecture approved; `SPEC_REVIEW_PASS = NO` pending this
revision's review.

## Decision

Implement Way A as a reusable, generic attachment-transfer primitive. The
primitive operates on an explicitly selected collection of battlefield Auras
and Equipment and one already-selected permanent-or-player destination. It is
not an Ardenn-specific effect, and it does not widen the existing
Equipment-to-creature primitive into an Aura/Equipment catch-all.

The selected collection is filtered and chosen before execution:

```text
controlled battlefield permanents
    -> CollectionFilter.AttachableTo(destination)
    -> explicit chooseAnyNumber
    -> AttachCollectionToTargetEffect(selected, destination)
```

The final effect evaluates the selected set as one multi-object instruction.
It must not implement the operation by calling `ForEachInCollection` around a
single-object attach effect.

## Authority and baseline

- Issue: #47, `Add generic legal attachment transfer for selected Auras and Equipment`.
- FrozenBaseline hash: `6ff9ded1403d59ac`.
- Implementation base: `origin/main` at `470e6d49c541b9af43253a07671d50ae6af268be`.
- Historical RED characterization: commit
  `2c70517794318d4f5b63dc4fab3a488aff3a0d77`, which intentionally stops after
  the explicit any-number selection because the generic execution primitive
  was missing. The historical `ArdennScenarioTest` is evidence for the
  contract, not permission to add an Ardenn-specific handler.
- Rules authority: the live [Wizards Comprehensive Rules page](https://magic.wizards.com/en/rules).
  The linked TXT filename is not a durable source identifier: cache and
  propagation can make different clients observe different resolved files. At
  implementation start, resolve the TXT link from that page and record the
  exact resolved URL, effective date, and SHA-256 in the implementation
  evidence. That execution-time record is the authority for the run.

Relevant rules contracts are CR 608.2f for multi-object instructions and the
relative ordering of actions on same-controller objects, CR 101.4c for a
player choosing the order of multiple simultaneous choices when no order is
specified, CR 701.3 for attaching and unattaching, CR 301.5 for Equipment
attachment, CR 303.4 for Aura enchant restrictions, and CR 613.7e/613.7m for
new timestamps and their APNAP-relative order. Rule numbers are recorded here
only after checking the live rules document; they are not a substitute for
checking the file again at implementation start.

## Required audit result: `ForEachInCollection`

`ForEachExecutor` snapshots the collection, but then evaluates the body in a
loop that threads the resulting `GameState` from one item to the next:

```text
state --body(A)--> stateA --body(B)--> stateB --body(C)--> stateC
```

That is a useful sequential composition primitive. It is not the required
CR 608.2f batch boundary for this operation. A first attachment could change
projected characteristics or the attachment links observed by a later body
execution, making a collection-order-dependent result. Therefore #47 must
introduce the smallest generic batch attachment execution path and must not
hide sequential semantics behind `ForEachInCollection`.

## Semantic contract

### Selection domain

1. The source collection contains current battlefield permanents controlled by
   the ability controller. Control, not ownership, determines membership.
2. `CollectionFilter.AttachableTo(destination)` publishes exactly the current
   legal candidates for the resolved destination. It does not silently choose
   any object and it does not sort or truncate the collection.
3. The explicit `chooseAnyNumber` decision may select any subset, including the
   empty subset. The selected collection is treated as a set of selected
   object identities; no `first()`, implicit ordering, or “take the first legal
   attachment” behavior is allowed.
4. The destination may be a player or a permanent controlled by any player.
   Opponent-controlled destinations are not excluded by the source-control
   filter.
5. Attachment type membership is read from the projected characteristics at
   the decision boundary. A candidate is in the domain when it is an Aura or
   Equipment under the applicable rules; the implementation must not encode
   this as an exclusive Aura-versus-Equipment branch.

### Per-attachment legality

The filter and the execution revalidation share one generic legality seam, but
the Aura and Equipment rules remain distinct and are evaluated independently:

- If a candidate is an Equipment, it must satisfy the legal-creature
  destination rule under the engine's Equipment rules. A player or
  noncreature destination is therefore not legal for that applicable part of
  the candidate's attachment legality. Equipment-specific restrictions such
  as reconfigure are honored only where the existing SDK models them; #47 must
  not infer Aura rules from an Equipment's `Equip` ability or vice versa.
- If a candidate is an Aura, it delegates to the Aura host/enchant legality
  represented by the card definition and projected state. Its `enchant`
  restriction, protection, `CANT_BE_ENCHANTED`, and other existing host
  restrictions remain effective. A player is a legal Aura destination only
  when that Aura's enchant ability permits a player; a permanent is legal only
  when that Aura's enchant ability permits that permanent.
- If a projected candidate is both an Aura and an Equipment, all applicable
  Aura and Equipment legality predicates must pass. There is no `if (Aura)
  ... else if (Equipment)` shortcut, and satisfying one attachment type does
  not waive the other type's restrictions.
- Ordinary target restrictions are not globally disabled. The already-selected
  destination is resolved and revalidated through the normal target contract;
  the attachment-specific check then validates each candidate against that
  destination. The non-targeted Aura-host lookup behavior in
  `AuraHostLegality` must not become a blanket bypass for ordinary effect
  targeting.

The candidate predicate is a projection of the state before the batch is
applied. It must use projected characteristics for battlefield legality. The
same predicate is run again at resolution so a stale selection cannot attach
an Aura or Equipment that is no longer legal. A submitted selection containing
an ID outside the published any-number decision domain fails closed; it must
not cause the executor to widen the domain or silently accept an unadvertised
object.

### Batch execution and revalidation

`AttachCollectionToTargetEffect` receives the selected collection and the one
resolved destination. Its executor must:

1. Resolve and validate the destination. If the destination is no longer a
   valid target, no selected attachment moves.
2. Validate every selected identity against the frozen selection domain and
   the current state: it must still be on the battlefield, still be controlled
   by the ability controller, still be an Aura and/or Equipment, and still
   satisfy every applicable Aura/Equipment legality predicate for the same
   destination. An ID outside the frozen domain is rejected fail-closed. A
   formerly valid ID that became stale is omitted from the move plan and does
   not attach.
3. Evaluate all surviving candidates against the same pre-application
   state/projected state and build a host-changing move plan. An attachment
   already on the destination is a no-op and is not part of that plan.
4. If the move plan is empty, finish without state mutation. If it contains
   one object, commit it directly. If it contains two or more objects, pause
   at the relative-timestamp ordering boundary described below before
   committing any attachment mutation.
5. After any required ordering response, revalidate the destination and every
   planned object once more. Invalid or stale objects are omitted, while the
   surviving objects retain their submitted relative order. Then apply all
   surviving legal moves as one state transition. An invalid selected
   candidate does not prevent other selected candidates from moving. This is
   the “do as much as possible” behavior for a multi-object instruction.
6. Preserve every non-selected attachment and every candidate rejected by
   revalidation. Moving an attachment changes neither its owner nor its
   controller.

The operation must not re-evaluate candidate legality after each individual
move. This is the material distinction from a sequential
`ForEachInCollection` body. If the implementation needs a helper for one
attachment, that helper may be reused by the batch executor only as a pure
legality calculation and a mutation step over the batch plan; it must not
turn the plan into `A -> stateA -> B` execution.

### Relative timestamp ordering boundary

All attachments that actually change host become attached as part of the same
batch action, and each receives a new attachment timestamp under CR 701.3c and
613.7e. If two or more moved objects receive timestamps simultaneously, their
relative timestamp order must follow CR 613.7m:

- APNAP determines the order of objects controlled by different players;
- within the objects controlled by one player, the player chooses their
  relative order;
- for the current #47 source domain, every selected attachment is controlled
  by the resolving ability's controller, so that controller has a real choice
  whenever at least two host-changing attachments remain after revalidation.

The relative order is a second decision boundary after `chooseAnyNumber`. For
#47 it must use the existing generic `OrderObjectsDecision` /
`OrderedResponse` machinery, carried by a serializable continuation; a new
ad-hoc ordering response is out of scope. The
ordering domain contains exactly the currently legal, host-changing selected
IDs at the point the order decision is created. The list used to present that
domain is only membership/presentation data; no order is inferred from it.
The response must contain every domain ID exactly once, with the first
returned ID representing the earlier relative timestamp. No default answer is
permitted.

On resume, IDs outside the frozen ordering domain fail closed. IDs that were
inside it but became illegal, left the battlefield, changed controller, lost
their applicable Aura/Equipment type, or became same-host no-ops are omitted
from the final move plan; the remaining IDs keep the player's submitted
relative order. The target and the original any-number domain are also
revalidated on resume. The continuation must persist the target, source/
ability-controller identity, selected IDs, frozen selection domain, frozen
ordering domain, and all information needed to reproduce this validation
through pause, serialization, fork, and replay.

### Links, events, and timestamps

For each attachment that actually moves to a different destination:

- remove its old reverse link and emit the normal
  `PermanentUnattachedEvent` when it was previously attached;
- write the new `AttachedToComponent` and add exactly one reverse link in the
  destination's `AttachmentsComponent`;
- emit the normal `PermanentAttachedEvent`;
- stamp the moved attachment with a fresh attachment timestamp consumed by the
  continuous-effect layer.

The attachment timestamp is separate from the battlefield-entry identity
stamp. A same-host no-op and an illegal or stale candidate do not receive a
new timestamp. The chosen relative order must be encoded as a strict
timestamp order consumed by the layer system: unique monotonic scalar values
in the chosen order are acceptable, or a richer timestamp representation may
be introduced if `EffectSorter` and serialization compare and preserve it.
Equal `TimestampComponent.timestamp` values are insufficient when they leave
the existing stable sort/list order as the semantic tie-breaker. The
implementation must not derive the relative order from selected-collection
order, entity IDs, map iteration, executor registration order, or another
deterministic fallback. The global state timestamp must advance consistently
with the chosen representation, and `TimestampComponent`
documentation/tests must no longer claim that attachment reattachments are
never stamped.

State mutation is batch-atomic even though the returned event list contains
the normal per-attachment detach/attach events. Event-list serialization may
use a stable canonical entity ordering, but neither that order nor the
selected collection's input order may decide legality, state, timestamp order,
or rules-visible trigger semantics. The player's explicit relative-timestamp
choice is the only permitted order input. If the existing event/trigger
pipeline cannot preserve batch-independent state mutation with the current
event representation, stop and surface that as a separate design blocker; do
not smuggle collection order into #47.

## API shape

The exact package/class names remain subject to the implementation plan, but
the public shape must have these properties:

- a reusable `CollectionFilter.AttachableTo(destination)` (or an equivalent
  generic name in the existing filter vocabulary);
- a reusable `AttachCollectionToTargetEffect` (or an equivalent generic batch
  name) that accepts an already-selected collection and a resolved
  permanent-or-player destination;
- no Ardenn/card-name parameter, no controller-specific handler, and no
  implicit selection inside the attachment executor;
- one shared attachment-legality seam used by candidate publication and
  resolution-time revalidation;
- a second decision boundary using the existing generic
  `OrderObjectsDecision`/`OrderedResponse` path when two or more
  host-changing attachments need CR 613.7m relative ordering;
- a serializable continuation that freezes the original selection domain,
  selected IDs, target, and ordering domain, then revalidates all of them on
  resume, fork, and replay;
- registration in the normal SDK/executor facade boundaries, with
  `docs/card-sdk-language-reference.md` updated in the same change if the
  public SDK vocabulary changes.

The existing Equipment-only primitives remain valid for their narrower use
cases. #47 does not change them into a generic Aura/Equipment API unless a
small shared helper is needed to guarantee identical detach/attach bookkeeping
and events.

## Test contract

Tests must be engine-level and generic, with the historical Ardenn scenario
used only as a contract fixture if needed. A new engine mechanic test file may
cover all attachment cases; this is not a batch of card implementations.

Required cases:

1. A mixed own Aura + Equipment collection exposes both when the same
   destination is legal for both.
2. An Aura with an incompatible enchant restriction is excluded, and an
   opponent-controlled attachment is excluded from the source collection.
3. A mixed explicit subset moves to an opponent-controlled permanent without
   moving an unselected attachment.
4. A player destination admits only Auras whose enchant ability permits that
   player; Equipment is excluded.
5. Resolution-time revalidation rejects a stale candidate while still moving
   other legal selected candidates.
6. Ownership and controller components remain unchanged.
7. Existing-host reverse links are removed/added without duplicates, and the
   expected detach/attach events are emitted exactly once per moved object.
8. Moved attachments receive fresh strictly ordered timestamps; illegal and
   same-host candidates do not. Equal timestamp values must not leave a
   stable list order as the semantic tie-breaker.
9. Two or more host-changing attachments create an explicit generic ordering
   decision; one or zero host-changing attachments do not.
10. The player's submitted relative order becomes the timestamp order, while
    changing only the input collection order does not. No entity-ID, map,
    executor, or collection-order fallback is accepted.
11. An ordering response with an ID outside the frozen ordering domain fails
    closed. A formerly valid but stale ID is omitted on resume while surviving
    IDs retain the submitted relative order.
12. The ordering continuation round-trips through serialization, fork, and
    replay with the same decision domain and resulting timestamp order.
13. Empty selection and already-correct attachment are no-ops.

The focused mechanic suite must be followed by the affected attachment,
targeting, projection, trigger, and scenario regressions selected by the
`verify` skill. The exact test commands and any required CI checks belong in
the implementation plan after this specification is approved.

## Scope exclusions and gates

This change does not:

- add an Ardenn-specific handler or card definition;
- modify the global sequential semantics of `ForEachInCollection`;
- broaden Equipment legality to Aura legality or disable ordinary targeting
  restrictions;
- change decklists, locked fixtures, training/B0/B1/B2/C0, Wave C blockers, or
  #55 card work;
- perform a broad timestamp-system rewrite; a small strict-order timestamp
  representation or `EffectSorter` comparison change is in scope if required
  to preserve CR 613.7m;
- claim coverage or hosted-CI success before the exact implementation head is
  independently verified.

The implementation is landable only when all of the following hold:

```text
WAY_A                         APPROVED
ATTACH_LEGALITY               GENERIC + PER ATTACHMENT TYPE
SELECTION_DOMAIN              EXPLICIT AND COMPLETE
EXECUTION_REVALIDATION        REQUIRED
OWNER/CONTROLLER              PRESERVED
PLAYER_TARGETS                SUPPORTED
OPPONENT_PERMANENTS           SUPPORTED
NORMAL_ATTACH_EVENTS          REQUIRED
NO_COLLECTION_ORDER_POLICY    REQUIRED
MULTI_ATTACH_SEMANTICS        CR 608.2f-COMPATIBLE
CR_613_7E_NEW_TIMESTAMP        REQUIRED
CR_613_7M_RELATIVE_ORDER       REQUIRED
EXTERNAL_ORDER_DECISION        REQUIRED WHEN >=2 MOVE
DOMAIN_REVALIDATION            BATTLEFIELD + CONTROLLER + ORIGINAL DOMAIN + LEGALITY
DUAL_AURA_EQUIPMENT            ALL APPLICABLE PREDICATES
ARDENN_SPECIFIC_HANDLER       NO
```

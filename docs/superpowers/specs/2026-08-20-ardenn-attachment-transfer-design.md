# #47 Generic Attachment Transfer for Selected Auras and Equipment

Status: architecture approved; specification submitted for user review.

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
- Campaign baseline: `6ff9ded1403d59ac`.
- Implementation base: `origin/main` at `470e6d49c541b9af43253a07671d50ae6af268be`.
- Historical RED characterization: commit
  `2c70517794318d4f5b63dc4fab3a488aff3a0d77`, which intentionally stops after
  the explicit any-number selection because the generic execution primitive
  was missing. The historical `ArdennScenarioTest` is evidence for the
  contract, not permission to add an Ardenn-specific handler.
- Rules authority: the live [Wizards Comprehensive Rules page](https://magic.wizards.com/en/rules)
  currently links `MagicCompRules 20260819.txt`; the linked document reports
  an effective date of August 7, 2026. The live link must be rechecked when
  implementation is executed.

Relevant rules contracts are CR 608.2f for multi-object instructions, CR
701.3 for attaching and unattaching, CR 301.5 for Equipment attachment, CR
303.4 for Aura enchant restrictions, and CR 613.7 for continuous-effect
timestamps. Rule numbers are recorded here only after checking the live rules
document; they are not a substitute for checking the file again at execution
time.

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

### Per-attachment legality

The filter and the execution revalidation share one generic legality seam, but
the Aura and Equipment rules remain distinct:

- An Equipment may be attached only to a legal creature destination under the
  engine's Equipment rules. A player or noncreature destination is therefore
  not an Equipment candidate. Equipment-specific restrictions such as
  reconfigure are honored only where the existing SDK models them; #47 must
  not infer Aura rules from an Equipment's `Equip` ability or vice versa.
- An Aura delegates to the Aura host/enchant legality already represented by
  the card definition and projected state. Its `enchant` restriction,
  protection, `CANT_BE_ENCHANTED`, and other existing host restrictions remain
  effective. A player is a legal Aura destination only when that Aura's
  enchant ability permits a player; a permanent is legal only when the Aura's
  enchant ability permits that permanent.
- Ordinary target restrictions are not globally disabled. The already-selected
  destination is resolved and revalidated through the normal target contract;
  the attachment-specific check then validates each candidate against that
  destination. The non-targeted Aura-host lookup behavior in
  `AuraHostLegality` must not become a blanket bypass for ordinary effect
  targeting.

The candidate predicate is a projection of the state before the batch is
applied. It must use projected characteristics for battlefield legality. The
same predicate is run again at resolution so a stale selection cannot attach
an Aura or Equipment that is no longer legal.

### Batch execution and revalidation

`AttachCollectionToTargetEffect` receives the selected collection and the one
resolved destination. Its executor must:

1. Resolve and validate the destination. If the destination is no longer a
   valid target, no selected attachment moves.
2. Snapshot the selected identities and evaluate every selected candidate's
   attachment legality against the same pre-application state/projected state.
3. Apply all currently legal moves as one state transition. An invalid
   selected candidate stays where it is; it does not prevent other selected
   candidates from moving. This is the “do as much as possible” behavior for
   a multi-object instruction.
4. Treat an attachment already on the destination as a no-op: it receives no
   detach event, no attach event, and no new attachment timestamp.
5. Preserve every non-selected attachment and every candidate rejected by
   revalidation. Moving an attachment changes neither its owner nor its
   controller.

The operation must not re-evaluate candidate legality after each individual
move. This is the material distinction from a sequential
`ForEachInCollection` body. If the implementation needs a helper for one
attachment, that helper may be reused by the batch executor only as a pure
legality calculation and a mutation step over the batch plan; it must not
turn the plan into `A -> stateA -> B` execution.

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
stamp. A batch uses one fresh timestamp for all attachments that change host;
it must not manufacture a relative order from the selected collection. A
same-host no-op and an illegal candidate do not receive a new timestamp. The
global state timestamp must advance consistently with the repository's
existing timestamp conventions, and `TimestampComponent` documentation/tests
must no longer claim that attachment reattachments are never stamped.

State mutation is batch-atomic even though the returned event list contains
the normal per-attachment detach/attach events. Event-list serialization may
use a stable canonical entity ordering, but the selected collection's input
order must not decide legality, state, timestamps, or rules-visible trigger
semantics. If the existing event/trigger pipeline cannot preserve this
batch-independent behavior with the current event representation, stop and
surface that as a separate design blocker; do not smuggle collection order
into #47.

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
8. Moved attachments receive the fresh attachment timestamp; illegal and
   same-host candidates do not.
9. Reordering the selected collection produces the same resulting state,
   timestamps, and rules-visible event semantics.
10. Empty selection and already-correct attachment are no-ops.

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
- perform a broad timestamp-system rewrite;
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
ARDENN_SPECIFIC_HANDLER       NO
```

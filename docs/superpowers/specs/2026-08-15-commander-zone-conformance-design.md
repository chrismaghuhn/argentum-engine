# ARG-02.1 Commander Zone-Movement Conformance

**Status:** Hardened after ARG-02.1R review
**Date:** 2026-08-15
**Branch:** `agent/a2-1-commander-zone-conformance`

## Problem

The current engine treats all four non-command destinations as the same Commander
state-based-action choice. That is not the current Comprehensive Rules shape:

- CR 903.9a is the post-zone state-based action for a commander that entered a
  graveyard or exile since the last state-based-action check.
- CR 903.9b is an optional replacement effect applied before a commander would
  enter its owner's hand or library. It may apply more than once to the same
  event, and the owner must retain control of the choice.

The current `CommanderZoneChoiceCheck` scans graveyard, exile, hand, and library,
while `ZoneTransitionService` is synchronous and `ZoneMovementUtils` only has a
deterministic `alwaysDivertToCommand` preference. The existing serializable
`PendingGameEvent` / `ReplacementEffectProcessor` / continuation machinery is the
right reusable seam, but it currently has no zone-change pending-event adapter.
This is classified as `REUSABLE_REPLACEMENT_DECISION_GAP`.

The official rules snapshot used for this design is the current TXT linked from
the Wizards rules page. The live page currently links the `20260808` filename;
the document itself says it is effective August 7, 2026:
<https://media.wizards.com/2026/downloads/MagicCompRules%2020260808.txt>.

## Goals

1. Make the 903.9a and 903.9b timing distinction observable in the engine.
2. Keep the normal 903.9b choice optional and owner-controlled. A dedicated
   deterministic/headless preference may answer it with YES, but only inside
   the normal replacement pipeline, never by bypassing CR 616.
3. Reuse the central replacement and continuation pipeline rather than adding a
   Commander-only decision stack.
4. Keep the canonical zone-transition atom responsible for cleanup, LKI,
   actual-destination events, triggers, ownership, and command-zone identity.
5. Preserve `alwaysDivertToCommand` as a deterministic/headless answer policy
   without making the normal Commander path deterministic.
6. Leave combat ordering, damage assignment, gym/AI/observation code, and card
   pools outside the change.

## Non-goals

- No combat or damage-assignment changes.
- No broad migration of unrelated replacement domains.
- No changes to commander tax, commander damage, casting legality, or deck
  validation beyond regression coverage.
- No golden re-blessing or unrelated cleanup.

## Chosen architecture

### 1. Add a reusable pending zone-change event

Add a serializable zone-change pending-event shape to the existing
`PendingGameEvent` hierarchy. It carries the entity, source and requested
destination, owner/affected player, and the existing zone-entry options. It
matches `EventPattern.ZoneChangeEvent`, supports the applicable redirect
outcomes, and can provide a perform-continuation that invokes the canonical
zone-transition atom only after replacement resolution is complete.

The synchronous mutation code remains a single canonical core. The new wrapper
is the decision-capable entry point used by effect, cost, stack, and other
player-facing zone-movement paths. Already-resolved internal paths may call the
core with an explicit post-replacement flag; they must not accidentally run the
same replacement twice.

### 2. Feed the Commander rule into the existing replacement pipeline

Represent the rule-owned 903.9b option as a serializable replacement candidate
in the same `GatheredReplacement` / replacement-identity pipeline used by other
optional replacements. The candidate is gathered only when all of these are
true:

- the event's moving object has the real `CommanderComponent`;
- the format uses commanders;
- the requested destination is the owner's hand or library;
- `alwaysDivertToCommand` does not suppress the candidate; it is represented
  as an automatic YES inside the normal replacement pipeline.

There is deliberately no source-zone exclusion: CR 903.9b says “from
anywhere”, including a command-zone-to-hand/library move.

The candidate redirects to the command zone when accepted. Declining does not
turn 903.9b into an ordinary once-per-event replacement: the explicit CR 903.9b
exception to CR 614.5 must be preserved. The bookkeeping records a decline only
for the current unchanged event shape, so the same unchanged hand/library move
does not immediately re-prompt, while a later replacement-modified event may
make 903.9b eligible again. The candidate is a rules-level input to the generic
processor, not a card-facing callback or a second Commander decision subsystem.

### 3. Put the decision before the physical transition

The wrapper must not add the card to hand or library before the 903.9b answer.
On YES, the final transition is to the command zone. On NO, the final
transition is to the requested hand/library destination. In both cases there is
exactly one physical transition and one `ZoneChangeEvent`, after which normal
trigger and continuation processing resumes.

The existing `ZoneMovementUtils` replacement behavior remains in the canonical
transition path, with the pending-event adapter carrying enough state to avoid
reapplying a replacement that has already been consumed. Battlefield exit
cleanup, last-known information, owner-based destination keys, commander tax
identity, and event/trigger semantics remain owned by
`ZoneTransitionService`.

### 4. Narrow the SBA check to CR 903.9a

`CommanderZoneChoiceCheck` will inspect only graveyard and exile. Hand and
library will no longer be treated as SBA locations. The existing marker remains
the per-zone-entry guard for a declined 903.9a choice and is still stripped by
the canonical zone transition.

`alwaysDivertToCommand` remains an explicit deterministic mode. For hand/library
events it is an automatic YES for the Commander replacement choice inside the
replacement pipeline; it must not bypass CR 616 ordering when other replacement
effects are applicable. For graveyard/exile it is an automatic YES only after
the requested physical move, through the 903.9a SBA path. It is limited to the
same Commander identity and relevant zone-change destinations and must not alter
ordinary non-Commander objects or token copies.

### 5. ARG-02.1R hardening corrections

- External `CastSpell` and `ActivateAbility` actions reject all serialized
  internal resume markers. Only trusted continuation resumers may re-enter the
  handlers with those fields populated.
- CR 616 ordering uses the moving object's projected controller, falling back
  to its owner; the 903.9b YES/NO choice remains with the Commander owner.
- Optional 903.9b continuations retain the actual ordinary replacement identity
  set already applied in the current chain. A decline is state-local, while the
  explicit 903.9b exception still permits reapplication after an event change.
- Ordered `MoveCollection` resumes retain the original effect context and route
  every physical card movement through `MoveCollectionExecutor` and
  `ZoneTransitionService`.
- Stack-to-hand/library resolution keeps stack components and stack membership
  coherent until the final physical transition; cleanup is performed by the
  canonical transition atom.

## Verification shape

Tests will be written first and kept in the rules-engine module. The focused
matrix covers graveyard/exile SBA choices, hand/library pre-zone replacement
choices, owner versus controller, external pending decisions, no duplicate
prompt, non-Commander/copy isolation, replacement interaction, actual
zone-change events, and deterministic state forks. Existing Commander setup,
tax, damage-loss, casting, and multiplayer tests remain regression gates.

The documented final verification will use the repository's `just` gates. If
the local environment still lacks `just`, the equivalent locked Gradle-wrapper
fallback will be recorded explicitly as a tooling limitation rather than
reported as a `just` run.

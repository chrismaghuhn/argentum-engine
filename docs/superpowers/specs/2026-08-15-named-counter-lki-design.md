# Named Counter + Marked-Permanent LKI Design

## Status

This design records the ARG-DECK-02 source-audit result. The reusable
named-counter / marked-permanent LKI primitive already exists in the current
fork baseline. This milestone adds the missing central `BOUNTY` vocabulary and
characterization coverage; it does not add a second LKI subsystem.

## Audit classification

`FEATURE_ALREADY_EXISTS`

Evidence in the current baseline:

- `ZoneTransitionService` captures the complete positive-counter map into
  `EntitySnapshot.counters` when an object leaves the battlefield.
- `EntitySnapshot` stores arbitrary counter names and counts, not a selected
  counter whitelist.
- `GameObjectFilter.withCounter(counterType)` composes the serializable
  `StatePredicate.HasCounter` predicate.
- `TriggerMatcher.matchesZoneChangeTrigger` evaluates `HasCounter` against the
  frozen `ZoneChangeEvent.lastKnown.counters` for battlefield exits and does
  not fall back to the new-zone object.
- The same matcher already uses last-known controller data for control-relative
  filters, and `DeathAndLeaveTriggerDetector` evaluates simultaneous leaves
  with each event's own snapshot.
- `RaybladeTrooperScenarioTest` is an existing card-level precedent for a
  named counter on a dying permanent; `LookBackTriggerTest` is an existing
  source-leaves-simultaneously precedent.

## Rules target

The official Comprehensive Rules snapshot used for this design is the Wizards
rules text effective August 7, 2026, downloaded August 15, 2026:

- CR 122.1 defines counters as markers on objects or players.
- CR 122.2 says counters are not retained when an object moves from one zone to
  another.
- CR 400.7 makes the object in the new zone a new object.
- CR 603.6 and 603.10/603.10a provide the zone-change and look-back behavior
  needed for leaves-the-battlefield triggers.
- CR 608.2h supplies last-known information when the object or source is no
  longer in the expected zone.

The eventual Chevill shape is therefore a normal zone-change trigger whose
object filter is equivalent to `Permanent.opponentControls().withCounter(BOUNTY)`.
The named counter itself is the mark. No source-relative identity tracker is
needed: a permanent that changes zones is a new object, and the trigger reads
the old object's frozen snapshot.

## Existing architecture checkpoint

```text
ZoneTransitionService
  -> EntitySnapshot.counters: Map<String, Int>
  -> ZoneChangeEvent.lastKnown
  -> TriggerMatcher.matchesZoneChangeTrigger
  -> StatePredicate.HasCounter
  -> GameObjectFilter.withCounter(...)
```

The new production change is limited to the normal counter vocabulary path:

- `CounterType.BOUNTY`
- `Counters.BOUNTY = "bounty"`
- client enum/display/icon/passive-counter wiring required by the repository
  counter contract
- SDK language-reference documentation

No new snapshot field, event field, condition, tracker, or card definition is
introduced.

## Acceptance matrix

The new engine-level tests cover the following cases. Existing source behavior
is characterized rather than reimplemented.

| ID | Acceptance case | Expected evidence |
| --- | --- | --- |
| LKI-01 | A dying object with `BOUNTY = 1` retains it in its snapshot and matches | PASS |
| LKI-02 | A different counter does not match `BOUNTY` | PASS |
| LKI-03 | The leave predicate does not depend on current-zone state | PASS |
| LKI-04 | Removing BOUNTY before death makes the predicate false | PASS |
| LKI-05 | Multiple counter kinds inspect only the requested kind | PASS |
| LKI-06 | Simultaneous leaves keep independent per-object answers | PASS |
| LKI-07 | Last-known controller is preserved for opponent-relative matching | PASS |
| LKI-08 | A source leaving simultaneously can still observe the marked death | PASS |
| LKI-09 | The named filter and counter vocabulary round-trip through serialization | PASS |
| LKI-10 | Existing unrelated named-counter and any-counter paths remain unaffected | PASS |

## Explicit non-goals

- No `Chevill, Bane of Monsters` definition.
- No `Akiri, Fearless Voyager` definition.
- No Commander zone-movement or combat-damage changes.
- No broad counter-system rewrite or string side channel.
- No persistent cross-zone identity semantics.
- No Gym, AI, observation, replay, or ML changes.

## Failure policy

The matcher must remain fail-closed when a battlefield-exit event has no LKI
snapshot. It must not inspect the new-zone object, search by entity id, or
infer that a counter existed previously.

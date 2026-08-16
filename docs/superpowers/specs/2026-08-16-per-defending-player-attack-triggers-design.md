# Per-defending-player attack trigger semantics

## Status

Approved design for Issue #16 / `ARG-RULES-ATTACK-GROUPING`.

This change adds a reusable trigger primitive for effects whose Oracle meaning
is “Whenever you attack a player with one or more [qualified] creatures”. It
does not implement Akiri, draw behavior, or any other card-specific behavior.

## Problem

`AttackersDeclaredEvent` currently exposes the attackers and an aggregate set
of attackers whose target is a player, but it does not preserve the target of
each attacker. `YouAttackEvent` is intentionally declaration-wide and its
matcher returns a Boolean, so the current trigger path cannot distinguish:

```text
A1 -> Player B
A2 -> Player B
A3 -> Player C
```

from one aggregate attack. The required result is one trigger for B and one
trigger for C after filtering, not one trigger per creature and not one trigger
for the declaration.

## Current authoritative data

`AttackPhaseManager` receives the legal attack declaration as
`Map<EntityId, EntityId>`, where each value is the actual declared attack
target. It stamps the same target into `AttackingComponent.defenderId`.

The declaration map is the authority at event creation time. The new event
data is a replay-safe snapshot of that authority; no second live combat target
store is introduced. Existing combat legality and the existing component remain
unchanged.

## Design

### Event snapshot

Add an immutable serializable value type:

```kotlin
@Serializable
data class DeclaredAttack(
    val attackerId: EntityId,
    val defenderId: EntityId,
)
```

`AttackersDeclaredEvent` gains:

```kotlin
val declaredAttacks: List<DeclaredAttack> = emptyList()
```

The existing `attackers`, `attackerNames`, `attackingPlayerId`,
`firstTimeAttackers`, and `attackersAgainstPlayer` fields retain their current
meaning. All map-derived event collections are emitted in canonical attacker
order, so their serialized order is independent of the declaration map's
iteration order. The new list is created from the declaration map in canonical
`(attackerId, defenderId)` order. No map or set iteration order is used for
serialized event data or trigger-group order.

An absent field in a historical serialized event decodes to an empty list.
The new matcher treats an empty `declaredAttacks` list as “no per-player target
information” and returns no per-player matches. It never reconstructs targets
from `attackers`, `attackersAgainstPlayer`, a controller, or live combat
state. This makes old payloads fail closed rather than inventing defender
mappings. The same empty result is correct for a new declaration with no
attackers.

### Explicit SDK event pattern

Add a new serializable event pattern:

```kotlin
@Serializable
@SerialName("YouAttackPlayerEvent")
data class YouAttackPlayerEvent(
    val minAttackers: Int = 1,
    val attackerFilter: GameObjectFilter? = null,
) : EventPattern
```

`minAttackers` is evaluated independently for each attacked player after the
attacker filter is applied. Therefore, with `minAttackers = 2`, two qualifying
attackers against B match B, while one qualifying attacker against B and one
against C match neither player.

Expose the pattern through a generic trigger facade such as
`Triggers.YouAttackPlayerWithFilter(filter)`. No Akiri-specific name,
equipment check, or effect is introduced. Existing `YouAttack` and
`YouAttackWithFilter` continue to construct `YouAttackEvent` and remain
declaration-wide.

### Matching and multiplicity boundary

The matcher gains a result-producing operation for the new pattern, conceptually
`matchingAttackedPlayers(...) : List<EntityId>`:

1. Read the event's `declaredAttacks` snapshot.
2. Evaluate `attackerFilter` against the existing projected declaration-time
   state path and `PredicateContext`.
3. Retain only entries whose `defenderId` is an actual player entity in the
   current player turn order. A planeswalker, battle, or other non-player
   target is not converted through its controller or protector.
4. Group qualified entries by `defenderId`.
5. Apply `minAttackers` to each group.
6. Return the matching player IDs in deterministic order.

The ordinary Boolean matcher may use whether this result is non-empty for
indexing/eligibility, but `TriggerDetector` consumes the complete result and
creates one `PendingTrigger` per returned player. The multiplicity is therefore
at the generic match/detection boundary and is not simulated by duplicating an
already-accepted Boolean trigger.

Each emitted `PendingTrigger` explicitly receives:

```kotlin
TriggerContext(triggeringPlayerId = attackedPlayer)
```

`TriggerContext.fromEvent(AttackersDeclaredEvent)` remains unchanged and empty.
The per-player path must not reinterpret that global helper.

### Ordering and existing trigger behavior

The per-player result is deterministically ordered before being added to the
existing trigger collection. The normal APNAP/simultaneous-trigger sorting
pipeline remains responsible for final ordering and any controller ordering
choice. No direct resolution or unordered `HashMap`/`HashSet` iteration is
introduced.

Only `YouAttackPlayerEvent` opts into per-player multiplicity. The following
remain unchanged:

- ordinary `YouAttackEvent` declaration-wide cardinality;
- `AttackEvent` per-attacker cardinality;
- existing attack-with-filter cards using `YouAttackWithFilter`;
- combat-damage trigger detection;
- delayed and duplicate-trigger paths, which must recognize the new event
  pattern without collapsing its per-player result.

## Serialization, fork, and replay safety

`DeclaredAttack` and the new event pattern are serializable. The existing
`GameEvent` polymorphic registration continues to cover
`AttackersDeclaredEvent`; no schema-version change is expected.

Focused tests will cover:

- new event serialize/decode equality and canonical ordering;
- historical payloads without `declaredAttacks` producing no invented
  per-player matches;
- equivalent immutable/forked state and equivalent declaration data producing
  identical player grouping and contexts.

## Test-first acceptance scope

A synthetic test ability, independent of Akiri, will cover:

- one qualifying attacker to one player;
- multiple qualifying attackers to the same player deduplicating to one;
- distinct attacked players producing distinct triggers;
- three attackers split B/B/C producing two triggers;
- planeswalker exclusion;
- mixed player/non-player targets;
- mixed qualification;
- all non-qualifying attackers;
- no attackers;
- `TriggerContext.triggeringPlayerId` binding for each emitted trigger.

Representative existing declaration-wide, per-attacker, and combat-damage
triggers will be run as regressions. No Akiri `CardDefinition` is added.

## Scope boundary

Expected production changes are limited to attack event data, the generic SDK
event pattern/facade, trigger indexing/matching/detection, serialization tests,
and the SDK language reference. Combat legality, damage, Commander rules,
Chevill, Gym, replay policy, card corpus, and Akiri implementation are out of
scope.

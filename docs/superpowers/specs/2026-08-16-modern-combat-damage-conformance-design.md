# A2.2 Modern Combat Damage Conformance

## Decision

Implement A2.2 as one coherent combat-resolution change on top of the existing
`CombatResolutionDecision` graph. The graph is already the correct integration
boundary for collecting all assignments before damage is applied; the unsafe
parts are the remaining order components, lethal-first defaults, and validator
rules around that graph.

The implementation keeps the serialized order components, `OrderBlockers`
action, and response order maps solely as decode/replay compatibility surfaces.
Current gameplay never emits, stores, reads, or evaluates them. The modern
graph is authoritative for every new combat-damage step.

## Semantics

1. Every source in the combat-resolution graph has a complete assignment plan.
   All legal target edges are present, including zero amounts, and the source's
   total is exactly its combat-damage amount when the source deals damage in the
   current step. The final plan is validated before any assignment is applied.
2. Ordinary combat damage has no generic lethal-first or assignment-order
   constraint. An attacking creature may divide its damage arbitrarily among
   its blockers; a blocking creature may divide its damage arbitrarily among
   the attacking creatures it blocks.
3. Trample retains its independent lethal requirement. For each trampler, the
   validator calculates each blocker's remaining lethal amount from projected
   toughness, marked damage, deathtouch, and all attacker-to-blocker damage in
   this same plan. A trample drain edge may be positive only after every live
   blocker assigned by that trampler is lethal under that aggregate.
4. Banding changes who may edit the relevant assignment edges, but does not
   recreate generic order gating and does not bypass the trample requirement.
5. The existing first-strike boundary remains two real combat-damage steps.
   Assignments and marked damage are cleared/retained at the step boundary by
   the existing combat lifecycle; each step builds a fresh complete plan.
6. Chooser sequencing is explicit and follows active-player/nonactive-player
   order for the sources present in the plan. A later chooser edits only its
   owned edges, while the cached graph carries earlier choices forward.
7. Damage application remains one batch after the plan is complete. The
   existing modifier, prevention, shield, redirect, lifelink, and SBA pipeline
   receives the complete batch and applies it without an intermediate damage
   event becoming observable.

## Compatibility boundary

The following remain serializable for old saves/replays only:

- `DamageAssignmentOrderComponent` and `AttackerOrderComponent`;
- `OrderBlockers` and its handler registration;
- `CombatResolutionResponse.orderedBlockers` and
  `CombatResolutionResponse.orderedAttackers`;
- nullable client DTO order fields needed to decode old payloads.

The current handler rejects a newly submitted `OrderBlockers` action, the
current enumerator/UI does not generate it, the board builder does not consult
order components, the resumer does not write them, and the damage pipeline does
not read them. This makes `OLD_DAMAGE_ASSIGNMENT_ORDER_REACHABLE = NO` while
preserving the wire shape.

## Scope exclusions

This change does not add or modify card definitions, Akiri, Chevill,
Commander-zone behavior, observation, Gym, ML, or unrelated SDK vocabulary.

## Exit invariants

```
OLD_DAMAGE_ASSIGNMENT_ORDER_REACHABLE = NO
GENERIC_LETHAL_FIRST_REACHABLE = NO
TRAMPLE_LETHAL_REQUIREMENT_PRESERVED = YES
SAME_STEP_ASSIGNMENT_ORDER_DEPENDENCE = NO
ASSIGNMENTS_COLLECTED_BEFORE_DAMAGE = YES
COMBAT_DAMAGE_SIMULTANEOUS = YES
```

## Verification matrix

The engine-level matrix is kept in
`ModernCombatDamageConformanceTest.kt` and is organized by the requested
COMBAT identifiers. It covers arbitrary ordinary splits, complete-plan
rejection, shared-blocker aggregate lethality, trample/deathtouch and marked
damage, planeswalker/battle trample destinations, two combat steps, state
changes between steps, banding chooser authority, APNAP sequencing,
simultaneous application, and static reachability scans for the obsolete order
and generic lethal-first paths.

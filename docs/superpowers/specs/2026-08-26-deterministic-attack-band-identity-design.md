# Deterministic combat-local attack-band identity

## Decision

Attack-band identity remains a `String?` on `AttackingComponent` and remains
combat-local. A valid non-empty band receives `combat-band-N`; the same value is
written to every member of that band. The identity is removed with the existing
`AttackingComponent` cleanup at end of combat. No global ID framework and no
replay/schema expansion are needed.

## Rules-owned rank audit

The current declaration path is:

`DeclareAttackers.attackers/bands` -> `AttackPhaseManager.validateDeclarationBeforeTax`
-> `validateBands` -> `commitAttackDeclaration` -> `AttackingComponent` ->
`GameState`/snapshot/replay canonicalization -> blockers, damage, predicates,
Gym observation, and client projection.

The existing `GameState.objectIdentityStamps` is the smallest suitable rank
primitive already present in the Rules state. `GameState.addToZone` assigns a
monotonic stamp to every zone object, including battlefield objects; the map and
counter are serialized and therefore survive forks, snapshots, and replay
reconstruction. The stamp is a state-owned object identity, not an
`EntityId.value`, allocation UUID, collection position, hash, or iteration result.

For legacy/synthetic states that have the serialized battlefield object marker
but no entry in the newer state map, the explicit
`BattlefieldEntryTimestampComponent` is a deterministic compatibility fallback.
If a band member has no usable rank, or ranks are not unique, the declaration is
rejected before combat mutation rather than choosing an arbitrary tie-breaker.

The rank is only used to canonicalize the submitted bands. Each set member is
sorted by rank, then bands are sorted lexicographically by their rank sequences.
The submitted `List<Set<EntityId>>` and member collection iteration order never
chooses an identity. Rules semantics of membership, defender equality, banding,
and the existing validation order are unchanged.

Band ordinals are allocated against the existing canonical `AttackingComponent`
band IDs in the current combat. They therefore continue across separate valid
declarations in one combat (such as one declaration from each teammate in a
shared-team combat), rather than resetting for each handler call. Legacy or
ephemeral non-canonical IDs are not parsed or counted. End-of-combat cleanup
removes the components, so the next combat starts again at `combat-band-0`.

## Invariants

1. Complete declaration validation, including deterministic band ordering, runs
   before `AttackingComponent` mutation.
2. Equivalent declarations with different map/set/list insertion order produce
   the same band mapping.
3. Distinct disjoint bands receive distinct deterministic ordinals, including
   bands submitted by separate declarations in one combat.
4. Reconstructed states with different transient entity ID values but the same
   object-identity ranks receive the same ordinals.
5. Fork/snapshot/serialization preserve the rank inputs and band semantics.
6. Invalid overlapping or malformed bands remain atomic.
7. Empty-band/no-band combat keeps `bandId == null` and existing attack behavior.

## Verification scope

The change adds a RED characterization before the implementation and focused
Rules tests for one band, two bands, order permutations, transient-ID remapping,
atomic rejection, fork/snapshot/serialization, cleanup, and no-band behavior.
The production `CompactReplay` codec, `ReplayReconstructor`, checkpoints, and
exact-fidelity path are exercised with a non-empty band. Existing A5 attack
domain tests are retained.

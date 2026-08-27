# A5 Public Attack Declaration Ordering

**Status:** Approved for implementation

**Issues:** #104, blocking #98

**Base:** `e0205fd1f29f6ed15ac0c15050669ba090070b1e`

## Goal

Make the Rules producer, rather than the Gym policy or a collection implementation, own every
ordering needed to present and consume a complete `DeclareAttackers` public choice domain.
The strict Gym path must publish and consume that order without using `EntityId.value`, map/set
iteration, allocation order, `toString()`, or local sorting as semantic authority.

## Boundary

This change adds a reusable combat-local object-rank primitive, makes attacker order first-class in
the Rules certificate and current Gym DTO, and preserves the producer-owned mixed defender order
through strict projection and observation identity. It does not change combat legality, add a second
legality engine, modify the B0 policy, introduce a global identity framework, change locked decks,
add native AI/AutoPay, or address B2 semantic-decision identity.

The existing `AttackersDeclaredEvent` canonicalization that sorts its event payload by
`EntityId.value` is explicitly discovered outside this issue's public-domain scope. It remains
unchanged unless a RED test proves that it affects the #104 public-domain, replay, or strict-Gym
acceptance boundary; that evidence would require a separately scoped follow-up rather than silent
scope expansion.

## Ordering authority

`CombatObjectOrder` is the only shared combat-local object ordering primitive. It exposes the
following behavior:

1. Resolve each requested battlefield object's rank from `GameState.objectIdentityStamps`.
2. Use `BattlefieldEntryTimestampComponent.timestamp` only as the bounded fallback for older or
   synthetic battlefield objects.
3. Return objects in ascending resolved rank.
4. Return `null` when an object has no rank, two requested objects share a rank, or the input has a
   duplicate object. Callers publishing a strict domain fail closed rather than inventing a tie
   breaker.

The primitive has no schema-visible rank field and does not compare entity IDs. `BlockPhaseManager`
uses it for blocker/attacker certificate order. `CombatAttackerOrder` keeps its separate band
identity responsibilities (`canonicalizeBands`, `firstBandOrdinal`, and `bandId`) and delegates
only the shared rank definition to `CombatObjectOrder`.

## Rules certificate

`RulesAttackDeclarationDomain` gains:

```kotlin
val attackerOrder: List<EntityId>
```

The producer obtains this list from `CombatObjectOrder` over the complete candidate-attacker
universe. The certificate invariants are:

- `attackerOrder` is duplicate-free;
- its set is exactly `attackerToDefenders.keys`;
- every relation list is non-empty, duplicate-free, and preserves one shared producer-owned
  defender sequence;
- mandatory attackers are the `attackerOrder` subsequence selected by Rules;
- co-attacker map keys are derived from attacker ranks, every `anyOf` list is an attacker-order
  subsequence, and requirement instances retain their multiplicity while being ordered by rank
  sequences;
- banding and non-banding values are attacker-order subsequences and partition every legal relation
  edge exactly once.

The validator checks these structural relationships using attacker-order indices and rank-sequence
comparisons. It never defines canonicality using `EntityId.value`. The registered certificate
remains the only input to the pure strict-domain validator; stateful Rules validation still runs
after the trusted boundary.

The certificate also retains the producer's global `defenderOrder`, reduced to defenders that occur
in at least one relation list. The validator uses this retained sequence to verify every filtered
relation list as an ordered subsequence; it never reconstructs the sequence from first occurrences.
The mapper uses the same certificate authority for ordered defender-key containers, while V2 does
not publish a separate `defenderOrder` field because each per-attacker defender list already carries
the complete policy-facing choice sequence.

## Mixed defender order

Rules constructs one mixed defender sequence for the current attack declaration:

1. Attackable opponent players in `state.activePlayers` seat order.
2. Attackable battlefield defenders (planeswalkers and battles) in `CombatObjectOrder` order.

The two sets are disjoint. Each `attackerToDefenders[attacker]` is a filtered subsequence of that
single sequence, so filtering by per-attacker legality never changes relative candidate order. If
any included battlefield defender lacks a unique rank, the certificate is unsupported. No defender
ID tie-breaker or collection-order fallback is permitted.

## Public contract and versioning

The repository documents `AttackDeclarationDomainV1` and
`argentum-gym-contract@v1.21-blocker-declaration-domain` as public versioned contract material.
Adding a required field therefore uses a new current DTO:

```text
AttackDeclarationDomainV2
  version = 2
  attackerOrder: List<EntityId>
```

`AttackDeclarationDomainV1` remains available as a historical DTO/codec form but is not used by the
current `LegalActionView`, `TrainingObservation`, mapper, or strict submission path. The live field
type becomes `AttackDeclarationDomainV2`, and the schema identity becomes:

```text
argentum-gym-contract@v1.22-attack-declaration-order
```

V2 construction and observation canonicalization reject unknown versions. A V1 live input or a
future version is unsupported/fail-closed; it is never silently interpreted as V2.

## Projection and observation identity

`AttackDeclarationDomainMapper` validates public addressability for every reference, including
`attackerOrder`, before producing a wire domain. One unaddressable reference rejects the complete
action domain. The mapper builds ordered `LinkedHashMap` containers only from the Rules-owned
attacker and defender sequences:

- attacker relation keys follow `attackerOrder`;
- relation defender lists are copied unchanged;
- mandatory, co-attacker, and band lists are copied unchanged;
- map keys for the band partitions are emitted in the defender order derived from the published
  Rules certificate, never from source-map iteration.

The mapper does not choose or sort attackers, defenders, requirements, mandatory members, or band
members.

`ObservationCanonicalizer` includes `attackerOrder` explicitly. It normalizes map insertion order
by walking the published attacker/defender sequences while preserving all ordered arrays. Thus:

- equal domains with different map insertion order have equal semantic identity;
- equal relations with different authoritative attacker order have different semantic identity;
- no attack-domain digest order is derived from entity IDs.

## Strict submission and replay

The strict seam remains:

```text
RulesAttackDeclarationDomain
  -> public V2 projection
  -> external policy selects using attackerOrder
  -> DeclareAttackers
  -> registered Rules certificate validation
  -> existing ActionProcessor / AttackPhaseManager
```

Malformed order, unknown attacker, invalid defender, stale certificate, and unaddressable
reference rejection must happen before authoritative mutation. The rejection tests compare the
state/replay fingerprint, turn, priority, combat state, RNG, pending decision/continuation, and
decision/accepted-transition counters where applicable.

`CompactReplay` continues to store the semantic `DeclareAttackers` action (`attackers` and `bands`),
not the observation DTO or attacker order. Reconstruction rebuilds state, regenerates the Rules
domain/order, validates the recorded action, and compares the resulting combat state exactly.

## Test matrix

Tests are written and observed RED before production implementation. The focused matrix covers:

- two legal attackers with explicit public attacker order;
- EntityId lexical order opposite object rank;
- map insertion order independence;
- mixed player/planeswalker/battle defender order;
- mandatory, co-attacker, and band partition subsequences;
- privacy-equivalent hidden opponent state;
- V2 serialization round-trip and unknown-version rejection;
- missing/duplicate rank and unaddressable reference fail-closed behavior;
- strict stale/off-domain rejection with zero mutation;
- unchanged semantic replay carrier with regenerated attacker order;
- the exact #104 seed/orientation episode, stopping at the first independent trust gap.

The existing `EnvironmentV1ExternalPolicy` is not modified to sort or infer candidates. Acceptance
requires the exact policy path to consume the published V2 `attackerOrder`.

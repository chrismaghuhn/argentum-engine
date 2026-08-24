# A5 heterogeneous floating-mana provenance

## Status

Approved implementation scope for the follow-up after the accepted
`origin/main` head `485e4338f954d198a9b61a381c9a03c3fc528f8f`. The change starts in
the isolated branch `chris/a5-heterogeneous-floating-provenance`. It does not
modify PR #73, its worktree, the locked decklists, Seed-0, or the 72-episode
corpus.

## Characterization

The current authoritative `ManaPoolComponent` stores unrestricted color totals
and two independent aggregate maps:

- `manaBySource: Map<EntityId, Int>`;
- `manaBySubtype: Map<Subtype, Int>`.

`ManaProvenanceTracker` records a source and amount, but not the color produced
by that source. The current state therefore cannot distinguish different
source/color matrices. For example, the aggregate state
`BLACK=1`, `GREEN=3`, and source counts `e108=1`, `e117=1`, `e136=2` is
compatible with more than one assignment of the black unit. Aggregate counts,
source production profiles, map iteration order, and heuristics cannot select
the real assignment. The existing state is insufficient for an exact external
payment choice.

## Boundary

This change makes unrestricted floating source/color provenance a Rules-owned,
authoritative part of the immutable mana pool. It does not add card-specific
logic, infer provenance from source capabilities, change the explicit payment
authority, or support restricted/rider-bearing mana in `PaymentPlanV1`.

## Data model and invariants

Add `manaBySourceAndColor` to both `ManaPoolComponent` and the transient
`ManaPool` value:

```kotlin
Map<EntityId, Map<PaymentManaColor, Int>>
```

Each inner value is a positive count of unrestricted units. The map is
authoritative, not a cache. Every unrestricted-mana mutation must preserve the
following invariants atomically:

1. The sum of all source/color buckets equals the unrestricted color totals
   whenever complete source/color provenance is present.
2. Summing the inner map by source equals `manaBySource`.
3. Adding mana updates the color total, `manaBySource`, and
   `manaBySourceAndColor` in the same immutable transition.
4. Exact spending decrements the selected source/color bucket and all aggregate
   counters by the same amount.
5. Clearing a pool or crossing a mana-loss boundary clears every provenance map.
6. A legacy path that cannot preserve the new map must clear or reject the
   detailed representation; it must never leave a stale map that could be
   published as authoritative.

The existing subtype counters remain aggregate metadata. A certified exact
payment may expose subtype provenance only when the counters prove that every
unit carries the same recorded subtype set; otherwise the payment domain stays
fail-closed rather than guessing which selected unit carried a subtype.

`ManaProvenanceTracker` remains the generic source of production metadata. Its
API receives the concrete produced color, including `COLORLESS`, and the
existing unrestricted mana executors pass that value. Restricted and rider
paths remain outside this certification boundary.

## Classification and exact materialization

`FloatingManaProvenanceClassification` validates the detailed map against the
color totals, source totals, positivity, restricted-mana absence, and subtype
consistency. It returns a `CertifiedHeterogeneousFloatingMana` candidate
containing sorted `CertifiedFloatingManaSourceColorBucket` values
`(sourceId, poolColor, amount)` when more than one color is present. Malformed,
partial, or inconsistent state remains ambiguous and fails closed. The
`PaymentDomainBuilder` separately rejects any candidate whose source identity
is not perspective-safe.

`ManaPool` receives an internal exact consumer for the heterogeneous candidate.
It accepts only a validated map of selected `(sourceId, poolColor)` amounts,
checks each bucket's capacity, decrements the selected buckets, and returns the
remaining pool plus exact spent provenance. It never calls the legacy greedy
`consumeProvenance()` operation to choose an external allocation.

## Public payment contract

`PaymentPlanV1` remains unchanged at version 1. Its existing
`ManaSpendReference(floatingSourceId, poolColor, amount)` fields are the
explicit controller selection against the Rules-owned source/color buckets.
`PoolSpend` remains an aggregate color checksum.

`PaymentPoolDomainV2` gains the additive
`certifiedHeterogeneousFloatingMana: CertifiedHeterogeneousFloatingManaDomainV2?`
field. The existing homogeneous representation remains available for the
already-supported shape. `PAYMENT_DOMAIN_VERSION` remains 2 because this is an
additive V2 capability, but `SchemaHash.CURRENT` is bumped. The Gym
`schemaHash` observation and `/schema-hash` endpoint are the compatibility
handshake; consumers must reject a mismatched hash before interpreting the new
field. No old client may silently treat the new domain shape as an unchanged
contract.

`PaymentDomainBuilder` publishes the heterogeneous buckets only when every
source identity is perspective-safe and the Rules classifier has proved the
complete source/color state. It uses stable source/color ordering. The builder
never reconstructs buckets from aggregate data.

`PaymentPlanValidator` consumes the same Rules classification used by the
builder. It aggregates submitted `floatingSourceId` plus `poolColor` references,
checks `PoolSpend`, bucket capacities, and complete allocation, then invokes
the exact heterogeneous consumer. A plan that omits a required bucket, names a
wrong color, or overspends is rejected without mutating state.

## Serialization, fork, digest, and replay

The new field is `@Serializable` and must survive every state copy path that
handles unrestricted mana, including add, spend, clear/phase cleanup,
payment-plan materialization, fork/snapshot, and checkpoint state construction.
The implementation must inspect all manual `ManaPoolComponent` and `ManaPool`
rebuilds rather than relying on default constructor values.

`StateDigest` and semantic replay fingerprints must include the new authoritative
field. Any canonicalizer or fingerprint exclusion that would make two states
with different source/color buckets collide must be corrected. Existing replay
actions remain the source of truth; do not increase `CompactReplay`'s version
unless the serialized replay format actually requires it. Tests must prove
encode/decode, checkpoint/fork restoration, deterministic reconstruction, and
digest separation for different source/color assignments.

## Verification shape

Tests are written before production changes and run through a visible RED →
GREEN cycle:

1. Rules classification rejects the supplied heterogeneous aggregate when only
   `manaBySource` and color totals are present.
2. Rules add/spend/clear tests prove atomic source/color invariants and no stale
   detailed map after cleanup or unsupported legacy consumption.
3. Rules classification certifies a valid heterogeneous map and rejects forged,
   incomplete, inconsistent, wrong-color, and restricted variants.
4. Gym publication exposes sorted heterogeneous buckets and remains fail-closed
   for hidden source identities.
5. `PaymentPlanValidator` accepts an exact multi-color allocation, rejects
   cross-color and over-capacity allocations, and leaves unselected buckets in
   the remaining pool.
6. Payment-domain canonicalization and `StateDigest` distinguish different
   source/color assignments while ignoring only proven collection ordering.
7. State serialization, fork/snapshot, checkpoint, and replay reconstruction
   preserve the authoritative map and deterministic digest.
8. Existing homogeneous PaymentDomainV2, PaymentPlanV1, replay, and surrounding
   payment tests remain green.

Only focused and surrounding tests are run locally and in hosted CI. Seed-0,
the 72-episode corpus, ML work, decklist changes, and PR #73 are out of scope.

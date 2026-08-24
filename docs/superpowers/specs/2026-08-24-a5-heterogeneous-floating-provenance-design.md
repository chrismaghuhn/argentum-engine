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

Add `manaBySourceAndColor` and an explicit completeness marker to both
`ManaPoolComponent` and the transient `ManaPool` value. `PaymentManaColor` is
already Rules-owned in `com.wingedsheep.engine.core`, so the Rules state does
not depend on a Gym/public DTO type:

```kotlin
Map<EntityId, Map<PaymentManaColor, Int>>
enum class ManaProvenanceCompleteness { UNKNOWN, COMPLETE, INCOMPLETE }
```

Each inner value is a positive count of unrestricted units. The map is
authoritative, not a cache. The completeness marker disambiguates an empty
detail map from a complete empty pool:

- `UNKNOWN` means no exact source/color detail is available for the current
  nonempty pool. It is the safe default for legacy state that has no new field.
- `COMPLETE` means every unrestricted unit is represented by the map. A
  nonempty pool therefore cannot have an empty map while marked `COMPLETE`.
- `INCOMPLETE` means detail was lost while mana remains, including when a
  previously complete pool passed through an aggregate-only legacy mutation.
  A later tracked add does not upgrade `UNKNOWN` or `INCOMPLETE` while the pool
  is nonempty; certification resumes only after the pool is fully emptied or a
  Rules-owned operation has reconstructed the complete matrix.

Every unrestricted-mana mutation must preserve the following invariants
atomically:

1. When completeness is `COMPLETE`, the sum of all source/color buckets equals
   each unrestricted color total W/U/B/R/G/C independently. `restrictedMana`
   is excluded from this comparison.
2. When completeness is `COMPLETE`, summing the inner map by source equals
   `manaBySource`; `UNKNOWN` and `INCOMPLETE` states are never certified.
3. A tracked add to a certifiable pool updates the color total,
   `manaBySource`, and `manaBySourceAndColor` in the same immutable transition.
   An aggregate-only add instead clears the detail map and marks the nonempty
   result `INCOMPLETE`.
4. Exact spending decrements the selected source/color bucket and all aggregate
   counters by the same amount.
5. Clearing a pool or crossing a mana-loss boundary clears every provenance map.
6. A legacy path that cannot preserve the new map must clear the detailed map
   and mark the nonempty result `INCOMPLETE` (or reject the transition); it must
   never leave a stale or partial map that could be published as authoritative.
7. A complete map can be established for a newly empty pool by a Rules-owned
   tracked add. A tracked add to a nonempty `UNKNOWN`/`INCOMPLETE` pool remains
   non-certifiable; no aggregate reconstruction, source profile, iteration
   order, or heuristic may fill the gap.

The implementation must make the `toManaPool()` and `fromManaPool()` seams
explicit and preserve all three representations through them. These seams,
plus every manual component/value reconstruction used by payment, fork,
checkpoint, serialization, and phase cleanup, are part of the authoritative
state boundary.

The existing subtype counters remain aggregate metadata. A certified exact
payment may expose subtype provenance only when the counters prove that every
unit carries the same recorded subtype set; otherwise the payment domain stays
fail-closed rather than guessing which selected unit carried a subtype.

`ManaProvenanceTracker` remains the generic source of production metadata. Its
API receives the concrete produced color, including `COLORLESS`, and the
existing unrestricted mana executors pass that value. Restricted and rider
paths remain outside this certification boundary.

## Classification and exact materialization

`FloatingManaProvenanceClassification` first requires `COMPLETE`, then validates
the detailed map against the unrestricted W/U/B/R/G/C totals, source totals,
positivity, restricted-mana absence, and the existing aggregate subtype
consistency. It returns a `CertifiedHeterogeneousFloatingMana` candidate
containing sorted `CertifiedFloatingManaSourceColorBucket` values
`(sourceId, poolColor, amount)` when more than one color is present. Malformed,
partial, status-inconsistent, or otherwise inconsistent state remains
ambiguous and fails closed. An inconsistent new detail field is not ignored in
the homogeneous path. The `PaymentDomainBuilder` separately rejects any
candidate whose source identity is not perspective-safe.

There is no new source-by-subtype matrix in this change. Existing aggregate
subtype certification is reused, and remains fail-closed when it cannot prove
the requested payment.

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
field only if the compatibility investigation proves that the actual
Gym/client path rejects a mismatched `SchemaHash` before interpreting any
payment-domain field. That proof must include a negative contract test; merely
advertising a hash from `/schema-hash` is insufficient. If the current path
cannot enforce that fail-closed handshake, the implementation bumps the
payment-domain version instead (for example to `PaymentDomainV3`) and updates
the schema hash accordingly. No old client may silently treat the new domain
shape as an unchanged contract.

Regardless of the chosen version, the published certified pool is an explicit
one-of: the existing homogeneous representation is set and the heterogeneous
representation is `null` for a homogeneous shape; a genuine multi-color
shape sets the heterogeneous representation and the homogeneous one is `null`.
Neither representation is published when the Rules state is incomplete,
unknown where exact provenance is required, or internally inconsistent.

`PaymentDomainBuilder` publishes the heterogeneous buckets only when every
source identity is perspective-safe and the Rules classifier has proved the
complete source/color state. It uses stable source/color ordering. The builder
never reconstructs buckets from aggregate data.

`PaymentPlanValidator` consumes the same Rules classification used by the
builder. It aggregates submitted `floatingSourceId` plus `poolColor` references,
checks `PoolSpend`, bucket capacities, and exact allocation, then invokes the
exact heterogeneous consumer. “Exact allocation” is per color: for every
color present in `PoolSpend`, the sum of submitted
`(floatingSourceId, poolColor, amount)` references must equal that color's
`PoolSpend` amount. The validator need not consume every available bucket;
unselected buckets remain in the resulting pool. A plan that omits part of a
requested color, names a wrong color, or overspends is rejected without
mutating state.

## Serialization, fork, digest, and replay

The new field and completeness marker are `@Serializable` and must survive
every state copy path that handles unrestricted mana, including add, spend,
clear/phase cleanup, payment-plan materialization, fork/snapshot, and
checkpoint state construction. The implementation must inspect all manual
`ManaPoolComponent` and `ManaPool` rebuilds rather than relying on default
constructor values, with `toManaPool()`/`fromManaPool()` covered explicitly.

`StateDigest` and every fingerprint whose contract covers semantic game state
must include the new authoritative field and completeness marker. Any
canonicalizer or state-fingerprint exclusion that would make two states with
different source/color buckets collide must be corrected. Pure action-payload
or replay-payload fingerprints need no artificial change. Existing replay
actions remain the source of truth; increase `CompactReplay`'s version only if
its persisted format or semantics actually change. Tests must prove
encode/decode, checkpoint/fork restoration, deterministic reconstruction, and
digest separation for different source/color assignments.

## Verification shape

Tests are written before production changes and run through a visible RED →
GREEN cycle:

1. Rules classification rejects the supplied heterogeneous aggregate when only
   `manaBySource` and color totals are present.
2. Rules add/spend/clear tests prove atomic source/color invariants, explicit
   `UNKNOWN`/`COMPLETE`/`INCOMPLETE` transitions, and no stale detailed map
   after cleanup or unsupported legacy consumption.
3. Rules classification certifies a valid heterogeneous map and rejects forged,
   incomplete, inconsistent, wrong-color, and restricted variants.
4. Gym publication exposes sorted heterogeneous buckets and remains fail-closed
   for hidden source identities.
5. `PaymentPlanValidator` accepts an exact multi-color allocation, rejects
   cross-color and over-capacity allocations, and leaves unselected buckets in
   the remaining pool.
6. Payment-domain canonicalization and `StateDigest` distinguish different
   source/color assignments while ignoring only proven collection ordering.
7. State serialization, `toManaPool()`/`fromManaPool()`, fork/snapshot,
   checkpoint, and replay reconstruction preserve the authoritative map and
   deterministic digest.
8. A negative schema-hash contract test proves fail-closed negotiation before
   domain interpretation when the existing version is retained; otherwise the
   new domain version is exercised.
9. Existing homogeneous PaymentDomainV2, PaymentPlanV1, replay, and surrounding
   payment tests remain green.

Only focused and surrounding tests are run locally and in hosted CI. Seed-0,
the 72-episode corpus, ML work, decklist changes, and PR #73 are out of scope.

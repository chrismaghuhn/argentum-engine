# Joint Floating-Mana Provenance Design

## Status

Approved design for the A5 floating-mana provenance follow-up. The implementation starts from
`origin/main` at `09e5159506301a25a322a0202b1c8a51fe7c2d35` and must not touch PR #73, Seed-0,
the 72-episode corpus, decklists, or ML work.

## Problem and decision

The current authoritative pool keeps unrestricted color totals, `manaBySource`,
`manaBySubtype`, and exact Source×Color buckets. `manaBySubtype` is an aggregate projection: a
unit with several subtypes contributes to every subtype counter, and the aggregate counters do not
say which subtype snapshot belongs to which Source×Color unit. `PaymentDomainV3` has the same gap:
its heterogeneous shape carries Source×Color rows and one subtype set common to the whole pool.

The smallest generic Rules-owned representation is a joint bucket map keyed by the exact
production identity of a fungible group:

```text
FloatingManaBucketKeyV1(
    sourceId,
    poolColor,
    sourceSubtypes
)
```

`sourceSubtypes` has set semantics. The Rules state stores counts by this key. Identical keys are
aggregated through `amount`; no per-unit IDs, nonces, sequence numbers, or allocation-order IDs
are introduced. An empty subtype set is an explicit, known-empty production snapshot.

The existing color totals, `manaBySource`, `manaBySubtype`, and Source×Color buckets remain
materialized projections because existing rules consumers use them. They must be updated atomically
with the joint buckets and validated against them whenever provenance is certified.

## Authoritative state and invariants

Both `ManaPoolComponent` and transient `ManaPool` carry the same joint bucket counts. For every
nonempty unrestricted pool whose completeness is `COMPLETE`:

1. Every joint bucket has a non-null source, a valid payment color, a nonempty positive amount, and
   exactly one subtype snapshot, including the explicit empty snapshot.
2. Summing joint buckets by `(sourceId, poolColor)` equals `manaBySourceAndColor`.
3. Summing joint buckets by `sourceId` equals `manaBySource`.
4. Summing joint buckets by `poolColor` equals the unrestricted color totals W/U/B/R/G/C. Restricted
   mana is excluded from these comparisons.
5. For every subtype, summing bucket amounts whose snapshot contains that subtype equals
   `manaBySubtype[subtype]`. A multi-subtype unit therefore contributes to each matching aggregate
   subtype counter without becoming ambiguous in the joint map.

`ManaProvenanceCompleteness.COMPLETE` means complete joint provenance for the supported dimensions,
not merely complete Source×Color detail. A nonempty pool that came through a legacy or untracked
path without the joint buckets is `INCOMPLETE` (or remains `UNKNOWN` when no provenance is known),
and cannot be certified. A tracked add cannot upgrade such a pool by appending a partial matrix.
When unrestricted mana is fully cleared, all detail maps are empty and the state returns to the
canonical empty-pool `UNKNOWN` state.

## Production-time capture and state seams

Subtype snapshots are captured when mana production is authored. Payment code never reconstructs
them from current source/card state, source profiles, aggregate counters, or iteration order.

The ordinary mana production path passes the snapshot into the Rules-owned add operation. Solver
output is not provenance authority and cannot create a joint bucket from a hypothetical payment
choice. The authoritative snapshot is created at the actual mana-production transition, after the
engine knows which mana was actually produced, but before any required last-known-information
source data is lost. A production result may carry that already-authoritative snapshot across a tap
or sacrifice only so later payment materialization does not reread current source state. Bonus
production follows the same rule. Deprecated compatibility paths remain fail-closed and cannot
manufacture joint detail.

The following paths are one atomic state contract and are covered together:

- unrestricted add, tracked add, spend, and phase/boundary cleanup;
- `toManaPool()` and `fromManaPool()`;
- copy/fork and persistence serialization;
- exact certified consumption and reconstruction of all remaining aggregates.

## Exact spend authority

`PaymentPlanV1` remains unchanged and remains the historical authority for plans it can represent.
The new path uses a versioned `PaymentPlanV2` carrier because the current
`PaymentStrategy.Explicit.paymentPlan: PaymentPlanV1?` cannot distinguish two joint buckets that
share source and color.

V1 representability is explicit: a V1 floating reference is accepted only when the pair
`(floatingSourceId, poolColor)` resolves to exactly one currently certified joint bucket. If two or
more subtype snapshots share that pair, including `{Forest}` and `{}`, V1 is rejected fail-closed
and V2 is required. V1 must never select a bucket by map order, source profile, aggregate subtype
counts, or an implicit empty snapshot.

The V2 floating selection contains the complete engine-issued semantic key
`(floatingSourceId, poolColor, sourceSubtypes)`, with the subtype list canonically sorted on the
wire. `sourceSubtypes` is only an echo of a bucket key already published by the server; it is not
client-authored provenance. The empty list is a deliberate empty snapshot, not an omitted value.
The aggregate `PoolSpend` color totals remain a checksum; exact identity lives in the spend
allocation.

The serialized action hierarchy must carry the version explicitly, either through a distinct
`ExplicitV2` strategy branch or an equally explicit versioned plan union. A V2 plan must never be
silently decoded as V1, and V1 must not gain an optional field whose absence can select an arbitrary
joint bucket. The server checks the complete V2 reference against the current certified Rules-owned
bucket before spending; a client-supplied subtype snapshot can never create or broaden authority.

The Rules validator checks that every submitted V2 bucket exists, has sufficient amount, and is
selected only for its exact color. It consumes only the requested joint bucket counts, permits all
unselected buckets to remain, and rebuilds the remaining joint and aggregate maps. The resulting
`SpentManaProvenance` is computed from the selected snapshots and source IDs, not from aggregate
counts. For a selected amount `n` from a bucket with snapshot `{Forest}`, `bySubtype[Forest]`
increases by `n`; for `{}`, no subtype entry is added.

## Public PaymentDomain and privacy

Historical `PaymentPlanV1`/`PaymentDomainV3` remain unchanged for their existing representable
shapes. Joint subtype buckets require `PaymentDomainV4` because V3 cannot publish two distinct rows
with the same Source×Color identity and different subtype snapshots without losing information.

V4 uses one canonical `certifiedFloatingBuckets` list for both homogeneous and heterogeneous pools.
Each row contains `(sourceId, poolColor, sourceSubtypes, amount)`, so no common-subtype assertion or
parallel homogeneous/heterogeneous validator path exists. An empty list means no certified floating
provenance; a nonempty list is the complete certified joint representation. Every row is a
Rules-certified bucket and uses canonical subtype ordering.

Publication has an additional privacy gate: an addressable source entity is not automatically
eligible for subtype publication. The source identity and the stored production subtype snapshot
must both be legally visible to the acting player according to authoritative known-information or
visibility metadata captured by the Rules state. The publisher must not infer that fact from the
current `CardComponent`, the current source type, or addressability alone. If authoritative
known-information metadata is absent or cannot prove disclosure of the stored snapshot, V4
publication fails closed and that metadata dependency is reported as a separate characterization
gate.

The Gym schema hash is bumped together with the V4 serializable DTOs. A negative contract test must
show that an old V3 client/handshake cannot silently interpret a V4 payload as V3 before domain
interpretation.

## Digest, serialization, and replay contract

The joint bucket map is part of authoritative semantic state. Component and transient round trips,
GameState persistence, fork/restore, and state equality must preserve it exactly. `StateDigest`
must differ when only the joint subtype association differs while totals and existing aggregate maps
remain equal. The observation canonicalizer must serialize the V4 domain and canonicalize subtype
sets without using map or iteration order as a choice.

The complete transition-semantic replay fingerprint must bind the joint state. CompactReplay version
is not changed by this spec. Its compatibility is a required implementation gate after the V2
strategy/plan carrier is chosen: if the serialized `PaymentStrategy`/`GameAction` carrier makes a
new V2 action not unambiguously decodable by an old CompactReplay-v3 reader, the persisted replay
format must receive a new version. No new payload may be stored under the old format label. A replay
bump is not made merely because a new state field exists when the persisted action format remains
unambiguously compatible.

## Test contract

RED/GREEN coverage must include:

- mixed subtype and non-subtype buckets with identical Source×Color identity;
- V1 acceptance when a floating pair resolves to exactly one joint bucket, and fail-closed V1
  rejection when the pair resolves to multiple subtype snapshots;
- classifier fail-closed behavior for aggregate-only or partial subtype provenance;
- V2 exact selection of each server-published bucket key, rejection of a client-invented snapshot,
  exact `SpentManaProvenance`, overspend rejection, and
  preservation of unselected remaining buckets;
- unrestricted add, spend, clear/phase cleanup, and `toManaPool()`/`fromManaPool()` consistency;
- component/transient serialization round trips, GameState persistence, and fork/restore;
- digest/fingerprint sensitivity to joint provenance and deterministic replay reconstruction;
- V4 wire round trip, canonical subtype ordering, single-list validation, and the negative V3
  compatibility/handshake contract;
- privacy fail-closed publication when source identity is visible but authoritative known-information
  does not prove that the stored subtype snapshot is disclosed.

No Diabolic Edict-specific branch, decklist change, Seed-0 run, corpus run, or ML work is part of
this change.

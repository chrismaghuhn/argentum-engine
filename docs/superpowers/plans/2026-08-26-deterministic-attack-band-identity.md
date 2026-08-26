# A6 deterministic attack-band identity implementation plan

## Baseline and audit

- Keep the isolated branch based on `0471b4c13037a94c00af8e80c429797af899ab72`.
- Use issue #95 as the focused tracking issue; do not merge upstream.
- Audit every producer/consumer of `bandId`, the `bands` payload, and random or
  ephemeral identities through declaration, state, snapshot, serialization,
  replay, observation, blocking, damage, and cleanup.
- Record that `objectIdentityStamps` is the existing state-owned canonical rank;
  explicitly forbid `EntityId.value` as a rank or tie-breaker.

## RED characterization

- Extend the Rules banding test with two semantically identical legal setups,
  same explicit seed and declaration, and assert equal post-declaration band
  identity/state semantics. Confirm the current UUID implementation fails.
- Add the stronger permutations: reversed band/member input order and equivalent
  state copies with remapped transient entity IDs but preserved object ranks.
- Do not add production code before the first RED is observed.

## Minimal Rules fix

- Add a small internal combat-local canonical-order helper backed by
  `objectIdentityStamps`, with the explicit battlefield timestamp fallback for
  legacy/synthetic state and fail-closed handling for missing/duplicate ranks.
- Invoke canonical rank validation after complete `validateBands` and before any
  tax pause or attacker mutation.
- Canonicalize every submitted band by rank, sort bands lexicographically by
  rank sequence, and assign the next available `combat-band-N` ordinals in that
  order. Existing canonical IDs in the current combat provide the offset so
  separate declarations cannot reuse an earlier band's identity; legacy/random
  IDs are ignored rather than used as an authority.
- Keep the existing `AttackingComponent`, continuation payload, and cleanup
  lifecycle. Do not hash IDs, add a schema field, or change attack legality.

## Regression and replay gates

- Cover one band, two disjoint bands, all insertion-order permutations, malformed
  overlap and atomicity, fork equivalence, snapshot/restore, serialization, end
  combat cleanup, and ordinary no-band behavior.
- Add a production `GameSession`/`CompactReplay` test that captures a banded
  declaration, round-trips through `ReplayCodec`, reconstructs with
  `ReplayReconstructor`, verifies exact checkpoints, and compares semantic
  trajectory/final state.
- Run focused tests first, then Rules combat/banding, Gym attack-domain,
  snapshot/fork, replay, canonicalization/digest, relevant module, and scenario
  gates. Use `just` first and separately label the documented Windows native
  Gradle fallback if `just` cannot invoke its wrapper.

## A6 audit, review, and delivery

- Search the locked Environment V1 path for UUIDs, nonces, object identity,
  unordered iteration, generated action IDs, debug text, wall-clock time, and
  unseeded randomness. Classify each hit and stop final A6 acceptance if a new
  authoritative blocker is found; track it separately instead of expanding this
  change.
- Perform an independent final-diff review with BLOCKER/MAJOR/MINOR counts.
- Verify the exact final head, push the branch, and open a Draft PR in
  `chrismaghuhn/argentum-engine` linked to issue #95. Report hosted CI and
  coverage separately; skipped coverage is not PASS.

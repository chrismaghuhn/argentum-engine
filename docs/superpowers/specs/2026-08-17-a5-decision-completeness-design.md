# A5 Decision Completeness Design

## Scope

Audit and harden the existing serializable decision surface for the trusted
Commander path. Every response must be checked against the server-provided
candidate domain, cardinality, distinctness, bounds, and pending owner. No policy
may choose `first`, random, or iteration order for a meaningful player choice.

## Bounded implementation slice

The first implementation slice fixes validator holes that can admit malformed
responses: duplicate card selections, incomplete target maps, duplicate modes,
negative distributions, and mana-source IDs outside the advertised domain. Each
change is generic and does not add a card-specific path or touch Gym.

The delayed-trigger ambiguity from Issue #22 remains explicitly fail-closed until
the detector can return a serializable decision plus continuation without losing
the occurrence-specific `TriggerContext`. The existing test that rejects an
ambiguous fire-once `YouAttackPlayerEvent` is retained as a regression gate; no
first-match or iteration-order fallback is permitted.

## Invariants

- `SubmitDecisionHandler` remains the owner and decision-ID gate.
- Validators reject unknown candidates and incomplete domains before resumption.
- No response mutates state or consumes a delayed trigger until validation and
  continuation resumption succeed.
- Replay/serialization registrations are changed only for a fully implemented
  primitive; the bounded validator slice introduces no new decision type.

## Verification

Add focused unit tests before implementation for each rejected shape, run them
through the repository `just` gates, and report the local launcher blocker if
Gradle cannot start. Re-run the existing delayed-trigger ambiguity scenario and
the frozen baseline suite; do not rebless snapshots.

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
validator change is generic and does not add a card-specific path; the later
occurrence-domain projection is the only Gym-facing addition.

The delayed-trigger ambiguity from Issue #22 is externalized through a generic,
serializable occurrence decision plus continuation. The detector preserves each
occurrence-specific `TriggerContext`; the controller chooses one occurrence, and
the decision domain exposes aligned public `triggeringPlayerId` metadata so a
controller can distinguish B from C rather than receiving opaque ordinal slots.
The resumer consumes the fire-once trigger exactly once. Any deeper trigger-order
gap remains explicitly fail-closed; no first-match or iteration-order fallback is
permitted.

## Invariants

- `SubmitDecisionHandler` remains the owner and decision-ID gate.
- Validators reject unknown candidates and incomplete domains before resumption.
- No response mutates state or consumes a delayed trigger until validation and
  continuation resumption succeed.
- Replay/serialization registrations are changed only for fully implemented
  primitives; the occurrence choice is registered and round-tripped, while the
  remaining bounded validator slice introduces no additional decision type.

## Verification

Add focused unit tests before implementation for each rejected shape, run them
through the repository `just` gates, and report the local launcher blocker if
Gradle cannot start. Run the delayed-trigger occurrence-choice matrix and the
frozen baseline suite; do not rebless snapshots.

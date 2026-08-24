# A5 Required Structured Payload Fields

## Goal

Publish the canonical structured-action fields required to complete each `LegalActionView`, so
external Gym controllers can make explicit choices—including an empty additional-cost choice—without
inferring requirements from presentation fields such as sacrifice cardinality.

## Scope and contract

`ActionPayloadRequirements.requiredPayloadFields(legalAction)` remains the single authoritative source
for both public publication and trusted submission validation. Its result becomes an explicitly
canonical, deduplicated ordered list. The canonical order is a named contract owned by the helper,
not an incidental consequence of independent conditional branches.

`LegalActionView` gains:

```kotlin
val requiredPayloadFields: List<String> = emptyList()
```

`ObservationBuilder` copies the authoritative helper result directly into the view. It does not
reimplement field-requirement rules. The field is structural and is published even when the action is
not affordable. `requiresStructuredAction` remains the boolean projection of whether the list is
non-empty.

Several rules may require the same payload field; the public list contains each field once. In the
Plumb-shaped case, the list includes `paymentStrategy` and `additionalCostPayment`, and an explicit
empty `additionalCostPayment` object is still required and accepted by trusted validation.

## Wire and digest behavior

The new serializable field is included in the observation wire DTO, the semantic action fingerprint,
and therefore `StateDigest`. The Gym schema hash is bumped because `LegalActionView` changes shape.
Replay versioning is unchanged because replay wire serialization and semantics are not modified.

## Verification

Gym regressions cover:

- canonical deduplication and ordering across target, X, payment, additional-cost, mode, and combat
  requirements;
- Plumb-style zero-card sacrifice publication and successful explicit-empty submission;
- missing required fields rejected without advancing the environment;
- existing non-empty sacrifice, payment, target, mode, and combat requirements remaining correct;
- required-field changes affecting canonical observation output and state digests;
- serialization round-trip and the new schema hash.

No card-specific logic, policy fallback, inferred empty choice, Seed 0 run, corpus run, or PR #73
change is in scope.

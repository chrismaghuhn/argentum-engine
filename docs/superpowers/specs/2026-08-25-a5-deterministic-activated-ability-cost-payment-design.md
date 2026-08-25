# A5 Deterministic Activated-Ability Additional-Cost Payment

## Status

Approved implementation design. Base commit: `f3939f72c4bbaa52cc708cb622aa8b5eb255ff5b` (`origin/main`).

## Problem

`ActivateAbility.costPayment` is already part of the public action model, and Gym already requires
the `costPayment` field whenever a legal action exposes `additionalCostInfo`. Wayfarer's Bauble is
currently rejected before payment-domain publication because `ObservationBuilder.paymentDomainFor`
blanket-rejects every activated ability with `additionalCostInfo`, even when the additional costs are
bound to the activated source and have no player choice.

The existing Rules executor already owns and deterministically executes `AbilityCost.Tap` and
`AbilityCost.SacrificeSelf`. The missing contract is an authoritative certificate and exact payload
validation for the external ExplicitV2 path. Choice-bearing costs must remain unsupported until their
candidate and cardinality domains are complete.

## Decision

Reuse the existing `AdditionalCostPayment` payload. Do not add an additional-cost domain DTO, schema
version, replay version, card-name branch, automatic payment, or heuristic completion.

For the first supported slice, Rules recursively examines the authoritative effective `AbilityCost`
computed by `ActivatedAbilityCostCalculator`. The only accepted leaves are:

- ordinary fixed mana;
- `AbilityCost.Tap`, meaning tap the activated source exactly once;
- `AbilityCost.SacrificeSelf`, meaning sacrifice the activated source exactly once.

Composite costs are accepted only when every leaf belongs to that set and the source-bound leaves are
represented exactly once. The Rules-derived expected payload for source `sourceId` is:

```json
{
  "tappedPermanents": ["<sourceId>"],
  "sacrificedPermanents": ["<sourceId>"]
}
```

Lists for absent deterministic leaves must be empty. Every other `AdditionalCostPayment` field must
also be empty/zero. The submitted payload must equal this Rules-derived value exactly. The payload is
an explicit representation of the already-bound source identity, not a player choice.

The Rules validator must run before explicit mana execution or any tap, sacrifice, or other state
mutation. On the public ExplicitV2 path, a missing payload is invalid as well as an unequal payload.
For legacy/non-structured direct Rules callers whose `paymentStrategy` is absent, a null payload remains
backward-compatible; a non-null payload is still checked against the Rules-derived value. Gym remains
strict and requires the field through the existing `ActionPayloadRequirements` contract.

## Publication contract

`PaymentDomainV4` remains mana-only. For an affordable activated ability, `ObservationBuilder` may
publish a V4 domain despite `additionalCostInfo` only when the effective-cost certificate is positive
for the exact `{Mana, TapSelf, SacrificeSelf}` slice. The source is excluded from mana source
activation candidates when the effective cost contains `TapSelf`, preserving the existing strict mana
path:

`PaymentDomainV4 → PaymentPlanV2 → ExplicitV2 → authoritative Rules execution`.

The public observation already supplies the needed values: `sourceEntityId`, the structural ability
semantics (including cost atoms), and `requiredPayloadFields = [paymentStrategy, costPayment]` for
Wayfarer. The policy materializes the existing `AdditionalCostPayment` lists using the public source
identity; it does not infer a hidden candidate or choose among permanents.

The blanket `additionalCostInfo != null -> null` behavior is removed only behind this positive Rules
certificate. Variable sacrifices, selected-permanent taps, discard/card selections, variable
quantities, target/domain-dependent costs, and every unresolved additional-cost shape remain
`PAYMENT_DOMAIN_UNSUPPORTED`.

## Authoritative validation boundary

The classifier/certificate must consume the effective cost and activated source ID, never
`LegalAction.additionalCostInfo`, the submitted payload, a card name, or the printed mana string.
`LegalAction.additionalCostInfo` remains an observation hint for candidate-bearing costs. The handler
continues to rerun the effective-cost calculation and validates the exact submitted payload before
calling the existing `PaymentPlanValidator`, `ExplicitPaymentPlanExecutor`, and `CostHandler` flow.

No AutoPay, legacy source-ID fallback, implicit source completion, or solver fallback is permitted on
the trusted Gym route.

## Test evidence required

The implementation must include tests proving:

1. A real Wayfarer's Bauble action publishes a usable V4 domain and required fields are
   `[paymentStrategy, costPayment]`.
2. The public observation is sufficient to construct the exact source-bound payload.
3. Wayfarer's `{2}` is paid using a public `PaymentPlanV2`.
4. The source is tapped exactly once, sacrificed exactly once, and the ability continues/resolves.
5. Missing or unequal `costPayment` rejects atomically before mana/tap/sacrifice mutation.
6. Invalid `PaymentPlanV2` rejects atomically.
7. AutoPay, legacy source-ID payment, and any engine-selected fallback are rejected.
8. A synthetic variable sacrifice/tap-selection ability remains unsupported with
   `PAYMENT_DOMAIN_UNSUPPORTED`.
9. The support decision contains no card-name special case; a differently named equivalent
   deterministic ability is handled by the same Rules shape.
10. At least one additional real `SacrificeSelf` activated ability is covered cheaply from the
    existing card inventory.

The existing Wayfarer's Bauble Rules scenario remains a direct/legacy compatibility test and must not
be converted into a card-definition workaround. No deck, corpus, PR #73, schema, or replay changes are
in scope.

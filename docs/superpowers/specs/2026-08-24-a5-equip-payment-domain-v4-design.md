# A5 Equip `PaymentDomainV4` Design

**Status:** Approved by the user on 2026-08-24

## Goal

Publish and accept the existing public payment contract for supported Equip
`ActivateAbility` actions:

`ActionTargetDomainV1 -> PaymentDomainV4 -> PaymentPlanV2 -> ExplicitV2 -> ActivateAbilityHandler`.

The change is generic: a fixed-cost Equip such as `Equip {1}` becomes externally payable, while
an Equip whose final cost depends on an unbound choice remains unsupported.

## Ownership and boundary

`ObservationBuilder.paymentDomainFor` remains the single canonical producer used both for
observations and the trusted Gym submission gate. The `ability.isEquipAbility` guard is not removed;
it becomes a proof boundary.

An Equip payment domain is eligible only when all of the following hold:

1. The Rules action carries a complete, supported canonical target domain. The published target
   requirements, rather than the legacy flat target fields or an inferred target, are the complete
   set of target choices. The payment domain is rejected if that domain is structurally unsupported.
2. The ability has a fixed ordinary mana cost and no alternative payment, X, Convoke, Waterbend,
   tap-for-generic, additional-cost, or other choice that is not represented by PaymentDomainV4.
   Target-dependent `genericCostReduction` remains rejected.
3. The exact effective Equip cost is invariant over every candidate in the published target domain.
   The check evaluates the complete `AbilityCost` transformation, including text replacement,
   activated-ability reductions, target-aware Equip reductions, and colored-cost relaxation. It
   compares every target-aware result with the unbound/enumerated result and with the public mana
   cost. A single divergent candidate rejects the whole payment domain; candidates are never
   filtered out of the target domain.
4. The payment context is built through the existing `buildAbilityPaymentContext` path, preserving
   the source's projected card types/subtypes, ability-activation classification, and Equip-specific
   mana restrictions. The existing source-exclusion and `PaymentDomainBuilder` certification rules
   remain authoritative.

This preserves the handler's ownership of final legality: after the controller supplies a target and
an ExplicitV2 plan, `ActivateAbilityHandler` recomputes the target-aware cost and validates the same
plan against the same ability payment context before attaching the Equipment. No automatic target or
payment selection is added.

## Rejected approaches

1. Removing `ability.isEquipAbility` would publish the enumerator's unbound cost even when the
   handler charges a different target-specific cost.
2. Allowing every Equip and relying only on equal numeric mana strings would ignore non-mana cost
   structure and public target-domain completeness.
3. Removing divergent targets from the payment check would make the target domain and payment
   domain describe different actions, so the entire action remains fail-closed instead.
4. Adding a Bonesplitter branch, AutoPay fallback, native payment path, or a schema/replay version
   would expand the contract without solving the generic ownership problem.

## Regression matrix

- RED then GREEN: a generic fixed `Equip {1}` publishes a complete PaymentDomainV4 instead of null.
- Positive Gym chain: observe the target domain and PaymentDomainV4, construct PaymentPlanV2 from
  that domain, submit ExplicitV2 with the chosen target, and verify `ActivateAbilityHandler` pays and
  attaches the Equipment.
- Negative Gym cases: target-dependent Equip reductions, target-specific Equip reductions with at
  least one divergent candidate, free-first/alternative payment, X, Convoke, and other unsupported
  dynamic shapes remain fail-closed.
- Rules regression: an ExplicitV2 Equip payment is validated against the exact chosen-target cost;
  a plan for a different target/cost is rejected.
- Existing CastSpell, floating-provenance, action-domain, and payment-domain regressions remain
  unchanged.

## Verification boundary

Run the focused Rules and Gym tests, the relevant module regression gates, and hosted CI on the
production PR. Independently review the final diff against `origin/main`. Do not modify PR #73,
decklists, cards, Seed-0 policy, ML, B0, trajectories, or run the 72-episode corpus. No public schema
or replay version changes are expected.

# A5 fixed CastSpellMode PaymentDomainV1

## Status

Approved scope for the follow-up to PR #73. The implementation is based on
`origin/main` at `ffba76fa112077f21adb3714046e07b3e9474bdc` and does not touch
PR #73 or the prior CycleCard characterization/worktree.

## Boundary

This change makes the existing `PaymentDomainV1 -> PaymentPlanV1 -> PaymentPlanValidator`
contract available to a fully-bound, ordinary fixed-cost `CastSpellMode` action. It does not
introduce a second payment model, a modal-specific executor, automatic source selection, or
support for unresolved modal choices.

The supported modal payment shape is deliberately narrow:

- the legal action is `CastSpellMode` and its underlying action is `CastSpell`;
- exactly one mode is already selected, and the Rules-owned effective choose-count range is
  exactly `1..1`;
- the selected mode has no mode-specific mana addition or additional-cost override;
- the final mana cost is ordinary colored, colorless, and/or generic mana;
- the cast has no X, alternative/resource payment, convoke, delve, harmonize, tap-for-generic,
  free-cast, splice, face, or unresolved payment-affecting choice;
- the existing `CostCalculator.hasTargetDependentCastCost` check proves the advertised cost is
  final across the legal targets.

Everything outside that shape remains fail-closed with `PAYMENT_DOMAIN_UNSUPPORTED`, including
choose-N modal actions, mode-specific additional mana/additional costs, target-dependent costs,
and unsupported ordinary CastSpell payment shapes already covered by V1.

## Data flow

`ObservationBuilder.paymentDomainFor` remains the canonical publication and trusted-Gym authority
check. For an eligible `CastSpellMode`, it reuses the existing CastSpell cost parsing, target-cost
finality check, `SpellPaymentContext`, source discovery, and `PaymentDomainBuilder`. The public
domain therefore describes the selected mode's already-enumerated fixed cost without exposing
engine runtime handles or suggesting a payment.

The existing `CastPaymentProcessor.explicitPlanPay` remains the only materialization path for a
submitted `PaymentPlanV1`. `CastSpellHandler.validatePayment` stops rejecting an eligible selected
mode merely because `chosenModes` is non-empty. It still validates the shared modal shape and then
passes the plan through `PaymentPlanValidator`; execution preserves the selected mode, targets,
stack payload, mana side effects, `ManaSpentEvent`, and all existing modal continuations.

No `ManaSolver.solve()`, AutoPay, FromPool, or legacy source-ID policy is introduced after a
trusted plan is submitted. The Gym guard already receives the underlying `CastSpell`, so it keeps
its generic explicit-plan requirement and needs no independent modal payment policy.

## Contract/versioning

Because `CastSpellMode` observations gain `paymentDomain` semantics, `SchemaHash.CURRENT` is bumped.
The existing serializable `CastSpell` fields (`chosenModes`, targets, and payment strategy) remain
the replay/fork/digest source of truth; no new wire action type is added.

## Verification shape

The RED characterization proves the Boros Charm action shape, current null domain, and
`PAYMENT_DOMAIN_UNSUPPORTED`, while the existing ordinary CastSpell domain test remains green.
GREEN coverage adds:

1. Boros-Charm-style fixed choose-1 modal payment;
2. a targeted modal mode whose cost is target-independent;
3. target-dependent modal cost rejection;
4. mode-specific/unsupported payment-shape rejection;
5. trusted-Gym rejection of AutoPay, FromPool, and legacy Explicit;
6. replay encode/decode/reconstruct plus modal chosen-mode identity;
7. Gym fork/snapshot and StateDigest preservation;
8. existing ordinary CastSpell and ActivateAbility payment coverage unchanged.

No card snapshots are reblessed.

# A5 Exact Mana Abilities With Deterministic Side Effects

## Goal

Publish and execute the narrow class of ordinary tap mana abilities whose exact mana production is
already representable by PaymentPlanV2 and whose only additional effect is a deterministic,
choice-free fixed amount of damage to the ability controller. The motivating real source is
Llanowar Wastes, but the implementation is generic and contains no card-name checks.

## Scope and non-goals

This change starts at `origin/main` commit `836a593a7dbff58e0a42e169e35926912724e44c`. It does not
modify PR #73, its acceptance corpus, policy/deck inputs, or any post-merge acceptance artifacts.

The public PaymentDomainV4, PaymentPlanV2, Gym schema hash, and replay versions remain unchanged.
`PaymentSourceActivationDomain` already publishes the stable structural `manaAbilityKey`, so no new
wire field is needed for this slice. The key excludes runtime/JVM identity and
`descriptionOverride`, and therefore distinguishes the colorless ability from the colored abilities
on Llanowar Wastes.

This change does not support pay-life costs, dynamic damage, non-controller recipients, player
choices, gates, restrictions, riders, secondary activation costs, or any other unproven shape. It
does not add a second damage executor or reinterpret `colorPainCost` as execution authority.

## Design

### Independent certificates

`PaymentManaProductionProfile` remains the Rules-owned description of what mana a selected ability
produces. Its resolver examines the exact mana-production leaves and can certify an ordinary fixed
single output or fixed output bundle even when the ability also contains a non-mana effect.

A separate `PaymentManaSideEffectCertificate` is bound by the same `manaAbilityKey` and describes
only the closure needed for external PaymentPlanV2 publication:

- `NoSideEffect` when the ability has no non-mana leaves;
- `FixedSelfDamage(amount)` when the ability has exactly one non-mana leaf, a fixed positive
  `DealDamageEffect`, whose target is exactly `Player.You`;
- `Unsupported(reason)` for every other shape.

The certificate is not an execution plan and is never used to apply damage. After
`PaymentPlanValidator` has bound the submitted `manaAbilityKey` to the current
`ActivatedAbility`, the existing `ManaAbilitySideEffectExecutor` receives that actual ability and
continues to execute tap plus non-mana side effects transactionally. Any failure restores the
original state and emits no partial events.

### Exact source completeness

Discovered `ManaSource` instances carry production profiles and side-effect certificates keyed by
the same stable ability key. A source is PaymentPlanV2-capable only when:

1. every currently legal payment-relevant mana ability has a profile entry;
2. every production profile is supported and agrees with the aggregate source representation;
3. every side-effect certificate is either `NoSideEffect` or `FixedSelfDamage(amount > 0)`;
4. no ability has an unsupported activation cost, choice, restriction, rider, secondary cost, or
   activation restriction;
5. the aggregate pain metadata is consistent with the exact per-ability certificates.

The fifth item is a consistency cross-check only. `colorPainCost` and `colorlessPainCost` never
identify or authorize a side effect. In particular, an ability with a `PayLife` cost remains
unsupported even if an aggregate pain value happens to match a damage amount. The existing
ordinary-tap-cost and restriction guards remain fail-closed.

If one sibling ability is unsupported or absent from the complete key set, the entire source is
withheld from the domain. Publication never exposes only a convenient subset such as Llanowar
Wastes' `{C}` ability while hiding `{B}` and `{G}`.

### Publication and execution flow

`ManaSolver` certifies the two independent profiles while discovering each currently usable mana
ability. `PaymentDomainBuilder` calls the shared source-completeness predicate before converting all
profiles into `PaymentSourceActivationDomain` entries. The resulting domain publishes `{C}`, `{B}`,
and `{G}` for a complete Llanowar Wastes source, with each production choice carrying its exact
`manaAbilityKey`.

`PaymentPlanValidator` continues to validate production choice, key identity, source availability,
and exact allocation. `ExplicitPaymentPlanExecutor` continues to pass the accepted selected ability
to `ManaAbilitySideEffectExecutor`; no validator or Gym code executes damage directly.

## Failure and atomicity contract

Unsupported profiles cause `paymentDomain == null` and the existing
`PAYMENT_DOMAIN_UNSUPPORTED` diagnostic at the trusted Gym boundary. A malformed, incomplete,
unpublished, or wrong-key PaymentPlanV2 is rejected without state advancement, taps, life changes,
or events. A valid `{B}` or `{G}` activation taps the selected source and applies exactly one
self-damage through the existing executor; `{C}` applies no damage.

## Test matrix

Rules and Gym regressions will prove:

- real Llanowar Wastes publishes all three exact abilities `{C}`, `{B}`, and `{G}`;
- ordinary Tear Asunder `{1}{G}` and kicked Tear Asunder `{2}{G}{B}` publish PaymentDomainV4;
- PaymentPlanV2 is constructed only from public domain fields;
- selecting `{B}` or `{G}` pays, taps the exact source, and damages its controller exactly once;
- selecting `{C}` pays and causes no damage;
- invalid plans and side-effect failures remain atomic;
- one unsupported sibling with an otherwise supported source suppresses the complete domain;
- dynamic/choice-bearing production or side effects, gates, restrictions, riders, pay-life costs, and
  secondary costs remain unsupported.

The focused tests run first in RED against the untouched baseline, then GREEN after the minimal
Rules-owned certificate/publication change. Affected Rules and Gym suites, `git diff --check`, and an
independent final diff review are required. Hosted CI is reported separately from local wrapper and
native fallback evidence. The 72-episode corpus is explicitly post-merge work and is not run here.

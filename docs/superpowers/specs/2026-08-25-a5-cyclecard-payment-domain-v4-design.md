# A5 Follow-up — CycleCard PaymentDomainV4 Design

**Status:** Approved by the user on 2026-08-25.

**Goal:** Publish and execute a public `PaymentDomainV4`/`PaymentPlanV2` contract for generic
`CycleCard` actions whose cycling cost is exactly a fixed ordinary mana cost, while preserving
fail-closed behavior for every dynamic or otherwise unrepresentable payment shape.

**Base:** `origin/main` at `935d097805a5053b8b2fc492e59dfa9427defa0c`.

## Problem and evidence

The exact-pair corpus reaches a real fixed-cost `CycleCard` at game seed `1`, policy seed
`5259908`, external step `1314`, with cost `{2}` and diagnostic `PAYMENT_DOMAIN_UNSUPPORTED`.
`Unearth` is a concrete corpus card with plain `Cycling {2}`.

The current authoritative path has two independent gaps:

1. `ObservationBuilder.paymentDomainFor` publishes action-level payment domains only for
   `ActivateAbility` and `CastSpell`, so a payable `CycleCard` remains unsupported.
2. `CycleCardHandler.validate` and `execute` explicitly reject `PaymentStrategy.ExplicitV2`.
   Its existing payment implementation consumes floating mana and then uses legacy explicit
   source IDs or `ManaSolver`, so publishing a domain before changing Rules would advertise a
   payment carrier the handler does not honor.

## Contract boundary

The supported slice is generic, action-level, and deliberately narrower than all Rules cycling:

- `CycleCard` only, meaning plain cycling rather than typecycling.
- The authoritative cycling ability must exist on the card and have no search filter.
- The cycling cost must be fixed and must match the publicly advertised `LegalAction.manaCostString`
  exactly after parsing/canonicalization.
- Every symbol must be an ordinary colored, colorless, or generic mana symbol.
- Plain Cycling constructs one canonical activated-ability payment context with
  `buildAbilityPaymentContext(cardComponent = cardComponent, projected = state.projectedState,
  sourceId = action.cardId, ability = null)`. The same non-null context is reused by action
  enumeration, public domain publication, explicit-plan validation/materialization, and the
  legacy solver path. No caller may substitute a null/unrestricted payment context.
- The public Gym carrier is always `PaymentStrategy.ExplicitV2` containing `PaymentPlanV2`.

Costs containing `X`, hybrid/Phyrexian/two-brid or other unsupported symbols, target-dependent or
dynamic changes, additional costs, alternative payments, restricted/rider-bearing mana, and
unsupported source shapes remain `paymentDomain = null` and produce the existing
`PAYMENT_DOMAIN_UNSUPPORTED` diagnostic. No card-name or `Unearth` branch is permitted.

## Architecture

### 1. Generic Rules-side ExplicitV2 execution

Add a small reusable `ExplicitPaymentPlanExecutor` in the Rules payment layer. Its only
responsibilities are:

- validate a submitted `PaymentPlanV2` with `PaymentPlanValidator.validateV2`;
- consume the exact submitted floating-pool allocation;
- execute the selected mana abilities through the existing
  `ManaAbilitySideEffectExecutor`;
- materialize unconsumed fixed outputs and provenance through the existing accepted
  `ExactPaymentMaterialization`; and
- emit the normal `ManaSpentEvent` plus the side-effect events.

The executor must not calculate spell/ability costs, apply permissions or reductions, select a
context, choose targets, call `ManaSolver.solve`, or fall back to AutoPay/FromPool or legacy
source-ID payment. It accepts the already-authoritative cost, non-null payment context, source
exclusions, and event reason from its caller. `ManaSpentEvent.reason` is caller-owned (for example,
`Cast X` versus `Cycle X`); the executor knows no action type.

If V2 validation or selected-source materialization fails, the executor returns the original state,
no partial payment events, and the error. Existing CastSpell V2 materialization should use this
executor so there is one generic V2 materialization implementation, while CastSpell-specific cost
and permission logic remains in `CastSpellHandler`/`CastPaymentProcessor`.

`CycleCardHandler` will:

- retain its existing legacy behavior for non-Gym callers;
- recognize `ExplicitV2` only for a fixed ordinary cycling cost;
- construct the canonical activated-ability context once and use it for affordability, V2
  validation/materialization, and the legacy solver path;
- validate the exact plan without mutating state in `validate`; and
- revalidate/materialize the plan through the shared executor before the existing discard, cycling
  event, trigger, and draw sequence in `execute`.

Any rejected or incomplete plan returns the original state and emits no payment or cycling events.
The CycleCard caller supplies the canonical activated-ability context; it does not use a CastSpell
context or a null/unrestricted context.

### 2. Exact CycleCard domain certification

Extend `ObservationBuilder.paymentDomainFor` with a `CycleCard` branch that resolves the card's
plain cycling ability, constructs the canonical activated-ability context, and proves the following
before calling `PaymentDomainBuilder`:

- the legal action is `CycleCard` and its cycling ability is plain, not typed;
- neither the legal action nor the authoritative cycling cost is an X/dynamic form;
- the parsed authoritative cost equals the legal action's advertised cost; and
- all symbols are ordinary fixed colored, colorless, or generic symbols.

The builder receives the authoritative fixed cost string and the canonical non-null context. It is
the same source/profile/provenance builder used by other V4 actions. If any proof fails, it returns
null rather than publishing an optimistic or partial domain.

`CyclingEnumerator` must construct and reuse the same context for `ManaSolver.canPay` and
`ManaSolver.solve` when it sets `LegalAction.affordable` and `autoTapPreview`. This keeps
enumeration, public certification, and authoritative execution aligned for context-sensitive mana
abilities; no context-free affordability or solver preview is permitted for plain Cycling.

### 3. Strict Gym routing

Extend `GameGymEnv.requireActionPaymentPlan` to resolve `CycleCard` strategies. For CycleCard, the
strict boundary accepts only a complete `ExplicitV2` plan with no legacy runtime source handles.
`AutoPay`, `FromPool`, and legacy `Explicit` are rejected before Rules execution. The existing
action serialization already contains `CycleCard.paymentStrategy` and the `ExplicitV2` carrier, so
the public wire schema and replay version do not change.

## Tests

The test sequence is RED → GREEN and must prove the real path:

1. A Gym RED characterization uses corpus `Unearth` and shows fixed `{2}` `CycleCard` publication
   currently fails closed with `PAYMENT_DOMAIN_UNSUPPORTED`.
2. Rules tests prove the shared V2 executor validates/materializes exact plans and that CycleCard
   rejects incomplete/invalid V2 plans without state advancement.
3. A Gym domain test observes real `Unearth`, asserts version 4 and the exact `{2}` cost units,
   then constructs `PaymentPlanV2` solely from the public domain.
4. The complete public plan executes the real CycleCard through Gym: the selected source is the
   source tapped, payment events are emitted, `Unearth` is discarded, and the normal draw path
   completes.
5. Invalid/incomplete plans, unpublished source choices, `AutoPay`, `FromPool`, and legacy
   source-ID-only payment reject without advancing the environment.
6. A mana source whose restriction is not satisfied by the Cycling activated-ability context must
   not make cycling affordable and must not be accepted as a CycleCard payment source. If the
   restricted shape is not representable by V4, the complete action remains fail-closed.
7. At least one existing CastSpell ExplicitV2 end-to-end test proves that extracting the shared
   executor preserves pool spend, source activation, side effects, unspent fixed outputs,
   `ManaSpentEvent`, and provenance semantics.
8. Dynamic/X, hybrid/unsupported-symbol, typed-cycling, and any future additional-payment shapes
   remain fail-closed with `paymentDomain = null`.

## Documentation and scope

Update the action-level payment contract documentation to include fixed ordinary `CycleCard`
actions and the `ExplicitV2` requirement. No SDK vocabulary, card definition, decklist, corpus,
replay schema, or PR #73 file changes are in scope.

## Verification and post-merge gate

Run focused Rules and Gym regressions, the full affected Rules/Gym suites, hosted CI, and an
independent final-diff review. Record `PASS`, `FAIL`, `NOT_RUN`, `SKIPPED`, or `BLOCKED` separately
for wrapper, native fallback, hosted, and corpus evidence.

After the change is merged, synchronize PR #73 without modifying its files, rerun the exact 72
episodes from `0/72`, confirm that episode 3 / game seed `1` / external step `1314` is crossed, and
stop at the first NEW failure.

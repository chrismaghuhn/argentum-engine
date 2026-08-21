# A5 CastSpell PaymentDomainV1 Design

## Scope

Extend the existing `PaymentDomainV1` / `PaymentPlanV1` contract from
`ActivateAbility` to ordinary fixed-cost `CastSpell` actions. The change spans
the Rules payment boundary, Gym observations and trusted action submission,
replay/fingerprint semantics, and the associated regression gates.

The implementation starts from the exact accepted `origin/main` head
`e2bb6f9e78e2136fd1e313b70ad2efa03fdd61ec` on branch
`chris/a5-castspell-payment-domain-v1`. PR #73 and its worktree are outside the
scope and must not be modified.

## Problem and confirmed RED

At the accepted base, `CastPaymentProcessor` treats an explicit strategy as the
legacy source-ID path. Its `explicitPay` implementation derives exclusions and
delegates to `autoPay`, which can call `ManaSolver.solve()`. A submitted
`PaymentPlanV1` is therefore ignored instead of being the complete payment
choice. The first implementation test will capture this failure for an
ordinary `{1}{B}` CastSpell before production code changes.

The supported path must make the external plan authoritative. Once a non-null
`paymentPlan` has been submitted for an action that publishes a payment domain,
execution must not enter `autoPay`, `payFromPool`, `ManaSolver.solve()`, or the
legacy source-ID-only branch.

## Design

### One Rules-owned spell payment context

The existing Rules `SpellPaymentContext` construction becomes the only context
definition used by the enumerator, `CastSpellHandler`, and the Gym domain
publisher. The shared helper carries card types, instant/sorcery and creature
status, legendary status, printed mana value, X presence, subtypes, and the
hand/exile zone flags. Face-down casts retain their existing dedicated context.

Gym must not reconstruct an independent approximation of cast semantics.

### Domain publication is conservative and cost-authoritative

The canonical observation/domain publication function is reused by both
`ObservationBuilder` and the trusted Gym guard. Conceptually, the guard is
based on `legalAction.paymentDomain != null`; it does not have independent
`ActivateAbility` and `CastSpell` policies.

An ordinary `CastSpell` may publish a domain only when all of the following are
true:

- the action is the ordinary fixed-cost CastSpell variant;
- `manaCostString` is present and is already the final effective cost for this
  exact action, not a printed, minimum, alternative, or pre-reduction cost;
- no X value, alternative cost, cost reduction choice, kicker or other
  payment-affecting choice remains unresolved;
- there is no convoke, delve, tap-for-generic, harmonize, secondary mana cost,
  additional mana cost, face-down payment shape, splice, mode-dependent cost,
  or other payment choice that V1 cannot encode;
- the cost parser accepts only ordinary colored, colorless, and generic units;
  X, hybrid, Phyrexian, and twobrid symbols fail closed; and
- `PaymentDomainBuilder` accepts the available mana pool and sources without
  restricted/provenance-bearing floating mana, riders, bonus or multi-mana
  production, source restrictions, or other unrepresented choices.

For an eligible action, the published `requiredCost` is the enumerator's
authoritative effective `manaCostString`. The handler independently computes
the effective cost at validation/execution and validates the submitted plan
against that exact cost and the same shared `SpellPaymentContext`. If the
action's cost can no longer be proven fixed and final, no domain is published
and the unsupported diagnostic is emitted.

Unsupported shapes remain `paymentDomain = null` and fail closed with
`PAYMENT_DOMAIN_UNSUPPORTED` at the external-policy/diagnostic boundary. This
milestone does not attempt lossy reconstruction of unsupported payment
semantics.

### Exact PaymentPlanV1 execution

`CastSpellHandler` uses the existing `PaymentPlanValidator` for a non-null
explicit plan. The validation path supplies the handler's effective cost and
the shared spell context, rejects legacy source IDs alongside a plan, and
returns the existing payment validation error for invalid or unsupported
plans. It never falls through to the legacy explicit validation branch.

`CastPaymentProcessor` adds a direct plan path before the legacy explicit path:

1. Validate the plan with `PaymentPlanValidator`; validation may discover
   available sources but must not solve for a different payment.
2. Apply the validator's exact pool remainder and selected source activations
   to the current immutable state.
3. Use the existing mana-ability side-effect executor so source taps and
   source-specific side effects retain normal Rules behavior.
4. Emit the normal mana-spent events and preserve
   `SpentManaProvenance`/source provenance derived from the accepted solution.

The direct path must not call `autoPay`, `payFromPool`, `ManaSolver.solve()`,
or consume legacy source-ID-only handles. The existing legacy explicit path
remains available only for callers that do not submit a plan; the trusted Gym
guard prevents that path for any action that published a domain.

### Trusted Gym boundary

`GameGymEnv` asks the same canonical observation/domain function whether the
resolved legal action published a domain. If it did, the submitted action must
carry `PaymentStrategy.Explicit` with a non-null `paymentPlan` and an empty
legacy `manaAbilitiesToActivate` list. `AutoPay`, `FromPool`, and legacy
Explicit submissions are rejected before execution. Actions without a
published domain are not silently upgraded; the existing unsupported/invalid
action behavior remains in force.

Diagnostics cover both domain-backed ability and ordinary cast candidates that
have a mana cost but cannot publish a complete V1 domain, so an external policy
sees `PAYMENT_DOMAIN_UNSUPPORTED` rather than a null-domain payment gap.

### Contract, replay, and digest semantics

`PaymentPlanV1` remains the sole serialized payment model. CastSpell actions
use the existing `GameAction` serializer and replay reconstruction; no second
spell-specific payment schema is introduced. Tests prove encode/decode and
reconstruction for a CastSpell plan.

The existing canonical observation path includes `paymentDomain` in semantic
action fingerprints and `StateDigest`. Add CastSpell coverage for fork and
snapshot preservation and for digest changes when the domain changes. Bump
`SchemaHash` because CastSpell observations now expose payment semantics.

## Verification matrix

Add tests before implementation and keep the first RED visible:

1. Ordinary `{1}{B}` CastSpell with an explicit plan: RED on the old
   processor path, then GREEN with exact plan execution.
2. Explicit multicolor source production choice.
3. Explicit controller-selected generic floating-pool remainder.
4. Trusted Gym rejects AutoPay, FromPool, and legacy Explicit for a published
   domain and accepts only a complete PaymentPlanV1.
5. Unsupported cast-payment shapes publish no domain and emit
   `PAYMENT_DOMAIN_UNSUPPORTED`.
6. CastSpell PaymentPlanV1 replay encode/decode/reconstruct.
7. Payment domain survives fork/snapshot and participates in StateDigest.
8. The exact seed-0 / step-163 reproducer no longer reports the `{1}{B}`
   payment gap after the separate fix is merged into its base.

Retain all existing ActivateAbility payment-domain coverage. Run focused Rules
and processor tests, engine tests, Gym tests, server/replay tests, and the
FrozenBaseline suite. Inspect the first semantic divergence and do not blindly
rebless snapshots. Run fresh Hosted CI on the final exact head; classify the
known Windows `WinError 193` launcher issue as infrastructure BLOCKED rather
than PASS if it recurs. Open a Draft PR only, never merge, and never modify
PR #73.

## Non-goals

- No support for X, hybrid, Phyrexian, twobrid, restricted/provenance-bearing,
  rider-bearing, bonus/multi-mana, secondary-cost, or unresolved alternative
  payment shapes in V1.
- No second spell payment model or spell-specific solver.
- No changes to PR #73, its branch, its worktree, or its locked acceptance
  test.
- No automatic merge of the resulting Draft PR.

## Alternatives considered

The selected approach keeps the accepted generic PaymentPlanV1 contract and
adds one shared spell-context boundary plus an exact CastSpell execution path.
A larger generic `PaymentPlanExecutor` refactor could reduce future duplication,
but would widen the diff around the accepted ActivateAbility path. A Gym-only
guard would leave the Rules processor ignoring submitted plans and would
violate the critical invariant, so it is insufficient.

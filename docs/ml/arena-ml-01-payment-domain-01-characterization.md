# ARENA_ML_01_PAYMENT_DOMAIN_01 — PaymentDomainV5 RED Characterization

Characterization only. No fix, no behavior change, no production code change in this task.
Remediated per first exact-SHA review (2026-09-16): boundary pool evidence is now durable
(test-only assertion inside the accepted ARENA_ML_01 smoke), the turn count was corrected
to the authoritative value, and L3 wording was narrowed to the actual evidence strength.

```text
TASK = ARENA_ML_01_PAYMENT_DOMAIN_01
BASE_SHA = 77058bc97e6c7bde68acf6560605625dee8b38ca  (fork main at task creation)
BRANCH = chris/arena-ml-01-payment-domain-01-characterization-20260916
PAYMENT_LOWER_LEVEL_ROOT_CAUSE = CHARACTERIZED (see L5)
```

## Accepted boundary being characterized (L0)

Source: ARENA_ML_01 smoke (PR #207, `docs/ml/arena-ml-01-smoke-report.md`), deterministic game
seed 20260916, policy seed 20260901, starting player P1 (Akiri on the play).

At decision #71 — ML seat (Akiri) priority, `phase=BEGINNING`, `step=UPKEEP`, authoritative
`turnNumber=7` (Akiri's fourth turn) — Shadowspear's paid `{1}` activated ability was present
in the legal/affordable action menu while the trusted observation carried
`PAYMENT_DOMAIN_UNSUPPORTED`, and `LivePolicySourceAdapter` failed closed with
`UNSUPPORTED_STRUCTURED_DECISION`. No model request was sent for the rejected boundary; no
fallback was used.

The provenance state of Akiri's pool at that exact boundary is now **durable, committed
evidence**: the accepted ARENA smoke test captures the acting seat's `ManaPoolComponent` plus
the Rules-owned provenance classification in its rejection branch and asserts the exact values
(`BOUNDARY_POOL` line in the rendered report, re-verified on real CUDA for this remediation):

```text
turn=7 step=UPKEEP
white=1, blue=0, black=0, red=0, green=0, colorless=0
manaBySubtype={Plains=1}
manaBySource={}
manaBySourceAndColor={}
manaByFloatingBucket={}
manaProvenanceCompleteness=INCOMPLETE
classification=Ambiguous(reason="source and subtype provenance must both identify the pool")
```

```text
white=1, blue=0, black=0, red=0, green=0, colorless=0
manaBySubtype={Plains=1}
manaBySource={}
manaBySourceAndColor={}
manaByFloatingBucket={}
manaProvenanceCompleteness=INCOMPLETE
```

Akiri's battlefield at that boundary is understood to have been two tapped Plains, one
untapped Mountain, and Shadowspear, with one floated white spent by the previous accepted ML
decision — but these are **historical/derived observations only, not part of the durable
root-cause evidence**: the committed `BOUNDARY_POOL` assertion pins the pool provenance and
classification (above), not the battlefield composition or the spending action. The durable
pool/classification evidence above is the complete accepted link from the real run to the
root cause.

## Exact reproducer (minimal, CPU-only, deterministic)

`gym/src/test/kotlin/com/wingedsheep/gym/contract/ShadowspearPaymentDomainV5RedCharacterizationTest.kt`

Fixture (generic engine/card infrastructure, same pattern as
`GameGymEnvDeterministicActivatedCostPaymentTest`): one player controls Shadowspear plus two
untapped Plains, holds priority in their precombat main phase; Shadowspear's paid `{1}`
activation is the first declared activated ability (`activatedAbilities[0]`).

Sequence:

1. Activate each Plains mana ability once — the pool now holds two certified whites
   (`white=2`, `manaBySource` with 2 entries, `manaByFloatingBucket` non-empty,
   `COMPLETE`). V5 publishes normally at this point (working control).
2. Execute Shadowspear's `{1}` activation through the engine's ordinary activation path.
   Because both Plains are already tapped, the legacy auto-tap payment seam spends exactly
   one floated white from the pool.
3. The post-payment pool is then bit-identical to the L0 boundary pool:
   `white=1`, `manaBySubtype={Plains=1}`, `manaBySource={}`, `manaByFloatingBucket={}`,
   `INCOMPLETE`.
4. `paymentDomainV5For(state, activation)` returns null for the same state/action, and the
   whole-observation build emits `PAYMENT_DOMAIN_UNSUPPORTED`.

No CUDA, no model, no worker. No card-name special case exists in production code.

## Evidence ladder

| Layer | Status | Evidence |
| --- | --- | --- |
| L0 ARENA boundary | PROVEN | Accepted smoke + **durable committed evidence**: the smoke test itself asserts the exact boundary pool shape and `Ambiguous` classification at decision #71 (`BOUNDARY_POOL`), re-verified on real CUDA |
| L1 minimal state | PROVEN | Fixture above; engine-legal menu at precombat main |
| L2 legal action | PROVEN | Menu contains exactly one `ActivateAbility` for `sourceId`/`abilityId`; `affordable=true`, `manaCostString="{1}"` (the live menu is the engine's affordability evidence here) |
| L3 payment request / context | PROVEN_BY_CODE_PATH + PRESTATE_DISCRIMINATOR | `paymentDomainFor` (V4) — which shares the private `paymentDomainRequestFor` seam with V5 — publishes a non-null domain for the identical action at the pre-payment state (same request inputs). At the boundary state V4 also returns null, because V4's own pool admission rejects the same degraded provenance; the discriminator therefore proves the request seam works for this action, while that the request is constructed at the boundary state itself rests on the shared code path (static evidence), not on an independent empirical probe |
| L4 V5 builder entry | PROVEN | Refusal isolated below request: V5-specific `reservedOuterLifePaymentForV5` and request succeed; null comes from the initial-pool admission inside `buildV5` |
| L5 exact rejecting condition | PROVEN | `ManaPoolComponent.toV5InitialPoolBuckets` (gym, `PaymentDomain.kt`) classifies the pool via `FloatingManaProvenanceClassification.classify` (rules-engine, `FloatingManaProvenance.kt`). The boundary shape (`manaBySubtype` non-empty, `manaBySource` empty) hits `Ambiguous("source and subtype provenance must both identify the pool")`; the `Ambiguous` arm maps to `null`. Test asserts the exact reason string and the exact pool shape |
| L6 diagnostic propagation | PROVEN | `ObservationBuilder.build` marks every affordable action with non-null `manaCostString` whose `paymentDomainV5For(...) == null` and adds `DiagnosticSignal(PAYMENT_DOMAIN_UNSUPPORTED)` to the whole observation — reproduced in the RED L6 test |

## Exact failing function / condition

```text
FAILURE_FILE = rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/FloatingManaProvenance.kt
FAILURE_FUNCTION = FloatingManaProvenanceClassification.classify(pool)
FAILURE_CONDITION =
    if (pool.manaBySource.isEmpty() || pool.manaBySubtype.isEmpty()) {
        return Ambiguous("source and subtype provenance must both identify the pool")
    }
FAILURE_VALUE_OR_MISSING_INPUT = manaBySource is empty while manaBySubtype={Plains=1};
    the subtype-only provenance shape is not certifiable for payment planning
ADMISSION_SITE = gym/.../contract/PaymentDomain.kt, ManaPoolComponent.toV5InitialPoolBuckets,
    `is FloatingManaProvenanceClassification.Ambiguous -> null`
PROPAGATION = gym/.../contract/ObservationBuilder.kt build() -> PAYMENT_DOMAIN_UNSUPPORTED
DURABLE_L0_LINK = game-server ArenaMl01RealModelVsEngineSmokeTest asserts the exact
    BOUNDARY_POOL (pool shape + classification) at decision #71 on real CUDA
```

### How the subtype-only shape is produced (engine-authentic, proven by the reproducer)

`ManaPoolComponent.consumeProvenance(unrestrictedSpent)` (rules-engine, `ManaPool.kt`) reduces
the pre-spend `manaBySubtype` and `manaBySource` counters **independently** and proportionally
(`min(count, unrestrictedSpent)` each), then stamps `INCOMPLETE`. Pre-spend
`white=2 / manaBySubtype={Plains:2} / manaBySource={e5:1, e12:1}` minus one unit therefore
yields exactly `manaBySubtype={Plains:1} / manaBySource={}`. This legacy proportional seam is
reached via `withNormalizedProvenanceAfterSpend`/`consumeProvenance` in the ordinary
auto-tap/activation payment path (e.g. `ActivateAbilityHandler` cost handling); it deliberately
cannot know which fungible unit was consumed, so it degrades the provenance. The V5 admission
then correctly fails closed because the remaining pool can no longer be certified.

This is the lower-level root cause: **the proportional legacy provenance-consumption seam can
degrade a fully certified joint pool into a shape that no certified classification admits**
(`CertifiedJoint` requires the exact floating buckets, which the seam empties;
`CertifiedHomogeneous`/`Heterogeneous` require source detail, which the seam drops; the
subtype-only remainder is classified `Ambiguous`), while the engine's own payment execution
continues to function. Payment-domain publication then refuses the whole candidate.

The real ARENA run reached exactly this state: the durable `BOUNDARY_POOL` assertion (above)
proves the decision-#71 pool had precisely the degraded shape, classified `Ambiguous` with the
exact reason, closing the L0→L5 chain with committed, re-runnable evidence.

## Prior-hypothesis correction

The standing hypothesis "PaymentDomainV5 does not support paid activated abilities" is disproved:

- `paymentDomainRequestFor`'s ActivateAbility branch constructs a full request for this action,
  proven indirectly (V4 publishes for identical request inputs at the pre-payment state).
- The working controls show Shadowspear's own `{1}` activation publishes a **complete V5
  domain** when the pool holds two certified floating whites, and Mind Stone's paid `{1}`
  activation publishes V5 in the same fixture shape.

The failure is conditional on the **pool provenance state**, not on the action class.

## Controls

```text
WORKING_CONTROL_1 = floating two certified whites (both Plains abilities) keeps V5 publishable
WORKING_CONTROL_2 = Mind Stone paid {1} activation publishes complete V5 in the same fixture shape
NEGATIVE_CONTROL = no mana resource: engine lists the activation affordable=false; V5 publishes
    neither a pool bucket nor a spendable source for it
```

## Magic-rules note

No rules conclusion is required for this characterization: the refusal is engine-internal
(provenance certification), not a Magic legality question. The engine's affordability of the
activation with one floated white is consistent with CR 106.4/107.4b-adjacent expectations
(floated generic-payable mana), but no card or rule verdict is issued here. Shadowspear Oracle
semantics were not needed; its card definition is untouched.

## Scope classification

```text
PRODUCTION_BEHAVIOR_CHANGED = NO
PAYMENT_FIX_IMPLEMENTED = NO
RULES_CHANGED = NO
CARD_DEFINITION_CHANGED = NO
ML_CHANGED = NO
```

Files changed in this task: the characterization test (gym test sourceset), the smoke test's
test-only durable boundary-pool assertion (game-server test sourceset), and this report. No
production file is touched; the smoke change is evidence capture/assertion inside the existing
test only.

## Likely fix ownership (diagnostic label only, no fix implemented)

```text
LIKELY_FIX_OWNERSHIP = PAYMENT_CONTEXT_GAP  (provenance-consumption representation gap)
```

Rationale: the certified pool is produced and consumed correctly by payment execution; the gap
is that the legacy proportional consumption seam destroys the exact joint buckets the certified
classification requires, and no certified class admits the resulting subtype-only shape. The
smallest likely fix location is the provenance-consumption seam (or a certified class for the
degraded shape), not `PaymentDomainV5` semantics and not legal-action generation.

## Proposed follow-up (separate scope, requires independent authorization)

`ARENA_ML_01_PAYMENT_DOMAIN_02`:

1. Decide and implement the smallest generic provenance-preserving change — either make the
   legacy consumption seam preserve exact joint buckets when the classification is
   `CertifiedJoint` (preferred: keeps single representation authority), or introduce a
   certified class for certified-homogeneous-derived shapes. A card-name special case or a
   Shadowspear-specific branch is explicitly forbidden.
2. Tests that must flip from characterized-unsupported to supported: the RED tests in
   `ShadowspearPaymentDomainV5RedCharacterizationTest` (L4/L5 and L6 tests), while keeping the
   negative control red-if-broken.
3. Required surrounding regressions: `gym:contract.*` (351 tests), the V5
   `PaymentDomainV5ContractTest`, `GameGymEnvDeterministicActivatedCostPaymentTest` and the
   `GameGymEnvDeterministic*` suites, rules-engine mana suites, plus the game-server policy
   suites and (only after CI-style green) the ARENA_ML_01 real CUDA smoke to confirm the game
   proceeds past decision #71.

## Hard stop

Characterization complete. No fix is implemented here; `_02` must receive a separate scope and
independent authorization.

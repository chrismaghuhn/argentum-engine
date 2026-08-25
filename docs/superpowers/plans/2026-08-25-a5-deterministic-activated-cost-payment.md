# A5 Deterministic Activated-Ability Cost Payment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish and execute real activated abilities whose effective cost contains only ordinary mana, `TapSelf`, and `SacrificeSelf` through the existing explicit Gym payment contract, while rejecting every unresolved choice-bearing additional cost.

**Architecture:** Add one Rules-owned pure certificate that derives the canonical `AdditionalCostPayment` from the effective `AbilityCost` and activated source ID. Reuse that certificate in `ActivateAbilityHandler` for pre-mutation exact validation and in `ObservationBuilder.paymentDomainFor` for the narrow positive publication slice. Keep `PaymentDomainV4` mana-only, leave `ActionPayloadRequirements` and the existing public `AdditionalCostPayment` wire shape unchanged, and update the contract documentation to describe the deterministic exception.

**Tech Stack:** Kotlin/JVM, Kotlin serialization, Kotest, Rules ECS, Gym `PaymentDomainV4`/`PaymentPlanV2`/`ExplicitV2`, Gradle via `just` with native `gradlew.bat` fallback on this Windows host.

---

### Task 1: Add the real Wayfarer’s Bauble RED characterization

**Files:**
- Create: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvDeterministicActivatedCostPaymentTest.kt`

- [ ] **Step 1: Write the failing real-card publication test**

Create a Gym test fixture that registers `PortalSet.cards`, `PortalSet.basicLands`, the real
`WayfarersBauble`, and a small real `MindStone` inventory. Reset a two-player environment, advance
the active player to `PRECOMBAT_MAIN`, move Wayfarer’s Bauble and two Mountains to that player’s
battlefield, and restore the prepared state. Find the real legal `ActivateAbility` for Wayfarer and
build a one-action `TrainingObservation` through `ObservationBuilder` so the characterization is not
masked by unrelated legal actions. Assert the desired contract before changing production code:

```kotlin
val view = ObservationBuilder(cardRegistry = registry())
    .build(environment.state, player, listOf(wayfarerAction))
    .observation
    .let { it as TrainingObservation }
    .legalActions
    .single()

view.requiredPayloadFields shouldBe listOf("paymentStrategy", "costPayment")
view.sourceEntityId shouldBe wayfarerId
view.validSacrificeTargets shouldBe listOf(wayfarerId)
view.paymentDomain shouldNotBe null
view.paymentDomain!!.version shouldBe 4
view.paymentDomain!!.requiredCost shouldBe "{2}"
view.paymentDomain!!.sourceActivations.any { it.sourceId == wayfarerId } shouldBe false
```

Use only the source ID from the public view in later payload construction; do not reach into a
private card definition to fill a payment field.

- [ ] **Step 2: Run the RED test without production changes**

Run the focused native fallback because the `just` wrapper is known to fail before Gradle on this
Windows host:

```powershell
.\gradlew.bat :gym:test --tests com.wingedsheep.gym.GameGymEnvDeterministicActivatedCostPaymentTest
```

Expected result: FAIL at the new assertion because the current blanket
`legalAction.additionalCostInfo != null` branch leaves `paymentDomain` null. Do not run any corpus.

- [ ] **Step 3: Commit the RED characterization**

```powershell
git add gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvDeterministicActivatedCostPaymentTest.kt
git commit -m "test: characterize deterministic activated cost publication"
```

### Task 2: Add the Rules-owned deterministic cost certificate

**Files:**
- Create: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/cost/DeterministicAdditionalCostPayment.kt`
- Create: `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/cost/DeterministicAdditionalCostPaymentTest.kt`

- [ ] **Step 1: Write unit tests for the certificate contract**

Define the test target as `DeterministicAdditionalCostPayment.expectedFor(cost, sourceId)`. Cover the
approved and rejected shapes before creating the production object:

```kotlin
test("Mana + Tap + SacrificeSelf binds both existing payload lists to source") {
    DeterministicAdditionalCostPayment.expectedFor(
        Costs.Composite(Costs.Mana("{2}"), Costs.Tap, Costs.SacrificeSelf),
        EntityId("source"),
    ) shouldBe AdditionalCostPayment(
        tappedPermanents = listOf(EntityId("source")),
        sacrificedPermanents = listOf(EntityId("source")),
    )
}

test("choice-bearing permanent costs are not certified") {
    DeterministicAdditionalCostPayment.expectedFor(
        Costs.Composite(
            Costs.Mana("{1}"),
            Costs.TapPermanents(count = 1, filter = GameObjectFilter.Creature),
            Costs.SacrificePermanents(filter = GameObjectFilter.Permanent),
        ),
        EntityId("source"),
    ) shouldBe null
}

test("self-bound slice has no card-name input") {
    DeterministicAdditionalCostPayment.expectedFor(
        Costs.Composite(Costs.Mana("{1}"), Costs.Tap, Costs.SacrificeSelf),
        EntityId("renamed-source"),
    ) shouldBe AdditionalCostPayment(
        tappedPermanents = listOf(EntityId("renamed-source")),
        sacrificedPermanents = listOf(EntityId("renamed-source")),
    )
}
```

Also assert that unsupported costs such as `ExileSelf`, `ReturnSelfToHand`, variable quantities,
discard, and target/domain-dependent costs return `null`; the first slice must not accidentally
expand while implementing the classifier. Reject a composite containing more than one `Tap` or
more than one `SacrificeSelf` so the certificate never claims that a source is paid more than once.

- [ ] **Step 2: Run the unit tests to establish the implementation RED**

```powershell
.\gradlew.bat :rules-engine:test --tests com.wingedsheep.engine.mechanics.cost.DeterministicAdditionalCostPaymentTest
```

Expected result: compilation failure because the new Rules-owned certificate does not yet exist.

- [ ] **Step 3: Implement the smallest pure certificate**

Implement an `internal object` with no `GameState`, `LegalAction`, card registry, card-name, or
payload input. Recursively walk `AbilityCost`:

```kotlin
internal object DeterministicAdditionalCostPayment {
    fun expectedFor(cost: AbilityCost, sourceId: EntityId): AdditionalCostPayment? =
        collect(cost)?.let { counts ->
            AdditionalCostPayment(
                tappedPermanents = List(counts.tapCount) { sourceId },
                sacrificedPermanents = List(counts.sacrificeCount) { sourceId },
            )
        }
}
```

The private collector accepts `AbilityCost.Atom` only when its atom is ordinary `CostAtom.Mana`,
accepts one `AbilityCost.Tap`, accepts one `AbilityCost.SacrificeSelf`, and combines only a flat
`AbilityCost.Composite` whose direct children have those shapes. Nested composites and every other
`AbilityCost` or `CostAtom` return `null`. Keep all other `AdditionalCostPayment` fields at their
defaults. The function must be pure and must not mutate a `GameState` or select a candidate.

- [ ] **Step 4: Run the unit tests to verify the certificate GREEN**

```powershell
.\gradlew.bat :rules-engine:test --tests com.wingedsheep.engine.mechanics.cost.DeterministicAdditionalCostPaymentTest
```

Expected result: all certificate cases PASS.

### Task 3: Use the certificate in authoritative Rules validation

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/ability/ActivateAbilityHandler.kt:effective-cost validation section`

- [ ] **Step 1: Add handler tests before changing the handler**

Once Task 4's publication gate is GREEN, extend the new Gym test fixture with payload helpers that serialize the existing
`AdditionalCostPayment` and a public `PaymentStrategy.ExplicitV2` built solely from
`LegalActionView.paymentDomain`:

```kotlin
fun selfCostPayment(view: LegalActionView) = AdditionalCostPayment(
    tappedPermanents = listOf(view.sourceEntityId!!),
    sacrificedPermanents = listOf(view.sourceEntityId),
)

fun payload(
    view: LegalActionView,
    strategy: PaymentStrategy,
    costPayment: AdditionalCostPayment? = selfCostPayment(view),
) = buildJsonObject {
    view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
    put("paymentStrategy", actionJson.encodeToJsonElement(PaymentStrategy.serializer(), strategy))
    costPayment?.let {
        put("costPayment", actionJson.encodeToJsonElement(AdditionalCostPayment.serializer(), it))
    }
}
```

Before the handler change, add tests for (a) a missing `costPayment` field, (b) a wrong source ID,
(c) an extra non-empty `discardedCards` field, and (d) an invalid V2 allocation. Each must snapshot
`environment.state`, `stepCount`, and `lastStepEvents`, call `gym.step`, expect
`IllegalArgumentException`, and assert the snapshots are unchanged.

- [ ] **Step 2: Run the new rejection tests to confirm the handler RED**

```powershell
.\gradlew.bat :gym:test --tests com.wingedsheep.gym.GameGymEnvDeterministicActivatedCostPaymentTest
```

The missing-field test may already fail at the Gym field guard; the wrong-source and extra-field
tests must expose the current Rules gap by accepting or mutating the action after publication is
enabled. Keep the evidence separate for the two validation layers.

- [ ] **Step 3: Add pre-mutation exact validation to `ActivateAbilityHandler.validate`**

Immediately after the existing `ActivatedAbilityCostCalculator.calculate` call, derive the expected
payload from that `effectiveCost` and `action.sourceId`. Only enforce presence when the Rules-derived
payment contains a `SacrificeSelf` entry (the current enumerator then publishes `additionalCostInfo`
and Gym requires `costPayment`) and the action uses `PaymentStrategy.Explicit` or
`PaymentStrategy.ExplicitV2`;
preserve legacy/non-structured `null` callers. If a non-null payload is supplied on any path, require
exact equality with the Rules-derived value. Return a validation error before explicit payment-plan
validation and before `executeActivation` can call `CostHandler`:

```kotlin
val expectedAdditionalCostPayment =
    DeterministicAdditionalCostPayment.expectedFor(effectiveCost, action.sourceId)
if (expectedAdditionalCostPayment != null && action.costPayment != null &&
    action.costPayment != expectedAdditionalCostPayment
) {
    return "Additional cost payment does not match the deterministic activated-ability cost"
}
if (expectedAdditionalCostPayment != null && action.costPayment == null &&
    expectedAdditionalCostPayment.sacrificedPermanents.isNotEmpty() &&
    (action.paymentStrategy is PaymentStrategy.Explicit ||
        action.paymentStrategy is PaymentStrategy.ExplicitV2)
) {
    return "Explicit activated-ability payment must include costPayment"
}
```

Do not change `CostHandler`’s deterministic source-bound execution; its existing `Tap` and
`SacrificeSelf` branches remain the sole mutation authority. Do not add a fallback that copies the
source ID into a missing payload. Choice-bearing cost handling remains unchanged and is still gated
off by Gym publication.

- [ ] **Step 4: Run the handler rejection tests GREEN**

```powershell
.\gradlew.bat :gym:test --tests com.wingedsheep.gym.GameGymEnvDeterministicActivatedCostPaymentTest
```

Expected result: all invalid/missing payload and invalid-plan cases reject without changing state,
step count, events, source tap state, or source zone.

### Task 4: Certify only the supported publication slice

**Files:**
- Modify: `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt:paymentDomainFor`
- Test: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvDeterministicActivatedCostPaymentTest.kt`

- [ ] **Step 1: Write publication and fail-closed assertions**

Add tests for all of the following:

```kotlin
test("real Wayfarer uses the public V4 domain and explicit source-bound cost payload") {
    val prepared = preparedWayfarer()
    val view = wayfarerView(prepared)
    view.requiredPayloadFields shouldBe listOf("paymentStrategy", "costPayment")
    view.paymentDomain shouldNotBe null
    view.paymentDomain!!.requiredCost shouldBe "{2}"
}

test("real Mind Stone uses the same cost-shape certificate without a card-name branch") {
    val prepared = preparedMindStone()
    val view = mindStoneView(prepared)
    view.paymentDomain shouldNotBe null
    view.paymentDomain!!.requiredCost shouldBe "{1}"
}

test("variable sacrifice and selected tap costs remain PAYMENT_DOMAIN_UNSUPPORTED") {
    val prepared = preparedChoiceCost()
    val result = ObservationBuilder(cardRegistry = prepared.cardRegistry).build(
        prepared.environment.state,
        prepared.playerId,
        listOf(prepared.legalAction),
    )
    result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
    (result.observation as TrainingObservation).legalActions.single().paymentDomain shouldBe null
}
```

For the synthetic choice test, use a separately named `card` whose activated cost is a composite of
ordinary mana, `Costs.TapPermanents(...)`, and `Costs.SacrificePermanents(...)`, put at least one
candidate creature on the battlefield, and inspect a real legal action. Assert the view’s domain is
null and the builder/Gym diagnostic is exactly `PAYMENT_DOMAIN_UNSUPPORTED`. Do not manufacture a
`LegalAction` whose action cannot resolve to the synthetic ability.

- [ ] **Step 2: Implement the narrow `ObservationBuilder` certificate gate**

For `ActivateAbility`, calculate the same effective cost using the existing
`activatedAbilityCostCalculator`, passing the action’s targets and explicit equip choice exactly as
the handler does. Call `DeterministicAdditionalCostPayment.expectedFor`. Return `null` for the entire
activated-ability branch when the certificate is absent. This makes `ExileSelf`, `ReturnSelfToHand`,
PayLife, variable costs, selected taps/sacrifices, and all other non-approved shapes fail closed even
when the enumerator did not create `additionalCostInfo`. Keep the existing X/convoke/waterbend,
alternative-payment, equip, and target-dependent guards.

For the positive slice, continue to use `PaymentDomainBuilder` and exclude `action.sourceId` from
source activations when the expected payload contains the source in `tappedPermanents`. Do not add
fields to `PaymentDomainV4`, `LegalActionView`, or `ActionPayloadRequirements`.

- [ ] **Step 3: Run the publication and fail-closed tests GREEN**

```powershell
.\gradlew.bat :gym:test --tests com.wingedsheep.gym.GameGymEnvDeterministicActivatedCostPaymentTest
```

Expected result: Wayfarer and real Mind Stone publish V4; the synthetic choice ability yields the
typed unsupported diagnostic; no AutoPay or source-ID fallback is accepted by the existing Gym guard.

### Task 5: Prove the full strict execution and atomicity contract

**Files:**
- Test: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvDeterministicActivatedCostPaymentTest.kt`
- Modify: `docs/data-contracts.md:Gym structured decision observations / PaymentDomainV4`

- [ ] **Step 1: Execute Wayfarer with public `PaymentPlanV2` and exact cost payload**

Build the plan from `view.paymentDomain` only: select the public Mountain source activation, its
published production choice, the `{2}` cost unit, and two `ManaSpendReferenceV2` units. Submit
`PaymentStrategy.ExplicitV2` plus the exact `AdditionalCostPayment` built from
`view.sourceEntityId`. Assert:

- `view.requiredPayloadFields` is exactly `["paymentStrategy", "costPayment"]`;
- the two Mountains are the only mana sources selected and are tapped by the explicit plan;
- exactly one `TappedEvent` names the Wayfarer source;
- exactly one `PermanentsSacrificedEvent` contains the Wayfarer source;
- the source leaves the battlefield and appears in its graveyard;
- the ability is on the stack after activation and the existing search/selection continuation can
  resolve normally by passing priority and selecting the prepared Mountain.

- [ ] **Step 2: Prove invalid and fallback submissions are atomic**

For fresh fixtures, reject and snapshot unchanged for:

```kotlin
PaymentStrategy.AutoPay
PaymentStrategy.FromPool
PaymentStrategy.Explicit(manaAbilitiesToActivate = listOf(mountainId))
validExplicitV2.copy(paymentPlan = validPlan.copy(spendAllocation = SpendAllocationV2()))
```

Repeat with missing `costPayment`, wrong source ID, an extra list field, and a valid plan paired with
an invalid cost payload. Verify no source tap, sacrifice, mana spend, event, stack entry, or step
increment occurs. This proves no engine-selected fallback or heuristic completion has been added.

- [ ] **Step 3: Document the unchanged public wire shape**

Update `docs/data-contracts.md` to state that the existing `ActivateAbility.costPayment` field is
required for the published deterministic `SacrificeSelf` additional-cost action and that its
`tappedPermanents`/`sacrificedPermanents` lists carry the activated `sourceEntityId` exactly. State
that this is a Rules-certified source-bound acknowledgement, not a selection domain, while all
choice-bearing secondary costs remain fail-closed. Preserve
`argentum-gym-contract@v1.19-required-payload-fields`; no schema/replay bump is allowed because no
serialized type changes.

- [ ] **Step 4: Run the complete focused regression set**

```powershell
.\gradlew.bat :rules-engine:test --tests com.wingedsheep.engine.mechanics.cost.DeterministicAdditionalCostPaymentTest --tests com.wingedsheep.engine.legalactions.ActivatedAbilityEnumeratorTest
.\gradlew.bat :gym:test --tests com.wingedsheep.gym.GameGymEnvDeterministicActivatedCostPaymentTest --tests com.wingedsheep.gym.GameGymEnvPaymentDomainAuthorityTest --tests com.wingedsheep.gym.GameGymEnvActionContractTest
```

Also run the repository-preferred commands separately and record the known wrapper result:

```powershell
just test-class GameGymEnvDeterministicActivatedCostPaymentTest
just test-class DeterministicAdditionalCostPaymentTest
```

The `just` commands may be `BLOCKED/INFRA` with `WinError 193`; the native Gradle commands are the
separately labeled fallback evidence. Do not run the 72-episode corpus.

- [ ] **Step 5: Commit the implementation and documentation**

```powershell
git diff --check
git add rules-engine gym docs/data-contracts.md
git commit -m "feat: support deterministic activated ability additional costs"
```

### Task 6: Independent final-diff review and handoff

**Files:**
- Review: `git diff f3939f72c4bbaa52cc708cb622aa8b5eb255ff5b...HEAD`
- Spec: `docs/superpowers/specs/2026-08-25-a5-deterministic-activated-ability-cost-payment-design.md`
- Plan: `docs/superpowers/plans/2026-08-25-a5-deterministic-activated-cost-payment.md`

- [ ] **Step 1: Run final-diff checks**

Verify the branch starts at the exact requested base, has no unrelated files, contains no card-name
special case, no `PaymentDomainV4` field, no schema/replay bump, no PR #73 modification, and no corpus
command. Run `git diff --check`, `git status --short`, and inspect the complete diff.

- [ ] **Step 2: Request an independent two-axis code/spec review**

Use the code-review/requesting-code-review workflow with fixed point
`f3939f72c4bbaa52cc708cb622aa8b5eb255ff5b`. Provide the reviewer the spec and the explicit
requirements: real Wayfarer V4 publication, public V2 construction, exact source-bound validation,
atomic invalid/fallback rejection, real second `SacrificeSelf`, synthetic choice fail-closed, and no
new domain/schema/card-name logic. Let the reviewer finish before acting on findings.

- [ ] **Step 3: Resolve findings and re-run verification**

Fix every Critical/Important finding, re-run the focused native tests and `git diff --check`, then
record hosted CI as `NOT_RUN` unless it is actually available. Do not create or merge a PR because the
request authorizes implementation and review evidence, not PR creation.

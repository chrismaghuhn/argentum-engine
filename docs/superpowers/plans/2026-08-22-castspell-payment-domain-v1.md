
# A5 CastSpell PaymentDomainV1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing PaymentPlanV1 contract to ordinary fixed-cost CastSpell actions while preserving Rules payment semantics and making every trusted Gym mana boundary fail closed.

**Architecture:** Keep PaymentPlanV1 and PaymentPlanValidator as the single external payment model. Make the Rules-owned SpellPaymentContext helper public to the Gym module, publish a domain only when the concrete LegalAction has a final effective mana cost and no unresolved payment choice, and execute a submitted CastSpell plan directly through validator materialization and existing mana side effects. Route both GameGymEnv step entry points through one generic action-level guard: payable action with no domain is PAYMENT_DOMAIN_UNSUPPORTED; payable action with a domain requires an explicit non-null plan and no legacy handles.

**Tech Stack:** Kotlin, Gradle, Kotest, kotlinx.serialization, Argentum Rules engine, Gym contract, game-server CompactReplay, SHA-256 StateDigest, GitHub Actions.

---

## Repository map and ownership

Production changes are limited to these responsibilities:

- rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentSubtypes.kt owns the shared Rules SpellPaymentContext construction used by the enumerator, handler, and Gym publisher.
- rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastSpellHandler.kt validates a non-null PaymentPlanV1 against the handler's authoritative effective cost before legacy strategy branches and uses the shared spell context in validation and execution.
- rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastPaymentProcessor.kt materializes a submitted plan directly, preserving pool deduction, source side effects, ManaSpentEvent, and provenance without autoPay or solve.
- gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt is the canonical publisher for both ability and CastSpell payment domains; it also emits the unsupported diagnostic for payable actions with no domain.
- gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt enforces the generic trusted action-level mana boundary from both step(actionId) and step(actionId, actionPayload) before execution.
- gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt, gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt, and docs/data-contracts.md document and version the new CastSpell observation semantics.

Tests extend the existing payment, Gym, digest, and replay files:

- rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt
- gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt
- gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt
- gym/src/test/kotlin/com/wingedsheep/gym/A9DiagnosticsTest.kt
- gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt
- game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/PaymentPlanReplayTest.kt

Do not edit the PR #73 worktree, branch, or acceptance test.

### Task 1: Capture the CastSpell RED before production changes

**Files:**

- Modify: rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt

- [ ] **Step 1: Add an ordinary spell fixture and register it.**

Use the existing card DSL and source fixtures:

~~~kotlin
val ordinarySpell = card("Payment Plan Ordinary Spell") {
    manaCost = "{1}{B}"
    typeLine = "Sorcery"
    spell {
        effect = Effects.GainLife(1)
    }
}
~~~

Register ordinarySpell in game() alongside the existing payment fixtures.

- [ ] **Step 2: Write the failing exact-plan CastSpell test.**

Put the spell in hand, put two anyColorSource permanents onto the battlefield, build a plan with one generic allocation and one black allocation, and submit CastSpell with an empty legacy source list:

~~~kotlin
test("CastSpell explicit PaymentPlanV1 is the submitted payment") {
    val (driver, player) = game()
    val spellId = driver.putCardInHand(player, ordinarySpell.name)
    val blackSource = driver.putPermanentOnBattlefield(player, anyColorSource.name)
    val genericSource = driver.putPermanentOnBattlefield(player, anyColorSource.name)

    val result = driver.submit(
        CastSpell(
            playerId = player,
            cardId = spellId,
            paymentStrategy = PaymentStrategy.Explicit(
                paymentPlan = plan(
                    sourceActivations = listOf(
                        SourceActivation(
                            blackSource,
                            key(driver, player, blackSource, PaymentManaColor.BLACK),
                            ProductionChoice(PaymentManaColor.BLACK),
                        ),
                        SourceActivation(
                            genericSource,
                            key(driver, player, genericSource, PaymentManaColor.GREEN),
                            ProductionChoice(PaymentManaColor.GREEN),
                        ),
                    ),
                    allocations = listOf(
                        CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = genericSource))),
                        CostUnitAllocation(1, listOf(ManaSpendReference(sourceId = blackSource))),
                    ),
                ),
            ),
        ),
    )

    result.isSuccess shouldBe true
    driver.isTapped(blackSource) shouldBe true
    driver.isTapped(genericSource) shouldBe true
}
~~~

The test must fail on the current base because the non-null plan is ignored, the empty legacy source list excludes every available source, and execution returns through the legacy auto-pay/solver path.

- [ ] **Step 3: Run only the RED test and record the exact failure.**

~~~powershell
just test-class PaymentPlanV1Test
~~~

Expected when the wrapper is usable: the new result.isSuccess assertion fails with the existing legacy selected-source or auto-pay payment error. If the wrapper fails with OSError: [WinError 193], classify the launcher as infrastructure BLOCKED and run this separately labelled fallback:

~~~powershell
./gradlew.bat --no-daemon :rules-engine:test --tests "*PaymentPlanV1Test"
~~~

- [ ] **Step 4: Commit only the RED test.**

~~~powershell
git add -- rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt
git commit -m "test: reproduce CastSpell payment plan fallback"
~~~

### Task 2: Extend Rules context ownership and direct plan execution

**Files:**

- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentSubtypes.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastSpellHandler.kt
- Modify: rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastPaymentProcessor.kt
- Modify: rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt

- [ ] **Step 1: Add GREEN assertions for explicit source production and pool remainder.**

Extend the CastSpell matrix before changing production. Assert the selected multicolor production, ManaSpentEvent, tapped sources, and the controller-selected floating remainder. Use the existing plan helper and these concrete test shapes:

~~~kotlin
test("CastSpell preserves an explicit multicolor production choice") {
    val (driver, player) = game()
    val spellId = driver.putCardInHand(player, ordinarySpell.name)
    val black = driver.putPermanentOnBattlefield(player, anyColorSource.name)
    val generic = driver.putPermanentOnBattlefield(player, anyColorSource.name)

    val result = driver.submit(
        CastSpell(
            playerId = player,
            cardId = spellId,
            paymentStrategy = PaymentStrategy.Explicit(
                paymentPlan = plan(
                    sourceActivations = listOf(
                        SourceActivation(black, key(driver, player, black, PaymentManaColor.BLACK), ProductionChoice(PaymentManaColor.BLACK)),
                        SourceActivation(generic, key(driver, player, generic, PaymentManaColor.GREEN), ProductionChoice(PaymentManaColor.GREEN)),
                    ),
                    allocations = listOf(
                        CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = generic))),
                        CostUnitAllocation(1, listOf(ManaSpendReference(sourceId = black))),
                    ),
                ),
            ),
        ),
    )

    result.isSuccess shouldBe true
    result.events.filterIsInstance<ManaSpentEvent>().single().black shouldBe 1
    driver.isTapped(black) shouldBe true
    driver.isTapped(generic) shouldBe true
}
~~~

For the floating remainder, give the player black, green, and red, select black for the colored unit and green for the generic unit as separate pool references, then assert red remains. Keep cost-unit indices aligned with the actual ManaCost.parse("{1}{B}") symbol order.

- [ ] **Step 2: Make the shared spell context callable across modules.**

Change spellPaymentContextFor from internal to public in PaymentSubtypes.kt. Preserve the existing signature and defaults:

~~~kotlin
fun spellPaymentContextFor(
    cardComponent: CardComponent,
    isKicked: Boolean = false,
    isFromExile: Boolean = false,
    isFromHand: Boolean = true,
): SpellPaymentContext
~~~

Keep paymentSubtypesOf internal; only the Rules-owned helper needs its changeling expansion.

- [ ] **Step 3: Replace duplicated CastSpellHandler context construction.**

In validatePayment and the execute-time payment block, use SpellPaymentContext.faceDownCast for face-down casts and spellPaymentContextFor for ordinary cards. Pass the actual kicked and hand/exile values from the concrete action/state. Do not change printed mana value or X semantics.

- [ ] **Step 4: Validate non-null plans before legacy handler branches.**

Add the same validator field shape used by ActivateAbilityHandler:

~~~kotlin
private val paymentPlanValidator = PaymentPlanValidator(manaSolver)
~~~

In validatePayment, before the legacy Explicit source-ID checks, handle a non-null plan:

~~~kotlin
val explicit = action.paymentStrategy as? PaymentStrategy.Explicit
if (explicit?.paymentPlan != null) {
    if (explicit.manaAbilitiesToActivate.isNotEmpty()) {
        return "PaymentPlanV1 must not include legacy runtime mana source handles"
    }
    return when (val validation = paymentPlanValidator.validate(
        state = state,
        playerId = action.playerId,
        cost = effectiveCost,
        plan = explicit.paymentPlan,
        spellContext = spellCtx,
    )) {
        is PaymentPlanValidation.Accepted -> null
        is PaymentPlanValidation.Rejected -> validation.reason
    }
}
~~~

Use the already relaxed authoritative effectiveCost and shared context. Do not call ManaSolver.solve on this branch. Leave AutoPay, FromPool, and plan-null Explicit behavior unchanged.

Before calling the validator, reject a CastSpell plan when the concrete action
still carries an unresolved alternative, X, face-down, modal, convoke/delve/
tap-for-generic/harmonize, secondary mana, or other payment-affecting choice.
The predicate must inspect the concrete action and the already computed final
cost; it must not reject commander tax, kicker, a cost increase, or a reduction
solely because the feature path exists. Return an error containing the stable
code PAYMENT_DOMAIN_UNSUPPORTED for a shape that is not completely fixed or
representable. This keeps direct Rules callers fail-closed even when they do
not pass through Gym.

- [ ] **Step 5: Add a direct CastPaymentProcessor plan path.**

Dispatch a non-null plan before the existing explicitPay function:

~~~kotlin
is PaymentStrategy.Explicit -> {
    val plan = action.paymentStrategy.paymentPlan
    if (plan != null) {
        explicitPlanPay(state, action.playerId, plan, cost, cardName, spellContext)
    } else {
        explicitPay(state, action.playerId, action.paymentStrategy, effectiveCost, cardName, xValue, spellContext, xManaRestriction)
    }
}
~~~

explicitPlanPay must validate with PaymentPlanValidator, return a PaymentResult error without mutation on rejection, replace only the player's pool with accepted.poolAfterSpend, tap accepted.solution through tapSourcesWithSideEffects, and emit the normal ManaSpentEvent. Source-produced mana is already allocated and must not be added back to the pool. Derive spentManaProvenance from the accepted source production and preserve the existing event/state ordering. V1 validator rejection of X, restricted/provenance, riders, bonus, and multi-mana shapes means consumedRiders and xManaSpentByColor remain empty on this path.

The function must contain no call to autoPay, payFromPool, solve, or the legacy source-ID list. Existing plan-null explicit callers retain the old path.

- [ ] **Step 6: Run Rules payment tests and commit the direct path.**

~~~powershell
just test-class PaymentPlanV1Test
~~~

Expected: the original RED, explicit multicolor, floating remainder, and existing ActivateAbility tests pass. If just produces WinError 193, retain that as infrastructure BLOCKED and run:

~~~powershell
./gradlew.bat --no-daemon :rules-engine:test --tests "*PaymentPlanV1Test"
~~~

Then inspect and commit:

~~~powershell
git diff --check
git add -- rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentSubtypes.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastSpellHandler.kt rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastPaymentProcessor.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt
git commit -m "feat: execute CastSpell PaymentPlanV1 directly"
~~~

### Task 3: Publish CastSpell domains from the final LegalAction

**Files:**

- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt

- [ ] **Step 1: Add observation tests for ordinary and unsupported CastSpell shapes.**

Add an ordinary sorcery with {1}{B} to the Gym fixture and assert its ordinary CastSpell LegalAction publishes a domain with the final requiredCost, colored units, and source choices. Add an X, hybrid, or unresolved alternative/additional-cost candidate and assert paymentDomain is null. Keep existing ActivateAbility authority tests green.

The eligibility test must distinguish final semantics from feature flags. An inert commander-tax contribution of {0}, a resolved fixed choice, or a reduction already reflected in the final enumerated cost is not rejected solely because its feature path exists. Reject only when the concrete LegalAction still has a payment-affecting choice, a non-final effective cost, or a V1-unrepresentable symbol/source.

- [ ] **Step 2: Extend the canonical ObservationBuilder publisher.**

Make paymentDomainFor(state, legalAction) callable by GameGymEnv and keep legalActionToView and diagnostics on that same function. Preserve the ActivateAbility branch, then add the ordinary CastSpell branch:

~~~kotlin
val cast = legalAction.action as? CastSpell ?: return null
if (legalAction.actionType != "CastSpell") return null
val requiredCost = legalAction.manaCostString ?: return null
val card = state.getEntity(cast.cardId)?.get<CardComponent>() ?: return null
~~~

Use the concrete action and LegalAction metadata to reject unresolved X, modal/alternative/free-cast, convoke/delve/tap-for-generic/harmonize, unresolved additional payment, and other fields whose value can change the effective cost. Do not reject commander tax, kicker, cost increase, or reduction code by feature flag alone when the enumerator has already materialized a final cost and the action carries no remaining choice. Pass requiredCost and spellPaymentContextFor(card, isFromExile = ..., isFromHand = ...) to PaymentDomainBuilder.

PaymentDomainBuilder remains the fail-closed authority for X/hybrid/Phyrexian/twobrid symbols, restricted/provenance pool entries, riders, bonus/multi-mana sources, secondary source choices, and source restrictions.

- [ ] **Step 3: Make diagnostics cover every payable unsupported action.**

Replace the ActivateAbility-only predicate with:

~~~kotlin
legalActions.any { action ->
    action.manaCostString != null && paymentDomainFor(state, action) == null
}
~~~

The signal remains exactly DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED.

- [ ] **Step 4: Update the public contract comment.**

Change LegalActionView.paymentDomain in TrainingObservation.kt to state that it contains complete external choices for supported ordinary fixed-cost mana actions, including CastSpell and ActivateAbility.

- [ ] **Step 5: Run focused observation tests and commit.**

~~~powershell
just test-class GameGymEnvPaymentDomainAuthorityTest
just test-class GameGymEnvPaymentPlanTest
~~~

Expected: ordinary CastSpell publishes a domain; unsupported CastSpell emits the exact diagnostic/null domain; existing ActivateAbility tests remain green. Use direct :gym:test --tests "*GameGymEnvPayment*" only as a separately labelled fallback after a wrapper failure.

~~~powershell
git add -- gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt
git commit -m "feat: publish CastSpell payment domains"
~~~

### Task 4: Harden both Trusted Gym step entry points

**Files:**

- Modify: gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/A9DiagnosticsTest.kt

- [ ] **Step 1: Add the null-domain boundary RED test.**

Start from the ordinary CastSpell observation fixture. Keep the Gym registry
and cached action from that observation, then mutate only the underlying
GameEnvironment state through its public restore method by adding a
RestrictedManaEntry to the acting player's ManaPoolComponent. The stale
LegalAction still has manaCostString, while the canonical domain recomputed by
the boundary must now be null because V1 rejects restricted floating mana.
Assert both entry points fail before Rules execution and expose the typed
diagnostic:

~~~kotlin
val failure = shouldThrow<UnsupportedPathFailure> {
    gym.step(actionId, autoPayPayload)
}
failure.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
~~~

Build autoPayPayload and legacyPayload by copying view.actionSemantics and
overwriting paymentStrategy with the serialized AutoPay and legacy Explicit
values, as the existing GameGymEnvPaymentPlanTest helper does. Repeat with
legacyPayload and with gym.step(actionId), and assert the state digest and
tapped-source state remain unchanged.

- [ ] **Step 2: Replace the ActivateAbility-only guard with one generic boundary.**

Have requireActionPaymentPlan inspect the canonical domain for the resolved LegalAction. For every manaCostString != null action:

~~~kotlin
val domain = observationBuilder.paymentDomainFor(environment.state, resolved.legalAction)
if (domain == null) {
    throw UnsupportedPathFailure(
        listOf(DiagnosticSignal(DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED))
    )
}
~~~

When the domain is non-null, extract paymentStrategy through one shared action-level helper, require PaymentStrategy.Explicit, require a non-null paymentPlan, and require an empty manaAbilitiesToActivate. Keep manaCostString == null actions unchanged. This is one policy boundary, not separate CastSpell and ActivateAbility policies.

- [ ] **Step 3: Route both step methods through the guard.**

Call the guard for legal action-ID submissions before the structured-action early return and make executeResolved call it before environment.stepStrict. The JSON payload path must continue to call it after materialization and before stepFromCandidateStrict. No trusted path may call either Rules executor directly for a payable action without the domain check.

- [ ] **Step 4: Test all strategy forms and commit.**

Use this matrix:

| Legal action | Submitted payment | Expected |
| --- | --- | --- |
| payable, domain null | AutoPay / FromPool / legacy Explicit | UnsupportedPathFailure(PAYMENT_DOMAIN_UNSUPPORTED) |
| payable, domain non-null | AutoPay / FromPool / legacy Explicit | rejected before Rules execution |
| payable, domain non-null | Explicit with complete plan, no legacy IDs | exact payment succeeds |
| non-payment | existing action payload | unchanged |

Run:

~~~powershell
just test-class GameGymEnvPaymentPlanTest
just test-class A9DiagnosticsTest
~~~

Commit after focused tests pass or the launcher blocker is recorded:

~~~powershell
git add -- gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt gym/src/test/kotlin/com/wingedsheep/gym/A9DiagnosticsTest.kt
git commit -m "feat: fail closed on unsupported Gym mana payments"
~~~

### Task 5: Version and prove observation, replay, fork, and digest semantics

**Files:**

- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt
- Modify: docs/data-contracts.md
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt
- Modify: game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/PaymentPlanReplayTest.kt

- [ ] **Step 1: Bump the schema hash.**

Change the current value to:

~~~kotlin
const val CURRENT: String = "argentum-gym-contract@v1.11-castspell-payment-domain"
~~~

- [ ] **Step 2: Document the expanded action-level contract.**

Update docs/data-contracts.md to name ordinary fixed-cost CastSpell and ActivateAbility, state that payable actions with no complete V1 domain fail closed with PAYMENT_DOMAIN_UNSUPPORTED, and preserve the existing source, pool, symbol, and legacy-handle restrictions.

- [ ] **Step 3: Add digest-specific domain coverage.**

Construct a TrainingObservation whose only semantic difference is a CastSpell LegalActionView.paymentDomain and assert the digest changes while action IDs/descriptions remain digest-irrelevant:

~~~kotlin
val withDomain = base.copy(
    legalActions = base.legalActions.map { action ->
        if (action.kind == "CastSpell") action.copy(paymentDomain = ordinaryDomain) else action
    },
)
StateDigest.compute(withDomain) shouldNotBe StateDigest.compute(base)
~~~

Also assert the CastSpell domain survives GameGymEnv.fork and SnapshotCodec restore alongside existing ActivateAbility coverage.

- [ ] **Step 4: Add CastSpell replay round-trip coverage.**

Extend PaymentPlanReplayTest with an ordinary spell and a recorded CastSpell carrying PaymentStrategy.Explicit(paymentPlan = ...). After encoding/decoding, assert:

~~~kotlin
val decoded = ReplayCodec.decode(ReplayCodec.encode(replay))
decoded shouldBe replay
decoded.actions.filterIsInstance<CastSpell>()
    .first { it.cardId == spellId }
    .paymentStrategy shouldBe explicitCast.paymentStrategy

val reconstructed = ReplayReconstructor(cardRegistry, null).reconstruct(decoded)
reconstructed.frameCount shouldBe decoded.frameCount
ReplayReconstructor(cardRegistry, null)
    .reconstructStateAt(decoded, decoded.actions.size)
    .shouldNotBeNull()
~~~

This proves the existing GameAction serializer is sufficient and no second payment model or replay migration is introduced.

- [ ] **Step 5: Run focused contract/replay tests and commit.**

~~~powershell
just test-class StateDigestTest
just test-class PaymentPlanReplayTest
git diff --check
git add -- gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt docs/data-contracts.md gym/src/test/kotlin/com/wingedsheep/gym/contract/StateDigestTest.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/PaymentPlanReplayTest.kt
git commit -m "test: cover CastSpell payment replay and digest semantics"
~~~

### Task 6: Run the full verification matrix without reblessing or touching PR #73

- [ ] **Step 1: Recheck the exact final branch head and worktree hygiene.**

~~~powershell
git rev-parse HEAD
git rev-parse origin/main
git status --short --branch
git diff --check
~~~

Confirm the branch remains based on e2bb6f9e78e2136fd1e313b70ad2efa03fdd61ec, only this branch's changes exist, and the PR #73 worktree is untouched.

- [ ] **Step 2: Run focused and module gates through just.**

~~~powershell
just test-class PaymentPlanV1Test
just test-class GameGymEnvPaymentPlanTest
just test-class GameGymEnvPaymentDomainAuthorityTest
just test-class A9DiagnosticsTest
just test-class StateDigestTest
just test-class PaymentPlanReplayTest
just test-rules
just test-gym
just test-server
~~~

If scripts/gradle-locked fails with WinError 193, retain that result as infrastructure BLOCKED and run the corresponding direct Gradle task only as separately labelled fallback evidence. A direct fallback is not a wrapper PASS.

- [ ] **Step 3: Run FrozenBaseline and stop on first semantic divergence.**

~~~powershell
just test-class FrozenBaselineTest
~~~

The historical expected digest is 6ff9ded1403d59ac. If it changes, record the old hash, new hash, and first divergent action/state; do not rebless snapshots and do not continue to publication until the divergence is explained. If the wrapper is blocked:

~~~powershell
./gradlew.bat --no-daemon :ai:test --tests "*FrozenBaselineTest"
~~~

- [ ] **Step 4: Run the complete local project gate.**

~~~powershell
just check
just test
~~~

Classify coverage SKIPPED as SKIPPED, not PASS, and separate unrelated pre-existing failures from failures in this branch.

- [ ] **Step 5: Open a Draft PR only after local evidence is recorded.**

Verify the destination:

~~~powershell
git remote get-url origin
git branch --show-current
git log -1 --oneline
~~~

The origin must be https://github.com/chrismaghuhn/argentum-engine.git. Push only chris/a5-castspell-payment-domain-v1 and create a Draft PR targeting chrismaghuhn/argentum-engine. Never target wingedsheep/argentum-engine and never merge it.

- [ ] **Step 6: Run fresh Hosted CI and record every gate.**

Use the newly opened Draft PR's GitHub checks, wait for final results, and distinguish PASS, FAIL, SKIPPED, and infrastructure BLOCKED. Hosted CI must run on the final exact PR head; older green checks do not count.

- [ ] **Step 7: Preserve the post-merge #73 acceptance boundary.**

Do not edit or merge PR #73 from this branch. The exact EnvironmentV1ExactPairAcceptanceTest seed-0/step-163 assertion is a post-merge synchronization gate: after the maintainer merges this separate fix, PR #73 must be synchronized and run again from 0/72. Until that merge exists, report the post-merge result as NOT_RUN rather than claiming acceptance is complete. A disposable integration worktree may be used only if the maintainer explicitly authorizes that pre-merge simulation; the PR #73 worktree itself remains unchanged.

## Final handoff checklist

- [ ] Exact base SHA and branch/worktree isolation are recorded.
- [ ] The original CastSpell {1}{B} RED is preserved in test history.
- [ ] A non-null plan never reaches AutoPay, FromPool, ManaSolver.solve, or legacy source-ID-only payment.
- [ ] Rules-owned SpellPaymentContext is shared by enumeration, handler, and Gym publication.
- [ ] Unsupported symbols, sources, and unresolved choices publish no domain.
- [ ] Payable null-domain actions fail closed in both Gym step entry points.
- [ ] SchemaHash, docs, replay, fork, snapshot, and StateDigest evidence are present.
- [ ] FrozenBaseline was inspected without blind rebless.
- [ ] Hosted CI ran on the final exact Draft PR head.
- [ ] PR #73 was not modified or merged.


# TargetPaymentDomainV1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish and strictly execute complete target-bound PaymentDomainV5 choices for finite,
mandatory, controller-chosen single battlefield-permanent targets whose effective activated-ability
cost depends on the selected target.

**Architecture:** Add TargetPaymentDomainV1 as an observation-only relation between the existing
ActionTargetDomainV1 and one non-null PaymentDomainV5 per target. Resolve every binding through the
Rules-owned ActivatedAbilityCostCalculator, compute affordability independently for the bound action,
and make the trusted Gym seam validate the submitted target and payment plan as one stale-safe
certificate. Leave PaymentDomainV5, PaymentPlanV3, SourceActivationV2, ExplicitV3, GameAction, and
CompactReplay v5 unchanged.

**Tech Stack:** Kotlin, kotlinx.serialization, Kotest, immutable GameState, Gym ObservationBuilder,
ActionTargetDomainMapper, ActivatedAbilityCostCalculator, ManaSolver, and the existing strict
GameGymEnv/GameEnvironment seams.

---

## File map and ownership

Create or modify only these production/documentation seams unless a test exposes an exact dependency:

- Create gym/src/main/kotlin/com/wingedsheep/gym/contract/TargetPaymentDomain.kt
  - Serializable TargetPaymentDomainV1 and TargetPaymentBindingV1 DTOs.
  - Constructor invariants for version, non-empty bindings, unique targets, and valid nested domains.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
  - Add nullable LegalActionView.targetPaymentDomain with a default of null.
  - Keep manaCost and paymentDomain historical fields unchanged for non-target-bound actions.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
  - Build the already-public ActionTargetDomainV1 first.
  - Qualify the permanent single-target slice, bind each published candidate, calculate its cost,
    compute its affordability, and build its non-null PaymentDomainV5.
  - Clear parent action-wide manaCost and paymentDomain when target payment is published.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt
  - Include target-payment bindings in wire and semantic canonicalization without reordering them.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt
  - Change only the current Gym schema identifier to
    argentum-gym-contract@v1.25-target-payment-domain.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt
  - Resolve the submitted target binding before validating ExplicitV3 payment.
  - Reject target/plan cross-binding and registered/current binding drift before execution.
- Modify gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt
  - Consume only targetPaymentDomain for the new target-coupled action shape. This is test-policy
    code, not a second Rules implementation.
- Create gym/src/test/kotlin/com/wingedsheep/gym/TargetPaymentDomainContractTest.kt
  - DTO invariants, serialization, binding order, non-null nested V5 domains, and parent-field rules.
- Create gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvTargetPaymentDomainTest.kt
  - Rules publication, target-bound affordability, strict execution, stale rejection, and atomicity.
- Modify gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt
  - Semantic/wire inclusion and producer-order preservation.
- Create gym/src/test/kotlin/com/wingedsheep/gym/contract/SchemaHashContractTest.kt
  - Pin the v1.25 identifier and reject use of the historical v1.24 identifier.
- Modify docs/data-contracts.md
  - Document the v1.25 target-payment relation and retain v1.24 as historical.

Do not modify PaymentDomainV5, PaymentPlanV3, SourceActivationV2, GameAction,
PaymentStrategy.ExplicitV3, CompactReplay serializers, locked decks, or the B0 overlay.

## Task 1: Add the observation DTO and structural invariants

**Files:**

- Create gym/src/main/kotlin/com/wingedsheep/gym/contract/TargetPaymentDomain.kt
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
- Create gym/src/test/kotlin/com/wingedsheep/gym/TargetPaymentDomainContractTest.kt

- [ ] **Step 1: Write the failing DTO tests.**

Add tests that construct a valid nested V5 fixture and assert this exact shape:

~~~kotlin
val domain = TargetPaymentDomainV1(
    targetBindings = listOf(
        TargetPaymentBindingV1(
            target = EntityId("target-a"),
            affordable = true,
            paymentDomain = paymentDomain(requiredCost = "{0}"),
        ),
        TargetPaymentBindingV1(
            target = EntityId("target-b"),
            affordable = false,
            paymentDomain = paymentDomain(requiredCost = "{2}"),
        ),
    ),
)

domain.targetBindings.map { it.target } shouldBe
    listOf(EntityId("target-a"), EntityId("target-b"))
domain.targetBindings.all { it.paymentDomain != null } shouldBe true
~~~

Define the test fixture used above in the same test file with the existing PaymentDomainV5
constructor:

~~~kotlin
private fun paymentDomain(requiredCost: String) = PaymentDomainV5(
    requiredCost = requiredCost,
    outerAtomicCostUnits = emptyList(),
    initialPoolBuckets = emptyList(),
    sourceActivationOptions = emptyList(),
)
~~~

Add rejection tests for unsupported version, empty bindings, duplicate target bindings, and a nested
invalid PaymentDomainV5. Add a kotlinx.serialization round-trip test and assert DTO equality.

- [ ] **Step 2: Run the focused tests and record the RED.**

Run:

~~~text
just test-class TargetPaymentDomainContractTest
~~~

Expected before implementation: compilation failure because TargetPaymentDomainV1,
TargetPaymentBindingV1, and LegalActionView.targetPaymentDomain do not exist.

If just fails with the known Windows WinError 193, run the same class through the repository's
Git-Bash scripts/gradle-locked wrapper and label that result as native fallback evidence.

- [ ] **Step 3: Implement the minimal DTO and view field.**

Implement the DTO with this public contract:

~~~kotlin
@Serializable
data class TargetPaymentDomainV1(
    val version: Int = 1,
    val targetBindings: List<TargetPaymentBindingV1>,
) {
    init {
        require(version == 1)
        require(targetBindings.isNotEmpty())
        require(targetBindings.map { it.target }.distinct().size == targetBindings.size)
    }
}

@Serializable
data class TargetPaymentBindingV1(
    val target: EntityId,
    val affordable: Boolean,
    val paymentDomain: PaymentDomainV5,
)
~~~

Add val targetPaymentDomain: TargetPaymentDomainV1? = null to LegalActionView. Do not add a target
identifier to PaymentPlanV3 or GameAction.

- [ ] **Step 4: Run the focused tests and verify GREEN.**

Run just test-class TargetPaymentDomainContractTest. Expected: all DTO, invariant, and round-trip
tests pass.

- [ ] **Step 5: Commit the contract slice.**

~~~text
git add gym/src/main/kotlin/com/wingedsheep/gym/contract/TargetPaymentDomain.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt gym/src/test/kotlin/com/wingedsheep/gym/TargetPaymentDomainContractTest.kt
git commit -m "feat: add target payment domain observation contract"
~~~

## Task 2: Publish target-bound V5 domains and fully-bound affordability

**Files:**

- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Create or extend gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvTargetPaymentDomainTest.kt

- [ ] **Step 1: Write the failing publication tests.**

Create a Rules fixture with one mandatory controller-chosen battlefield-permanent target and two public
candidates. Use a generic Rules-owned target-aware cost modification so the fixture is not named for
Fervent Champion or Equip.

Assert:

~~~kotlin
val view = observeTargetDependentAction()
val relation = view.targetPaymentDomain ?: error("expected target payment domain")
val candidates = view.targetDomain!!.requirements.single().candidates

relation.targetBindings.map { it.target } shouldBe candidates
relation.targetBindings.map { it.paymentDomain.requiredCost } shouldBe
    listOf("{0}", "{1}")
view.manaCost shouldBe null
view.paymentDomain shouldBe null
view.affordable shouldBe relation.targetBindings.any { it.affordable }
relation.targetBindings.all { it.paymentDomain != null } shouldBe true
~~~

Add tests proving that one unaffordable candidate still receives a non-null complete V5 domain, while
one unrepresentable candidate makes the entire relation unsupported. Add tests for player/card/spell
targets, two requirements, optional/unlimited/dynamic targets, X/mode coupling, and unresolved
additional or alternative payment. Every case must publish neither a partial relation nor an
action-wide fallback.

- [ ] **Step 2: Run the publication tests to capture RED.**

Run:

~~~text
just test-class GameGymEnvTargetPaymentDomainTest
~~~

Expected: the new target-dependent fixture has no relation and the assertions fail before the
publisher exists. Existing fixed-cost target tests must remain unchanged at this stage.

- [ ] **Step 3: Add the target-bound publication seam.**

In ObservationBuilder.legalActionToView, use the already mapped ActionTargetDomainV1 as the sole
candidate source. Do not read raw LegalAction.validTargets or independently traverse the card
definition.

Add a private/internal flow equivalent to:

~~~kotlin
private fun targetPaymentDomainV1For(
    state: GameState,
    legalAction: LegalAction,
    targetDomain: ActionTargetDomainV1,
): TargetPaymentDomainV1? {
    val action = legalAction.action as? ActivateAbility ?: return null
    val requirement = targetDomain.requirements.singleOrNull() ?: return null
    if (requirement.minTargets != 1 || requirement.maxTargets != 1) return null
    if (legalAction.targetRequirements.singleOrNull()?.targetChooser != TargetChooser.Controller) {
        return null
    }

    val bindings = requirement.candidates.map { targetId ->
        val boundAction = action.copy(
            targets = listOf(ChosenTarget.Permanent(targetId)),
        )
        val ability = resolveActivatedAbility(state, boundAction) ?: return null
        val effectiveCost = activatedAbilityCostCalculator.calculate(
            state = state,
            sourceId = boundAction.sourceId,
            controllerId = boundAction.playerId,
            ability = ability,
            targets = boundAction.targets,
            equipPayment = boundAction.alternativePayment?.equipPayment,
        )
        val request = targetBoundPaymentRequest(state, legalAction, boundAction, ability, effectiveCost)
            ?: return null
        val paymentDomain = paymentDomainBuilder.buildV5(
            state = state,
            playerId = request.playerId,
            requiredCost = request.requiredCost,
            spellContext = request.spellContext,
            excludeSources = request.excludeSources,
            reservedOuterLifePayment = request.reservedOuterLifePayment,
        ) ?: return null
        TargetPaymentBindingV1(
            target = targetId,
            affordable = targetBoundAffordable(state, request, paymentDomain),
            paymentDomain = paymentDomain,
        )
    }
    return TargetPaymentDomainV1(targetBindings = bindings)
}
~~~

The concrete implementation must verify that every candidate is a battlefield permanent and resolves to
ChosenTarget.Permanent. It must never special-case isEquipAbility, a card name, or Fervent Champion.

- [ ] **Step 4: Implement the shared target-bound request and affordability proof.**

Factor a private Rules-owned request path so publication and strict execution use the same values:

~~~text
resolved target-bound ability
→ ActivatedAbilityCostCalculator effective AbilityCost
→ canonical PaymentDomainV5.requiredCost
→ buildAbilityPaymentContext for the bound action
→ deterministic source-cost exclusions
→ reserved outer PayLife amount
~~~

Define the private request/result seam before calling it from the publication loop:

~~~kotlin
private data class TargetBoundPaymentRequest(
    val playerId: EntityId,
    val requiredCost: String,
    val spellContext: SpellPaymentContext,
    val excludeSources: Set<EntityId>,
    val reservedOuterLifePayment: Int,
)

private fun targetBoundPaymentRequest(
    state: GameState,
    template: LegalAction,
    action: ActivateAbility,
    ability: ActivatedAbility,
    effectiveCost: AbilityCost,
): TargetBoundPaymentRequest? {
    val manaCost = effectiveCost.manaCostOrNull?.canonicalPaymentManaCost() ?: return null
    val deterministic = deterministicAdditionalCostPaymentFor(
        state,
        template.copy(action = action, manaCostString = manaCost.toString()),
    ) ?: return null
    val card = state.getEntity(action.sourceId)?.get<CardComponent>() ?: return null
    val spellContext = buildAbilityPaymentContext(
        cardComponent = card,
        projected = state.projectedState,
        sourceId = action.sourceId,
        ability = ability,
    )
    val reservedOuterLifePayment = CostAmountResolver.resolvePayLifeTotal(
        state = state,
        cost = effectiveCost,
        sourceId = action.sourceId,
        controllerId = action.playerId,
        cardRegistry = cardRegistry,
    ) ?: return null
    return TargetBoundPaymentRequest(
        playerId = action.playerId,
        requiredCost = manaCost.toString(),
        spellContext = spellContext,
        excludeSources = if (deterministic.tappedPermanents.isNotEmpty()) {
            setOf(action.sourceId)
        } else {
            emptySet()
        },
        reservedOuterLifePayment = reservedOuterLifePayment,
    )
}

private fun targetBoundAffordable(
    state: GameState,
    request: TargetBoundPaymentRequest,
    paymentDomain: PaymentDomainV5,
): Boolean = manaSolver.canPay(
    state = state,
    playerId = request.playerId,
    cost = ManaCost.parse(paymentDomain.requiredCost),
    excludeSources = request.excludeSources,
    spellContext = request.spellContext,
    additionalPayLife = request.reservedOuterLifePayment,
)
~~~

Compute binding.affordable independently from parent LegalAction.affordable by using the target-bound
PaymentDomainV5.requiredCost, target-bound SpellPaymentContext, the same source exclusions, outer-life
reservation, deterministic non-mana cost checks, and the current V5-supported source model. The
implementation may call existing ManaSolver.canPay/solve feasibility, but it must not use
paymentDomain != null as an affordability result.

The targetBoundPaymentRequest function must return null for an unresolved cost choice and must derive
requiredCost from the target-bound calculator result. The binding's PaymentDomainV5.requiredCost is
the sole public target-bound cost authority.

- [ ] **Step 5: Set parent view semantics and run publication tests.**

When targetPaymentDomainV1For returns a relation, construct LegalActionView with:

~~~text
targetPaymentDomain = relation
manaCost = null
paymentDomain = null
affordable = relation.targetBindings.any { it.affordable }
~~~

When the action is target-dependent but qualification fails, emit the typed unsupported diagnostic and
publish no partial target-payment relation. Run just test-class GameGymEnvTargetPaymentDomainTest and
expect the publication, unsupported-shape, cost-authority, and affordability tests to pass.

- [ ] **Step 6: Commit the Rules publication slice.**

~~~text
git add gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvTargetPaymentDomainTest.kt
git commit -m "feat: publish target-bound payment domains"
~~~

## Task 3: Canonicalization, schema, and public documentation

**Files:**

- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt
- Modify docs/data-contracts.md
- Modify gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt
- Create gym/src/test/kotlin/com/wingedsheep/gym/contract/SchemaHashContractTest.kt

- [ ] **Step 1: Write failing wire, semantic, and schema tests.**

Add a target-dependent observation fixture and assert:

~~~kotlin
val wire = ObservationCanonicalizer.wireJson(observation)
val semantic = ObservationCanonicalizer.semanticJson(observation)

wire shouldContain "targetPaymentDomain"
semantic shouldContain "targetPaymentDomain"
semantic shouldContain "requiredCost"
observation.schemaHash shouldBe "argentum-gym-contract@v1.25-target-payment-domain"
~~~

Assert that reversing the internal input order does not change the producer-published binding order,
that the canonicalizer does not add a second binding sort, and that changing a binding's nested
PaymentDomainV5 changes the semantic representation and StateDigest.

- [ ] **Step 2: Run the focused tests and capture RED.**

Run:

~~~text
just test-class ObservationCanonicalizationTest
just test-class SchemaHashContractTest
~~~

Expected: the target-payment field is absent from the current semantic projection and the schema
still reports v1.24.

- [ ] **Step 3: Include the new field without changing historical payment serialization.**

Add targetPaymentDomain to semanticActionFingerprint and rely on the DTO serializer for the wire form.
Preserve targetBindings exactly as received from the already published ActionTargetDomainV1 candidate
list. Do not sort bindings in ObservationCanonicalizer.

Change only SchemaHash.CURRENT to:

~~~text
argentum-gym-contract@v1.25-target-payment-domain
~~~

Document that v1.24 remains historical, PaymentDomainV5 remains the nested payment contract, and
CompactReplay remains version 5 because the target-payment relation is observation-only.

- [ ] **Step 4: Run canonicalization, schema, and historical payment tests.**

Run:

~~~text
just test-class ObservationCanonicalizationTest
just test-class SchemaHashContractTest
just test-class PaymentDomainV5ContractTest
~~~

Expected: new target-payment wire/digest tests pass and historical PaymentDomainV5 tests remain green.

- [ ] **Step 5: Commit the observation-contract slice.**

~~~text
git add gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt docs/data-contracts.md gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt gym/src/test/kotlin/com/wingedsheep/gym/contract/SchemaHashContractTest.kt
git commit -m "feat: version Gym target payment observations"
~~~

## Task 4: Bind strict submission to the selected target domain

**Files:**

- Modify gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt
- Extend gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvTargetPaymentDomainTest.kt

- [ ] **Step 1: Write failing strict-boundary tests.**

Use a fixture with two target bindings and a valid ExplicitV3 plan for the affordable target.

Add tests for:

~~~text
target=e146 + plan validated against binding e146
  → accepted

target=e147 + plan from binding e146
  → rejected before mutation and event emission

missing target, duplicate target, or off-domain target
  → rejected before mutation

binding.affordable=false
  → rejected before mutation

registered target/payment binding differs from current binding
  → stale rejection before mutation

current binding.requiredCost differs from registered binding.paymentDomain.requiredCost
  → stale rejection before mutation

unavailable target-payment domain
  → no AutoPay, native fallback, legacy payment, or partial observation
~~~

For every rejection, capture state, event list, and action cursor/step count before submission and assert
they are unchanged afterward.

- [ ] **Step 2: Run the strict tests to capture RED.**

Run:

~~~text
just test-class GameGymEnvTargetPaymentDomainTest
~~~

Expected: the current GameGymEnv checks only the action-level PaymentDomainV5 and cannot select a
target binding, so the cross-binding and stale tests fail or cannot compile.

- [ ] **Step 3: Add a target-binding resolver at the trusted Gym seam.**

Before requireActionPaymentPlan, detect targetPaymentDomain on the registered/current action view and
resolve exactly one submitted ChosenTarget.Permanent. Validate:

~~~text
registered TargetPaymentDomainV1 is structurally valid
current TargetPaymentDomainV1 is structurally valid
registered/current targetDomain candidates match
registered/current binding lists match exactly
submitted target is one of the current binding targets
selected binding.affordable is true
~~~

Pass only the selected binding's PaymentDomainV5 and requiredCost into the existing ExplicitV3
validation path. Keep PaymentPlanV3 target-free; target/plan association is established by the selected
binding at the Gym seam.

- [ ] **Step 4: Add registered/current stale equality before mutation.**

Compare the selected registered and current binding using target identity, affordable status, nested
PaymentDomainV5 including requiredCost, and every public nested payment choice. Re-resolve the current
Rules action with the submitted target and verify its target-bound cost produces the same current
binding. Reject with the existing atomic strict error path before GameEnvironment.processAndCommit.

Do not alter GameEnvironment.isCurrentActionCandidate to treat a target/payment plan as a new replay
identity. Its existing normalized action membership remains responsible only for matching the action
template.

- [ ] **Step 5: Run strict and historical Gym tests.**

Run:

~~~text
just test-class GameGymEnvTargetPaymentDomainTest
just test-class GameGymEnvActionContractTest
just test-class GameGymEnvStrictExecutionTest
~~~

Expected: target-bound positive execution passes; cross-binding, stale, malformed, and fallback tests
reject atomically; historical fixed-cost explicit payment remains green.

- [ ] **Step 6: Commit the strict-boundary slice.**

~~~text
git add gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvTargetPaymentDomainTest.kt
git commit -m "feat: enforce target-bound payment submissions"
~~~

## Task 5: Make the external policy consume the published relation

**Files:**

- Modify gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt
- Create gym/src/test/kotlin/com/wingedsheep/gym/TargetPaymentDomainPolicyTest.kt
- Extend gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvTargetPaymentDomainTest.kt

- [ ] **Step 1: Write failing policy-consumption tests.**

Create a LegalActionView with targetPaymentDomain containing one affordable and one unaffordable
binding. Assert the policy:

~~~text
selects only an affordable published binding
reads PaymentDomainV5 from that exact binding
does not read parent manaCost or parent paymentDomain
returns a typed A5 gap when the relation is missing, duplicate, or inconsistent
does not reconstruct target cost from action semantics or card names
~~~

- [ ] **Step 2: Run the policy tests and capture RED.**

Run:

~~~text
just test-class TargetPaymentDomainPolicyTest
just test-class GameGymEnvTargetPaymentDomainTest
~~~

Expected: the current policy sees no target-payment field and either reports an unsupported structured
action or tries the parent payment domain.

- [ ] **Step 3: Implement the policy adapter against the public relation.**

For a target-payment action, validate the targetDomain/binding bijection in published order, choose from
bindings whose affordable flag is true, and build the ExplicitV3 plan from that binding's
PaymentDomainV5. Submit the chosen target separately as the existing target payload, but never decouple
it from the selected binding.

Return the existing fail-closed semantic gap for missing, duplicate, stale, or unsupported public
relation data. Do not call ManaSolver, ActivatedAbilityCostCalculator, CardRegistry, or native AI
from the policy.

- [ ] **Step 4: Run the policy and strict tests.**

Run:

~~~text
just test-class TargetPaymentDomainPolicyTest
just test-class GameGymEnvTargetPaymentDomainTest
~~~

Expected: the policy selects only published affordable bindings and strict Gym accepts only the
matching plan.

- [ ] **Step 5: Commit the policy slice.**

~~~text
git add gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt gym/src/test/kotlin/com/wingedsheep/gym/TargetPaymentDomainPolicyTest.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvTargetPaymentDomainTest.kt
git commit -m "test: consume target-bound payment domains"
~~~

## Task 6: Replay and historical compatibility guardrails

**Files:**

- Modify gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt
- Modify game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/CompactReplayV5PaymentTest.kt
- Modify game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprintV3Test.kt
- Do not modify CompactReplay production code

- [ ] **Step 1: Write replay guard tests.**

Use an accepted target-bound ExplicitV3 GameAction and assert CompactReplay v5 round-trip produces the
same final state fingerprint and action carrier. Assert that the replay payload contains the selected
target and ExplicitV3 plan, not TargetPaymentDomainV1.

Add historical assertions that PaymentDomainV5, PaymentPlanV3, and v4 observation fixtures retain their
existing serialized shape and version behavior.

- [ ] **Step 2: Run replay tests and verify the expected current result.**

Run:

~~~text
just test-class CompactReplayV5PaymentTest
just test-class ReplayFingerprintV3Test
~~~

Expected: the target-bound action round-trip remains supported by v5 without a replay schema change.

- [ ] **Step 3: Add only test-side assertions.**

Do not add target identifiers to PaymentPlanV3 or GameAction. Verify that the selected target is already
carried by ActivateAbility.targets and that the existing ExplicitV3 plan is the only payment carrier.

- [ ] **Step 4: Run all replay and historical Gym tests.**

Run:

~~~text
just test-class CompactReplayV5PaymentTest
just test-class ReplayFingerprintV3Test
just test-class PaymentDomainV5ContractTest
just test-class GameGymEnvPaymentPlanTest
~~~

Expected: all pass with CompactReplay CURRENT_VERSION still 5.

- [ ] **Step 5: Commit the compatibility slice.**

~~~text
git add gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/CompactReplayV5PaymentTest.kt game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprintV3Test.kt
git commit -m "test: preserve replay for target-bound payment actions"
~~~

The commit must contain only the specifically changed replay/compatibility test files; do not stage unrelated
test changes.

## Task 7: Exact B0 witness handoff (deferred, no source changes)

**Files:**

- No implementation source file is changed by this task.
- This task is deferred until Task 8 Step 4 has passed and the B0 gate is explicitly reopened.
- Run the existing B0 diagnostic/acceptance test from the unchanged overlay worktree only after the
  production implementation has passed independent review.
- Modify no locked deck, policy seed, or B0 overlay file.

- [ ] **Step 1: Run the existing exact B0 target-payment assertions.**

At episode b0-v1-0-chevill_seat_0-chevill, engine seed 0, policy seed 8396027631620334333,
decision 1027, assert:

~~~text
three affordable production offenders are target-payment actions
Sword of the Animist source=e164
Swiftfoot Boots source=e162
Mask of Memory source=e165
each has a complete target↔binding bijection
e146 and e147 use their independently calculated PaymentDomainV5.requiredCost
parent manaCost and paymentDomain are null
Slayers' Stronghold source=e136 remains non-causal unaffordable diagnostic noise
~~~

- [ ] **Step 2: Run only the exact B0 diagnostic/targeted test.**

Run:

~~~text
just test-class B0PaymentDomain1027DiagnosticTest
~~~

If just is blocked by WinError 193, run the exact class through Git-Bash scripts/gradle-locked and label
the result as native fallback evidence.

Expected after the production fix: the decision-1027 payment-domain gap is absent in the targeted
fixture. This step changes no source and is not permission to start the 64/512/2048 corpus.

- [ ] **Step 3: Run the focused target-payment suite.**

Run:

~~~text
just test-class TargetPaymentDomainContractTest
just test-class GameGymEnvTargetPaymentDomainTest
just test-class ObservationCanonicalizationTest
~~~

Expected: all target-binding, cost-authority, affordability, ordering, strict, digest, and atomicity
tests pass.

## Task 8: Full verification, review package, and handoff

**Files:** Review the production and test files changed by Tasks 1-6. The B0 overlay remains outside
this implementation branch.

- [ ] **Step 1: Run the repository-required Gym and engine gates.**

Run through just:

~~~text
just test-gym
just test-gym-server
just test-rules
~~~

Because this feature changes Gym DTO/strict execution and reuses Rules cost authority, the full Gym
module and Rules suite are required. If a wrapper fails with WinError 193, run the equivalent native
Gradle commands separately and report them as fallback evidence, never as just PASS.

- [ ] **Step 2: Run the targeted acceptance checks.**

Run:

~~~text
just test-class TargetPaymentDomainContractTest
just test-class GameGymEnvTargetPaymentDomainTest
just test-class TargetPaymentDomainPolicyTest
just test-class ObservationCanonicalizationTest
just test-class CompactReplayV5PaymentTest
~~~

Expected: all focused target-payment and historical compatibility tests pass.

- [ ] **Step 3: Check the immutable/historical boundaries.**

Verify:

~~~text
git diff --stat 458022a8e61eb6399ac3b8fe8f0cbce2e28e34ce..HEAD
git diff 458022a8e61eb6399ac3b8fe8f0cbce2e28e34ce -- rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameAction.kt game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt
git diff --check
~~~

Confirm that PaymentDomainV5, PaymentPlanV3, SourceActivationV2, ExplicitV3, GameAction, and CompactReplay
production serializers were not changed except where the spec explicitly permits no change, and that
no AutoPay/native fallback was added to the trusted path.

- [ ] **Step 4: Perform independent review before B0.**

Review the complete diff for:

~~~text
target↔binding bijection and published order
paymentDomain.requiredCost as sole target-bound cost authority
fully-bound affordability independent of parent LegalAction.affordable
strict target/plan coupling and stale zero-mutation rejection
no private-state or Rules reconstruction in policy
privacy-safe nested PaymentDomainV5
v1.25 schema and v5 replay preservation
~~~

Stop with B0 disabled if any check is unresolved.

- [ ] **Step 5: Prepare the handoff without starting a soak.**

Report the final implementation HEAD, exact test commands/results, wrapper/native/hosted distinctions,
changed production files, unchanged historical files, and the explicit gate:

~~~text
TARGET_PAYMENT_DOMAIN_IMPLEMENTATION_REVIEW=WAITING
B0_RERUN_ALLOWED=NO
512_SOAK_ALLOWED=NO
2048_SOAK_ALLOWED=NO
~~~

Do not run B0 smoke, 512, or 2048 in this task after implementation review is requested.


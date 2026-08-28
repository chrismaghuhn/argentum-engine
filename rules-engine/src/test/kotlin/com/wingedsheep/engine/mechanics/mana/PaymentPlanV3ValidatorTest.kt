package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariSignet
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.ReduceActivatedAbilityCost
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/** RED/contract coverage for the V3 ordered program's single global resource ledger. */
class PaymentPlanV3ValidatorTest : FunSpec({

    /** Rules-owned cost modifier used only to prove effective-cost re-resolution on a stale plan. */
    val activationCostModifier = card("PAY106 Activated Cost Modifier") {
        typeLine = "Enchantment"
        staticAbility {
            ability = ReduceActivatedAbilityCost(
                filter = GroupFilter(GameObjectFilter.Artifact.youControl()),
                amount = DynamicAmount.Fixed(1),
            )
        }
    }

    data class SignetFixture(
        val driver: GameTestDriver,
        val player: EntityId,
        val forestId: EntityId,
        val signetId: EntityId,
        val forestKey: String,
        val signetKey: String,
        val signetOutputs: List<FixedManaOutput>,
    )

    fun signetFixture(forestCount: Int = 1, includePool: ManaPoolComponent? = null): SignetFixture {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + GolgariSignet + activationCostModifier)
        driver.initMirrorMatch(Deck.of("Forest" to 40), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val forests = (0 until forestCount).map { driver.putPermanentOnBattlefield(player, "Forest") }
        val signetId = driver.putPermanentOnBattlefield(player, GolgariSignet.name)
        if (includePool != null) driver.addComponent(player, includePool)

        val solver = ManaSolver(driver.cardRegistry)
        val sources = solver.findAvailableManaSources(
            state = driver.state,
            playerId = player,
            spellContext = null,
            paymentOrderRequired = true,
        )
        val forestSource = sources.single { it.entityId == forests.first() }
        val signetSource = sources.single { it.entityId == signetId }
        val bundle = signetSource.paymentManaProductionProfiles.values
            .single { it is PaymentManaProductionProfile.FixedOutputBundle }
                as PaymentManaProductionProfile.FixedOutputBundle

        return SignetFixture(
            driver = driver,
            player = player,
            forestId = forests.first(),
            signetId = signetId,
            forestKey = forestSource.paymentManaAbilityOrder.single(),
            signetKey = signetSource.paymentManaAbilityOrder.single(),
            signetOutputs = bundle.outputs.mapIndexed { index, output ->
                FixedManaOutput(index, output.color)
            },
        )
    }

    fun forestActivation(fixture: SignetFixture): SourceActivationV2 =
        SourceActivationV2(
            sourceId = fixture.forestId,
            manaAbilityKey = fixture.forestKey,
            productionChoice = ProductionChoice(PaymentManaColor.GREEN),
            activationCostOrder = listOf(ActivationCostComponentRefV1.DeterministicNonManaComponent(0)),
        )

    fun signetActivation(
        fixture: SignetFixture,
        activationIndex: Int = 1,
        paymentResource: ManaResourceRefV1 = ManaResourceRefV1.ActivationOutputUnit(0, 0),
    ): SourceActivationV2 = SourceActivationV2(
        sourceId = fixture.signetId,
        manaAbilityKey = fixture.signetKey,
        productionChoice = ProductionChoice(
            producedColor = PaymentManaColor.BLACK,
            fixedOutputs = fixture.signetOutputs,
        ),
        activationCostOrder = listOf(
            ActivationCostComponentRefV1.ManaComponent,
            ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
        ),
        activationCostAllocation = listOf(
            PaymentAllocationV1(
                target = PaymentTargetV1.ActivationCostUnit(activationIndex, 0, 0),
                resource = paymentResource,
            ),
        ),
    )

    fun validPlan(fixture: SignetFixture): PaymentPlanV3 = PaymentPlanV3(
        activations = listOf(
            forestActivation(fixture),
            signetActivation(fixture),
        ),
        outerAllocation = listOf(
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(0, 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(1, 1),
            ),
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(1, 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
            ),
        ),
    )

    fun validate(
        fixture: SignetFixture,
        cost: ManaCost = ManaCost.parse("{1}{B}"),
        plan: PaymentPlanV3 = validPlan(fixture),
    ): PaymentPlanValidation = PaymentPlanValidator(ManaSolver(fixture.driver.cardRegistry)).validateV3(
        state = fixture.driver.state,
        playerId = fixture.player,
        cost = cost,
        plan = plan,
        spellContext = SpellPaymentContext(),
    )

    test("PAY106-03: Forest output pays Signet activation and Signet output pays outer cost") {
        val fixture = signetFixture()

        val accepted = validate(fixture).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()

        accepted.program.activations.map { it.source.entityId } shouldBe
            listOf(fixture.forestId, fixture.signetId)
        accepted.program.allocations.size shouldBe 3
    }

    test("PAY106-ATOMIC-01: both units of an outer {2} cost are independently addressable") {
        val fixture = signetFixture(includePool = ManaPoolComponent(green = 1))
        val plan = validPlan(fixture).copy(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(1, 1),
                ),
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 1),
                    resource = ManaResourceRefV1.InitialPoolResource(
                        InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.GREEN),
                    ),
                ),
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(1, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )

        validate(fixture, cost = ManaCost.parse("{2}{B}"), plan = plan)
            .shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()
    }

    test("PAY106-04: a Signet output cannot fund its own activation") {
        val fixture = signetFixture()
        val plan = validPlan(fixture).copy(
            activations = listOf(
                forestActivation(fixture),
                signetActivation(
                    fixture,
                    paymentResource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "earlier"
        fixture.driver.state shouldBe before
    }

    test("PAY106-05: a later activation output cannot pay an earlier activation") {
        val fixture = signetFixture()
        val plan = PaymentPlanV3(
            activations = listOf(
                signetActivation(
                    fixture,
                    activationIndex = 0,
                    paymentResource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
                forestActivation(fixture),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, cost = ManaCost.ZERO, plan = plan)
            .shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "earlier"
        fixture.driver.state shouldBe before
    }

    test("PAY106-06: one activation output cannot pay both an inner and outer target") {
        val fixture = signetFixture(forestCount = 2)
        val plan = validPlan(fixture).copy(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                ),
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(1, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )
        // Keep the same forest output for both the Signet mana cost and outer generic cost.
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "more than once"
        fixture.driver.state shouldBe before
    }

    test("PAY106-07: the same source cannot appear in two activation nodes") {
        val fixture = signetFixture()
        val plan = validPlan(fixture).copy(
            activations = listOf(
                forestActivation(fixture),
                forestActivation(fixture),
                signetActivation(fixture, activationIndex = 2),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "more than once"
        fixture.driver.state shouldBe before
    }

    test("PAY106-08: initial pool bucket capacity is global across inner and outer payments") {
        val fixture = signetFixture(includePool = ManaPoolComponent(green = 1))
        val greenBucket = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.GREEN)
        val poolResource = ManaResourceRefV1.InitialPoolResource(greenBucket)
        val plan = PaymentPlanV3(
            activations = listOf(
                signetActivation(
                    fixture,
                    activationIndex = 0,
                    paymentResource = poolResource,
                ),
            ),
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = poolResource,
                ),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "capacity"
        fixture.driver.state shouldBe before
    }

    test("PAY106-09: a source that is absent from the current state is stale") {
        val fixture = signetFixture()
        val stale = validPlan(fixture).copy(
            activations = listOf(
                forestActivation(fixture),
                signetActivation(fixture).copy(sourceId = EntityId("stale-source")),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = stale).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "currently available"
        fixture.driver.state shouldBe before
    }

    test("PAY106-10: a stale mana ability key is rejected before ledger use") {
        val fixture = signetFixture()
        val stale = validPlan(fixture).copy(
            activations = listOf(
                forestActivation(fixture),
                signetActivation(fixture).copy(manaAbilityKey = "stale-key"),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = stale).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "identity"
        fixture.driver.state shouldBe before
    }

    test("PAY106-09B: a selected source tapped after publication makes the plan stale") {
        val fixture = signetFixture()
        val plan = validPlan(fixture)

        validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()
        fixture.driver.tapPermanent(fixture.signetId)
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan)
            .shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "currently available"
        fixture.driver.state shouldBe before
    }

    test("PAY106-10B: an effective activation-cost change rejects the old plan with the same ability key") {
        val fixture = signetFixture()
        val plan = validPlan(fixture)

        validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()

        val signetAbility = fixture.driver.cardRegistry
            .requireCard(GolgariSignet.name)
            .activatedAbilities
            .single()
        val solver = ManaSolver(fixture.driver.cardRegistry)
        val originalCost = solver.calculateEffectiveActivatedAbilityCost(
            state = fixture.driver.state,
            sourceId = fixture.signetId,
            controllerId = fixture.player,
            ability = signetAbility,
        )

        fixture.driver.putPermanentOnBattlefield(fixture.player, activationCostModifier.name)

        val changedCost = solver.calculateEffectiveActivatedAbilityCost(
            state = fixture.driver.state,
            sourceId = fixture.signetId,
            controllerId = fixture.player,
            ability = signetAbility,
        )
        changedCost shouldNotBe originalCost
        plan.activations[1].manaAbilityKey shouldBe fixture.signetKey
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan)
            .shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "cost"
        fixture.driver.state shouldBe before
    }
})

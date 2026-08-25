package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.CostUnitAllocationV2
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.ManaSpendReferenceV2
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV2
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.core.SpendAllocationV2
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ExplicitPaymentPlanExecutorTest : FunSpec({

    val fixedBundleSource = card("Executor Fixed Bundle Source") {
        typeLine = "Land — Cave"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.RED).then(Effects.AddMana(Color.GREEN))
            manaAbility = true
        }
    }

    fun setup(): Triple<GameTestDriver, com.wingedsheep.sdk.model.EntityId, com.wingedsheep.sdk.model.EntityId> {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(Deck.of("Forest" to 20), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val forest = driver.findCardInHand(player, "Forest")!!
        driver.playLand(player, forest)
        return Triple(driver, player, forest)
    }

    fun planForForest(forest: com.wingedsheep.sdk.model.EntityId): PaymentPlanV2 = PaymentPlanV2(
        sourceActivations = listOf(
            SourceActivation(
                sourceId = forest,
                manaAbilityKey = ManaAbilityIdentity.intrinsic(Color.GREEN),
                productionChoice = com.wingedsheep.engine.core.ProductionChoice(PaymentManaColor.GREEN),
            ),
        ),
        poolSpend = PoolSpend(),
        spendAllocation = SpendAllocationV2(
            costUnits = listOf(
                CostUnitAllocationV2(
                    symbolIndex = 0,
                    spends = listOf(ManaSpendReferenceV2(sourceId = forest, amount = 1)),
                ),
            ),
        ),
    )

    fun setupWithManaSource(
        manaSource: CardDefinition,
    ): Triple<GameTestDriver, com.wingedsheep.sdk.model.EntityId, com.wingedsheep.sdk.model.EntityId> {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + manaSource)
        driver.initMirrorMatch(Deck.of("Forest" to 20), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val source = driver.putPermanentOnBattlefield(player, manaSource.name)
        return Triple(driver, player, source)
    }

    fun setupWithFixedBundle() = setupWithManaSource(fixedBundleSource)

    test("valid V2 plan materializes selected source, provenance, and caller-owned reason") {
        val (driver, player, forest) = setup()
        val card = driver.state.getEntity(forest)!!.get<CardComponent>()!!
        val context = buildAbilityPaymentContext(
            cardComponent = card,
            projected = driver.state.projectedState,
            sourceId = forest,
            ability = null,
        )
        val services = EngineServices(driver.cardRegistry)
        val result = ExplicitPaymentPlanExecutor(
            manaSolver = ManaSolver(driver.cardRegistry),
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        ).executeV2(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            plan = planForForest(forest),
            paymentContext = context,
            reason = "Caller-supplied payment reason",
        )

        result.error shouldBe null
        result.state shouldNotBe driver.state
        result.state.getEntity(forest)?.has<TappedComponent>() shouldBe true
        result.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()
            ?.green shouldBe 0
        result.spentManaProvenance.sourceIds shouldBe setOf(forest)
        result.events.filterIsInstance<ManaSpentEvent>().single().reason shouldBe "Caller-supplied payment reason"
    }

    test("invalid V2 plans return original state and no partial payment events") {
        val (driver, player, forest) = setup()
        val context = buildAbilityPaymentContext(
            cardComponent = driver.state.getEntity(forest)!!.get<CardComponent>()!!,
            projected = driver.state.projectedState,
            sourceId = forest,
            ability = null,
        )
        val originalState = driver.state
        val result = ExplicitPaymentPlanExecutor(
            manaSolver = ManaSolver(driver.cardRegistry),
            manaAbilitySideEffectExecutor = EngineServices(driver.cardRegistry).manaAbilitySideEffectExecutor,
        ).executeV2(
            state = originalState,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            plan = planForForest(forest).copy(spendAllocation = SpendAllocationV2()),
            paymentContext = context,
            reason = "Caller-supplied payment reason",
        )

        result.error shouldNotBe null
        (result.state === originalState) shouldBe true
        result.events shouldBe emptyList()
        result.state.getEntity(forest)?.has<TappedComponent>() shouldBe false
    }

    test("V2 materialization preserves unspent fixed outputs and source provenance") {
        val (driver, player, sourceId) = setupWithFixedBundle()
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }
        val manaAbilityKey = source.manaAbilityOptionsFor(Color.RED)
            .single()
            .let(ManaAbilityIdentity::key)
        val profile = source.paymentManaProductionProfiles.values.single()
            .shouldBeInstanceOf<PaymentManaProductionProfile.FixedOutputBundle>()
        val fixedOutputs = profile.outputs.mapIndexed { index, output ->
            FixedManaOutput(index, output.color)
        }
        val plan = PaymentPlanV2(
            sourceActivations = listOf(
                SourceActivation(
                    sourceId = sourceId,
                    manaAbilityKey = manaAbilityKey,
                    productionChoice = ProductionChoice(
                        producedColor = PaymentManaColor.RED,
                        fixedOutputs = fixedOutputs,
                    ),
                ),
            ),
            spendAllocation = SpendAllocationV2(
                costUnits = listOf(
                    CostUnitAllocationV2(
                        symbolIndex = 0,
                        spends = listOf(
                            ManaSpendReferenceV2(
                                sourceId = sourceId,
                                sourceOutputIndex = fixedOutputs.first().index,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val card = driver.state.getEntity(sourceId)!!.get<CardComponent>()!!
        val context = buildAbilityPaymentContext(
            cardComponent = card,
            projected = driver.state.projectedState,
            sourceId = sourceId,
            ability = null,
        )
        val result = ExplicitPaymentPlanExecutor(
            manaSolver = ManaSolver(driver.cardRegistry),
            manaAbilitySideEffectExecutor = EngineServices(driver.cardRegistry).manaAbilitySideEffectExecutor,
        ).executeV2(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{R}"),
            plan = plan,
            paymentContext = context,
            reason = "Cast Executor Fixed Bundle",
        )

        result.error shouldBe null
        result.state.getEntity(sourceId)?.has<TappedComponent>() shouldBe true
        val pool = result.state.getEntity(player)!!
            .get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!
        pool.red shouldBe 0
        pool.green shouldBe 1
        pool.manaBySource shouldBe mapOf(sourceId to 1)
        result.spentManaProvenance.sourceIds shouldBe setOf(sourceId)
        result.events.filterIsInstance<ManaSpentEvent>().single().red shouldBe 1
    }

})

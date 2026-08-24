package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.mechanics.layers.AffectsFilter
import com.wingedsheep.engine.mechanics.layers.ContinuousEffectData
import com.wingedsheep.engine.mechanics.layers.ContinuousEffectSourceComponent
import com.wingedsheep.engine.mechanics.layers.Modification
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Production-time subtype snapshots must use projected, not printed, characteristics. */
class ManaProductionSubtypeSnapshotTest : FunSpec({

    val typeShifter = CardDefinition(
        name = "Production Type Shifter",
        manaCost = ManaCost.ZERO,
        typeLine = TypeLine.parse("Enchantment"),
        script = CardScript(),
    )
    val nonbasicForest = CardDefinition(
        name = "Projected Forest",
        manaCost = ManaCost.ZERO,
        typeLine = TypeLine(
            cardTypes = setOf(CardType.LAND),
            subtypes = setOf(Subtype.FOREST),
        ),
        script = CardScript(),
    )

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(typeShifter, nonbasicForest))
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        driver.passPriorityUntil(com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.setProjectedLandType(sourceId: com.wingedsheep.sdk.model.EntityId): com.wingedsheep.sdk.model.EntityId {
        val player = activePlayer!!
        val shifterId = putPermanentOnBattlefield(player, typeShifter.name)
        replaceState(state.updateEntity(shifterId) { container ->
            container.with(
                ContinuousEffectSourceComponent(
                    listOf(
                        ContinuousEffectData(
                            modification = Modification.SetBasicLandTypes(setOf(Subtype.MOUNTAIN.value)),
                            affectsFilter = AffectsFilter.Generic(
                                GroupFilter(baseFilter = GameObjectFilter.Companion.Land)
                            ),
                        )
                    )
                )
            )
        })
        return shifterId
    }

    test("ManaSolver captures the effective projected subtype") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, nonbasicForest.name)
        driver.setProjectedLandType(sourceId)

        driver.state.projectedState.getSubtypes(sourceId) shouldBe setOf(Subtype.MOUNTAIN.value)

        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }
        source.producesColors shouldBe setOf(Color.RED)
        source.sourceSubtypes shouldBe setOf(Subtype.MOUNTAIN)
    }

    test("manual production keeps the production snapshot after the type-changing effect disappears") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, nonbasicForest.name)
        val shifterId = driver.setProjectedLandType(sourceId)

        val decision = ManaPaymentWindow.buildDecision(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{R}"),
            decisionId = "projected-production",
            prompt = "Pay {R}",
            context = com.wingedsheep.engine.core.DecisionContext(
                sourceId = sourceId,
                phase = com.wingedsheep.engine.core.DecisionPhase.RESOLUTION,
            ),
            canDecline = false,
            cardRegistry = driver.cardRegistry,
        )

        val result = ManaPaymentWindow.floatSelectedMana(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{R}"),
            response = ManaSourcesSelectedResponse(
                decisionId = decision.id,
                selectedSources = listOf(sourceId),
            ),
            availableSources = decision.availableSources,
            services = EngineServices(driver.cardRegistry),
        )
        result.paid shouldBe true

        val key = FloatingManaBucketKeyV1(
            sourceId = sourceId,
            poolColor = PaymentManaColor.RED,
            sourceSubtypes = setOf(Subtype.MOUNTAIN),
        )
        val pool = result.state.getEntity(player)!!.get<ManaPoolComponent>()!!.toManaPool()
        pool.manaByFloatingBucket shouldBe mapOf(key to 1)
        pool.manaBySource shouldBe mapOf(sourceId to 1)
        pool.manaBySourceAndColor shouldBe mapOf(
            sourceId to mapOf(PaymentManaColor.RED to 1),
        )
        pool.manaBySubtype shouldBe mapOf(Subtype.MOUNTAIN to 1)

        val effectRemoved = result.state.updateEntity(shifterId) { container ->
            container.without<ContinuousEffectSourceComponent>()
        }
        effectRemoved.projectedState.getSubtypes(sourceId) shouldBe setOf(Subtype.FOREST.value)

        val remainingPool = effectRemoved.getEntity(player)!!.get<ManaPoolComponent>()!!.toManaPool()
        remainingPool.manaByFloatingBucket shouldBe mapOf(key to 1)
        remainingPool.manaBySubtype shouldBe mapOf(Subtype.MOUNTAIN to 1)
    }

    test("direct mana production uses the same projected snapshot seam") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, nonbasicForest.name)
        val shifterId = driver.setProjectedLandType(sourceId)

        driver.submitSuccess(
            ActivateAbility(
                playerId = player,
                sourceId = sourceId,
                abilityId = com.wingedsheep.sdk.scripting.AbilityId.intrinsicMana(Color.RED.symbol),
            )
        )

        val key = FloatingManaBucketKeyV1(sourceId, PaymentManaColor.RED, setOf(Subtype.MOUNTAIN))
        val pool = driver.state.getEntity(player)!!.get<ManaPoolComponent>()!!.toManaPool()
        pool.manaByFloatingBucket shouldBe mapOf(key to 1)

        val afterEffect = driver.state.updateEntity(shifterId) { it.without<ContinuousEffectSourceComponent>() }
        afterEffect.projectedState.getSubtypes(sourceId) shouldBe setOf(Subtype.FOREST.value)
        afterEffect.getEntity(player)!!.get<ManaPoolComponent>()!!.toManaPool()
            .manaByFloatingBucket shouldBe mapOf(key to 1)
    }
})

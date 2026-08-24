package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.engineSerializersModule
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
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun dynamicTreasure(name: String, allowedColors: Set<Color>) = card(name) {
        typeLine = "Artifact — Treasure"
        activatedAbility {
            cost = Costs.SacrificeSelf
            effect = Effects.AddDynamicMana(
                amount = DynamicAmount.Fixed(1),
                allowedColors = allowedColors,
            )
            manaAbility = true
            timing = TimingRule.ManaAbility
        }
    }

    val immediateDynamicTreasure = dynamicTreasure(
        "Immediate Dynamic Treasure",
        setOf(Color.RED),
    )
    val twoColorDynamicTreasure = dynamicTreasure(
        "Two-Color Dynamic Treasure",
        setOf(Color.RED, Color.GREEN),
    )
    val threeColorDynamicTreasure = dynamicTreasure(
        "Three-Color Dynamic Treasure",
        setOf(Color.RED, Color.GREEN, Color.BLUE),
    )

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(
            TestCards.all + listOf(
                typeShifter,
                nonbasicForest,
                immediateDynamicTreasure,
                twoColorDynamicTreasure,
                threeColorDynamicTreasure,
            )
        )
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        driver.passPriorityUntil(com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.assertFloatingTreasure(
        playerId: com.wingedsheep.sdk.model.EntityId,
        sourceId: com.wingedsheep.sdk.model.EntityId,
        color: Color,
    ) {
        val key = FloatingManaBucketKeyV1(
            sourceId = sourceId,
            poolColor = PaymentManaColor.fromEngine(color),
            sourceSubtypes = setOf(Subtype.TREASURE),
        )
        val pool = state.getEntity(playerId)!!.get<ManaPoolComponent>()!!.toManaPool()
        pool.manaByFloatingBucket shouldBe mapOf(key to 1)
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

    test("self-sacrifice dynamic mana keeps the LKI production subtype without pausing") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, immediateDynamicTreasure.name)

        driver.submitSuccess(
            ActivateAbility(
                playerId = player,
                sourceId = sourceId,
                abilityId = immediateDynamicTreasure.activatedAbilities.first().id,
            )
        )

        driver.assertFloatingTreasure(player, sourceId, Color.RED)
    }

    test("self-sacrifice dynamic mana keeps the LKI production subtype through number and color resumes") {
        val cases = listOf(
            Triple(twoColorDynamicTreasure, Color.RED, "number"),
            Triple(threeColorDynamicTreasure, Color.BLUE, "color"),
        )

        for ((card, chosenColor, decisionKind) in cases) {
            val driver = createDriver()
            val player = driver.activePlayer!!
            val sourceId = driver.putPermanentOnBattlefield(player, card.name)

            val activation = driver.submit(
                ActivateAbility(
                    playerId = player,
                    sourceId = sourceId,
                    abilityId = card.activatedAbilities.first().id,
                )
            )
            activation.isPaused shouldBe true

            val json = Json {
                serializersModule = engineSerializersModule
                encodeDefaults = true
            }
            val continuation = driver.state.continuationStack.last()
            val decodedContinuation = json.decodeFromString<ContinuationFrame>(
                json.encodeToString(ContinuationFrame.serializer(), continuation),
            )
            decodedContinuation shouldBe continuation
            driver.replaceState(
                driver.state.copy(
                    continuationStack = driver.state.continuationStack.dropLast(1) + decodedContinuation,
                )
            )

            when (decisionKind) {
                "number" -> {
                    val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseNumberDecision>()
                    driver.submitDecision(
                        player,
                        NumberChosenResponse(decision.id, 1),
                    )
                }
                "color" -> {
                    val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseColorDecision>()
                    driver.submitDecision(
                        player,
                        ColorChosenResponse(decision.id, chosenColor),
                    )
                }
            }

            driver.assertFloatingTreasure(player, sourceId, chosenColor)
        }
    }
})

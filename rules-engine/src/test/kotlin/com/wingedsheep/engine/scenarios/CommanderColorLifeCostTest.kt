package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CommanderRegistryComponent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Characterizes the generic activation-time life-cost gap behind commander-color costs.
 *
 * The test permanent deliberately has no production card identity. It exercises the reusable SDK
 * cost shape directly, so this test does not create or depend on War Room.
 */
class CommanderColorLifeCostTest : FunSpec({

    val testPermanent = card("Test Commander Color Life Cost Permanent") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{3}"), Costs.PayLife(DynamicAmounts.commanderColorIdentityCount()))
            effect = Effects.DrawCards(1)
        }
    }

    val monoCommander = card("Test Mono Commander For Life Cost") {
        manaCost = "{G}"
        colorIdentity = "G"
        typeLine = "Legendary Creature — Human"
        power = 1
        toughness = 1
    }

    val blueCommander = card("Test Blue Commander For Life Cost") {
        manaCost = "{U}"
        colorIdentity = "U"
        typeLine = "Legendary Creature — Human"
        power = 1
        toughness = 1
    }

    val colorlessCommander = card("Test Colorless Commander For Life Cost") {
        manaCost = "{5}"
        colorIdentity = ""
        typeLine = "Legendary Creature — Golem"
        power = 5
        toughness = 5
    }

    fun createDriver(commanders: List<String>): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(testPermanent, monoCommander, blueCommander, colorlessCommander))
        driver.initMultiplayer(
            decks = listOf(Deck.of("Forest" to 40), Deck.of("Forest" to 40)),
            format = if (commanders.isEmpty()) Format.Standard else Format.Commander(),
            commanders = if (commanders.isEmpty()) emptyList() else listOf(commanders.first(), commanders.first()),
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun addRegisteredCommander(driver: GameTestDriver, player: com.wingedsheep.sdk.model.EntityId, name: String) {
        val commanderId = driver.putPermanentOnBattlefield(player, name)
        val movedState = driver.state
            .removeFromZone(ZoneKey(player, Zone.BATTLEFIELD), commanderId)
            .addToZone(ZoneKey(player, Zone.COMMAND), commanderId)
            .updateEntity(player) { container ->
                val registry = container.get<CommanderRegistryComponent>()!!
                container.with(registry.copy(commanderIds = registry.commanderIds + commanderId))
            }
        driver.replaceState(movedState)
    }

    test("a one-color commander can pay the commander-color life cost with one life") {
        val driver = createDriver(listOf(monoCommander.name))
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 1)
        val sourceId = driver.putPermanentOnBattlefield(player, testPermanent.name)
        driver.giveColorlessMana(player, 3)
        val abilityId = driver.cardRegistry.getCard(testPermanent.name)!!.activatedAbilities.single().id

        driver.submit(
            ActivateAbility(playerId = player, sourceId = sourceId, abilityId = abilityId)
        ).error shouldBe null
    }

    test("multiple registered commanders combine to a two-color life cost") {
        val driver = createDriver(listOf(monoCommander.name, blueCommander.name))
        val player = driver.activePlayer!!
        addRegisteredCommander(driver, player, blueCommander.name)
        driver.setLifeTotal(player, 2)
        val sourceId = driver.putPermanentOnBattlefield(player, testPermanent.name)
        driver.giveColorlessMana(player, 3)
        val abilityId = driver.cardRegistry.getCard(testPermanent.name)!!.activatedAbilities.single().id

        driver.submit(ActivateAbility(player, sourceId, abilityId)).error shouldBe null
    }

    test("a colorless commander contributes zero life") {
        val driver = createDriver(listOf(colorlessCommander.name))
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 1)
        val sourceId = driver.putPermanentOnBattlefield(player, testPermanent.name)
        driver.giveColorlessMana(player, 3)
        val abilityId = driver.cardRegistry.getCard(testPermanent.name)!!.activatedAbilities.single().id

        driver.submit(ActivateAbility(player, sourceId, abilityId)).error shouldBe null
        driver.getLifeTotal(player) shouldBe 1
    }

    test("no registered commander makes the activation unavailable") {
        val driver = createDriver(emptyList())
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, testPermanent.name)
        driver.giveColorlessMana(player, 3)
        val action = ActivateAbility(
            player,
            sourceId,
            driver.cardRegistry.getCard(testPermanent.name)!!.activatedAbilities.single().id
        )

        driver.legalActions(player).any { it.action == action } shouldBe false
        driver.submit(action).error shouldBe "Cannot pay ability cost"
    }

    test("insufficient life is excluded from enumeration and rejected by validation") {
        val driver = createDriver(listOf(monoCommander.name, blueCommander.name))
        val player = driver.activePlayer!!
        addRegisteredCommander(driver, player, blueCommander.name)
        driver.setLifeTotal(player, 1)
        val sourceId = driver.putPermanentOnBattlefield(player, testPermanent.name)
        driver.giveColorlessMana(player, 3)
        val action = ActivateAbility(
            player,
            sourceId,
            driver.cardRegistry.getCard(testPermanent.name)!!.activatedAbilities.single().id
        )

        driver.legalActions(player).any { it.action == action } shouldBe false
        driver.submit(action).error shouldBe "Cannot pay ability cost"
    }

    test("serialized state can be replayed identically from a fork") {
        val driver = createDriver(listOf(monoCommander.name, blueCommander.name))
        val player = driver.activePlayer!!
        addRegisteredCommander(driver, player, blueCommander.name)
        driver.setLifeTotal(player, 2)
        val sourceId = driver.putPermanentOnBattlefield(player, testPermanent.name)
        driver.giveColorlessMana(player, 3)
        val action = ActivateAbility(
            player,
            sourceId,
            driver.cardRegistry.getCard(testPermanent.name)!!.activatedAbilities.single().id
        )
        val json = Json {
            serializersModule = engineSerializersModule
            allowStructuredMapKeys = true
        }
        val restored = json.decodeFromString<com.wingedsheep.engine.state.GameState>(
            json.encodeToString(com.wingedsheep.engine.state.GameState.serializer(), driver.state)
        )
        val original = ActionProcessor(driver.cardRegistry).process(driver.state, action).result
        val replay = ActionProcessor(driver.cardRegistry).process(restored, action).result

        original.error shouldBe null
        replay.error shouldBe null
        json.encodeToString(com.wingedsheep.engine.state.GameState.serializer(), original.newState) shouldBe
            json.encodeToString(com.wingedsheep.engine.state.GameState.serializer(), replay.newState)
    }
})

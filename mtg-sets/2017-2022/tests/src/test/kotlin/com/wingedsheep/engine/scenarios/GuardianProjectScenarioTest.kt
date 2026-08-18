package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** Focused characterization for Guardian Project's generic intervening-if condition. */
class GuardianProjectScenarioTest : FunSpec({

    val characterizedGuardianProject = card("Guardian Project") {
        manaCost = "{3}{G}"
        colorIdentity = "G"
        typeLine = "Enchantment"
        oracleText = "Whenever a nontoken creature you control enters, if it doesn't have the same " +
            "name as another creature you control or a creature card in your graveyard, draw a card."

        triggeredAbility {
            trigger = Triggers.entersBattlefield(
                filter = GameObjectFilter.Creature.nontoken().youControl(),
                binding = TriggerBinding.ANY,
            )
            interveningIf = Conditions.TriggeringEntityNameNotSharedWithControlledCreatureOrGraveyard
            effect = Effects.DrawCards(1)
        }
    }

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + characterizedGuardianProject)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castCreatureAndResolveSpell(
        driver: GameTestDriver,
        player: com.wingedsheep.sdk.model.EntityId,
        name: String,
    ) {
        val creature = driver.putCardInHand(player, name)
        driver.giveMana(player, Color.GREEN, amount = 2)
        driver.castSpell(player, creature).isSuccess shouldBe true
        driver.bothPass()
    }

    test("a unique nontoken creature entering draws one card") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("a nontoken creature sharing a name with another controlled creature does not trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")

        driver.stackSize shouldBe 0
        driver.getHandSize(player) shouldBe handBeforeCast
    }

    test("a nontoken creature sharing a name with a creature card in its controller's graveyard does not trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        driver.putCardInGraveyard(player, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")

        driver.stackSize shouldBe 0
        driver.getHandSize(player) shouldBe handBeforeCast
    }

    test("an opponent's creature with the same name does not suppress the draw") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("an opponent's graveyard card with the same name does not suppress the draw") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        driver.putCardInGraveyard(driver.player2, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("a same-name creature appearing after the trigger makes the ability fizzle") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
    }

    test("a same-name creature appearing then disappearing allows the ability to resolve") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        val existing = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        driver.replaceState(
            driver.state.moveToZone(
                existing,
                ZoneKey(player, Zone.BATTLEFIELD),
                ZoneKey(player, Zone.EXILE),
            )
        )
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("a same-name graveyard card appearing after the trigger makes the ability fizzle") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.putCardInGraveyard(player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
    }

    test("a same-name graveyard card appearing then disappearing allows the ability to resolve") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        val existing = driver.putCardInGraveyard(player, "Grizzly Bears")
        driver.replaceState(
            driver.state.moveToZone(
                existing,
                ZoneKey(player, Zone.GRAVEYARD),
                ZoneKey(player, Zone.EXILE),
            )
        )
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("a token creature entering does not trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val dissenter = driver.putCreatureOnBattlefield(player, "Doomed Dissenter")
        val bolt = driver.putCardInHand(player, "Lightning Bolt")
        val handBeforeCast = driver.getHandSize(player)
        driver.giveMana(player, Color.RED)

        driver.castSpell(player, bolt, targets = listOf(dissenter)).isSuccess shouldBe true
        driver.bothPass()
        driver.bothPass()

        driver.stackSize shouldBe 0
        driver.getHandSize(player) shouldBe handBeforeCast - 1
        val token = driver.getPermanents(player).singleOrNull { entityId ->
            driver.state.getEntity(entityId)?.get<CardComponent>()?.name == "Zombie Token"
        }
        token.shouldNotBeNull()
        driver.state.getEntity(token)?.get<TokenComponent>().shouldNotBeNull()
    }

    test("the intervening name check is repeated when a unique entering creature moves to the graveyard") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val creature = driver.putCardInHand(player, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)
        driver.giveMana(player, Color.GREEN, amount = 2)

        driver.castSpell(player, creature).isSuccess shouldBe true
        driver.bothPass()
        driver.moveToGraveyard(driver.findPermanent(player, "Grizzly Bears")!!)
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast - 1
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
        driver.state.getZone(ZoneKey(player, Zone.GRAVEYARD)).size shouldBe 1
    }
})

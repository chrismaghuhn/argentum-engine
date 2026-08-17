package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Focused RED coverage for Guardian Project (RNA #130).
 *
 * Current Oracle:
 * "Whenever a nontoken creature you control enters, if it doesn't have the same name as another
 * creature you control or a creature card in your graveyard, draw a card."
 *
 * The scenario is intentionally RED until the SDK has a reusable predicate/condition that can
 * compare the triggering creature's name against both the controller's battlefield and graveyard
 * at trigger time and again at resolution. A battlefield-only approximation would be incorrect.
 */
class GuardianProjectScenarioTest : FunSpec({

    val guardianProject = "Guardian Project"

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castCreatureAndResolveSpell(driver: GameTestDriver, player: com.wingedsheep.sdk.model.EntityId, name: String) {
        val creature = driver.putCardInHand(player, name)
        driver.giveMana(player, Color.GREEN, amount = 2)
        driver.castSpell(player, creature).isSuccess shouldBe true
        driver.bothPass()
    }

    test("a unique nontoken creature entering draws one card") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, guardianProject)
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast
    }

    test("a nontoken creature sharing a name with another controlled creature does not trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, guardianProject)
        driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")

        driver.stackSize shouldBe 0
        driver.getHandSize(player) shouldBe handBeforeCast - 1
    }

    test("a nontoken creature sharing a name with a creature card in its controller's graveyard does not trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, guardianProject)
        driver.putCardInGraveyard(player, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")

        driver.stackSize shouldBe 0
        driver.getHandSize(player) shouldBe handBeforeCast - 1
    }

    test("an opponent's creature with the same name does not suppress the draw") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, guardianProject)
        driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast
    }

    test("a token creature entering does not trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, guardianProject)
        val dissenter = driver.putCreatureOnBattlefield(player, "Doomed Dissenter")
        val bolt = driver.putCardInHand(player, "Lightning Bolt")
        val handBeforeCast = driver.getHandSize(player)
        driver.giveMana(player, Color.RED)

        driver.castSpell(player, bolt, targets = listOf(dissenter)).isSuccess shouldBe true
        driver.bothPass()
        driver.bothPass()

        driver.stackSize shouldBe 0
        driver.getHandSize(player) shouldBe handBeforeCast - 1
        val token = driver.getPermanents(player).firstOrNull { entityId ->
            driver.state.getEntity(entityId)?.get<CardComponent>()?.name == "Zombie Token"
        }
        token.shouldNotBeNull()
        driver.state.getEntity(token)?.get<TokenComponent>().shouldNotBeNull()
    }

    test("the intervening name check is repeated when a unique entering creature moves to the graveyard") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, guardianProject)
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

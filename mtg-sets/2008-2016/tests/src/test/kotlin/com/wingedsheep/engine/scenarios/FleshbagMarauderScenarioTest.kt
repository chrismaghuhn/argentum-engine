package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ala.cards.FleshbagMarauder
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Fleshbag Marauder (Shards of Alara #76).
 *
 * "When this creature enters, each player sacrifices a creature of their choice."
 */
class FleshbagMarauderScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + FleshbagMarauder)
        driver.initMirrorMatch(
            deck = Deck.of("Swamp" to 40),
            skipMulligans = true,
            startingPlayer = 0,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveUntilDecisionOrStackEmpty(driver: GameTestDriver) {
        var guard = 0
        while (driver.pendingDecision == null && driver.state.stack.isNotEmpty()) {
            check(guard++ < 20) { "Fleshbag Marauder resolution did not make progress" }
            driver.bothPass()
        }
    }

    fun castFleshbag(driver: GameTestDriver, player: EntityId): EntityId {
        val fleshbag = driver.putCardInHand(player, "Fleshbag Marauder")
        driver.giveMana(player, Color.BLACK)
        driver.giveColorlessMana(player, 2)
        driver.castSpell(player, fleshbag).error shouldBe null
        return fleshbag
    }

    test("ETB asks each player to choose one of their own creatures") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val ownCreature = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val opponentCreatureA = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val opponentCreatureB = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val fleshbag = castFleshbag(driver, you)

        resolveUntilDecisionOrStackEmpty(driver)

        val yourChoice = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        yourChoice.playerId shouldBe you
        yourChoice.options.toSet() shouldBe setOf(fleshbag, ownCreature)
        driver.submitCardSelection(you, listOf(ownCreature))

        val opponentChoice = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        opponentChoice.playerId shouldBe opponent
        opponentChoice.options.toSet() shouldBe setOf(opponentCreatureA, opponentCreatureB)
        driver.submitCardSelection(opponent, listOf(opponentCreatureB))

        while (driver.state.stack.isNotEmpty()) {
            driver.bothPass()
        }

        driver.state.getZone(you, Zone.GRAVEYARD) shouldContain ownCreature
        driver.state.getZone(opponent, Zone.GRAVEYARD) shouldContain opponentCreatureB
        driver.state.getZone(you, Zone.BATTLEFIELD) shouldContain fleshbag
        driver.state.getZone(opponent, Zone.BATTLEFIELD) shouldContain opponentCreatureA
    }

    test("controller may choose Fleshbag Marauder itself") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val opponentCreatureA = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val opponentCreatureB = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val fleshbag = castFleshbag(driver, you)

        resolveUntilDecisionOrStackEmpty(driver)

        // Fleshbag is the controller's only creature, so the only legal choice is itself.
        driver.state.getZone(you, Zone.GRAVEYARD) shouldContain fleshbag

        val opponentChoice = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        opponentChoice.playerId shouldBe opponent
        opponentChoice.options.toSet() shouldBe setOf(opponentCreatureA, opponentCreatureB)
        driver.submitCardSelection(opponent, listOf(opponentCreatureA))

        while (driver.state.stack.isNotEmpty()) {
            driver.bothPass()
        }

        driver.state.getZone(opponent, Zone.GRAVEYARD) shouldContain opponentCreatureA
        driver.state.getZone(opponent, Zone.BATTLEFIELD) shouldContain opponentCreatureB
    }

    test("player with no creatures is not prompted") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val ownCreature = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val fleshbag = castFleshbag(driver, you)

        resolveUntilDecisionOrStackEmpty(driver)

        val yourChoice = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        yourChoice.playerId shouldBe you
        driver.submitCardSelection(you, listOf(ownCreature))

        driver.pendingDecision shouldBe null
        while (driver.state.stack.isNotEmpty()) {
            driver.bothPass()
        }

        driver.state.getZone(you, Zone.GRAVEYARD) shouldContain ownCreature
        driver.state.getZone(you, Zone.BATTLEFIELD) shouldContain fleshbag
        driver.getCreatures(opponent).size shouldBe 0
    }
})

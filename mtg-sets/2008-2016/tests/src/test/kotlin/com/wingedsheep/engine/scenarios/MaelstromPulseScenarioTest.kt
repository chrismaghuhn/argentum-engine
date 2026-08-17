package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.arb.cards.MaelstromPulse
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Maelstrom Pulse (ARB #92)
 * Destroys the target nonland permanent and all other permanents with the same name.
 */
class MaelstromPulseScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(MaelstromPulse)
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("destroys every same-named nonland permanent but leaves other names and lands") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = listOf(driver.player1, driver.player2).first { it != player }
        val target = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val sameName = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val opponentCopy = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val differentName = driver.putCreatureOnBattlefield(player, "Hill Giant")
        val land = driver.putLandOnBattlefield(player, "Forest")
        val pulse = driver.putCardInHand(player, "Maelstrom Pulse")

        driver.giveMana(player, Color.BLACK, 1)
        driver.giveMana(player, Color.GREEN, 1)
        driver.giveColorlessMana(player, 1)
        driver.castSpell(player, pulse, targets = listOf(target)).error shouldBe null
        driver.bothPass()

        driver.findPermanent(player, "Grizzly Bears") shouldBe null
        driver.state.getGraveyard(player) shouldContain target
        driver.state.getGraveyard(player) shouldContain sameName
        driver.state.getGraveyard(opponent) shouldContain opponentCopy
        driver.findPermanent(player, "Hill Giant") shouldNotBe null
        driver.state.getEntity(land) shouldNotBe null
    }

    test("cannot target a land") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val land = driver.putLandOnBattlefield(player, "Forest")
        val pulse = driver.putCardInHand(player, "Maelstrom Pulse")

        driver.giveMana(player, Color.BLACK, 1)
        driver.giveMana(player, Color.GREEN, 1)
        driver.giveColorlessMana(player, 1)
        driver.castSpell(player, pulse, targets = listOf(land)).error shouldNotBe null
    }
})

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.isd.cards.BlasphemousAct
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.matchers.shouldBe

/**
 * Blasphemous Act (ISD #130).
 *
 * Oracle: "This spell costs {1} less to cast for each creature on the battlefield. Blasphemous
 * Act deals 13 damage to each creature."
 *
 * The seven-creature board proves the battlefield-wide generic reduction, while resolution proves
 * that the damage reaches creatures on both sides and does not affect a noncreature permanent.
 */
class BlasphemousActScenarioTest : io.kotest.core.spec.style.FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(BlasphemousAct)
        return driver
    }

    test("counts all battlefield creatures for its cost and damages each creature") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)

        val act = driver.putCardInHand(you, "Blasphemous Act")
        repeat(4) { driver.putCreatureOnBattlefield(you, "Grizzly Bears") }
        repeat(3) { driver.putCreatureOnBattlefield(opponent, "Grizzly Bears") }
        val noncreature = driver.putPermanentOnBattlefield(you, "Mind Stone")

        // Seven creatures reduce {8}{R} to {1}{R}; the pool contains exactly that cost.
        driver.giveMana(you, Color.RED, 1)
        driver.giveColorlessMana(you, 1)
        driver.castSpell(you, act).isSuccess shouldBe true
        driver.bothPass()

        driver.getCreatures(you).size shouldBe 0
        driver.getCreatures(opponent).size shouldBe 0
        driver.getPermanents(you) shouldBe listOf(noncreature)
    }
})

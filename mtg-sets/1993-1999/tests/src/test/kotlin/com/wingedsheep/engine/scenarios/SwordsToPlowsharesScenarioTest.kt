package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Swords to Plowshares (LEA #40).
 *
 * Oracle: "Exile target creature. Its controller gains life equal to its power."
 */
class SwordsToPlowsharesScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = com.wingedsheep.sdk.model.Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("exiles a creature and its controller gains its power as life") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        driver.putLandOnBattlefield(you, "Plains")
        val swords = driver.putCardInHand(you, "Swords to Plowshares")
        val victim = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val opponentLife = driver.getLifeTotal(opponent)

        driver.castSpell(you, swords, listOf(victim)).isSuccess shouldBe true
        driver.bothPass()

        driver.getExile(opponent) shouldContain victim
        driver.findPermanent(opponent, "Centaur Courser") shouldBe null
        driver.getLifeTotal(opponent) shouldBe opponentLife + 3
    }

    test("requires a creature target") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        driver.putLandOnBattlefield(you, "Plains")
        val swords = driver.putCardInHand(you, "Swords to Plowshares")
        val land = driver.putLandOnBattlefield(opponent, "Forest")
        val result = driver.castSpell(you, swords, listOf(land))

        result.isSuccess shouldBe false
        driver.getExile(opponent) shouldNotContain land
    }
})

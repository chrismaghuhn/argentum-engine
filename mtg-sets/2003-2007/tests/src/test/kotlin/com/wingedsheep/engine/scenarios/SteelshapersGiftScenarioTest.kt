package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Steelshaper's Gift (5DN #19) — searches for an Equipment and reveals it. */
class SteelshapersGiftScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("presents only Equipment cards for an explicit search and puts the choice into hand") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val gift = driver.putCardInHand(player, "Steelshaper's Gift")
        val equipment = driver.putCardOnTopOfLibrary(player, "Basilisk Collar")
        val irrelevant = driver.putCardOnTopOfLibrary(player, "Pacifism")
        driver.giveMana(player, Color.WHITE)

        driver.castSpell(player, gift).isSuccess shouldBe true
        driver.bothPass()

        val search = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        search.playerId shouldBe player
        search.options shouldContain equipment
        search.options shouldNotContain irrelevant

        driver.submitCardSelection(player, listOf(equipment)).isSuccess shouldBe true
        driver.findCardInHand(player, "Basilisk Collar") shouldBe equipment
        driver.state.getLibrary(player) shouldNotContain equipment
    }
})

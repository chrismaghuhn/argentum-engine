package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Thrilling Discovery (STX #243) — gain life, then optionally discard two to draw three. */
class ThrillingDiscoveryScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castDiscovery(driver: GameTestDriver, player: com.wingedsheep.sdk.model.EntityId) {
        val spell = driver.putCardInHand(player, "Thrilling Discovery")
        driver.giveMana(player, Color.RED)
        driver.giveMana(player, Color.WHITE)
        driver.castSpell(player, spell).isSuccess shouldBe true
        driver.bothPass()
    }

    test("gains two life, asks whether to discard, and draws three after discarding two") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val firstDiscard = driver.putCardInHand(player, "Forest")
        val secondDiscard = driver.putCardInHand(player, "Swamp")
        val drawOne = driver.putCardOnTopOfLibrary(player, "Mountain")
        val drawTwo = driver.putCardOnTopOfLibrary(player, "Command Tower")
        val drawThree = driver.putCardOnTopOfLibrary(player, "Plains")

        castDiscovery(driver, player)

        driver.getLifeTotal(player) shouldBe 22
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>().playerId shouldBe player
        driver.submitYesNo(player, true).error shouldBe null

        val discard = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        discard.playerId shouldBe player
        discard.minSelections shouldBe 2
        discard.maxSelections shouldBe 2
        driver.submitCardSelection(player, listOf(firstDiscard, secondDiscard)).isSuccess shouldBe true

        driver.getGraveyardCardNames(player) shouldContain "Forest"
        driver.getGraveyardCardNames(player) shouldContain "Swamp"
        driver.getHand(player) shouldContain drawOne
        driver.getHand(player) shouldContain drawTwo
        driver.getHand(player) shouldContain drawThree
    }

    test("gains two life but declining the optional discard draws no cards") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putCardInHand(player, "Forest")
        val topCard = driver.putCardOnTopOfLibrary(player, "Mountain")

        castDiscovery(driver, player)

        driver.getLifeTotal(player) shouldBe 22
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, false).error shouldBe null
        driver.state.getLibrary(player).first() shouldBe topCard
        driver.findCardInHand(player, "Mountain").shouldBeNull()
    }
})

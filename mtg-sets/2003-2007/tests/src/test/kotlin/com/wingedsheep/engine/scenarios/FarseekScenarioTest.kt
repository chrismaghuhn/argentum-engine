package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.Farseek
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Farseek (RAV #163)
 * Search your library for a Plains, Island, Swamp, or Mountain card, put it onto the battlefield
 * tapped, then shuffle.
 */
class FarseekScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(Farseek)
        driver.initMirrorMatch(deck = Deck.of("Grizzly Bears" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("searches a permitted basic land, puts it tapped onto the battlefield, and shuffles") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val farseek = driver.putCardInHand(player, "Farseek")
        val matching = driver.putCardOnTopOfLibrary(player, "Plains")
        val invalid = driver.putCardOnTopOfLibrary(player, "Forest")
        driver.putLandOnBattlefield(player, "Forest")
        driver.putLandOnBattlefield(player, "Mountain")

        val shufflesBefore = driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        }

        val cast = driver.castSpell(player, farseek)
        cast.isSuccess shouldBe true
        driver.bothPass()

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.options shouldContain matching
        decision.options shouldNotContain invalid
        driver.submitCardSelection(player, listOf(matching)).isSuccess shouldBe true

        val plains = driver.findPermanent(player, "Plains")
        plains shouldNotBe null
        driver.isTapped(plains!!) shouldBe true
        driver.state.getLibrary(player) shouldNotContain matching
        driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        } shouldBe shufflesBefore + 1
    }
})

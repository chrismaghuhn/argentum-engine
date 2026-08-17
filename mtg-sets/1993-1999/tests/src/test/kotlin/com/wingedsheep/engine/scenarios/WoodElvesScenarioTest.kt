package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.por.cards.WoodElves
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Wood Elves (POR #195)
 * When this creature enters, search your library for a Forest card, put that card onto the
 * battlefield, then shuffle.
 */
class WoodElvesScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(WoodElves)
        driver.initMirrorMatch(deck = Deck.of("Grizzly Bears" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveCreatureAndEtb(driver: GameTestDriver) {
        driver.bothPass()
        if (driver.pendingDecision == null) {
            driver.bothPass()
        }
    }

    test("its enters trigger searches a Forest onto the battlefield untapped and shuffles") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val spell = driver.putCardInHand(player, "Wood Elves")
        val matching = driver.putCardOnTopOfLibrary(player, "Forest")
        val invalid = driver.putCardOnTopOfLibrary(player, "Mountain")
        driver.giveMana(player, Color.GREEN, 1)
        driver.giveColorlessMana(player, 2)

        val shufflesBefore = driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        }

        driver.castSpell(player, spell).isSuccess shouldBe true
        resolveCreatureAndEtb(driver)

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.options shouldContain matching
        decision.options shouldNotContain invalid
        driver.submitCardSelection(player, listOf(matching)).isSuccess shouldBe true

        driver.findPermanent(player, "Wood Elves") shouldNotBe null
        val forest = driver.findPermanent(player, "Forest")
        forest shouldNotBe null
        driver.isTapped(forest!!) shouldBe false
        driver.state.getLibrary(player) shouldNotContain matching
        driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        } shouldBe shufflesBefore + 1
    }

    test("its enters trigger still shuffles when the library has no Forest") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val spell = driver.putCardInHand(player, "Wood Elves")
        val nonmatching = driver.putCardOnTopOfLibrary(player, "Mountain")
        driver.giveMana(player, Color.GREEN, 1)
        driver.giveColorlessMana(player, 2)

        val shufflesBefore = driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        }

        driver.castSpell(player, spell).isSuccess shouldBe true
        resolveCreatureAndEtb(driver)

        driver.pendingDecision shouldBe null
        driver.findPermanent(player, "Wood Elves") shouldNotBe null
        driver.state.getLibrary(player) shouldContain nonmatching
        driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        } shouldBe shufflesBefore + 1
    }
})

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ice.cards.NaturesLore
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
 * Nature's Lore (ICE #255)
 * Search your library for a Forest card, put that card onto the battlefield, then shuffle.
 */
class NaturesLoreScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(NaturesLore)
        driver.initMirrorMatch(deck = Deck.of("Grizzly Bears" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("searches a Forest onto the battlefield untapped and shuffles") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val spell = driver.putCardInHand(player, "Nature's Lore")
        val matching = driver.putCardOnTopOfLibrary(player, "Forest")
        val invalid = driver.putCardOnTopOfLibrary(player, "Plains")
        driver.giveMana(player, Color.GREEN, 1)
        driver.giveColorlessMana(player, 1)

        val shufflesBefore = driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        }

        driver.castSpell(player, spell).isSuccess shouldBe true
        driver.bothPass()

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.options shouldContain matching
        decision.options shouldNotContain invalid
        driver.submitCardSelection(player, listOf(matching)).isSuccess shouldBe true

        val forest = driver.findPermanent(player, "Forest")
        forest shouldNotBe null
        driver.isTapped(forest!!) shouldBe false
        driver.state.getLibrary(player) shouldNotContain matching
        driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        } shouldBe shufflesBefore + 1
    }

    test("can fail to find when the library has no Forest") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val spell = driver.putCardInHand(player, "Nature's Lore")
        val nonmatching = driver.putCardOnTopOfLibrary(player, "Plains")
        driver.giveMana(player, Color.GREEN, 1)
        driver.giveColorlessMana(player, 1)

        driver.castSpell(player, spell).isSuccess shouldBe true
        driver.bothPass()

        driver.pendingDecision shouldBe null

        driver.state.getLibrary(player) shouldContain nonmatching
    }
})

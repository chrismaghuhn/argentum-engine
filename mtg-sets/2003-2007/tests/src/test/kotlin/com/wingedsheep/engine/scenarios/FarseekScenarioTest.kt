package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.Farseek
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

    test("offers only the permitted land types to the caster, rejects invalid selection, and resolves tapped") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val farseek = driver.putCardInHand(player, "Farseek")
        val plains = driver.putCardOnTopOfLibrary(player, "Plains")
        val island = driver.putCardOnTopOfLibrary(player, "Island")
        val swamp = driver.putCardOnTopOfLibrary(player, "Swamp")
        val mountain = driver.putCardOnTopOfLibrary(player, "Mountain")
        val forest = driver.putCardOnTopOfLibrary(player, "Forest")
        val otherCard = driver.putCardOnTopOfLibrary(player, "Grizzly Bears")
        val legalCards = setOf(plains, island, swamp, mountain)

        val shufflesBefore = driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        }

        driver.giveMana(player, Color.GREEN, 1)
        driver.giveColorlessMana(player, 1)

        val cast = driver.castSpell(player, farseek)
        cast.isSuccess shouldBe true
        driver.bothPass()

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.playerId shouldBe player
        decision.context.sourceId shouldBe farseek
        decision.minSelections shouldBe 0
        decision.maxSelections shouldBe 1
        decision.options.toSet() shouldBe legalCards
        decision.cardInfo?.keys shouldBe legalCards
        decision.options shouldNotContain forest
        decision.options shouldNotContain otherCard

        val invalidSelection = driver.submitCardSelection(player, listOf(forest))
        invalidSelection.error shouldNotBe null
        driver.pendingDecision shouldBe decision
        driver.state.getLibrary(player) shouldContain forest
        driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        } shouldBe shufflesBefore

        driver.submitCardSelection(player, listOf(mountain)).isSuccess shouldBe true

        val mountainPermanent = driver.findPermanent(player, "Mountain")
        mountainPermanent shouldNotBe null
        driver.isTapped(mountainPermanent!!) shouldBe true
        driver.state.getLibrary(player) shouldNotContain mountain
        driver.state.getLibrary(player) shouldContain plains
        driver.state.getLibrary(player) shouldContain island
        driver.state.getLibrary(player) shouldContain swamp
        driver.state.getLibrary(player) shouldContain forest
        driver.state.getLibrary(player) shouldContain otherCard
        driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        } shouldBe shufflesBefore + 1
    }

    test("shuffles without a selection when no permitted land is present") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val farseek = driver.putCardInHand(player, "Farseek")
        val nonmatching = driver.putCardOnTopOfLibrary(player, "Forest")
        driver.giveMana(player, Color.GREEN, 1)
        driver.giveColorlessMana(player, 1)

        val shufflesBefore = driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        }

        driver.castSpell(player, farseek).isSuccess shouldBe true
        driver.bothPass()

        driver.pendingDecision shouldBe null
        driver.state.getLibrary(player) shouldContain nonmatching
        driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        } shouldBe shufflesBefore + 1
    }
})

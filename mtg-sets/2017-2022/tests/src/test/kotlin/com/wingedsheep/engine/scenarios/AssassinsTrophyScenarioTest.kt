package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Assassin's Trophy (GRN #152).
 *
 * "Destroy target permanent an opponent controls. Its controller may search their library for a
 * basic land card, put it onto the battlefield, then shuffle."
 */
class AssassinsTrophyScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.castTrophy(caster: com.wingedsheep.sdk.model.EntityId, target: com.wingedsheep.sdk.model.EntityId) {
        val trophy = putCardInHand(caster, "Assassin's Trophy")
        giveMana(caster, Color.BLACK, 1)
        giveMana(caster, Color.GREEN, 1)
        castSpell(caster, trophy, listOf(target)).error shouldBe null
        bothPass()
    }

    test("destroys an opposing permanent and delegates the optional search to its controller") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val target = driver.putPermanentOnBattlefield(opponent, "Mind Stone")
        val basic = driver.putCardOnTopOfLibrary(opponent, "Swamp")
        val shufflesBefore = driver.events.count { it is LibraryShuffledEvent && it.playerId == opponent }

        driver.castTrophy(caster, target)

        val maySearch = driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        maySearch.playerId shouldBe opponent
        driver.submitYesNo(opponent, true).error shouldBe null

        val search = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        search.playerId shouldBe opponent
        search.options shouldContain basic
        driver.submitCardSelection(opponent, listOf(basic)).error shouldBe null
        driver.bothPass()

        driver.findPermanent(opponent, "Mind Stone") shouldBe null
        driver.findPermanent(opponent, "Swamp") shouldBe basic
        driver.getGraveyardCardNames(opponent) shouldContain "Mind Stone"
        driver.state.getLibrary(opponent) shouldNotContain basic
        withClue("shuffle events=${driver.events.filterIsInstance<LibraryShuffledEvent>().map { it.playerId }}") {
            driver.events.count { it is LibraryShuffledEvent && it.playerId == opponent } shouldBe shufflesBefore + 1
        }
    }

    test("the destroyed permanent's controller may decline the search") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val target = driver.putPermanentOnBattlefield(opponent, "Mind Stone")
        val basic = driver.putCardOnTopOfLibrary(opponent, "Swamp")

        driver.castTrophy(caster, target)
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(opponent, false).error shouldBe null

        driver.findPermanent(opponent, "Mind Stone") shouldBe null
        withClue("declining the optional search leaves the basic land in the library") {
            driver.state.getLibrary(opponent) shouldContain basic
        }
    }

    test("Assassin's Trophy cannot target a permanent controlled by its caster") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val ownPermanent = driver.putPermanentOnBattlefield(caster, "Mind Stone")
        val trophy = driver.putCardInHand(caster, "Assassin's Trophy")
        driver.giveMana(caster, Color.BLACK, 1)
        driver.giveMana(caster, Color.GREEN, 1)

        val result = driver.castSpell(caster, trophy, listOf(ownPermanent))

        result.isSuccess shouldBe false
        driver.findPermanent(caster, "Mind Stone") shouldBe ownPermanent
        driver.pendingDecision shouldBe null
    }
})

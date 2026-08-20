package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.LibrarySearchedEvent
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Stoneforge Mystic (WWK #20).
 *
 * "When this creature enters, you may search your library for an Equipment card, reveal it,
 * put it into your hand, then shuffle."
 * "{1}{W}, {T}: You may put an Equipment card from your hand onto the battlefield."
 */
class StoneforgeMysticScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + BasiliskCollar)
        driver.initMirrorMatch(
            deck = Deck.of("Plains" to 40),
            skipMulligans = true,
            startingPlayer = 0,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveStack(driver: GameTestDriver) {
        var guard = 0
        while (guard++ < 30 && driver.state.stack.isNotEmpty() && !driver.isPaused) {
            driver.bothPass()
        }
    }

    test("enters: may search, reveal, and put an Equipment into hand") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val nonEquipmentInLibrary = driver.putCardOnTopOfLibrary(you, "Plains")
        val collarInLibrary = driver.putCardOnTopOfLibrary(you, "Basilisk Collar")
        val stoneforge = driver.putCardInHand(you, "Stoneforge Mystic")

        driver.giveMana(you, Color.WHITE)
        driver.giveColorlessMana(you, 1)
        driver.castSpell(you, stoneforge)
        driver.bothPass() // resolve Stoneforge Mystic
        driver.bothPass() // put its enters-the-battlefield ability on the decision boundary

        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(you, true)

        val search = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        search.options.map(driver::getCardName) shouldBe listOf("Basilisk Collar")
        search.options shouldNotContain nonEquipmentInLibrary
        driver.submitCardSelection(you, listOf(collarInLibrary))
        resolveStack(driver)

        driver.findCardInHand(you, "Basilisk Collar") shouldBe collarInLibrary
        driver.state.getLibrary(you) shouldNotContain collarInLibrary
        driver.events.filterIsInstance<LibrarySearchedEvent>().any { it.playerId == you } shouldBe true
        driver.events.filterIsInstance<LibraryShuffledEvent>().any { it.playerId == you } shouldBe true
    }

    test("enters: declining the may search leaves the Equipment in the library") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val collarInLibrary = driver.putCardOnTopOfLibrary(you, "Basilisk Collar")
        val stoneforge = driver.putCardInHand(you, "Stoneforge Mystic")

        driver.giveMana(you, Color.WHITE)
        driver.giveColorlessMana(you, 1)
        driver.castSpell(you, stoneforge)
        driver.bothPass()
        driver.bothPass()

        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(you, false)
        resolveStack(driver)

        driver.state.getLibrary(you) shouldContain collarInLibrary
        driver.findCardInHand(you, "Basilisk Collar") shouldBe null
    }

    test("activated ability: may put an Equipment from hand onto the battlefield") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val stoneforge = driver.putPermanentOnBattlefield(you, "Stoneforge Mystic")
        driver.removeSummoningSickness(stoneforge)
        val collar = driver.putCardInHand(you, "Basilisk Collar")
        val nonEquipment = driver.putCardInHand(you, "Plains")

        driver.giveMana(you, Color.WHITE)
        driver.giveColorlessMana(you, 1)
        driver.submitSuccess(
            ActivateAbility(
                playerId = you,
                sourceId = stoneforge,
                abilityId = com.wingedsheep.mtg.sets.definitions.wwk.cards.StoneforgeMystic.activatedAbilities.first().id,
                targets = emptyList<ChosenTarget>(),
            ),
        )
        driver.bothPass()

        val selection = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        selection.options shouldContain collar
        selection.options shouldNotContain nonEquipment
        driver.submitCardSelection(you, listOf(collar))
        resolveStack(driver)

        driver.findPermanent(you, "Basilisk Collar") shouldBe collar
        driver.getHand(you) shouldNotContain collar
        driver.isTapped(stoneforge) shouldBe true
        withClue("putting the Equipment onto the battlefield does not attach it") {
            driver.state.getEntity(collar)?.get<AttachedToComponent>() shouldBe null
        }
    }
})

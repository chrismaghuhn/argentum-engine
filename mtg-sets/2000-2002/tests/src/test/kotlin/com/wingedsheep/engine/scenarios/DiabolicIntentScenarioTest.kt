package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.pls.cards.DiabolicIntent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Diabolic Intent (PLS #42) — sacrifice a creature, then search for any card and put it into hand.
 */
class DiabolicIntentScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + DiabolicIntent)
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("sacrifices a creature and presents any library card for an explicit search choice") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrifice = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val intent = driver.putCardInHand(player, "Diabolic Intent")
        val noncreature = driver.putCardOnTopOfLibrary(player, "Lightning Bolt")
        driver.giveMana(player, Color.BLACK, 1)
        driver.giveColorlessMana(player, 1)

        val cast = driver.submit(
            CastSpell(
                playerId = player,
                cardId = intent,
                targets = emptyList(),
                additionalCostPayment = AdditionalCostPayment(
                    sacrificedPermanents = listOf(sacrifice),
                ),
            )
        )
        (cast.isSuccess || cast.isPaused) shouldBe true
        driver.bothPass()

        val search = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        search.playerId shouldBe player
        search.options shouldContain noncreature
        driver.submitCardSelection(player, listOf(noncreature)).isSuccess shouldBe true

        driver.findCardInHand(player, "Lightning Bolt") shouldBe noncreature
        driver.getGraveyardCardNames(player) shouldContain "Grizzly Bears"
        driver.getGraveyardCardNames(player) shouldContain "Diabolic Intent"
        driver.state.getLibrary(player) shouldNotContain noncreature
    }

    test("cannot cast without a creature available for the additional sacrifice cost") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val intent = driver.putCardInHand(player, "Diabolic Intent")
        driver.giveMana(player, Color.BLACK, 1)
        driver.giveColorlessMana(player, 1)

        val cast = driver.submit(
            CastSpell(
                playerId = player,
                cardId = intent,
                targets = emptyList(),
            )
        )

        cast.isSuccess shouldBe false
        driver.findCardInHand(player, "Diabolic Intent") shouldBe intent
    }
})

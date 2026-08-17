package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.m11.cards.VisceraSeer
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Viscera Seer (M11 #120)
 * Sacrifice a creature: Scry 1.
 */
class VisceraSeerScenarioTest : FunSpec({

    val abilityId = VisceraSeer.activatedAbilities.single().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(VisceraSeer)
        return driver
    }

    test("sacrificing a creature presents a scry decision and can leave the card on top") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val seer = driver.putCreatureOnBattlefield(activePlayer, "Viscera Seer")
        val sacrificed = driver.putCreatureOnBattlefield(activePlayer, "Grizzly Bears")
        val topCard = driver.putCardOnTopOfLibrary(activePlayer, "Forest")

        val result = driver.submit(
            ActivateAbility(
                playerId = activePlayer,
                sourceId = seer,
                abilityId = abilityId,
                costPayment = AdditionalCostPayment(sacrificedPermanents = listOf(sacrificed))
            )
        )
        result.isSuccess shouldBe true
        driver.findPermanent(activePlayer, "Grizzly Bears") shouldBe null

        driver.bothPass()

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.playerId shouldBe activePlayer
        decision.options shouldContain topCard
        driver.submitCardSelection(activePlayer, emptyList()).error shouldBe null

        driver.state.getLibrary(activePlayer).first() shouldBe topCard
    }

    test("the scry decision can put the looked-at card on the bottom") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val seer = driver.putCreatureOnBattlefield(activePlayer, "Viscera Seer")
        val sacrificed = driver.putCreatureOnBattlefield(activePlayer, "Grizzly Bears")
        val nextCard = driver.putCardOnTopOfLibrary(activePlayer, "Mountain")
        val topCard = driver.putCardOnTopOfLibrary(activePlayer, "Forest")

        val result = driver.submit(
            ActivateAbility(
                playerId = activePlayer,
                sourceId = seer,
                abilityId = abilityId,
                costPayment = AdditionalCostPayment(sacrificedPermanents = listOf(sacrificed))
            )
        )
        result.isSuccess shouldBe true
        driver.bothPass()

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        driver.submitCardSelection(activePlayer, listOf(topCard)).error shouldBe null

        driver.state.getLibrary(activePlayer).first() shouldBe nextCard
        driver.state.getLibrary(activePlayer).last() shouldBe topCard
    }
})

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.m21.cards.GarruksUprising
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Garruk's Uprising (M21 #186)
 * Conditional draw on entry and on a qualifying creature entering, plus trample for your creatures.
 */
class GarruksUprisingScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(GarruksUprising)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.castAndResolve(cardId: com.wingedsheep.sdk.model.EntityId, player: com.wingedsheep.sdk.model.EntityId) {
        castSpell(player, cardId).error shouldBe null
        bothPass()
        if (stackSize > 0) bothPass()
    }

    test("enters without drawing when no qualifying creature exists and grants trample") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val uprising = driver.putCardInHand(player, "Garruk's Uprising")
        val librarySizeBefore = driver.state.getLibrary(player).size

        driver.giveMana(player, Color.GREEN, 3)
        driver.castAndResolve(uprising, player)

        driver.state.getLibrary(player).size shouldBe librarySizeBefore
        driver.state.projectedState.hasKeyword(creature, Keyword.TRAMPLE) shouldBe true
    }

    test("draws when a power-four creature is already present or enters later") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putCreatureOnBattlefield(player, "Craw Wurm")
        val uprising = driver.putCardInHand(player, "Garruk's Uprising")
        val librarySizeBeforeUprising = driver.state.getLibrary(player).size

        driver.giveMana(player, Color.GREEN, 3)
        driver.castAndResolve(uprising, player)
        driver.state.getLibrary(player).size shouldBe librarySizeBeforeUprising - 1

        val laterCreature = driver.putCardInHand(player, "Craw Wurm")
        val librarySizeBeforeCreature = driver.state.getLibrary(player).size
        driver.giveMana(player, Color.GREEN, 6)
        driver.castAndResolve(laterCreature, player)

        driver.state.getLibrary(player).size shouldBe librarySizeBeforeCreature - 1
    }
})

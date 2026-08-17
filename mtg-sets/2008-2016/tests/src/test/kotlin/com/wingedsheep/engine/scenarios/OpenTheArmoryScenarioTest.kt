package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.soi.cards.OpenTheArmory
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Open the Armory (SOI #32) — searches for either an Aura or an Equipment. */
class OpenTheArmoryScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + OpenTheArmory)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("presents both Aura and Equipment cards as explicit search choices") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val armory = driver.putCardInHand(player, "Open the Armory")
        val aura = driver.putCardOnTopOfLibrary(player, "Pacifism")
        val equipment = driver.putCardOnTopOfLibrary(player, "Basilisk Collar")
        val irrelevant = driver.putCardOnTopOfLibrary(player, "Lightning Bolt")
        driver.giveMana(player, Color.WHITE)
        driver.giveColorlessMana(player, 1)

        driver.castSpell(player, armory).isSuccess shouldBe true
        driver.bothPass()

        val search = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        search.playerId shouldBe player
        search.options shouldContain aura
        search.options shouldContain equipment
        search.options shouldNotContain irrelevant

        driver.submitCardSelection(player, listOf(equipment)).isSuccess shouldBe true
        driver.findCardInHand(player, "Basilisk Collar") shouldBe equipment
        driver.state.getLibrary(player) shouldNotContain equipment
    }
})

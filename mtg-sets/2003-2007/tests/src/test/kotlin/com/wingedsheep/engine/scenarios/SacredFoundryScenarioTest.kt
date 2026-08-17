package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.SacredFoundry
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Sacred Foundry (RAV #280)
 * A Mountain Plains that may enter untapped by paying 2 life, and taps for {R} or {W}.
 */
class SacredFoundryScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(SacredFoundry)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("paying 2 life lets it enter untapped with both printed land types") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val foundry = driver.putCardInHand(player, "Sacred Foundry")

        driver.playLand(player, foundry).isPaused shouldBe true
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, true).error shouldBe null

        driver.pendingDecision shouldBe null
        driver.isTapped(foundry) shouldBe false
        driver.getLifeTotal(player) shouldBe 18
        driver.state.projectedState.hasSubtype(foundry, "Mountain") shouldBe true
        driver.state.projectedState.hasSubtype(foundry, "Plains") shouldBe true
    }

    test("declining the payment makes it enter tapped without losing life") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val foundry = driver.putCardInHand(player, "Sacred Foundry")

        driver.playLand(player, foundry).isPaused shouldBe true
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, false).error shouldBe null

        driver.isTapped(foundry) shouldBe true
        driver.getLifeTotal(player) shouldBe 20
    }

    test("its Mountain and Plains abilities add red and white mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val foundry = driver.putPermanentOnBattlefield(player, "Sacred Foundry")

        driver.submitSuccess(ActivateAbility(player, foundry, AbilityId.intrinsicMana('R')))
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()?.red shouldBe 1

        driver.untapPermanent(foundry)
        driver.submitSuccess(ActivateAbility(player, foundry, AbilityId.intrinsicMana('W')))
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()?.white shouldBe 1
    }
})

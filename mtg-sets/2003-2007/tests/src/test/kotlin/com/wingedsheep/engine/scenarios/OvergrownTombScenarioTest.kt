package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.OvergrownTomb
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Overgrown Tomb (RAV #279)
 * A Swamp Forest that may enter untapped by paying 2 life, and taps for {B} or {G}.
 */
class OvergrownTombScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(OvergrownTomb)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("paying 2 life lets it enter untapped with both printed land types") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val tomb = driver.putCardInHand(player, "Overgrown Tomb")

        driver.playLand(player, tomb).isPaused shouldBe true
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, true).error shouldBe null

        driver.pendingDecision shouldBe null
        driver.isTapped(tomb) shouldBe false
        driver.getLifeTotal(player) shouldBe 18
        driver.state.projectedState.hasSubtype(tomb, "Swamp") shouldBe true
        driver.state.projectedState.hasSubtype(tomb, "Forest") shouldBe true
    }

    test("declining the payment makes it enter tapped without losing life") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val tomb = driver.putCardInHand(player, "Overgrown Tomb")

        driver.playLand(player, tomb).isPaused shouldBe true
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, false).error shouldBe null

        driver.isTapped(tomb) shouldBe true
        driver.getLifeTotal(player) shouldBe 20
    }

    test("its Swamp and Forest abilities add black and green mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val tomb = driver.putPermanentOnBattlefield(player, "Overgrown Tomb")

        driver.submitSuccess(ActivateAbility(player, tomb, AbilityId.intrinsicMana('B')))
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()?.black shouldBe 1

        driver.untapPermanent(tomb)
        driver.submitSuccess(ActivateAbility(player, tomb, AbilityId.intrinsicMana('G')))
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()?.green shouldBe 1
    }
})

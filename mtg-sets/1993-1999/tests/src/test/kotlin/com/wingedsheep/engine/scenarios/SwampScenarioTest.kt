package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.Swamp295
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Swamp — Basic Land — Swamp; {T}: Add {B}. */
class SwampScenarioTest : FunSpec({

    test("taps for black mana as a basic Swamp") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(Swamp295)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val swamp = driver.putPermanentOnBattlefield(player, "Swamp")

        driver.state.projectedState.hasSubtype(swamp, "Swamp") shouldBe true
        driver.submitSuccess(ActivateAbility(player, swamp, AbilityId.intrinsicMana('B')))
        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.black shouldBe 1
    }
})

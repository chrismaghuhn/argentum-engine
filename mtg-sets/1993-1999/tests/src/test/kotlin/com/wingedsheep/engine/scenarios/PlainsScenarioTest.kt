package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.Plains287
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Plains — Basic Land — Plains; {T}: Add {W}. */
class PlainsScenarioTest : FunSpec({

    test("taps for white mana as a basic Plains") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(Plains287)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val plains = driver.putPermanentOnBattlefield(player, "Plains")

        driver.state.projectedState.hasSubtype(plains, "Plains") shouldBe true
        driver.submitSuccess(ActivateAbility(player, plains, AbilityId.intrinsicMana('W')))
        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.white shouldBe 1
    }
})

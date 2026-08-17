package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.Mountain299
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Mountain — Basic Land — Mountain; {T}: Add {R}. */
class MountainScenarioTest : FunSpec({

    test("taps for red mana as a basic Mountain") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(Mountain299)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val mountain = driver.putPermanentOnBattlefield(player, "Mountain")

        driver.state.projectedState.hasSubtype(mountain, "Mountain") shouldBe true
        driver.submitSuccess(ActivateAbility(player, mountain, AbilityId.intrinsicMana('R')))
        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.red shouldBe 1
    }
})

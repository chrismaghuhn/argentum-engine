package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/** Llanowar Elves (LEA #210): {T}: Add {G}. */
class LlanowarElvesScenarioTest : ScenarioTestBase() {

    private val manaAbilityId by lazy {
        cardRegistry.requireCard("Llanowar Elves").activatedAbilities.single().id
    }

    init {
        test("tapping it adds exactly one green mana") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Llanowar Elves", tapped = false, summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val elves = game.findPermanent("Llanowar Elves")!!
            game.execute(ActivateAbility(game.player1Id, elves, manaAbilityId)).error shouldBe null

            game.state.getEntity(elves)?.has<TappedComponent>() shouldBe true
            game.state.getEntity(game.player1Id)?.get<ManaPoolComponent>()?.green shouldBe 1
        }
    }
}

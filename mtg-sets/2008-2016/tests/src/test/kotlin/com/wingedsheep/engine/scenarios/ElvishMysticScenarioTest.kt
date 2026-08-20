package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/** Elvish Mystic (M14 #169): {T}: Add {G}. */
class ElvishMysticScenarioTest : ScenarioTestBase() {

    private val manaAbilityId by lazy {
        cardRegistry.requireCard("Elvish Mystic").activatedAbilities.single().id
    }

    init {
        test("tapping it adds exactly one green mana") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Elvish Mystic", tapped = false, summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val mystic = game.findPermanent("Elvish Mystic")!!
            game.execute(ActivateAbility(game.player1Id, mystic, manaAbilityId)).error shouldBe null

            game.state.getEntity(mystic)?.has<TappedComponent>() shouldBe true
            game.state.getEntity(game.player1Id)?.get<ManaPoolComponent>()?.green shouldBe 1
        }
    }
}

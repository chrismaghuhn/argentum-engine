package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/** Sign in Blood (M10 #112): target player draws two cards and loses 2 life. */
class SignInBloodScenarioTest : ScenarioTestBase() {

    init {
        test("the caster can target themselves") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardInHand(1, "Sign in Blood")
                .withCardInLibrary(1, "Forest")
                .withCardInLibrary(1, "Island")
                .withLandsOnBattlefield(1, "Swamp", 2)
                .withLifeTotal(1, 20)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val handBefore = game.handSize(1)
            game.castSpellTargetingPlayer(1, "Sign in Blood", 1).error shouldBe null
            game.resolveStack()

            game.handSize(1) shouldBe handBefore + 1
            game.getLifeTotal(1) shouldBe 18
        }

        test("the caster can target the opponent") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardInHand(1, "Sign in Blood")
                .withCardInLibrary(2, "Forest")
                .withCardInLibrary(2, "Island")
                .withLandsOnBattlefield(1, "Swamp", 2)
                .withLifeTotal(1, 20)
                .withLifeTotal(2, 20)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val casterHandBefore = game.handSize(1)
            val opponentHandBefore = game.handSize(2)
            game.castSpellTargetingPlayer(1, "Sign in Blood", 2).error shouldBe null
            game.resolveStack()

            game.handSize(1) shouldBe casterHandBefore - 1
            game.handSize(2) shouldBe opponentHandBefore + 2
            game.getLifeTotal(1) shouldBe 20
            game.getLifeTotal(2) shouldBe 18
        }
    }
}

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/** Phyrexian Arena (APC #47): your upkeep draws one card and loses 1 life. */
class PhyrexianArenaScenarioTest : ScenarioTestBase() {

    init {
        test("triggers on your upkeep, drawing one card and losing one life") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Phyrexian Arena")
                .withCardInLibrary(1, "Forest")
                .withCardInLibrary(1, "Island")
                .withActivePlayer(2)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val handBefore = game.handSize(1)
            val lifeBefore = game.getLifeTotal(1)

            game.passUntilPhase(Phase.ENDING, Step.END)
            game.passUntilPhase(Phase.BEGINNING, Step.UPKEEP)
            game.state.activePlayerId shouldBe game.player1Id
            game.resolveStack()

            game.handSize(1) shouldBe handBefore + 1
            game.getLifeTotal(1) shouldBe lifeBefore - 1
        }

        test("does not trigger during an opponent's upkeep") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Phyrexian Arena")
                .withCardInLibrary(1, "Forest")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val handBefore = game.handSize(1)
            val lifeBefore = game.getLifeTotal(1)

            game.passUntilPhase(Phase.ENDING, Step.END)
            game.passUntilPhase(Phase.BEGINNING, Step.UPKEEP)
            game.state.activePlayerId shouldBe game.player2Id
            game.state.stack.size shouldBe 0

            game.handSize(1) shouldBe handBefore
            game.getLifeTotal(1) shouldBe lifeBefore
        }
    }
}

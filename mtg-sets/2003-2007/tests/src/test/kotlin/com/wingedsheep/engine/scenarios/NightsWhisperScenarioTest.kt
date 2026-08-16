package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/** Night's Whisper (5DN #55): draw two cards and lose 2 life. */
class NightsWhisperScenarioTest : ScenarioTestBase() {

    init {
        test("draws exactly two cards and loses exactly two life") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Night's Whisper")
                .withCardInLibrary(1, "Forest")
                .withCardInLibrary(1, "Island")
                .withCardInLibrary(1, "Swamp")
                .withLandsOnBattlefield(1, "Swamp", 2)
                .withLifeTotal(1, 20)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val handBeforeCast = game.handSize(1)
            game.castSpell(1, "Night's Whisper").error shouldBe null
            val handAfterCast = game.handSize(1)

            game.resolveStack()

            handAfterCast shouldBe handBeforeCast - 1
            game.handSize(1) shouldBe handAfterCast + 2
            game.getLifeTotal(1) shouldBe 18
        }
    }
}

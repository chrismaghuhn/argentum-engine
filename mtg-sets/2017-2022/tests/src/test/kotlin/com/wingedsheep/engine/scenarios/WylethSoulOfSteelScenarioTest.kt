package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/**
 * Wyleth, Soul of Steel (CMR #362).
 *
 * Oracle: "Trample\nWhenever Wyleth attacks, draw a card for each Aura and Equipment attached
 * to it."
 *
 * The attack scenarios deliberately include unrelated permanents so the draw amount is proved
 * to be the attachment count, not the size of the controller's battlefield.
 */
class WylethSoulOfSteelScenarioTest : ScenarioTestBase() {

    init {
        test("attacking with no attached Aura or Equipment draws no card") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Wyleth, Soul of Steel", summoningSickness = false)
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                .build()

            game.declareAttackers(mapOf("Wyleth, Soul of Steel" to 2)).error shouldBe null
            game.resolveStack()

            game.librarySize(1) shouldBe 1
        }

        test("draws exactly one card for one attached Equipment") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Wyleth, Soul of Steel", summoningSickness = false)
                .withCardAttachedTo(1, "Loxodon Warhammer", "Wyleth, Soul of Steel")
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withCardInLibrary(1, "Plains")
                .withCardInLibrary(1, "Forest")
                .withActivePlayer(1)
                .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                .build()

            game.declareAttackers(mapOf("Wyleth, Soul of Steel" to 2)).error shouldBe null
            game.resolveStack()

            game.librarySize(1) shouldBe 1
        }
    }
}

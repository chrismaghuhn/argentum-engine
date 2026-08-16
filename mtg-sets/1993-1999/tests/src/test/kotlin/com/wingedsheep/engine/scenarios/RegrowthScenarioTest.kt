package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Regrowth (LEA #214) — {1}{G} Sorcery.
 *
 * "Return target card from your graveyard to your hand."
 */
class RegrowthScenarioTest : ScenarioTestBase() {

    private fun game(targetOwner: Int) = scenario()
        .withPlayers("Player1", "Player2")
        .withCardInHand(1, "Regrowth")
        .withLandsOnBattlefield(1, "Forest", 2)
        .withCardInGraveyard(targetOwner, "Lightning Bolt")
        .withActivePlayer(1)
        .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
        .build()

    init {
        context("Regrowth") {

            test("returns a card from its caster's graveyard to its hand") {
                val game = game(targetOwner = 1)
                val result = game.castSpellTargetingGraveyardCard(1, "Regrowth", 1, "Lightning Bolt")

                withClue("Regrowth can target a card in its caster's graveyard: ${result.error}") {
                    result.error shouldBe null
                }
                game.resolveStack()

                game.isInHand(1, "Lightning Bolt") shouldBe true
                game.isInGraveyard(1, "Lightning Bolt") shouldBe false
            }

            test("cannot target a card in an opponent's graveyard") {
                val game = game(targetOwner = 2)
                val result = game.castSpellTargetingGraveyardCard(1, "Regrowth", 2, "Lightning Bolt")

                withClue("Regrowth is restricted to its caster's graveyard") {
                    result.error shouldNotBe null
                }
                game.isInHand(1, "Lightning Bolt") shouldBe false
                game.isInGraveyard(2, "Lightning Bolt") shouldBe true
            }
        }
    }
}

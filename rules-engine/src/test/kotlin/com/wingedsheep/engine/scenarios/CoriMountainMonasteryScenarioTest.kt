package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Cori Mountain Monastery — the TDM impulse ability.
 *
 * This is intentionally separate from the TDM entry-condition coverage: the Sync-04 change
 * replaced the hand-written gather/move/grant composition with `Patterns.Exile.impulse`.
 */
class CoriMountainMonasteryScenarioTest : ScenarioTestBase() {

    init {
        context("Cori Mountain Monastery") {
            test("exiles the top card and grants permission to play it until next end step") {
                var builder = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardOnBattlefield(1, "Cori Mountain Monastery")
                    .withLandsOnBattlefield(1, "Mountain", 6)
                    .withLandsOnBattlefield(1, "Forest", 2)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)

                // The builder places successive entries on top, so the first entry is the card
                // Cori will exile.
                builder = builder.withCardInLibrary(1, "Grizzly Bears")
                repeat(3) { builder = builder.withCardInLibrary(1, "Forest") }
                val game = builder.build()

                val cori = game.findPermanent("Cori Mountain Monastery")!!
                val impulse = cardRegistry.getCard("Cori Mountain Monastery")!!
                    .activatedAbilities.last()
                val activation = game.execute(
                    ActivateAbility(
                        playerId = game.player1Id,
                        sourceId = cori,
                        abilityId = impulse.id,
                    )
                )
                withClue("activating the {3}{R}, {T} impulse ability should succeed: ${activation.error}") {
                    activation.error shouldBe null
                }
                if (game.hasPendingDecision()) game.submitManaSourcesAutoPay()
                game.resolveStack()

                withClue("the top card was exiled") {
                    game.isInExile(1, "Grizzly Bears") shouldBe true
                }
                withClue("the exiled card remains playable under the impulse permission") {
                    game.castSpellFromExile(1, "Grizzly Bears").error shouldBe null
                }
                game.resolveStack()
                game.isOnBattlefield("Grizzly Bears") shouldBe true
            }
        }
    }
}

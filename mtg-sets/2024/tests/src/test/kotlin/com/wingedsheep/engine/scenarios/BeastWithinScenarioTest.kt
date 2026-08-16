package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Beast Within (BLC #206) — {2}{G} Instant.
 *
 * "Destroy target permanent. Its controller creates a 3/3 green Beast creature token."
 */
class BeastWithinScenarioTest : ScenarioTestBase() {

    init {
        context("Beast Within") {

            test("destroys an opponent's permanent and gives that controller a 3/3 Beast") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardInHand(1, "Beast Within")
                    .withLandsOnBattlefield(1, "Forest", 3)
                    .withCardOnBattlefield(2, "Grizzly Bears", summoningSickness = false)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                val target = game.findPermanent("Grizzly Bears")!!
                val result = game.castSpell(1, "Beast Within", targetId = target)
                withClue("casting Beast Within should succeed: ${result.error}") {
                    result.error shouldBe null
                }
                game.resolveStack()

                withClue("the targeted permanent is destroyed") {
                    game.findPermanent("Grizzly Bears") shouldBe null
                    game.findCardsInGraveyard(2, "Grizzly Bears").size shouldBe 1
                }
                withClue("the destroyed permanent's controller receives one 3/3 Beast") {
                    val beast = game.findPermanent("Beast Token")!!
                    game.findPermanents("Beast Token").size shouldBe 1
                    controllerOf(game, beast) shouldBe game.player2Id
                    game.state.projectedState.getPower(beast) shouldBe 3
                    game.state.projectedState.getToughness(beast) shouldBe 3
                }
                withClue("the caster does not receive the token") {
                    game.findPermanents("Beast Token").count { controllerOf(game, it) == game.player1Id } shouldBe 0
                }
            }
        }
    }

    private fun controllerOf(game: TestGame, id: com.wingedsheep.sdk.model.EntityId) =
        game.state.getEntity(id)?.get<ControllerComponent>()?.playerId
}

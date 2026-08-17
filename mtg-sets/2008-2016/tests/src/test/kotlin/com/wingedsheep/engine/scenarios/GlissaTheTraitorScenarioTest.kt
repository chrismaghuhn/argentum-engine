package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.handlers.continuations.entityIdToChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Glissa, the Traitor (MBS #96): first strike, deathtouch, and the optional artifact recovery
 * trigger for an opponent's creature dying.
 */
class GlissaTheTraitorScenarioTest : ScenarioTestBase() {

    private fun answerRecovery(game: TestGame, artifact: com.wingedsheep.sdk.model.EntityId, accept: Boolean) {
        repeat(3) {
            when (val decision = game.state.pendingDecision) {
                null -> return
                is YesNoDecision -> {
                    game.answerYesNo(accept).error shouldBe null
                }
                is ChooseTargetsDecision -> {
                    if (accept) {
                        game.selectTargets(listOf(artifact)).error shouldBe null
                    } else {
                        game.skipTargets().error shouldBe null
                    }
                }
                else -> error("Unexpected Glissa decision: ${decision::class.simpleName}")
            }
            game.resolveStack()
        }
    }

    init {
        test("has first strike and deathtouch") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Glissa, the Traitor", summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val glissa = game.findPermanent("Glissa, the Traitor")!!
            game.state.projectedState.getPower(glissa) shouldBe 3
            game.state.projectedState.getToughness(glissa) shouldBe 3
            game.state.projectedState.hasKeyword(glissa, Keyword.FIRST_STRIKE) shouldBe true
            game.state.projectedState.hasKeyword(glissa, Keyword.DEATHTOUCH) shouldBe true
        }

        test("an opponent creature death may return a chosen artifact from your graveyard") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Glissa, the Traitor")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withCardInGraveyard(1, "Mind Stone")
                .withCardInHand(1, "Shock")
                .withLandsOnBattlefield(1, "Mountain", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val bears = game.findPermanent("Grizzly Bears")!!
            val shock = game.findCardsInHand(1, "Shock").single()
            val artifact = game.findCardsInGraveyard(1, "Mind Stone").single()

            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = shock,
                    targets = listOf(entityIdToChosenTarget(game.state, bears)),
                ),
            ).error shouldBe null
            game.resolveStack()
            answerRecovery(game, artifact, accept = true)

            withClue("the opponent's creature died") {
                game.isOnBattlefield("Grizzly Bears") shouldBe false
            }
            withClue("the selected artifact returned to its owner's hand") {
                game.findCardsInHand(1, "Mind Stone").size shouldBe 1
            }
        }

        test("the recovery is optional and does not trigger for your own creature") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Glissa, the Traitor")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInGraveyard(1, "Mind Stone")
                .withCardInHand(1, "Shock")
                .withLandsOnBattlefield(1, "Mountain", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val bears = game.findPermanent("Grizzly Bears")!!
            val shock = game.findCardsInHand(1, "Shock").single()
            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = shock,
                    targets = listOf(entityIdToChosenTarget(game.state, bears)),
                ),
            ).error shouldBe null
            game.resolveStack()

            game.state.pendingDecision shouldBe null
            game.findCardsInHand(1, "Mind Stone").size shouldBe 0
        }

        test("declining the may choice leaves the artifact in the graveyard") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Glissa, the Traitor")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withCardInGraveyard(1, "Mind Stone")
                .withCardInHand(1, "Shock")
                .withLandsOnBattlefield(1, "Mountain", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val bears = game.findPermanent("Grizzly Bears")!!
            val shock = game.findCardsInHand(1, "Shock").single()
            val artifact = game.findCardsInGraveyard(1, "Mind Stone").single()
            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = shock,
                    targets = listOf(entityIdToChosenTarget(game.state, bears)),
                ),
            ).error shouldBe null
            game.resolveStack()
            answerRecovery(game, artifact, accept = false)

            game.findCardsInGraveyard(1, "Mind Stone").size shouldBe 1
            game.findCardsInHand(1, "Mind Stone").size shouldBe 0
        }
    }
}

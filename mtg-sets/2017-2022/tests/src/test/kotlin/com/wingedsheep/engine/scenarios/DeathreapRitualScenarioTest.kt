package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Deathreap Ritual (CNS #44; reprinted NCC #336) — {2}{B}{G} Enchantment
 *
 * "Morbid — At the beginning of each end step, if a creature died this turn, you may draw a card."
 *
 * The tests cover the intervening-if gate, a controller-controlled creature dying before the end
 * step, and both choices for the optional draw.
 */
class DeathreapRitualScenarioTest : ScenarioTestBase() {

    init {
        test("no creature died this turn: Deathreap Ritual does not trigger") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Deathreap Ritual")
                .withCardInLibrary(1, "Island")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val handBefore = game.state.getZone(game.player1Id, Zone.HAND).size
            val libraryBefore = game.state.getZone(game.player1Id, Zone.LIBRARY).size

            game.passUntilPhase(Phase.ENDING, Step.END)
            game.resolveStack()

            withClue("the Morbid intervening-if should fail without a creature death") {
                game.state.pendingDecision shouldBe null
            }
            game.state.getZone(game.player1Id, Zone.HAND).size shouldBe handBefore
            game.state.getZone(game.player1Id, Zone.LIBRARY).size shouldBe libraryBefore
        }

        test("a creature you control dies this turn: accepting the optional draw draws one card") {
            val game = gameWithCreatureDyingThisTurn()
            val handBefore = game.state.getZone(game.player1Id, Zone.HAND).size

            game.passUntilPhase(Phase.ENDING, Step.END)
            game.resolveStack()

            game.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(true).error shouldBe null
            game.resolveStack()

            withClue("the accepted optional draw adds one card after the spell left hand") {
                game.state.getZone(game.player1Id, Zone.HAND).size shouldBe handBefore + 1
            }
            game.state.getZone(game.player1Id, Zone.LIBRARY).size shouldBe 0
        }

        test("a creature you control dies this turn: declining the optional draw draws nothing") {
            val game = gameWithCreatureDyingThisTurn()
            val handBefore = game.state.getZone(game.player1Id, Zone.HAND).size

            game.passUntilPhase(Phase.ENDING, Step.END)
            game.resolveStack()

            game.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(false).error shouldBe null
            game.resolveStack()

            game.state.getZone(game.player1Id, Zone.HAND).size shouldBe handBefore
            game.state.getZone(game.player1Id, Zone.LIBRARY).size shouldBe 1
        }
    }

    private fun gameWithCreatureDyingThisTurn(): TestGame {
        val game = scenario()
            .withPlayers("P1", "P2")
            .withCardOnBattlefield(1, "Deathreap Ritual")
            .withCardOnBattlefield(1, "Grizzly Bears")
            .withCardInHand(1, "Shock")
            .withLandsOnBattlefield(1, "Mountain", 1)
            .withCardInLibrary(1, "Island")
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            .build()

        val bears = game.findPermanent("Grizzly Bears")!!
        game.castSpell(1, "Shock", bears).error shouldBe null
        game.resolveStack()
        game.isOnBattlefield("Grizzly Bears") shouldBe false
        return game
    }
}

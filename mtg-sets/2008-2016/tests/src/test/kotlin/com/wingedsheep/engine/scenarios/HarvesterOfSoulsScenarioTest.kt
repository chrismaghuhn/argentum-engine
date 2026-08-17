package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
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

class HarvesterOfSoulsScenarioTest : ScenarioTestBase() {
    init {
        test("when another nontoken creature dies, you may draw a card") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Harvester of Souls")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Shock")
                .withLandsOnBattlefield(1, "Mountain", 1)
                .withCardInLibrary(1, "Island")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val bears = game.findPermanent("Grizzly Bears")!!
            val harvester = game.findPermanent("Harvester of Souls")!!
            val shock = game.findCardsInHand(1, "Shock").single()
            val handBefore = game.state.getZone(game.player1Id, Zone.HAND).size

            val cast = game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = shock,
                    targets = listOf(entityIdToChosenTarget(game.state, bears)),
                ),
            )
            withClue("Shock cast: ${cast.error}") { cast.error shouldBe null }
            game.resolveStack()

            game.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(true).error shouldBe null
            game.resolveStack()

            game.isOnBattlefield("Harvester of Souls") shouldBe true
            game.isOnBattlefield("Grizzly Bears") shouldBe false
            game.state.projectedState.hasKeyword(harvester, Keyword.DEATHTOUCH) shouldBe true
            // Shock left hand (-1), then may-draw yes (+1) → back to handBefore.
            withClue("drew a card after accepting may") {
                game.state.getZone(game.player1Id, Zone.HAND).size shouldBe handBefore
            }
        }

        test("you may decline the draw after another nontoken creature dies") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Harvester of Souls")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withCardInHand(1, "Shock")
                .withLandsOnBattlefield(1, "Mountain", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val bears = game.findPermanent("Grizzly Bears")!!
            val shock = game.findCardsInHand(1, "Shock").single()
            val handBefore = game.state.getZone(game.player1Id, Zone.HAND).size

            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = shock,
                    targets = listOf(entityIdToChosenTarget(game.state, bears)),
                ),
            ).error shouldBe null
            game.resolveStack()

            game.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(false).error shouldBe null
            game.resolveStack()

            game.state.getZone(game.player1Id, Zone.HAND).size shouldBe handBefore - 1
            game.isOnBattlefield("Grizzly Bears") shouldBe false
        }

        test("a token creature death does not trigger Harvester of Souls") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Harvester of Souls")
                .withCardOnBattlefield(1, "Grizzly Bears", isToken = true)
                .withCardInHand(1, "Shock")
                .withLandsOnBattlefield(1, "Mountain", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val token = game.findPermanent("Grizzly Bears")!!
            val shock = game.findCardsInHand(1, "Shock").single()
            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = shock,
                    targets = listOf(entityIdToChosenTarget(game.state, token)),
                ),
            ).error shouldBe null
            game.resolveStack()

            game.state.pendingDecision shouldBe null
            game.isOnBattlefield("Harvester of Souls") shouldBe true
            game.isOnBattlefield("Grizzly Bears") shouldBe false
        }

        test("the word another excludes Harvester itself from its death trigger") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Harvester of Souls")
                .withCardInHand(1, "Wrath of God")
                .withLandsOnBattlefield(1, "Plains", 4)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Wrath of God").error shouldBe null
            game.resolveStack()

            game.state.pendingDecision shouldBe null
            game.isOnBattlefield("Harvester of Souls") shouldBe false
        }
    }
}

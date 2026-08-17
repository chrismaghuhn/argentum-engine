package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Garruk's Packleader (M11 #177).
 *
 * Oracle: "Whenever another creature you control with power 3 or greater enters, you may draw
 * a card."
 *
 * Covers the power threshold, controller restriction, the explicit may choice, and the fact that
 * a smaller creature does not create a decision.
 */
class GarruksPackleaderScenarioTest : ScenarioTestBase() {

    init {
        test("offers a may draw for your creature entering with power at least three") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Garruk's Packleader")
                .withLandsOnBattlefield(1, "Forest", 3)
                .withCardInHand(1, "Centaur Courser")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Centaur Courser").error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(true).error shouldBe null
            game.resolveStack()

            game.librarySize(1) shouldBe 0
        }

        test("a player may decline the draw") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Garruk's Packleader")
                .withLandsOnBattlefield(1, "Forest", 3)
                .withCardInHand(1, "Centaur Courser")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Centaur Courser").error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(false).error shouldBe null
            game.resolveStack()

            game.librarySize(1) shouldBe 1
        }

        test("a smaller creature entering does not trigger") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Garruk's Packleader")
                .withLandsOnBattlefield(1, "Forest", 3)
                .withCardInHand(1, "Grizzly Bears")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Grizzly Bears").error shouldBe null
            game.resolveStack()
            game.getPendingDecision() shouldBe null
            game.librarySize(1) shouldBe 1
        }

        test("an opponent's creature is ignored") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Garruk's Packleader")
                .withLandsOnBattlefield(2, "Forest", 3)
                .withCardInHand(2, "Centaur Courser")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(2)
                .inPhase(com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(2, "Centaur Courser").error shouldBe null
            game.resolveStack()
            game.getPendingDecision() shouldBe null

            game.librarySize(1) shouldBe 1
        }
    }
}

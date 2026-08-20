package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Mentor of the Meek (ISD #21).
 *
 * Oracle: "Whenever another creature you control with power 2 or less enters, you may pay {1}.
 * If you do, draw a card."
 *
 * The scenarios cover the controller and "another" restrictions, the power threshold, and the
 * explicit optional mana-payment boundary.
 */
class MentorOfTheMeekScenarioTest : ScenarioTestBase() {

    init {
        context("Mentor of the Meek") {

            test("offers the payment and draws when a qualifying creature enters") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, "Mentor of the Meek")
                    .withLandsOnBattlefield(1, "Forest", 3)
                    .withCardInHand(1, "Grizzly Bears")
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.castSpell(1, "Grizzly Bears").error shouldBe null
                game.resolveStack()

                game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
                game.answerYesNo(true).error shouldBe null
                game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()
                game.submitManaSourcesAutoPay()
                game.resolveStack()

                game.librarySize(1) shouldBe 0
            }

            test("declining the payment does not draw") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, "Mentor of the Meek")
                    .withLandsOnBattlefield(1, "Forest", 3)
                    .withCardInHand(1, "Grizzly Bears")
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.castSpell(1, "Grizzly Bears").error shouldBe null
                game.resolveStack()

                game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
                game.answerYesNo(false).error shouldBe null
                game.resolveStack()

                game.librarySize(1) shouldBe 1
            }

            test("a creature above power two does not trigger") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, "Mentor of the Meek")
                    .withLandsOnBattlefield(1, "Forest", 5)
                    .withCardInHand(1, "Centaur Courser")
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.castSpell(1, "Centaur Courser").error shouldBe null
                game.resolveStack()

                game.getPendingDecision() shouldBe null
                game.librarySize(1) shouldBe 1
            }

            test("an opponent's qualifying creature is ignored") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, "Mentor of the Meek")
                    .withLandsOnBattlefield(2, "Forest", 3)
                    .withCardInHand(2, "Grizzly Bears")
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(2)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.castSpell(2, "Grizzly Bears").error shouldBe null
                game.resolveStack()

                game.getPendingDecision() shouldBe null
                game.librarySize(1) shouldBe 1
            }
        }
    }
}

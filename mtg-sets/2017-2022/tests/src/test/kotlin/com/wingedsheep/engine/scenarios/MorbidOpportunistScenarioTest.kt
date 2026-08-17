package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Morbid Opportunist (MID #113) — {2}{B} Creature — Human Rogue, 1/3.
 *
 * "Whenever one or more other creatures die, draw a card. This ability triggers only once each
 * turn."
 *
 * The scenarios cover the global death trigger, the once-per-turn cap, and the Scryfall ruling
 * that the ability still triggers when Morbid Opportunist dies at the same time as another creature.
 */
class MorbidOpportunistScenarioTest : ScenarioTestBase() {

    init {
        context("Morbid Opportunist — draw after other creatures die") {

            test("draws when an opponent's creature dies") {
                val game = scenario()
                    .withPlayers("P1", "P2")
                    .withCardOnBattlefield(1, "Morbid Opportunist")
                    .withCardOnBattlefield(2, "Grizzly Bears")
                    .withCardInHand(1, "Shock")
                    .withLandsOnBattlefield(1, "Mountain", 1)
                    .withCardInLibrary(1, "Island")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                val bears = game.findPermanent("Grizzly Bears")!!
                val handBefore = game.handSize(1)

                game.castSpell(1, "Shock", bears).error shouldBe null
                game.resolveStack()

                game.isOnBattlefield("Grizzly Bears") shouldBe false
                withClue("the opposing creature's death replaces the Shock card in hand") {
                    game.handSize(1) shouldBe handBefore
                }
                game.librarySize(1) shouldBe 0
            }

            test("draws when it dies at the same time as another creature") {
                val game = scenario()
                    .withPlayers("P1", "P2")
                    .withCardOnBattlefield(1, "Morbid Opportunist")
                    .withCardOnBattlefield(1, "Grizzly Bears")
                    .withCardOnBattlefield(2, "Hill Giant")
                    .withCardInHand(1, "Day of Judgment")
                    .withLandsOnBattlefield(1, "Plains", 4)
                    .withCardInLibrary(1, "Island")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                val handBefore = game.handSize(1)

                game.castSpell(1, "Day of Judgment").error shouldBe null
                game.resolveStack()

                game.isOnBattlefield("Morbid Opportunist") shouldBe false
                game.isOnBattlefield("Grizzly Bears") shouldBe false
                game.isOnBattlefield("Hill Giant") shouldBe false
                withClue("last-known information keeps the simultaneous-death trigger alive") {
                    game.handSize(1) shouldBe handBefore
                }
                game.librarySize(1) shouldBe 0
            }

            test("triggers only once across separate death batches in one turn") {
                val game = scenario()
                    .withPlayers("P1", "P2")
                    .withCardOnBattlefield(1, "Morbid Opportunist")
                    .withCardOnBattlefield(2, "Grizzly Bears")
                    .withCardOnBattlefield(2, "Hill Giant")
                    .withCardInHand(1, "Shock")
                    .withCardInHand(1, "Shock")
                    .withLandsOnBattlefield(1, "Mountain", 2)
                    .withCardInLibrary(1, "Island")
                    .withCardInLibrary(1, "Forest")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                val firstTarget = game.findPermanent("Grizzly Bears")!!
                val secondTarget = game.findPermanent("Hill Giant")!!

                game.castSpell(1, "Shock", firstTarget).error shouldBe null
                game.resolveStack()
                val libraryAfterFirstDeath = game.librarySize(1)
                libraryAfterFirstDeath shouldBe 1

                game.castSpell(1, "Shock", secondTarget).error shouldBe null
                game.resolveStack()

                withClue("the second death batch in the same turn is capped") {
                    game.librarySize(1) shouldBe libraryAfterFirstDeath
                }
            }
        }
    }
}

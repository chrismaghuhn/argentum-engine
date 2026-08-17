package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Sram, Senior Edificer (AER #23).
 *
 * Casting each of an Aura, Equipment, and Vehicle draws one card; casting an unrelated creature
 * does not. The test uses a one-card library so the trigger's exact one-card effect is visible.
 */
class SramSeniorEdificerScenarioTest : ScenarioTestBase() {

    private fun gameWithSram(cardName: String, landName: String, landCount: Int) = scenario()
        .withPlayers("P1", "P2")
        .withCardOnBattlefield(1, "Sram, Senior Edificer")
        .withCardInHand(1, cardName)
        .withCardInLibrary(1, "Plains")
        .withLandsOnBattlefield(1, landName, landCount)
        .withActivePlayer(1)
        .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
        .build()

    init {
        test("casting an Equipment draws a card") {
            val game = gameWithSram("Bonesplitter", "Plains", 2)

            val cast = game.castSpell(1, "Bonesplitter")
            withClue("Bonesplitter should be castable: ${cast.error}") { cast.error shouldBe null }
            game.resolveStack()

            game.librarySize(1) shouldBe 0
        }

        test("casting an Aura draws a card") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Sram, Senior Edificer")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Holy Strength")
                .withCardInLibrary(1, "Plains")
                .withLandsOnBattlefield(1, "Plains", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val bear = game.findPermanent("Grizzly Bears")!!
            val cast = game.castSpell(1, "Holy Strength", bear)
            withClue("Holy Strength should be castable: ${cast.error}") { cast.error shouldBe null }
            game.resolveStack()

            game.librarySize(1) shouldBe 0
        }

        test("casting a Vehicle draws a card") {
            val game = gameWithSram("Cultivator's Caravan", "Plains", 4)

            val cast = game.castSpell(1, "Cultivator's Caravan")
            withClue("Cultivator's Caravan should be castable: ${cast.error}") { cast.error shouldBe null }
            game.resolveStack()

            game.librarySize(1) shouldBe 0
        }

        test("casting an unrelated creature does not draw") {
            val game = gameWithSram("Grizzly Bears", "Forest", 2)

            val cast = game.castSpell(1, "Grizzly Bears")
            withClue("Grizzly Bears should be castable: ${cast.error}") { cast.error shouldBe null }
            game.resolveStack()

            game.librarySize(1) shouldBe 1
        }
    }
}

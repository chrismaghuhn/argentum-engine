package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step

/**
 * Annoyed Altisaur (CMR #216) — its Cascade keyword is backed by a real cast trigger.
 *
 * The pinned upstream delta changed this card from display-only metadata to
 * `WhenYouCastThisSpell -> Effects.Cascade`; this test proves the trigger resolves before the
 * creature and offers a cheaper hit for free.
 */
class AnnoyedAltisaurScenarioTest : ScenarioTestBase() {

    init {
        context("Annoyed Altisaur") {
            test("casting it resolves a real Cascade trigger and casts a cheaper hit for free") {
                var builder = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardInHand(1, "Annoyed Altisaur")
                    .withLandsOnBattlefield(1, "Forest", 7)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)

                // Cascade walks past the land and hits Grizzly Bears (mana value 2 < 7).
                builder = builder.withCardInLibrary(1, "Mountain")
                builder = builder.withCardInLibrary(1, "Grizzly Bears")
                repeat(4) { builder = builder.withCardInLibrary(1, "Forest") }
                val game = builder.build()

                game.castSpell(1, "Annoyed Altisaur").error shouldBe null
                game.resolveStack()

                withClue("the cast trigger pauses for the Cascade free-cast choice") {
                    game.hasPendingDecision() shouldBe true
                    game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
                }
                game.answerYesNo(true).error shouldBe null
                game.resolveStack()

                withClue("the cheaper cascade hit was cast without paying its mana cost") {
                    game.isOnBattlefield("Grizzly Bears") shouldBe true
                }
                withClue("the original creature spell still resolved underneath Cascade") {
                    game.isOnBattlefield("Annoyed Altisaur") shouldBe true
                }
            }
        }
    }
}

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Toski, Bearer of Secrets (KHM #197).
 *
 * Oracle:
 * "This spell can't be countered.
 * Indestructible
 * Toski attacks each combat if able.
 * Whenever a creature you control deals combat damage to a player, draw a card."
 */
class ToskiBearerOfSecretsScenarioTest : ScenarioTestBase() {

    init {
        test("cannot be countered, survives lethal damage, and must attack if able") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Toski, Bearer of Secrets")
                .withCardInHand(1, "Lightning Bolt")
                .withCardInHand(2, "Counterspell")
                .withLandsOnBattlefield(1, "Forest", 4)
                .withLandsOnBattlefield(1, "Mountain", 1)
                .withLandsOnBattlefield(2, "Island", 2)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Toski, Bearer of Secrets").error shouldBe null
            game.passPriority().error shouldBe null
            game.castSpellTargetingStackSpell(2, "Counterspell", "Toski, Bearer of Secrets").error shouldBe null
            game.resolveStack()

            val toski = game.findPermanent("Toski, Bearer of Secrets")!!
            withClue("the uncounterable spell resolves") {
                toski shouldNotBe null
            }

            game.castSpell(1, "Lightning Bolt", targetId = toski).error shouldBe null
            game.resolveStack()
            withClue("indestructible Toski survives lethal damage") {
                game.findPermanent("Toski, Bearer of Secrets") shouldNotBe null
            }

            val attackGame = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Toski, Bearer of Secrets", summoningSickness = false)
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            attackGame.passUntilPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
            withClue("Toski must be declared as an attacker when able") {
                attackGame.declareAttackers(mapOf("Grizzly Bears" to 2)).error shouldNotBe null
            }
            attackGame.declareAttackers(
                mapOf("Toski, Bearer of Secrets" to 2, "Grizzly Bears" to 2),
            ).error shouldBe null
        }

        test("draws once for each creature you control that deals combat damage") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Toski, Bearer of Secrets", summoningSickness = false)
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withCardInLibrary(1, "Forest")
                .withCardInLibrary(1, "Mountain")
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val handBefore = game.handSize(1)
            game.passUntilPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
            game.declareAttackers(
                mapOf("Toski, Bearer of Secrets" to 2, "Grizzly Bears" to 2),
            ).error shouldBe null
            game.passUntilPhase(Phase.COMBAT, Step.END_COMBAT)

            withClue("each unblocked creature's combat damage draws one card") {
                game.handSize(1) shouldBe handBefore + 2
            }
        }
    }
}

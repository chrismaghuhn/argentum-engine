package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Unearth (ULG #72) — "Return target creature card with mana value 3 or less from your
 * graveyard to the battlefield. Cycling {2}."
 *
 * The scenario keeps the two printed clauses separate: reanimation proves the creature, owner,
 * zone, and mana-value boundaries; cycling proves the discard-and-draw alternate ability and its
 * payment gate.
 */
class UnearthScenarioTest : ScenarioTestBase() {

    private fun precombatMain() =
        scenario()
            .withPlayers("Player1", "Player2")
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)

    init {
        test("returns a creature with mana value 3 or less from its controller's graveyard") {
            val game = precombatMain()
                .withCardInHand(1, "Unearth")
                .withCardInGraveyard(1, "Grizzly Bears")
                .withCardInLibrary(1, "Forest")
                .withLandsOnBattlefield(1, "Swamp", 1)
                .build()

            val cast = game.castSpellTargetingGraveyardCard(1, "Unearth", 1, "Grizzly Bears")
            withClue("Unearth should accept a qualifying graveyard target: ${cast.error}") {
                cast.error shouldBe null
            }
            game.resolveStack()

            game.isOnBattlefield("Grizzly Bears") shouldBe true
            game.isInGraveyard(1, "Grizzly Bears") shouldBe false
        }

        test("rejects an over-limit or opponent-controlled graveyard target") {
            val overLimit = precombatMain()
                .withCardInHand(1, "Unearth")
                .withCardInGraveyard(1, "Air Elemental")
                .withLandsOnBattlefield(1, "Swamp", 1)
                .build()

            val overLimitCast = overLimit.castSpellTargetingGraveyardCard(1, "Unearth", 1, "Air Elemental")
            overLimitCast.error shouldBe "Target does not match filter: you own creature with mana value 3 or less in a graveyard"
            overLimit.isInGraveyard(1, "Air Elemental") shouldBe true

            val opponentTarget = precombatMain()
                .withCardInHand(1, "Unearth")
                .withCardInGraveyard(2, "Grizzly Bears")
                .withLandsOnBattlefield(1, "Swamp", 1)
                .build()

            val opponentCast = opponentTarget.castSpellTargetingGraveyardCard(1, "Unearth", 2, "Grizzly Bears")
            opponentCast.error shouldBe "Target does not match filter: you own creature with mana value 3 or less in a graveyard"
            opponentTarget.isInGraveyard(2, "Grizzly Bears") shouldBe true
        }

        test("cycling pays {2}, discards Unearth, and draws a replacement card") {
            val game = precombatMain()
                .withCardInHand(1, "Unearth")
                .withCardInLibrary(1, "Grizzly Bears")
                .withLandsOnBattlefield(1, "Swamp", 2)
                .build()

            val cycle = game.cycleCard(1, "Unearth")
            withClue("Cycling should succeed: ${cycle.error}") { cycle.error shouldBe null }
            game.resolveStack()

            game.isInGraveyard(1, "Unearth") shouldBe true
            game.isInHand(1, "Unearth") shouldBe false
            game.isInHand(1, "Grizzly Bears") shouldBe true
        }

        test("cycling fails without the {2} payment") {
            val game = precombatMain()
                .withCardInHand(1, "Unearth")
                .withCardInLibrary(1, "Grizzly Bears")
                .build()

            val cycle = game.cycleCard(1, "Unearth")

            cycle.error shouldBe "Not enough mana to cycle this card"
            game.isInHand(1, "Unearth") shouldBe true
            game.isInGraveyard(1, "Unearth") shouldBe false
        }
    }
}

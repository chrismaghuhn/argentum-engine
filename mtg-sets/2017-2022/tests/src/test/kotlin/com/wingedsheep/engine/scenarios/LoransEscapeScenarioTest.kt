package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Loran's Escape (BRO #14).
 *
 * Oracle: "Target artifact or creature gains hexproof and indestructible until end of turn.
 * Scry 1."
 */
class LoransEscapeScenarioTest : ScenarioTestBase() {

    init {
        test("protects an artifact and exposes the scry choice") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardsInHand(1, "Loran's Escape", 2)
                .withCardOnBattlefield(1, "Mind Stone")
                .withCardInLibrary(1, "Forest")
                .withLandsOnBattlefield(1, "Plains", 1)
                .withLifeTotal(1, 20)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val artifact = game.findPermanent("Mind Stone")!!
            game.castSpell(1, "Loran's Escape", artifact).error shouldBe null
            game.resolveStack()

            val select = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.selectCards(listOf(select.options.first())).error shouldBe null
            game.resolveStack()

            game.state.projectedState.hasKeyword(artifact, Keyword.HEXPROOF) shouldBe true
            game.state.projectedState.hasKeyword(artifact, Keyword.INDESTRUCTIBLE) shouldBe true

            val startTurn = game.state.turnNumber
            var guard = 0
            while (game.state.turnNumber == startTurn && guard++ < 200) {
                game.passPriority().error shouldBe null
            }
            game.state.turnNumber shouldBe startTurn + 1
            game.state.projectedState.hasKeyword(artifact, Keyword.HEXPROOF) shouldBe false
            game.state.projectedState.hasKeyword(artifact, Keyword.INDESTRUCTIBLE) shouldBe false
        }

        test("can target a creature regardless of controller but rejects a land") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardsInHand(1, "Loran's Escape", 2)
                .withCardOnBattlefield(2, "Hill Giant")
                .withCardOnBattlefield(1, "Forest")
                .withCardInLibrary(1, "Mountain")
                .withLandsOnBattlefield(1, "Plains", 1)
                .withLifeTotal(1, 20)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val opponentCreature = game.findPermanent("Hill Giant")!!
            game.castSpell(1, "Loran's Escape", opponentCreature).error shouldBe null
            game.resolveStack()
            val select = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.selectCards(listOf(select.options.first())).error shouldBe null
            game.resolveStack()

            game.state.projectedState.hasKeyword(opponentCreature, Keyword.HEXPROOF) shouldBe true
            game.state.projectedState.hasKeyword(opponentCreature, Keyword.INDESTRUCTIBLE) shouldBe true

            val land = game.findPermanent("Forest")!!
            game.castSpell(1, "Loran's Escape", land).error shouldNotBe null
        }
    }
}

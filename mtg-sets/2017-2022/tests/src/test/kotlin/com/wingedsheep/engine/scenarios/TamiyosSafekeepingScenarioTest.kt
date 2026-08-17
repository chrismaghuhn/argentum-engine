package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Tamiyo's Safekeeping (NEO #211): protect a permanent you control and gain 2 life. */
class TamiyosSafekeepingScenarioTest : ScenarioTestBase() {

    init {
        test("grants hexproof and indestructible to your permanent until end of turn") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardInHand(1, "Tamiyo's Safekeeping")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(2, "Hill Giant")
                .withLandsOnBattlefield(1, "Forest", 1)
                .withLifeTotal(1, 20)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val ownPermanent = game.findPermanent("Grizzly Bears")!!
            val opponentPermanent = game.findPermanent("Hill Giant")!!
            game.castSpell(1, "Tamiyo's Safekeeping", opponentPermanent).error shouldNotBe null

            game.castSpell(1, "Tamiyo's Safekeeping", ownPermanent).error shouldBe null
            game.resolveStack()

            game.state.projectedState.hasKeyword(ownPermanent, Keyword.HEXPROOF) shouldBe true
            game.state.projectedState.hasKeyword(ownPermanent, Keyword.INDESTRUCTIBLE) shouldBe true
            game.getLifeTotal(1) shouldBe 22

            val startTurn = game.state.turnNumber
            var guard = 0
            var attackersSubmitted = false
            var blockersSubmitted = false
            while (game.state.turnNumber == startTurn && guard++ < 200) {
                when (game.state.step) {
                    Step.DECLARE_ATTACKERS -> if (!attackersSubmitted) {
                        game.declareAttackers(emptyMap()).error shouldBe null
                        attackersSubmitted = true
                    } else {
                        game.passPriority().error shouldBe null
                    }
                    Step.DECLARE_BLOCKERS -> if (!blockersSubmitted) {
                        game.declareNoBlockers().error shouldBe null
                        blockersSubmitted = true
                    } else {
                        game.passPriority().error shouldBe null
                    }
                    else -> game.passPriority().error shouldBe null
                }
            }
            game.state.turnNumber shouldBe startTurn + 1
            game.state.projectedState.hasKeyword(ownPermanent, Keyword.HEXPROOF) shouldBe false
            game.state.projectedState.hasKeyword(ownPermanent, Keyword.INDESTRUCTIBLE) shouldBe false
        }
    }
}

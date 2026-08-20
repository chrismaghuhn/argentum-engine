package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/**
 * Ohran Frostfang (C19 #33).
 *
 * Oracle:
 * "Attacking creatures you control have deathtouch.
 * Whenever a creature you control deals combat damage to a player, draw a card."
 */
class OhranFrostfangScenarioTest : ScenarioTestBase() {

    init {
        test("grants deathtouch only to attacking creatures you control and draws for combat damage") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Ohran Frostfang", summoningSickness = false)
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withCardOnBattlefield(1, "Aegis Turtle", summoningSickness = false)
                .withCardInLibrary(1, "Forest")
                .withCardInLibrary(1, "Mountain")
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val frostfang = game.findPermanent("Ohran Frostfang")!!
            val bear = game.findPermanent("Grizzly Bears")!!
            val turtle = game.findPermanent("Aegis Turtle")!!

            game.state.projectedState.hasKeyword(frostfang, Keyword.DEATHTOUCH) shouldBe false
            game.state.projectedState.hasKeyword(bear, Keyword.DEATHTOUCH) shouldBe false
            game.state.projectedState.hasKeyword(turtle, Keyword.DEATHTOUCH) shouldBe false

            game.passUntilPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
            game.declareAttackers(
                mapOf("Ohran Frostfang" to 2, "Grizzly Bears" to 2),
            ).error shouldBe null

            game.state.projectedState.hasKeyword(frostfang, Keyword.DEATHTOUCH) shouldBe true
            game.state.projectedState.hasKeyword(bear, Keyword.DEATHTOUCH) shouldBe true
            game.state.projectedState.hasKeyword(turtle, Keyword.DEATHTOUCH) shouldBe false

            val handBeforeCombat = game.handSize(1)
            game.passUntilPhase(Phase.COMBAT, Step.END_COMBAT)

            game.handSize(1) shouldBe handBeforeCombat + 2
        }
    }
}

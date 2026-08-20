package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Reyav, Master Smith (CMR #290).
 *
 * Each attacking creature you control that is enchanted or equipped gets double strike until
 * end of turn. The trigger must bind to the matching attacking creature, not only to Reyav.
 */
class ReyavMasterSmithScenarioTest : ScenarioTestBase() {

    init {
        test("grants temporary double strike to each enchanted or equipped attacker you control") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Reyav, Master Smith", summoningSickness = false)
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withCardOnBattlefield(1, "Hill Giant", summoningSickness = false)
                .withCardOnBattlefield(2, "Voldaren Duelist", summoningSickness = false)
                .withCardAttachedTo(1, "Bonesplitter", "Reyav, Master Smith")
                .withCardAttachedTo(1, "Fireshrieker", "Grizzly Bears")
                .withCardAttachedTo(2, "Lightning Greaves", "Voldaren Duelist")
                .withActivePlayer(1)
                .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                .build()

            val reyav = game.findPermanent("Reyav, Master Smith")!!
            val equippedAttacker = game.findPermanent("Grizzly Bears")!!
            val unequippedAttacker = game.findPermanent("Hill Giant")!!
            val opponentCreature = game.findPermanent("Voldaren Duelist")!!

            game.declareAttackers(
                mapOf(
                    "Reyav, Master Smith" to 2,
                    "Grizzly Bears" to 2,
                    "Hill Giant" to 2,
                )
            ).error shouldBe null
            game.resolveStack()

            withClue("Reyav is an equipped attacker and receives double strike") {
                game.state.projectedState.hasKeyword(reyav, Keyword.DOUBLE_STRIKE) shouldBe true
            }
            withClue("another equipped attacker you control receives double strike") {
                game.state.projectedState.hasKeyword(equippedAttacker, Keyword.DOUBLE_STRIKE) shouldBe true
            }
            withClue("an attacking creature without an attachment does not receive it") {
                game.state.projectedState.hasKeyword(unequippedAttacker, Keyword.DOUBLE_STRIKE) shouldBe false
            }
            withClue("an opponent's equipped creature is outside the trigger domain") {
                game.state.projectedState.hasKeyword(opponentCreature, Keyword.DOUBLE_STRIKE) shouldBe false
            }
        }
    }
}

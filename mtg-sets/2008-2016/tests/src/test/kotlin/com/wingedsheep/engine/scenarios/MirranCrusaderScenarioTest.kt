package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Mirran Crusader (MBS #14): double strike and protection from black and green. */
class MirranCrusaderScenarioTest : ScenarioTestBase() {

    init {
        test("has double strike and protection from both printed colors") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardInHand(1, "Mirran Crusader")
                .withLandsOnBattlefield(1, "Plains", 3)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Mirran Crusader").error shouldBe null
            game.resolveStack()

            val crusader = game.findPermanent("Mirran Crusader")!!
            game.state.projectedState.getPower(crusader) shouldBe 2
            game.state.projectedState.getToughness(crusader) shouldBe 2
            game.state.projectedState.hasKeyword(crusader, Keyword.DOUBLE_STRIKE) shouldBe true
            game.state.projectedState.hasKeyword(crusader, "PROTECTION_FROM_BLACK") shouldBe true
            game.state.projectedState.hasKeyword(crusader, "PROTECTION_FROM_GREEN") shouldBe true
        }

        test("cannot be targeted by black or green spells") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardInHand(1, "Mirran Crusader")
                .withCardInHand(1, "Doom Blade")
                .withCardInHand(1, "Giant Growth")
                .withLandsOnBattlefield(1, "Plains", 3)
                .withLandsOnBattlefield(1, "Swamp", 1)
                .withLandsOnBattlefield(1, "Forest", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Mirran Crusader").error shouldBe null
            game.resolveStack()
            val crusader = game.findPermanent("Mirran Crusader")!!

            game.castSpell(1, "Doom Blade", crusader).error shouldNotBe null
            game.castSpell(1, "Giant Growth", crusader).error shouldNotBe null
        }
    }
}

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Kor Blademaster (ZNR #21).
 *
 * The source has double strike, and only equipped Warriors controlled by its controller receive
 * the static double-strike grant. Equipment, creature type, and controller are all part of the
 * affected-group boundary.
 */
class KorBlademasterScenarioTest : ScenarioTestBase() {

    init {
        test("grants double strike only to equipped Warriors you control") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Kor Blademaster")
                .withCardOnBattlefield(1, "Moriok Reaver")
                .withCardOnBattlefield(1, "Shatterskull Giant")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(2, "Voldaren Duelist")
                .withCardOnBattlefield(1, "Bonesplitter")
                .withCardAttachedTo(1, "Bonesplitter", "Kor Blademaster")
                .withCardOnBattlefield(1, "Fireshrieker")
                .withCardAttachedTo(1, "Fireshrieker", "Moriok Reaver")
                .withCardOnBattlefield(1, "Loxodon Warhammer")
                .withCardAttachedTo(1, "Loxodon Warhammer", "Grizzly Bears")
                .withCardOnBattlefield(2, "Lightning Greaves")
                .withCardAttachedTo(2, "Lightning Greaves", "Voldaren Duelist")
                .build()

            val projected = game.state.projectedState
            val blademaster = game.findPermanent("Kor Blademaster")!!
            val equippedWarrior = game.findPermanent("Moriok Reaver")!!
            val unequippedWarrior = game.findPermanent("Shatterskull Giant")!!
            val equippedNonWarrior = game.findPermanent("Grizzly Bears")!!
            val opponentWarrior = game.findPermanent("Voldaren Duelist")!!

            withClue("Kor Blademaster itself has printed double strike") {
                projected.hasKeyword(blademaster, Keyword.DOUBLE_STRIKE) shouldBe true
            }
            withClue("an equipped Warrior you control receives double strike") {
                projected.hasKeyword(equippedWarrior, Keyword.DOUBLE_STRIKE) shouldBe true
            }
            withClue("an un equipped Warrior does not receive the grant") {
                projected.hasKeyword(unequippedWarrior, Keyword.DOUBLE_STRIKE) shouldBe false
            }
            withClue("a non-Warrior creature does not receive the grant") {
                projected.hasKeyword(equippedNonWarrior, Keyword.DOUBLE_STRIKE) shouldBe false
            }
            withClue("an equipped Warrior controlled by an opponent does not receive the grant") {
                projected.hasKeyword(opponentWarrior, Keyword.DOUBLE_STRIKE) shouldBe false
            }
        }
    }
}

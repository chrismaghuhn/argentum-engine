package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Abrupt Decay (RTR #141).
 *
 * Oracle: "This spell can't be countered. Destroy target nonland permanent with mana value 3 or
 * less."
 */
class AbruptDecayScenarioTest : ScenarioTestBase() {

    init {
        test("destroys a nonland permanent with mana value three or less") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Abrupt Decay")
                .withLandsOnBattlefield(1, "Swamp", 1)
                .withLandsOnBattlefield(1, "Forest", 1)
                .withCardOnBattlefield(2, "Mind Stone")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val target = game.findPermanent("Mind Stone")!!
            game.castSpell(1, "Abrupt Decay", targetId = target).error shouldBe null
            game.resolveStack()

            game.findPermanent("Mind Stone") shouldBe null
            game.isInGraveyard(2, "Mind Stone") shouldBe true
        }

        test("rejects lands and permanents with mana value greater than three") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Abrupt Decay")
                .withCardInHand(1, "Abrupt Decay")
                .withLandsOnBattlefield(1, "Swamp", 2)
                .withLandsOnBattlefield(1, "Forest", 2)
                .withCardOnBattlefield(2, "Hill Giant")
                .withCardOnBattlefield(2, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val giant = game.findPermanent("Hill Giant")!!
            val land = game.findPermanent("Plains")!!
            game.castSpell(1, "Abrupt Decay", targetId = giant).error shouldNotBe null
            game.castSpell(1, "Abrupt Decay", targetId = land).error shouldNotBe null
            withClue("the invalid targets remain untouched") {
                game.findPermanent("Hill Giant") shouldNotBe null
                game.findPermanent("Forest") shouldNotBe null
            }
        }

        test("cannot be countered") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Abrupt Decay")
                .withLandsOnBattlefield(1, "Swamp", 1)
                .withLandsOnBattlefield(1, "Forest", 1)
                .withCardOnBattlefield(2, "Mind Stone")
                .withCardInHand(2, "Counterspell")
                .withLandsOnBattlefield(2, "Island", 2)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val target = game.findPermanent("Mind Stone")!!
            game.castSpell(1, "Abrupt Decay", targetId = target).error shouldBe null
            game.passPriority().error shouldBe null
            game.castSpellTargetingStackSpell(2, "Counterspell", "Abrupt Decay").error shouldBe null
            game.resolveStack()

            withClue("Counterspell fails to counter Abrupt Decay") {
                game.findPermanent("Mind Stone") shouldBe null
            }
            game.isInGraveyard(2, "Counterspell") shouldBe true
        }
    }
}

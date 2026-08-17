package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Ravenous Chupacabra (RIX #82).
 *
 * Oracle: "When this creature enters, destroy target creature an opponent controls."
 */
class RavenousChupacabraScenarioTest : ScenarioTestBase() {

    init {
        test("enters as a 2/2 and destroys a chosen opponent creature") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Ravenous Chupacabra")
                .withLandsOnBattlefield(1, "Swamp", 4)
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(2, "Hill Giant")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Ravenous Chupacabra").error shouldBe null
            game.resolveStack()

            val target = game.findPermanent("Hill Giant")!!
            withClue("the ETB target must be chosen explicitly") {
                game.selectTargets(listOf(target)).error shouldBe null
            }
            game.resolveStack()

            withClue("the Chupacabra remains on the battlefield") {
                game.findPermanent("Ravenous Chupacabra") shouldNotBe null
            }
            withClue("the chosen opposing creature is destroyed") {
                game.findPermanent("Hill Giant") shouldBe null
            }
            game.isInGraveyard(2, "Hill Giant") shouldBe true
            val chupacabra = game.findPermanent("Ravenous Chupacabra")!!
            game.state.projectedState.getPower(chupacabra) shouldBe 2
            game.state.projectedState.getToughness(chupacabra) shouldBe 2
        }

        test("cannot target a creature you control") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Ravenous Chupacabra")
                .withLandsOnBattlefield(1, "Swamp", 4)
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Ravenous Chupacabra").error shouldBe null
            game.resolveStack()

            withClue("an ETB with no legal opposing creature has no target decision") {
                game.hasPendingDecision() shouldBe false
            }
            game.findPermanent("Grizzly Bears") shouldNotBe null
        }
    }
}

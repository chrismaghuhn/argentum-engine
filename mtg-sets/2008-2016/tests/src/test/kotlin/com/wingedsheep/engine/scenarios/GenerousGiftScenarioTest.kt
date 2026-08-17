package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Generous Gift (MH1 #11): destroy target permanent; its controller creates a 3/3 Elephant. */
class GenerousGiftScenarioTest : ScenarioTestBase() {

    init {
        test("destroys an opponent permanent and gives that controller a 3/3 Elephant") {
            val game = scenario()
                .withPlayers("Player1", "Player2")
                .withCardInHand(1, "Generous Gift")
                .withLandsOnBattlefield(1, "Plains", 3)
                .withCardOnBattlefield(2, "Grizzly Bears", summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val target = game.findPermanent("Grizzly Bears")!!
            val result = game.castSpell(1, "Generous Gift", targetId = target)
            withClue("casting Generous Gift should succeed: ${result.error}") {
                result.error shouldBe null
            }
            game.resolveStack()

            game.findPermanent("Grizzly Bears") shouldBe null
            game.findCardsInGraveyard(2, "Grizzly Bears").size shouldBe 1
            val elephant = game.findPermanent("Elephant Token")!!
            game.findPermanents("Elephant Token").size shouldBe 1
            controllerOf(game, elephant) shouldBe game.player2Id
            game.state.projectedState.getPower(elephant) shouldBe 3
            game.state.projectedState.getToughness(elephant) shouldBe 3
        }

        test("can target a noncreature permanent") {
            val game = scenario()
                .withPlayers("Player1", "Player2")
                .withCardInHand(1, "Generous Gift")
                .withLandsOnBattlefield(1, "Plains", 3)
                .withCardOnBattlefield(2, "Test Enchantment")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val target = game.findPermanent("Test Enchantment")!!
            game.castSpell(1, "Generous Gift", targetId = target).error shouldBe null
            game.resolveStack()

            game.findPermanent("Test Enchantment") shouldBe null
            game.findPermanent("Elephant Token") shouldNotBe null
        }
    }

    private fun controllerOf(game: TestGame, id: com.wingedsheep.sdk.model.EntityId) =
        game.state.getEntity(id)?.get<ControllerComponent>()?.playerId
}

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Read the Bones (THS #101): scry 2, draw two cards, then lose 2 life. */
class ReadTheBonesScenarioTest : ScenarioTestBase() {

    init {
        test("scry choice resolves before drawing two cards and losing life") {
            val game = buildGame()

            game.castSpell(1, "Read the Bones").error shouldBe null
            game.resolveStack()

            val scry = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            scry.options.map { game.cardName(it) } shouldBe listOf("Mountain", "Forest")
            game.handSize(1) shouldBe 0
            game.librarySize(1) shouldBe 5
            game.getLifeTotal(1) shouldBe 20

            val mountain = scry.options.single { game.cardName(it) == "Mountain" }
            game.selectCards(listOf(mountain)).error shouldBe null
            game.resolveStack()

            val reorder = game.getPendingDecision().shouldBeInstanceOf<ReorderLibraryDecision>()
            reorder.cards.map { game.cardName(it) } shouldBe listOf("Forest")
            game.submitDecision(OrderedResponse(reorder.id, reorder.cards)).error shouldBe null
            game.resolveStack()

            game.handSize(1) shouldBe 2
            game.isInHand(1, "Forest") shouldBe true
            game.isInHand(1, "Mountain") shouldBe false
            game.librarySize(1) shouldBe 3
            game.cardNameAtBottom(1) shouldBe "Mountain"
            game.getLifeTotal(1) shouldBe 18
        }

        test("kept scry cards can be reordered before the draw") {
            val game = buildGame()

            game.castSpell(1, "Read the Bones").error shouldBe null
            game.resolveStack()

            game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.skipSelection().error shouldBe null
            game.resolveStack()

            val reorder = game.getPendingDecision().shouldBeInstanceOf<ReorderLibraryDecision>()
            reorder.cards.map { game.cardName(it) } shouldBe listOf("Mountain", "Forest")
            game.handSize(1) shouldBe 0
            game.librarySize(1) shouldBe 5
            game.getLifeTotal(1) shouldBe 20

            game.submitDecision(OrderedResponse(reorder.id, reorder.cards.reversed())).error shouldBe null
            game.resolveStack()

            game.handNames(1) shouldBe listOf("Forest", "Mountain")
            game.librarySize(1) shouldBe 3
            game.getLifeTotal(1) shouldBe 18
        }
    }

    private fun buildGame() = scenario()
        .withPlayers("Caster", "Opponent")
        .withCardInHand(1, "Read the Bones")
        // Library order is top to bottom: Mountain, Forest, then three Islands.
        .withCardInLibrary(1, "Mountain")
        .withCardInLibrary(1, "Forest")
        .withCardInLibrary(1, "Island")
        .withCardInLibrary(1, "Island")
        .withCardInLibrary(1, "Island")
        .withLandsOnBattlefield(1, "Swamp", 3)
        .withLifeTotal(1, 20)
        .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
        .build()

    private fun TestGame.cardName(entityId: EntityId): String =
        state.getEntity(entityId)?.get<CardComponent>()?.name
            ?: error("No card component for $entityId")

    private fun TestGame.cardNameAtBottom(playerNumber: Int): String {
        val playerId = if (playerNumber == 1) player1Id else player2Id
        return cardName(state.getLibrary(playerId).last())
    }

    private fun TestGame.handNames(playerNumber: Int): List<String> {
        val playerId = if (playerNumber == 1) player1Id else player2Id
        return state.getHand(playerId).map { cardName(it) }
    }
}

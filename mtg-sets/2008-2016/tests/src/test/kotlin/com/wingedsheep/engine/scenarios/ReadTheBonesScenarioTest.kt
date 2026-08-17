package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Read the Bones (THS #101): scry 2, draw two cards, then lose 2 life. */
class ReadTheBonesScenarioTest : ScenarioTestBase() {

    init {
        test("makes the scry choice before drawing two cards and losing life") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardInHand(1, "Read the Bones")
                .withCardInLibrary(1, "Forest")
                .withCardInLibrary(1, "Mountain")
                .withCardInLibrary(1, "Island")
                .withCardInLibrary(1, "Island")
                .withCardInLibrary(1, "Island")
                .withLandsOnBattlefield(1, "Swamp", 3)
                .withLifeTotal(1, 20)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val handBefore = game.handSize(1)
            game.castSpell(1, "Read the Bones").error shouldBe null
            game.resolveStack()

            val select = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.handSize(1) shouldBe handBefore - 1
            game.selectCards(listOf(select.options.first())).error shouldBe null
            game.resolveStack()

            val reorder = game.getPendingDecision().shouldBeInstanceOf<ReorderLibraryDecision>()
            game.submitDecision(OrderedResponse(reorder.id, reorder.cards)).error shouldBe null
            game.resolveStack()

            game.handSize(1) shouldBe handBefore + 1
            game.librarySize(1) shouldBe 3
            game.getLifeTotal(1) shouldBe 18
        }
    }
}

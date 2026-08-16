package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Reclamation Sage (M15 #194): optional ETB destruction of an artifact or enchantment. */
class ReclamationSageScenarioTest : ScenarioTestBase() {

    init {
        test("accepting its ETB may destroys an artifact, but not an ordinary creature") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Reclamation Sage")
                .withLandsOnBattlefield(1, "Forest", 3)
                .withCardOnBattlefield(2, "Mind Stone")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .build()

            game.castSpell(1, "Reclamation Sage").error shouldBe null
            game.resolveStack()

            game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(true).error shouldBe null
            val targetDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            val stone = game.findPermanent("Mind Stone")!!
            val bears = game.findPermanent("Grizzly Bears")!!
            targetDecision.legalTargets[0].orEmpty() shouldContain stone
            targetDecision.legalTargets[0].orEmpty() shouldNotContain bears

            game.submitDecision(TargetsResponse(targetDecision.id, mapOf(0 to listOf(stone)))).error shouldBe null
            game.resolveStack()

            game.isOnBattlefield("Reclamation Sage") shouldBe true
            game.isOnBattlefield("Mind Stone") shouldBe false
            game.isOnBattlefield("Grizzly Bears") shouldBe true
        }

        test("declining its optional ETB leaves the artifact untouched") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Reclamation Sage")
                .withLandsOnBattlefield(1, "Forest", 3)
                .withCardOnBattlefield(2, "Mind Stone")
                .build()

            game.castSpell(1, "Reclamation Sage").error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(false).error shouldBe null
            game.resolveStack()

            game.isOnBattlefield("Reclamation Sage") shouldBe true
            game.isOnBattlefield("Mind Stone") shouldBe true
        }
    }
}

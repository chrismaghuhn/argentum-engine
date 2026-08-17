package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Acidic Slime (M10 #165): deathtouch and ETB destruction of an artifact, enchantment, or land. */
class AcidicSlimeScenarioTest : ScenarioTestBase() {

    init {
        test("enters with deathtouch and can destroy an artifact, enchantment, or land") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Acidic Slime")
                .withLandsOnBattlefield(1, "Forest", 5)
                .withCardOnBattlefield(2, "Mind Stone")
                .withCardOnBattlefield(2, "Test Enchantment")
                .withCardOnBattlefield(2, "Mountain")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .build()

            game.castSpell(1, "Acidic Slime").error shouldBe null
            game.resolveStack()

            val decision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            val stone = game.findPermanent("Mind Stone")!!
            val enchantment = game.findPermanent("Test Enchantment")!!
            val land = game.findPermanent("Mountain")!!
            val bear = game.findPermanent("Grizzly Bears")!!
            decision.legalTargets[0].orEmpty() shouldContain stone
            decision.legalTargets[0].orEmpty() shouldContain enchantment
            decision.legalTargets[0].orEmpty() shouldContain land
            decision.legalTargets[0].orEmpty() shouldNotContain bear

            game.submitDecision(TargetsResponse(decision.id, mapOf(0 to listOf(stone)))).error shouldBe null
            game.resolveStack()

            game.isOnBattlefield("Mind Stone") shouldBe false
            val slime = game.findPermanent("Acidic Slime")!!
            game.state.projectedState.hasKeyword(slime, Keyword.DEATHTOUCH) shouldBe true
        }
    }
}

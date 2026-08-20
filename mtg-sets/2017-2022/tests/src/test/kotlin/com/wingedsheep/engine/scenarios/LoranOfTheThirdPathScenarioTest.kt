package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Loran of the Third Path (BRO #12).
 *
 * Oracle:
 * "Vigilance
 * When Loran enters, destroy up to one target artifact or enchantment.
 * {T}: You and target opponent each draw a card."
 */
class LoranOfTheThirdPathScenarioTest : ScenarioTestBase() {

    private fun abilityId(): AbilityId =
        cardRegistry.requireCard("Loran of the Third Path").activatedAbilities.single().id

    init {
        test("has vigilance and destroys a chosen artifact on entry") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Loran of the Third Path")
                .withLandsOnBattlefield(1, "Plains", 3)
                .withCardOnBattlefield(2, "Mind Stone")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Loran of the Third Path").error shouldBe null
            game.resolveStack()

            val mindStone = game.findPermanent("Mind Stone")!!
            game.selectTargets(listOf(mindStone)).error shouldBe null
            game.resolveStack()

            val loran = game.findPermanent("Loran of the Third Path")!!
            game.state.projectedState.getPower(loran) shouldBe 2
            game.state.projectedState.getToughness(loran) shouldBe 1
            game.state.projectedState.hasKeyword(loran, Keyword.VIGILANCE) shouldBe true
            withClue("the chosen artifact is destroyed") {
                game.findPermanent("Mind Stone") shouldBe null
            }
        }

        test("may destroy no permanent and does not treat lands or creatures as legal targets") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Loran of the Third Path")
                .withLandsOnBattlefield(1, "Plains", 3)
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withCardOnBattlefield(2, "Mountain")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Loran of the Third Path").error shouldBe null
            game.resolveStack()
            game.skipTargets().error shouldBe null
            game.resolveStack()

            game.findPermanent("Loran of the Third Path") shouldNotBe null
            game.findPermanent("Grizzly Bears") shouldNotBe null
            game.findPermanent("Mountain") shouldNotBe null
        }

        test("tapping Loran makes you and a chosen opponent each draw") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Loran of the Third Path")
                .withCardInLibrary(1, "Plains")
                .withCardInLibrary(2, "Mountain")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val loran = game.findPermanent("Loran of the Third Path")!!
            val youBefore = game.handSize(1)
            val opponentBefore = game.handSize(2)
            val result = game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = loran,
                    abilityId = abilityId(),
                    targets = listOf(ChosenTarget.Player(game.player2Id)),
                ),
            )
            result.error shouldBe null
            game.resolveStack()

            game.handSize(1) shouldBe youBefore + 1
            game.handSize(2) shouldBe opponentBefore + 1
            game.state.getEntity(loran) shouldNotBe null
        }
    }
}

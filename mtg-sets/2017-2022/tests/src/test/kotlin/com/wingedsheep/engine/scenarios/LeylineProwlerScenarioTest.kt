package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Leyline Prowler (C21 #222): deathtouch, lifelink, and "{T}: Add one mana of any color."
 */
class LeylineProwlerScenarioTest : ScenarioTestBase() {

    init {
        test("has its printed stats and both keyword abilities") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Leyline Prowler", summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val prowler = game.findPermanent("Leyline Prowler")!!
            game.state.projectedState.getPower(prowler) shouldBe 2
            game.state.projectedState.getToughness(prowler) shouldBe 3
            game.state.projectedState.hasKeyword(prowler, Keyword.DEATHTOUCH) shouldBe true
            game.state.projectedState.hasKeyword(prowler, Keyword.LIFELINK) shouldBe true
        }

        test("the mana ability can choose a colored mana") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Leyline Prowler", summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val prowler = game.findPermanent("Leyline Prowler")!!
            val ability = cardRegistry.getCard("Leyline Prowler")!!.script.activatedAbilities.single()
            val result = game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = prowler,
                    abilityId = ability.id,
                    manaColorChoice = Color.RED,
                ),
            )

            withClue("choosing red should activate the any-color mana ability: ${result.error}") {
                result.error shouldBe null
            }
            val pool = game.state.getEntity(game.player1Id)?.get<ManaPoolComponent>()
            pool?.red shouldBe 1
        }

        test("the same mana ability also permits a different color") {
            val game = scenario()
                .withPlayers("P1", "P2")
                .withCardOnBattlefield(1, "Leyline Prowler", summoningSickness = false)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val prowler = game.findPermanent("Leyline Prowler")!!
            val ability = cardRegistry.getCard("Leyline Prowler")!!.script.activatedAbilities.single()
            val result = game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = prowler,
                    abilityId = ability.id,
                    manaColorChoice = Color.BLUE,
                ),
            )

            result.error shouldBe null
            val pool = game.state.getEntity(game.player1Id)?.get<ManaPoolComponent>()
            pool?.blue shouldBe 1
        }
    }
}

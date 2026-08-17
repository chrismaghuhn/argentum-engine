package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Scenario tests for Inspiring Vantage (KLD #246).
 *
 * This land enters tapped unless you control two or fewer other lands and has
 * the usual red/white mana abilities.  The threshold is deliberately tested
 * against the entering land itself: the source must not count as an "other"
 * land while the replacement effect is evaluated.
 */
class InspiringVantageScenarioTest : ScenarioTestBase() {

    init {
        context("Inspiring Vantage enters-tapped condition") {

            test("enters untapped with two other lands — the boundary case") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardInHand(1, "Inspiring Vantage")
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withLandsOnBattlefield(1, "Mountain", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.execute(
                    PlayLand(
                        game.player1Id,
                        game.findCardsInHand(1, "Inspiring Vantage").single()
                    )
                ).error shouldBe null

                val vantage = game.findPermanent("Inspiring Vantage")!!
                withClue("Two other lands is 'two or fewer', so it enters untapped") {
                    game.state.getEntity(vantage)?.has<TappedComponent>() shouldBe false
                }
            }

            test("enters tapped with three other lands") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardInHand(1, "Inspiring Vantage")
                    .withLandsOnBattlefield(1, "Plains", 2)
                    .withLandsOnBattlefield(1, "Mountain", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.execute(
                    PlayLand(
                        game.player1Id,
                        game.findCardsInHand(1, "Inspiring Vantage").single()
                    )
                ).error shouldBe null

                val vantage = game.findPermanent("Inspiring Vantage")!!
                withClue("Three other lands is more than 'two or fewer', so it enters tapped") {
                    game.state.getEntity(vantage)?.has<TappedComponent>() shouldBe true
                }
            }

            test("the entering land is not counted against itself") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardInHand(1, "Inspiring Vantage")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.execute(
                    PlayLand(
                        game.player1Id,
                        game.findCardsInHand(1, "Inspiring Vantage").single()
                    )
                ).error shouldBe null

                val vantage = game.findPermanent("Inspiring Vantage")!!
                withClue("No other lands at all — it enters untapped") {
                    game.state.getEntity(vantage)?.has<TappedComponent>() shouldBe false
                }
            }

            test("an untapped Vantage exposes both legal mana abilities") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardInHand(1, "Inspiring Vantage")
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.execute(
                    PlayLand(
                        game.player1Id,
                        game.findCardsInHand(1, "Inspiring Vantage").single()
                    )
                ).error shouldBe null

                val vantage = game.findPermanent("Inspiring Vantage")!!
                val manaActions = game.getLegalActions(1).filter { legal ->
                    val action = legal.action as? ActivateAbility
                    action?.sourceId == vantage &&
                        (legal.description.contains("Add {R}") || legal.description.contains("Add {W}"))
                }

                withClue("Vantage should expose red and white mana abilities") {
                    manaActions.size shouldBe 2
                }
                manaActions.forEach { it.isAffordable shouldBe true }
            }
        }
    }
}

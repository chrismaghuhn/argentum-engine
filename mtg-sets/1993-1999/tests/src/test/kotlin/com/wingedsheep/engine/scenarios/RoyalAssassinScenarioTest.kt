package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Royal Assassin (LEA #123) — {1}{B}{B} 1/1 Human Assassin.
 *
 * "{T}: Destroy target tapped creature."
 */
class RoyalAssassinScenarioTest : ScenarioTestBase() {

    private val abilityId = cardRegistry.getCard("Royal Assassin")!!.activatedAbilities.single().id

    init {
        context("Royal Assassin") {

            test("destroys a target tapped creature") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardOnBattlefield(1, "Royal Assassin")
                    .withCardOnBattlefield(2, "Grizzly Bears", tapped = true, summoningSickness = false)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                val assassin = game.findPermanent("Royal Assassin")!!
                val bears = game.findPermanent("Grizzly Bears")!!
                val activation = game.execute(
                    ActivateAbility(
                        playerId = game.player1Id,
                        sourceId = assassin,
                        abilityId = abilityId,
                        targets = listOf(ChosenTarget.Permanent(bears)),
                    )
                )

                withClue("Royal Assassin can activate against a tapped creature: ${activation.error}") {
                    activation.error shouldBe null
                }
                game.resolveStack()

                withClue("the targeted tapped creature is destroyed") {
                    game.findPermanent("Grizzly Bears") shouldBe null
                    game.findCardsInGraveyard(2, "Grizzly Bears").size shouldBe 1
                }
            }

            test("cannot target an untapped creature") {
                val game = scenario()
                    .withPlayers("Player1", "Player2")
                    .withCardOnBattlefield(1, "Royal Assassin")
                    .withCardOnBattlefield(2, "Grizzly Bears", tapped = false, summoningSickness = false)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                val assassin = game.findPermanent("Royal Assassin")!!
                val bears = game.findPermanent("Grizzly Bears")!!
                val activation = game.execute(
                    ActivateAbility(
                        playerId = game.player1Id,
                        sourceId = assassin,
                        abilityId = abilityId,
                        targets = listOf(ChosenTarget.Permanent(bears)),
                    )
                )

                withClue("an untapped creature is not a legal target") {
                    activation.error shouldNotBe null
                }
                game.findPermanent("Grizzly Bears") shouldNotBe null
            }
        }
    }
}

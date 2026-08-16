package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.mechanics.layers.ActiveFloatingEffect
import com.wingedsheep.engine.mechanics.layers.FloatingEffectData
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.Duration
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Putrefy (RAV #221) — {1}{B}{G} Instant.
 *
 * "Destroy target artifact or creature. It can't be regenerated."
 */
class PutrefyScenarioTest : ScenarioTestBase() {

    private fun game(targetName: String) = scenario()
        .withPlayers("Player1", "Player2")
        .withCardInHand(1, "Putrefy")
        .withLandsOnBattlefield(1, "Swamp", 2)
        .withLandsOnBattlefield(1, "Forest", 1)
        .withCardOnBattlefield(2, targetName, summoningSickness = false)
        .withActivePlayer(1)
        .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
        .build()

    init {
        context("Putrefy") {

            test("destroys a target artifact") {
                val game = game("Welding Jar")
                val target = game.findPermanent("Welding Jar")!!

                val result = game.castSpell(1, "Putrefy", targetId = target)
                withClue("Putrefy can target an artifact: ${result.error}") {
                    result.error shouldBe null
                }
                game.resolveStack()

                game.findPermanent("Welding Jar") shouldBe null
                game.findCardsInGraveyard(2, "Welding Jar").size shouldBe 1
            }

            test("destroys a target creature") {
                val game = game("Grizzly Bears")
                val target = game.findPermanent("Grizzly Bears")!!

                val result = game.castSpell(1, "Putrefy", targetId = target)
                withClue("Putrefy can target a creature: ${result.error}") {
                    result.error shouldBe null
                }
                game.resolveStack()

                game.findPermanent("Grizzly Bears") shouldBe null
                game.findCardsInGraveyard(2, "Grizzly Bears").size shouldBe 1
            }

            test("cannot target a non-artifact, noncreature permanent") {
                val game = game("Test Enchantment")
                val target = game.findPermanent("Test Enchantment")!!

                val result = game.castSpell(1, "Putrefy", targetId = target)
                withClue("an enchantment without another qualifying type is not a legal target") {
                    result.error shouldNotBe null
                }
                game.findPermanent("Test Enchantment") shouldNotBe null
            }

            test("destroys a creature despite a regeneration shield") {
                val game = game("Grizzly Bears")
                val target = game.findPermanent("Grizzly Bears")!!
                game.state = game.state.copy(
                    floatingEffects = game.state.floatingEffects + regenerationShield(target, game.player2Id)
                )

                val result = game.castSpell(1, "Putrefy", targetId = target)
                withClue("Putrefy should cast against a shielded creature: ${result.error}") {
                    result.error shouldBe null
                }
                game.resolveStack()

                withClue("Putrefy's \"can't be regenerated\" clause ignores the shield") {
                    game.findPermanent("Grizzly Bears") shouldBe null
                    game.isInGraveyard(2, "Grizzly Bears") shouldBe true
                }
            }
        }
    }
}

private fun regenerationShield(entityId: EntityId, controllerId: EntityId) = ActiveFloatingEffect(
    id = EntityId.generate(),
    effect = FloatingEffectData(
        layer = Layer.ABILITY,
        modification = SerializableModification.RegenerationShield,
        affectedEntities = setOf(entityId)
    ),
    duration = Duration.EndOfTurn,
    sourceId = null,
    controllerId = controllerId,
    timestamp = 1L
)

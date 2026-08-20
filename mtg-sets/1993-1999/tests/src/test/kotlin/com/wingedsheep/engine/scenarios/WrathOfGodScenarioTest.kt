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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Wrath of God (LEA #45): destroy all creatures; they can't be regenerated. */
class WrathOfGodScenarioTest : ScenarioTestBase() {

    private fun game() = scenario()
        .withPlayers("Player1", "Player2")
        .withCardInHand(1, "Wrath of God")
        .withLandsOnBattlefield(1, "Plains", 4)
        .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
        .withCardOnBattlefield(2, "Centaur Courser", summoningSickness = false)
        .withCardOnBattlefield(2, "Test Enchantment")
        .withActivePlayer(1)
        .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
        .build()

    init {
        test("destroys every creature but leaves noncreature permanents") {
            val game = game()
            val result = game.castSpell(1, "Wrath of God")

            withClue("Wrath of God should cast: ${result.error}") {
                result.error shouldBe null
            }
            game.resolveStack()

            game.findPermanent("Grizzly Bears") shouldBe null
            game.findPermanent("Centaur Courser") shouldBe null
            game.isInGraveyard(1, "Grizzly Bears") shouldBe true
            game.isInGraveyard(2, "Centaur Courser") shouldBe true
            game.findPermanent("Test Enchantment") shouldNotBe null
        }

        test("ignores regeneration shields") {
            val game = game()
            val target = game.findPermanent("Grizzly Bears")!!
            game.state = game.state.copy(
                floatingEffects = game.state.floatingEffects + regenerationShield(target, game.player1Id)
            )

            val result = game.castSpell(1, "Wrath of God")
            withClue("Wrath of God should cast against a shielded creature: ${result.error}") {
                result.error shouldBe null
            }
            game.resolveStack()

            game.findPermanent("Grizzly Bears") shouldBe null
            game.isInGraveyard(1, "Grizzly Bears") shouldBe true
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

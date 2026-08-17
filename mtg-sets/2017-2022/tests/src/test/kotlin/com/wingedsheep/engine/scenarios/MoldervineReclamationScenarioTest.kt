package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import io.kotest.matchers.shouldBe

/**
 * Moldervine Reclamation (M20 #214).
 *
 * Oracle: "Whenever a creature you control dies, you gain 1 life and draw a card."
 */
class MoldervineReclamationScenarioTest : ScenarioTestBase() {

    init {
        val slay = card("Slay") {
            manaCost = "{0}"
            typeLine = "Sorcery"
            spell {
                val creature = target("target creature", Targets.Creature)
                effect = Effects.Destroy(creature)
            }
        }
        cardRegistry.register(slay)

        test("a creature you control dying gains 1 life and draws a card") {
            val game = scenario()
                .withPlayers()
                .withLifeTotal(1, 20)
                .withCardOnBattlefield(1, "Moldervine Reclamation")
                .withCardOnBattlefield(1, "Aegis Turtle")
                .withCardInHand(1, "Slay")
                .withCardInLibrary(1, "Bear Cub")
                .build()

            val turtle = game.findPermanent("Aegis Turtle")!!
            game.castSpell(1, "Slay", targetId = turtle).error shouldBe null
            game.resolveStack()

            game.getLifeTotal(1) shouldBe 21
            game.isInHand(1, "Bear Cub") shouldBe true
        }

        test("token creatures you control also trigger, but opponent creatures do not") {
            val game = scenario()
                .withPlayers()
                .withLifeTotal(1, 20)
                .withCardOnBattlefield(1, "Moldervine Reclamation")
                .withCardOnBattlefield(1, "Doomed Dissenter")
                .withCardOnBattlefield(2, "Aegis Turtle")
                .withCardInHand(1, "Slay")
                .withCardInHand(1, "Slay")
                .withCardInLibrary(1, "Bear Cub")
                .withCardInLibrary(1, "Grizzly Bears")
                .build()

            val dissenter = game.findPermanent("Doomed Dissenter")!!
            game.castSpell(1, "Slay", targetId = dissenter).error shouldBe null
            game.resolveStack()

            val zombie = game.findPermanent("Zombie Token")!!
            game.castSpell(1, "Slay", targetId = zombie).error shouldBe null
            game.resolveStack()

            game.getLifeTotal(1) shouldBe 22
            game.isInHand(1, "Bear Cub") shouldBe true
            game.isInHand(1, "Grizzly Bears") shouldBe true
        }

        test("a creature an opponent controls dying does not trigger") {
            val game = scenario()
                .withPlayers()
                .withLifeTotal(1, 20)
                .withCardOnBattlefield(1, "Moldervine Reclamation")
                .withCardOnBattlefield(2, "Aegis Turtle")
                .withCardInHand(1, "Slay")
                .withCardInLibrary(1, "Bear Cub")
                .build()

            val opponentCreature = game.state.getBattlefield(EntityId.of("player-2")).single()
            game.castSpell(1, "Slay", targetId = opponentCreature).error shouldBe null
            game.resolveStack()

            game.getLifeTotal(1) shouldBe 20
            game.isInHand(1, "Bear Cub") shouldBe false
        }
    }
}

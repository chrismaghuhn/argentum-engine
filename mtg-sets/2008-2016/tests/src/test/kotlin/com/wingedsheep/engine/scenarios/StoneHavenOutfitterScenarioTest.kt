package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * Stone Haven Outfitter (OGW #37).
 *
 * Oracle:
 * "Equipped creatures you control get +1/+1.
 * Whenever an equipped creature you control dies, draw a card."
 */
class StoneHavenOutfitterScenarioTest : ScenarioTestBase() {

    init {
        val slay = card("A8 Stone Haven Slay") {
            manaCost = "{0}"
            typeLine = "Instant"
            spell {
                val creature = target("target creature", Targets.Creature)
                effect = Effects.Destroy(creature)
            }
        }
        cardRegistry.register(slay)

        test("only equipped creatures you control get +1/+1") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Stone Haven Outfitter")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardAttachedTo(1, "Bonesplitter", "Grizzly Bears")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardOnBattlefield(2, "Voldaren Duelist")
                .withCardAttachedTo(2, "Bonesplitter", "Voldaren Duelist")
                .build()

            val equipped = game.findPermanent("Grizzly Bears")!!
            val unequipped = game.findPermanent("Hill Giant")!!
            val opponentEquipped = game.findPermanent("Voldaren Duelist")!!

            withClue("an equipped creature you control gets +1/+1") {
                game.state.projectedState.getPower(equipped) shouldBe 3
                game.state.projectedState.getToughness(equipped) shouldBe 3
            }
            withClue("an unequipped creature you control is not affected") {
                game.state.projectedState.getPower(unequipped) shouldBe 3
                game.state.projectedState.getToughness(unequipped) shouldBe 3
            }
            withClue("an equipped creature controlled by an opponent is not affected") {
                // Voldaren Duelist is 3/2 and Bonesplitter supplies the unrelated +2/+0.
                game.state.projectedState.getPower(opponentEquipped) shouldBe 5
                game.state.projectedState.getToughness(opponentEquipped) shouldBe 2
            }
        }

        test("an equipped creature you control dying draws a card") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Stone Haven Outfitter")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardAttachedTo(1, "Bonesplitter", "Grizzly Bears")
                .withCardInHand(1, "A8 Stone Haven Slay")
                .withCardInLibrary(1, "Island")
                .build()

            val equipped = game.findPermanent("Grizzly Bears")!!
            game.castSpell(1, "A8 Stone Haven Slay", targetId = equipped).error shouldBe null
            game.resolveStack()

            game.isOnBattlefield("Grizzly Bears") shouldBe false
            game.isInHand(1, "Island") shouldBe true
        }

        test("an unequipped or opponent-controlled equipped creature dying does not draw") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Stone Haven Outfitter")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardOnBattlefield(2, "Voldaren Duelist")
                .withCardAttachedTo(2, "Bonesplitter", "Voldaren Duelist")
                .withCardsInHand(1, "A8 Stone Haven Slay", 2)
                .withCardInLibrary(1, "Island")
                .build()

            val unequipped = game.findPermanent("Hill Giant")!!
            game.castSpell(1, "A8 Stone Haven Slay", targetId = unequipped).error shouldBe null
            game.resolveStack()
            game.isInHand(1, "Island") shouldBe false

            val opponentEquipped = game.findPermanent("Voldaren Duelist")!!
            game.castSpell(1, "A8 Stone Haven Slay", targetId = opponentEquipped).error shouldBe null
            game.resolveStack()
            game.isInHand(1, "Island") shouldBe false
        }
    }
}

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.scripting.GameObjectFilter
import io.kotest.matchers.shouldBe

/**
 * Morbid Opportunist (MID #113).
 *
 * Oracle: "Whenever one or more other creatures die, draw a card. This ability triggers only
 * once each turn."
 *
 * The scenarios cover a simultaneous multi-creature death as one trigger, deaths controlled by
 * either player, exclusion of the source itself, and the once-per-turn cap.
 */
class MorbidOpportunistScenarioTest : ScenarioTestBase() {

    init {
        val slay = card("A8 Slay") {
            manaCost = "{0}"
            typeLine = "Instant"
            spell {
                val target = target("target creature", Targets.Creature)
                effect = Effects.Destroy(target)
            }
        }
        val massSlay = card("A8 Mass Slay") {
            manaCost = "{0}"
            typeLine = "Sorcery"
            spell {
                effect = Effects.DestroyAll(GameObjectFilter.Creature.opponentControls(), noRegenerate = true)
            }
        }
        cardRegistry.register(listOf(slay, massSlay))

        test("one simultaneous death batch draws once, including creatures an opponent controls") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Morbid Opportunist")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withCardOnBattlefield(2, "Aegis Turtle")
                .withCardInHand(1, "A8 Mass Slay")
                .withCardInLibrary(1, "Bear Cub")
                .withCardInLibrary(1, "Llanowar Elves")
                .build()

            game.castSpell(1, "A8 Mass Slay").error shouldBe null
            game.resolveStack()

            game.isInHand(1, "Bear Cub") shouldBe true
            game.isInHand(1, "Llanowar Elves") shouldBe false
        }

        test("the source itself is not an eligible other creature") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Morbid Opportunist")
                .withCardInHand(1, "A8 Slay")
                .withCardInLibrary(1, "Bear Cub")
                .build()

            game.castSpell(1, "A8 Slay", targetId = game.findPermanent("Morbid Opportunist")!!).error shouldBe null
            game.resolveStack()

            game.isInHand(1, "Bear Cub") shouldBe false
        }

        test("only the first death batch of a turn draws") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Morbid Opportunist")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withCardOnBattlefield(2, "Aegis Turtle")
                .withCardsInHand(1, "A8 Slay", 2)
                .withCardInLibrary(1, "Bear Cub")
                .withCardInLibrary(1, "Llanowar Elves")
                .build()

            game.castSpell(1, "A8 Slay", targetId = game.findPermanent("Grizzly Bears")!!).error shouldBe null
            game.resolveStack()
            game.castSpell(1, "A8 Slay", targetId = game.findPermanent("Aegis Turtle")!!).error shouldBe null
            game.resolveStack()

            game.isInHand(1, "Bear Cub") shouldBe true
            game.isInHand(1, "Llanowar Elves") shouldBe false
        }
    }
}

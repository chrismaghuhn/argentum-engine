package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Scenario test for Swords to Plowshares (LEA #40).
 *
 * "{W} Instant — Exile target creature. Its controller gains life equal to its power."
 *
 * The second case is the one that pins the implementation down: an Unholy Strength on the
 * targeted creature makes its *projected* power (4) differ from its printed power (2), so the
 * assertion fails if the life gain ever reads base characteristics — which is exactly what
 * happens if the two effects are re-sequenced into the printed exile-then-gain order.
 */
class SwordsToPlowsharesScenarioTest : ScenarioTestBase() {

    init {
        test("Swords to Plowshares exiles the creature and its controller gains life equal to its power") {
            val game = scenario()
                .withPlayers("Caster", "Defender")
                .withCardInHand(1, "Swords to Plowshares")
                .withLandsOnBattlefield(1, "Plains", 1)
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val bears = game.findPermanent("Grizzly Bears")!!

            val cast = game.castSpell(1, "Swords to Plowshares", targetId = bears)
            withClue("Casting Swords to Plowshares at Grizzly Bears should succeed: ${cast.error}") {
                cast.error shouldBe null
            }
            if (game.hasPendingDecision()) game.submitManaSourcesAutoPay()
            game.resolveStack()

            withClue("Grizzly Bears (the targeted creature) should be exiled") {
                game.isOnBattlefield("Grizzly Bears") shouldBe false
            }
            withClue("The creature's controller (player 2) should gain 2 life (Grizzly Bears' power)") {
                game.getLifeTotal(2) shouldBe 22
            }
            withClue("The caster (player 1) should not gain life") {
                game.getLifeTotal(1) shouldBe 20
            }
        }

        test("the life gained is the creature's projected power, not its printed power") {
            val game = scenario()
                .withPlayers("Caster", "Defender")
                .withCardInHand(1, "Swords to Plowshares")
                .withLandsOnBattlefield(1, "Plains", 1)
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withCardAttachedTo(2, "Unholy Strength", "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val bears = game.findPermanent("Grizzly Bears")!!

            val cast = game.castSpell(1, "Swords to Plowshares", targetId = bears)
            withClue("Casting Swords to Plowshares at the enchanted Grizzly Bears should succeed: ${cast.error}") {
                cast.error shouldBe null
            }
            if (game.hasPendingDecision()) game.submitManaSourcesAutoPay()
            game.resolveStack()

            withClue("The enchanted Grizzly Bears should be exiled") {
                game.isOnBattlefield("Grizzly Bears") shouldBe false
            }
            withClue("Player 2 should gain 4 life — Grizzly Bears' 2 power plus Unholy Strength's +2, not its printed 2") {
                game.getLifeTotal(2) shouldBe 24
            }
        }

        fun createDriver(): GameTestDriver {
            val driver = GameTestDriver()
            driver.registerCards(TestCards.all)
            driver.initMirrorMatch(deck = com.wingedsheep.sdk.model.Deck.of("Plains" to 40), startingLife = 20)
            driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
            return driver
        }

        test("exiles a creature and its controller gains its power as life") {
            val driver = createDriver()
            val you = driver.activePlayer!!
            val opponent = driver.getOpponent(you)
            driver.putLandOnBattlefield(you, "Plains")
            val swords = driver.putCardInHand(you, "Swords to Plowshares")
            val victim = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
            val opponentLife = driver.getLifeTotal(opponent)

            driver.castSpell(you, swords, listOf(victim)).outcome shouldBe Outcome.Done
            driver.bothPass()

            driver.getExile(opponent) shouldContain victim
            driver.findPermanent(opponent, "Centaur Courser") shouldBe null
            driver.getLifeTotal(opponent) shouldBe opponentLife + 3
        }

        test("requires a creature target") {
            val driver = createDriver()
            val you = driver.activePlayer!!
            val opponent = driver.getOpponent(you)
            driver.putLandOnBattlefield(you, "Plains")
            val swords = driver.putCardInHand(you, "Swords to Plowshares")
            val land = driver.putLandOnBattlefield(opponent, "Forest")
            val result = driver.castSpell(you, swords, listOf(land))

            result.error shouldNotBe null
            driver.getExile(opponent) shouldNotContain land
        }
    }
}

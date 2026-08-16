package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.dka.cards.FaithlessLooting
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Faithless Looting (DKA #87) — draw two, discard two; flashback {2}{R}.
 */
class FaithlessLootingScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + FaithlessLooting)
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveLootingAndChooseTwo(driver: GameTestDriver, player: com.wingedsheep.sdk.model.EntityId) {
        driver.bothPass()
        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.playerId shouldBe player
        decision.minSelections shouldBe 2
        decision.maxSelections shouldBe 2
        driver.submitCardSelection(player, driver.getHand(player).take(2)).isSuccess shouldBe true
    }

    test("draws two cards and then requires exactly two discards") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val spell = driver.putCardInHand(player, "Faithless Looting")
        val graveyardBefore = driver.getGraveyard(player).size
        driver.giveMana(player, Color.RED, 1)

        driver.castSpell(player, spell).isSuccess shouldBe true
        resolveLootingAndChooseTwo(driver, player)

        driver.getHandSize(player) shouldBe 7
        driver.getGraveyard(player).size shouldBe graveyardBefore + 3
        driver.getGraveyardCardNames(player).count { it == "Faithless Looting" } shouldBe 1
    }

    test("flashback casts from the graveyard and exiles Faithless Looting after resolution") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val spell = driver.putCardInGraveyard(player, "Faithless Looting")
        driver.giveMana(player, Color.RED, 1)
        driver.giveColorlessMana(player, 2)

        val cast = driver.submit(
            CastSpell(
                playerId = player,
                cardId = spell,
                paymentStrategy = PaymentStrategy.FromPool
            )
        )
        cast.isSuccess shouldBe true
        resolveLootingAndChooseTwo(driver, player)

        driver.getGraveyardCardNames(player).count { it == "Faithless Looting" } shouldBe 0
        driver.getExile(player) shouldBe listOf(spell)
    }
})

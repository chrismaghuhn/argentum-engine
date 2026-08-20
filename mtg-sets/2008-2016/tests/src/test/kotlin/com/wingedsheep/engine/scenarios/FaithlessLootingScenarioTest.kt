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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
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

    test("matches current DKA Scryfall Oracle and canonical metadata") {
        FaithlessLooting.manaCost.toString() shouldBe "{R}"
        FaithlessLooting.typeLine.toString() shouldBe "Sorcery"
        FaithlessLooting.oracleText shouldBe
            "Draw two cards, then discard two cards.\n" +
                "Flashback {2}{R} (You may cast this card from your graveyard for its flashback cost. Then exile it.)"
        FaithlessLooting.metadata.collectorNumber shouldBe "87"
        FaithlessLooting.metadata.artist shouldBe "Gabor Szikszai"
        FaithlessLooting.metadata.imageUri shouldBe
            "https://cards.scryfall.io/normal/front/a/1/a1b0da17-d595-441d-811c-a2d28d2bb232.jpg?1783940820"
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

    test("draws before offering the exact discard choice and rejects other selection counts") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val spell = driver.putCardInHand(player, "Faithless Looting")
        val drawnFirst = driver.putCardOnTopOfLibrary(player, "Forest")
        val drawnSecond = driver.putCardOnTopOfLibrary(player, "Island")
        driver.giveMana(player, Color.RED, 1)

        driver.castSpell(player, spell).isSuccess shouldBe true
        driver.bothPass()

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        val handAtChoice = driver.getHand(player)
        decision.playerId shouldBe player
        decision.context.sourceId shouldBe spell
        decision.minSelections shouldBe 2
        decision.maxSelections shouldBe 2
        withClue("the discard choice must contain exactly the resolving player's hand") {
            decision.options.toSet() shouldBe handAtChoice.toSet()
        }
        withClue("both cards are drawn before the discard choice is presented") {
            decision.options shouldContain drawnFirst
            decision.options shouldContain drawnSecond
        }

        driver.submitCardSelection(player, handAtChoice.take(1)).isSuccess shouldBe false
        driver.pendingDecision shouldBe decision
        driver.submitCardSelection(player, handAtChoice.take(3)).isSuccess shouldBe false
        driver.pendingDecision shouldBe decision
        driver.submitCardSelection(player, handAtChoice.take(2)).isSuccess shouldBe true
    }
})

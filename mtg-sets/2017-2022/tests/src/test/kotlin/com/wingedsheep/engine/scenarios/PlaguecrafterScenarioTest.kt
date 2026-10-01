package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Plaguecrafter (GRN #82).
 *
 * "When this creature enters, each player sacrifices a creature or planeswalker of their
 * choice. Each player who can't discards a card."
 *
 * Pins the distinction from Read the Runes: a player with a legal creature or planeswalker is
 * never offered a choice between sacrificing and discarding; discarding is only the fallback when
 * sacrifice cannot occur (no creature or planeswalker, or an effect such as Sigarda forbids it).
 * "Who can't" is decided per player before their own sacrifice: a player whose only creature is
 * sacrificed *could* sacrifice, so they don't also discard.
 */
class PlaguecrafterScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(
            deck = Deck.of("Swamp" to 40),
            skipMulligans = true,
            startingPlayer = 0,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveUntilDecisionOrStackEmpty(driver: GameTestDriver) {
        var guard = 0
        while (driver.pendingDecision == null && driver.stackSize > 0) {
            check(guard++ < 30) { "Plaguecrafter resolution did not make progress" }
            driver.bothPass()
        }
    }

    fun finish(driver: GameTestDriver) {
        var guard = 0
        while (driver.pendingDecision != null || driver.stackSize > 0) {
            check(guard++ < 40) { "Plaguecrafter scenario did not finish" }
            if (driver.pendingDecision != null) {
                driver.autoResolveDecision()
            } else {
                driver.bothPass()
            }
        }
    }

    fun castPlaguecrafter(driver: GameTestDriver, player: EntityId): EntityId {
        val card = driver.putCardInHand(player, "Plaguecrafter")
        driver.giveMana(player, Color.BLACK, 1)
        driver.giveColorlessMana(player, 2)
        driver.castSpell(player, card).error shouldBe null
        driver.bothPass()
        resolveUntilDecisionOrStackEmpty(driver)
        return card
    }

    test("each player chooses only a creature or planeswalker when sacrifice is possible") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val ownCreature = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val ownPlaneswalker = driver.putPermanentOnBattlefield(you, "Liliana of the Veil")
        driver.addComponent(ownPlaneswalker, CountersComponent(mapOf(CounterType.LOYALTY to 3)))
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val opponentPlaneswalker = driver.putPermanentOnBattlefield(opponent, "Liliana of the Veil")
        driver.addComponent(opponentPlaneswalker, CountersComponent(mapOf(CounterType.LOYALTY to 3)))

        val plaguecrafter = castPlaguecrafter(driver, you)

        val yourChoice = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        yourChoice.playerId shouldBe you
        yourChoice.options.toSet() shouldBe setOf(plaguecrafter, ownCreature, ownPlaneswalker)
        // A legal sacrifice exists, so an alternate discard option must not be exposed.
        yourChoice.options shouldNotContain driver.getHand(you).first()

        // An opponent permanent is outside this player's legal choice domain and must fail closed.
        driver.submitCardSelection(you, listOf(opponentCreature)).error shouldNotBe null
        driver.pendingDecision shouldBe yourChoice
        driver.submitCardSelection(you, listOf(ownPlaneswalker)).error shouldBe null

        val opponentChoice = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        opponentChoice.playerId shouldBe opponent
        opponentChoice.options.toSet() shouldBe setOf(opponentCreature, opponentPlaneswalker)
        driver.submitCardSelection(opponent, listOf(opponentCreature)).error shouldBe null
        finish(driver)

        driver.state.getZone(you, Zone.GRAVEYARD) shouldContain ownPlaneswalker
        driver.state.getZone(opponent, Zone.GRAVEYARD) shouldContain opponentCreature
        driver.state.getZone(you, Zone.BATTLEFIELD) shouldContain ownCreature
        driver.state.getZone(opponent, Zone.BATTLEFIELD) shouldContain opponentPlaneswalker
    }

    test("a player with no creature or planeswalker must discard instead") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val plaguecrafter = castPlaguecrafter(driver, you)

        // The caster's only legal permanent is Plaguecrafter itself, so its ETB continues to the
        // opponent. The opponent has no creature or planeswalker and therefore reaches fallback.
        driver.state.getZone(you, Zone.GRAVEYARD) shouldContain plaguecrafter
        val discard = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        discard.playerId shouldBe opponent
        discard.options.toSet() shouldBe driver.getHand(opponent).toSet()
        driver.submitCardSelection(opponent, listOf(discard.options.first())).error shouldBe null
        finish(driver)
    }

    test("a player with no legal sacrifice and no cards in hand is not prompted") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        driver.getHand(opponent).toList().forEach(driver::moveToGraveyard)
        castPlaguecrafter(driver, you)

        // The opponent has no legal sacrifice and no card to discard, so the mandatory fallback
        // resolves without creating a policy-relevant choice.
        finish(driver)
        driver.pendingDecision shouldBe null
        driver.getHand(opponent) shouldBe emptyList()
    }

    test("Sigarda makes the sacrifice impossible, so the player gets the discard fallback") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        driver.putCreatureOnBattlefield(opponent, "Sigarda, Host of Herons")

        castPlaguecrafter(driver, you)

        val discard = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        discard.playerId shouldBe opponent
        discard.options.toSet() shouldBe driver.getHand(opponent).toSet()
        driver.submitCardSelection(opponent, listOf(discard.options.first())).error shouldBe null
        finish(driver)

        driver.findPermanent(opponent, "Sigarda, Host of Herons") shouldNotBe null
    }

    fun newGame(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.castPlaguecrafterAndResolve() {
        val crafter = putCardInHand(player1, "Plaguecrafter")
        giveMana(player1, Color.BLACK, 3)
        castSpell(player1, crafter)
        var guard = 0
        while ((stackSize > 0 || pendingDecision != null) && guard++ < 20) {
            if (pendingDecision != null) {
                val choice = pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
                submitCardSelection(choice.playerId, choice.options.take(choice.minSelections.coerceAtLeast(1)))
            } else {
                passPriority(state.priorityPlayerId!!)
            }
        }
    }

    test("a player without creatures discards; the controller sacrifices Plaguecrafter and keeps their hand") {
        val driver = newGame()
        val controllerHand = driver.getHandSize(driver.player1)
        val opponentHand = driver.getHandSize(driver.player2)

        driver.castPlaguecrafterAndResolve()

        withClue("Plaguecrafter was its controller's only creature, so it was sacrificed") {
            driver.getGraveyardCardNames(driver.player1) shouldBe listOf("Plaguecrafter")
        }
        withClue("…and its controller could sacrifice, so they did not discard") {
            driver.getHandSize(driver.player1) shouldBe controllerHand
        }
        withClue("The opponent controlled no creature or planeswalker, so they discarded one card") {
            driver.getHandSize(driver.player2) shouldBe opponentHand - 1
        }
    }

    test("a player with a creature sacrifices it instead of discarding") {
        val driver = newGame()
        driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val opponentHand = driver.getHandSize(driver.player2)

        driver.castPlaguecrafterAndResolve()

        withClue("The opponent sacrificed their Bears and kept their hand") {
            driver.getGraveyardCardNames(driver.player2) shouldBe listOf("Grizzly Bears")
            driver.getHandSize(driver.player2) shouldBe opponentHand
        }
    }
})

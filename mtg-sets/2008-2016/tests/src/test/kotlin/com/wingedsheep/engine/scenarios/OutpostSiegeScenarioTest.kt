package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.frf.cards.OutpostSiege
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Outpost Siege (FRF #110).
 *
 * "As this enchantment enters, choose Khans or Dragons.
 *  • Khans — At the beginning of your upkeep, exile the top card of your library. Until end of
 *    turn, you may play that card.
 *  • Dragons — Whenever a creature you control leaves the battlefield, this enchantment deals
 *    1 damage to any target."
 */
class OutpostSiegeScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(OutpostSiege)
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.castOutpostSiege(modeLabel: String): String {
        val player = activePlayer!!
        giveColorlessMana(player, 3)
        giveMana(player, Color.RED, 1)
        val card = putCardInHand(player, "Outpost Siege")

        castSpell(player, card).error shouldBe null
        bothPass()

        val modeDecision = pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
        modeDecision.options shouldContain modeLabel
        val modeIndex = modeDecision.options.indexOf(modeLabel)
        submitDecision(player, OptionChosenResponse(modeDecision.id, modeIndex)).error shouldBe null
        return card.toString()
    }

    test("Dragons deals 1 damage when your creature leaves and asks for an explicit target") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val creature = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        driver.castOutpostSiege("Dragons")

        val removal = driver.putCardInHand(player, "Go for the Throat")
        driver.giveMana(player, Color.BLACK, 2)
        driver.castSpell(player, removal, listOf(creature)).error shouldBe null
        driver.bothPass()

        driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        driver.submitTargetSelection(player, listOf(opponent)).error shouldBe null
        driver.bothPass()

        driver.getLifeTotal(opponent) shouldBe 19
        driver.findPermanent(player, "Grizzly Bears") shouldBe null
    }

    test("Dragons does not trigger for a creature an opponent controls") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        driver.castOutpostSiege("Dragons")

        val creature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val removal = driver.putCardInHand(player, "Go for the Throat")
        driver.giveMana(player, Color.BLACK, 2)
        driver.castSpell(player, removal, listOf(creature)).error shouldBe null
        driver.bothPass()

        withClue("an opposing creature leaving must not create a Dragons trigger") {
            driver.pendingDecision shouldBe null
        }
        driver.getLifeTotal(opponent) shouldBe 20
    }

    test("Khans exiles the top card at your upkeep and grants a same-turn play permission") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val topCard = driver.putCardOnTopOfLibrary(player, "Plains")
        driver.castOutpostSiege("Khans")

        driver.passPriorityUntil(Step.END, maxPasses = 200)
        driver.bothPass()
        driver.passPriorityUntil(Step.UPKEEP, maxPasses = 200)
        driver.bothPass()
        // The first upkeep belongs to the opponent.  Advance through that turn to the
        // controller's upkeep, where the Khans trigger actually fires.
        driver.passPriorityUntil(Step.END, maxPasses = 200)
        driver.bothPass()
        driver.passPriorityUntil(Step.UPKEEP, maxPasses = 200)
        driver.bothPass()

        val exiled = driver.getExile(player)
        withClue("Khans should exile the top card") {
            exiled shouldContain topCard
            driver.state.getZone(player, Zone.LIBRARY).contains(topCard) shouldBe false
        }
        withClue("the exiled card should be playable through a tracked permission") {
            driver.state.mayPlayPermissions.any { permission ->
                topCard in permission.cardIds && permission.controllerId == player
            } shouldBe true
        }
    }
})

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.khm.cards.SnakeskinVeil
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Snakeskin Veil (KHM #194)
 * Put a +1/+1 counter on target creature you control. It gains hexproof until end of turn.
 */
class SnakeskinVeilScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(SnakeskinVeil)
        driver.initMirrorMatch(deck = Deck.of("Grizzly Bears" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("puts a counter on your creature, grants hexproof, and expires at end of turn") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Hill Giant")
        val spell = driver.putCardInHand(player, "Snakeskin Veil")
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.GREEN, 1)

        driver.castSpell(player, spell, listOf(ownCreature)).isSuccess shouldBe true
        driver.bothPass()

        driver.state.getEntity(ownCreature)?.get<CountersComponent>()
            ?.getCount(CounterType.PLUS_ONE_PLUS_ONE) shouldBe 1
        driver.state.projectedState.hasKeyword(ownCreature, Keyword.HEXPROOF) shouldBe true
        driver.state.projectedState.hasKeyword(opponentCreature, Keyword.HEXPROOF) shouldBe false

        val startTurn = driver.state.turnNumber
        var guard = 0
        while (driver.state.turnNumber == startTurn && guard++ < 200) {
            when (driver.currentStep) {
                Step.DECLARE_ATTACKERS -> driver.declareAttackers(
                    driver.activePlayer!!,
                    emptyMap()
                )
                Step.DECLARE_BLOCKERS -> driver.declareNoBlockers(driver.activePlayer!!)
                else -> driver.passPriority(driver.priorityPlayer!!)
            }
        }

        driver.state.turnNumber shouldBe startTurn + 1
        driver.state.projectedState.hasKeyword(ownCreature, Keyword.HEXPROOF) shouldBe false
        driver.state.getEntity(ownCreature) shouldNotBe null
    }
})

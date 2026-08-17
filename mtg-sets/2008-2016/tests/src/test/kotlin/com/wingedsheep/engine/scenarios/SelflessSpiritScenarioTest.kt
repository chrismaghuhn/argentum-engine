package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.emn.cards.SelflessSpirit
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Selfless Spirit (EMN #40)
 * Flying; sacrifice this creature: creatures you control gain indestructible until end of turn.
 */
class SelflessSpiritScenarioTest : FunSpec({

    val abilityId = SelflessSpirit.activatedAbilities.single().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(SelflessSpirit)
        driver.initMirrorMatch(deck = Deck.of("Grizzly Bears" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("sacrifices itself and grants indestructible to your creatures until end of turn") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val spirit = driver.putCreatureOnBattlefield(player, "Selfless Spirit")
        val ally = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val opposingCreature = driver.putCreatureOnBattlefield(opponent, "Hill Giant")

        driver.state.projectedState.hasKeyword(spirit, Keyword.FLYING) shouldBe true
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = spirit,
                abilityId = abilityId
            )
        ).isSuccess shouldBe true
        driver.findPermanent(player, "Selfless Spirit") shouldBe null
        driver.state.getGraveyard(player) shouldBe listOf(spirit)

        driver.bothPass()

        driver.state.projectedState.hasKeyword(ally, Keyword.INDESTRUCTIBLE) shouldBe true
        driver.state.projectedState.hasKeyword(opposingCreature, Keyword.INDESTRUCTIBLE) shouldBe false

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
        driver.state.projectedState.hasKeyword(ally, Keyword.INDESTRUCTIBLE) shouldBe false
    }
})

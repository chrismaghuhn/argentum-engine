package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.gtc.cards.BorosCharm
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Boros Charm (GTC #148)
 * Explicitly exercises each of the three printed modes and their target domains.
 */
class BorosCharmScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(BorosCharm)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.castCharm(player: com.wingedsheep.sdk.model.EntityId, mode: Int, targets: List<com.wingedsheep.sdk.model.EntityId> = emptyList()) {
        giveMana(player, Color.RED, 1)
        giveMana(player, Color.WHITE, 1)
        val charm = putCardInHand(player, "Boros Charm")
        castSpell(player, charm)
        val modeDecision = pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
        submitDecision(player, OptionChosenResponse(modeDecision.id, mode))
        if (targets.isNotEmpty()) {
            val targetDecision = pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
            submitDecision(
                player,
                TargetsResponse(targetDecision.id, targetDecision.targetRequirements.indices.associateWith { targets })
            )
        }
        bothPass()
    }

    fun GameTestDriver.advanceToNextPrecombatMain() {
        passPriorityUntil(Step.END, maxPasses = 200)
        bothPass()
        passPriorityUntil(Step.PRECOMBAT_MAIN, maxPasses = 200)
    }

    test("damage mode deals 4 damage to the chosen player") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)

        driver.castCharm(player, mode = 0, targets = listOf(opponent))

        driver.getLifeTotal(opponent) shouldBe 16
    }

    test("indestructible mode protects your permanents only until end of turn") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val opposingCreature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")

        driver.castCharm(player, mode = 1)

        driver.state.projectedState.hasKeyword(ownCreature, Keyword.INDESTRUCTIBLE) shouldBe true
        driver.state.projectedState.hasKeyword(opposingCreature, Keyword.INDESTRUCTIBLE) shouldBe false
        driver.advanceToNextPrecombatMain()
        driver.state.projectedState.hasKeyword(ownCreature, Keyword.INDESTRUCTIBLE) shouldBe false
    }

    test("double-strike mode affects only the chosen creature until end of turn") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val chosen = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val other = driver.putCreatureOnBattlefield(player, "Grizzly Bears")

        driver.castCharm(player, mode = 2, targets = listOf(chosen))

        driver.state.projectedState.hasKeyword(chosen, Keyword.DOUBLE_STRIKE) shouldBe true
        driver.state.projectedState.hasKeyword(other, Keyword.DOUBLE_STRIKE) shouldBe false
        driver.advanceToNextPrecombatMain()
        driver.state.projectedState.hasKeyword(chosen, Keyword.DOUBLE_STRIKE) shouldBe false
    }
})

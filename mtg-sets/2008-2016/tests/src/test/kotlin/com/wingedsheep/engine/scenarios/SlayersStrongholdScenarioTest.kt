package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.avr.cards.SlayersStronghold
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Slayers' Stronghold (AVR #229)
 * {T}: Add {C}. {R}{W}, {T}: Target creature gets +2/+0 and gains vigilance and haste until end
 * of turn.
 */
class SlayersStrongholdScenarioTest : FunSpec({

    val manaAbilityId = SlayersStronghold.activatedAbilities[0].id
    val pumpAbilityId = SlayersStronghold.activatedAbilities[1].id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(SlayersStronghold)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("its mana ability taps it and adds one colorless mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val stronghold = driver.putPermanentOnBattlefield(player, "Slayers' Stronghold")

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = stronghold,
                abilityId = manaAbilityId
            )
        ).isSuccess shouldBe true

        driver.isTapped(stronghold) shouldBe true
        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        pool.colorless shouldBe 1
    }

    test("its red-white ability buffs any target creature until end of turn") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val stronghold = driver.putPermanentOnBattlefield(player, "Slayers' Stronghold")
        val target = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        driver.giveMana(player, Color.RED, 1)
        driver.giveMana(player, Color.WHITE, 1)

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = stronghold,
                abilityId = pumpAbilityId,
                targets = listOf(com.wingedsheep.engine.state.components.stack.ChosenTarget.Permanent(target))
            )
        ).isSuccess shouldBe true
        driver.bothPass()

        projector.getProjectedPower(driver.state, target) shouldBe 4
        projector.getProjectedToughness(driver.state, target) shouldBe 2
        driver.state.projectedState.hasKeyword(target, Keyword.VIGILANCE) shouldBe true
        driver.state.projectedState.hasKeyword(target, Keyword.HASTE) shouldBe true

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
        projector.getProjectedPower(driver.state, target) shouldBe 2
        projector.getProjectedToughness(driver.state, target) shouldBe 2
        driver.state.projectedState.hasKeyword(target, Keyword.VIGILANCE) shouldBe false
        driver.state.projectedState.hasKeyword(target, Keyword.HASTE) shouldBe false
    }
})

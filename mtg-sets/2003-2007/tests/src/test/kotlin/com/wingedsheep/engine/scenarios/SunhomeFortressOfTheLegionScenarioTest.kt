package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.SunhomeFortressOfTheLegion
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Sunhome, Fortress of the Legion (RAV #282)
 * Adds {C}, or pays {2}{R}{W} to give a target creature double strike until end of turn.
 */
class SunhomeFortressOfTheLegionScenarioTest : FunSpec({

    val manaAbilityId = SunhomeFortressOfTheLegion.activatedAbilities[0].id
    val doubleStrikeAbilityId = SunhomeFortressOfTheLegion.activatedAbilities[1].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(SunhomeFortressOfTheLegion)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.advanceToNextPrecombatMain() {
        passPriorityUntil(Step.END, maxPasses = 200)
        bothPass()
        passPriorityUntil(Step.PRECOMBAT_MAIN, maxPasses = 200)
    }

    test("adds colorless mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sunhome = driver.putPermanentOnBattlefield(player, "Sunhome, Fortress of the Legion")

        driver.submit(ActivateAbility(player, sunhome, manaAbilityId)).isSuccess shouldBe true
        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.colorless shouldBe 1
    }

    test("gives only the chosen creature double strike until end of turn") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sunhome = driver.putPermanentOnBattlefield(player, "Sunhome, Fortress of the Legion")
        val chosen = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val other = driver.putCreatureOnBattlefield(player, "Grizzly Bears")

        driver.giveColorlessMana(player, 2)
        driver.giveMana(player, Color.RED, 1)
        driver.giveMana(player, Color.WHITE, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = sunhome,
                abilityId = doubleStrikeAbilityId,
                targets = listOf(ChosenTarget.Permanent(chosen))
            )
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.state.projectedState.hasKeyword(chosen, Keyword.DOUBLE_STRIKE) shouldBe true
        driver.state.projectedState.hasKeyword(other, Keyword.DOUBLE_STRIKE) shouldBe false

        driver.advanceToNextPrecombatMain()
        driver.state.projectedState.hasKeyword(chosen, Keyword.DOUBLE_STRIKE) shouldBe false
    }
})

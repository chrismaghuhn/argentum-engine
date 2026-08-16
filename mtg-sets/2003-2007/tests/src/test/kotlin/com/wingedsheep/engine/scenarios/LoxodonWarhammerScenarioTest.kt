package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.LoxodonWarhammer
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Loxodon Warhammer (MRD #201) — "Equipped creature gets +3/+0 and has trample and lifelink.
 * Equip {3}."
 */
class LoxodonWarhammerScenarioTest : FunSpec({

    val equipAbilityId = LoxodonWarhammer.activatedAbilities.first().id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + LoxodonWarhammer)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("Equip grants +3/+0, trample, and lifelink") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val hammer = driver.putPermanentOnBattlefield(player, "Loxodon Warhammer")
        driver.giveColorlessMana(player, 3)

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = hammer,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature))
            )
        )
        result.isSuccess shouldBe true
        driver.bothPass()

        projector.getProjectedPower(driver.state, creature) shouldBe 6
        projector.getProjectedToughness(driver.state, creature) shouldBe 3
        driver.state.projectedState.hasKeyword(creature, Keyword.TRAMPLE) shouldBe true
        driver.state.projectedState.hasKeyword(creature, Keyword.LIFELINK) shouldBe true
    }
})

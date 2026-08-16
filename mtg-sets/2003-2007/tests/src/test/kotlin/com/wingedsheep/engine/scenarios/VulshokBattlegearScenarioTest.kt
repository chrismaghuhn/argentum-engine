package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.VulshokBattlegear
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Vulshok Battlegear (MRD #272) — "Equipped creature gets +3/+3. Equip {3}."
 */
class VulshokBattlegearScenarioTest : FunSpec({

    val equipAbilityId = VulshokBattlegear.activatedAbilities.first().id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + VulshokBattlegear)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("Equip gives the creature exactly +3/+3") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val battlegear = driver.putPermanentOnBattlefield(player, "Vulshok Battlegear")
        driver.giveColorlessMana(player, 3)

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = battlegear,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature))
            )
        )
        result.isSuccess shouldBe true
        driver.bothPass()

        projector.getProjectedPower(driver.state, creature) shouldBe 6
        projector.getProjectedToughness(driver.state, creature) shouldBe 6
    }
})

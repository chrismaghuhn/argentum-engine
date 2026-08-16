package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.dst.cards.VulshokMorningstar
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Vulshok Morningstar (DST #157) — "Equipped creature gets +2/+2. Equip {2}."
 */
class VulshokMorningstarScenarioTest : FunSpec({

    val equipAbilityId = VulshokMorningstar.activatedAbilities.first().id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + VulshokMorningstar)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("Equip gives the creature exactly +2/+2") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val morningstar = driver.putPermanentOnBattlefield(player, "Vulshok Morningstar")
        driver.giveColorlessMana(player, 2)

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = morningstar,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature))
            )
        )
        result.isSuccess shouldBe true
        driver.bothPass()

        projector.getProjectedPower(driver.state, creature) shouldBe 5
        projector.getProjectedToughness(driver.state, creature) shouldBe 5
    }
})

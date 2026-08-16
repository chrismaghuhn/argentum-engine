package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.LightningGreaves
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Lightning Greaves (MRD #199) — "Equipped creature has haste and shroud. Equip {0}."
 */
class LightningGreavesScenarioTest : FunSpec({

    val equipAbilityId = LightningGreaves.activatedAbilities.first().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + LightningGreaves)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun equipForZero(driver: GameTestDriver, player: EntityId, equipment: EntityId, creature: EntityId) {
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = equipment,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature))
            )
        ).isSuccess shouldBe true
        driver.bothPass()
    }

    test("zero-cost Equip grants haste and shroud to the equipped creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val greaves = driver.putPermanentOnBattlefield(player, "Lightning Greaves")

        equipForZero(driver, player, greaves, creature)

        driver.state.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe true
        driver.state.projectedState.hasKeyword(creature, Keyword.SHROUD) shouldBe true
    }
})

package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Fireshrieker
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Fireshrieker (MRD #171) — "Equipped creature has double strike. Equip {2}."
 */
class FireshriekerScenarioTest : FunSpec({

    val equipAbilityId = Fireshrieker.activatedAbilities.first().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Fireshrieker)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun equip(driver: GameTestDriver, player: EntityId, equipment: EntityId, creature: EntityId) {
        driver.giveColorlessMana(player, 2)
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

    test("Equip grants double strike to the equipped creature only") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val equipped = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val other = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val fireshrieker = driver.putPermanentOnBattlefield(player, "Fireshrieker")

        equip(driver, player, fireshrieker, equipped)

        driver.state.projectedState.hasKeyword(equipped, Keyword.DOUBLE_STRIKE) shouldBe true
        driver.state.projectedState.hasKeyword(other, Keyword.DOUBLE_STRIKE) shouldBe false
    }
})

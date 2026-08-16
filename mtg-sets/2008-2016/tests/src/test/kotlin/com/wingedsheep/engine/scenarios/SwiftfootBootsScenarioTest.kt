package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.m12.cards.SwiftfootBoots
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Swiftfoot Boots (M12 #219) — "Equipped creature has hexproof and haste. Equip {1}."
 */
class SwiftfootBootsScenarioTest : FunSpec({

    val equipAbilityId = SwiftfootBoots.activatedAbilities.first().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + SwiftfootBoots)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("Equip grants hexproof and haste to the equipped creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val boots = driver.putPermanentOnBattlefield(player, "Swiftfoot Boots")
        driver.giveColorlessMana(player, 1)

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = boots,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature))
            )
        )
        result.isSuccess shouldBe true
        driver.bothPass()

        driver.state.projectedState.hasKeyword(creature, Keyword.HEXPROOF) shouldBe true
        driver.state.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe true
    }
})

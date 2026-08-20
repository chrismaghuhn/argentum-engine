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

    test("Equip grants hexproof and haste only to the equipped creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val other = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponent = driver.getOpponent(player)
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
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
        driver.state.projectedState.hasKeyword(other, Keyword.HEXPROOF) shouldBe false
        driver.state.projectedState.hasKeyword(other, Keyword.HASTE) shouldBe false
        driver.state.projectedState.hasKeyword(opponentCreature, Keyword.HEXPROOF) shouldBe false
        driver.state.projectedState.hasKeyword(opponentCreature, Keyword.HASTE) shouldBe false
    }

    test("hexproof prevents an opponent's targeted spell") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val boots = driver.putPermanentOnBattlefield(player, "Swiftfoot Boots")
        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = boots,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature)),
            )
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.passPriority(player)
        val bolt = driver.putCardInHand(opponent, "Lightning Bolt")
        driver.giveMana(opponent, com.wingedsheep.sdk.core.Color.RED, 1)
        driver.castSpellWithTargets(opponent, bolt, listOf(ChosenTarget.Permanent(creature)))
            .isSuccess shouldBe false
    }

    test("re-equipping transfers both granted keywords and a leaving host clears them") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val first = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val second = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val boots = driver.putPermanentOnBattlefield(player, "Swiftfoot Boots")

        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = boots,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(first)),
            )
        ).isSuccess shouldBe true
        driver.bothPass()
        driver.state.projectedState.hasKeyword(first, Keyword.HASTE) shouldBe true

        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = boots,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(second)),
            )
        ).isSuccess shouldBe true
        driver.bothPass()
        driver.state.projectedState.hasKeyword(first, Keyword.HASTE) shouldBe false
        driver.state.projectedState.hasKeyword(second, Keyword.HEXPROOF) shouldBe true

        val caster = driver.priorityPlayer!!
        val bolt = driver.putCardInHand(caster, "Lightning Bolt")
        driver.giveMana(caster, com.wingedsheep.sdk.core.Color.RED, 1)
        driver.castSpellWithTargets(caster, bolt, listOf(ChosenTarget.Permanent(second)))
            .isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(player, "Swiftfoot Boots") shouldBe boots
        driver.state.getEntity(boots)?.get<com.wingedsheep.engine.state.components.battlefield.AttachedToComponent>() shouldBe null
        driver.state.projectedState.hasKeyword(second, Keyword.HEXPROOF) shouldBe false
    }
})

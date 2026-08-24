package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.legalactions.utils.CastPermissionUtils
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.ReduceEquipCost
import com.wingedsheep.sdk.scripting.costs.CostAtom
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ActivatedAbilityCostCalculatorTest : ScenarioTestBase() {

    private val fixedEquipment = card("Calculator Fixed Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{1}")
    }

    private val targetPowerEquipment = card("Calculator Target Power Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{3}", genericCostReduction = DynamicAmounts.targetPower())
    }

    private val lowPowerTarget = card("Calculator Low Power Target") {
        typeLine = "Creature"
        power = 1
        toughness = 1
    }

    private val highPowerTarget = card("Calculator High Power Target") {
        typeLine = "Creature"
        power = 3
        toughness = 3
    }

    private val restrictedGrant = card("Calculator Target Restricted Equip Grant") {
        typeLine = "Creature"
        power = 2
        toughness = 2
        staticAbility {
            ability = ReduceEquipCost(amount = 1, onlyIfTargetIsSource = true)
        }
    }

    private val restrictedEquipment = card("Calculator Target Restricted Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{2}")
    }

    init {
        cardRegistry.register(fixedEquipment)
        cardRegistry.register(targetPowerEquipment)
        cardRegistry.register(lowPowerTarget)
        cardRegistry.register(highPowerTarget)
        cardRegistry.register(restrictedGrant)
        cardRegistry.register(restrictedEquipment)

        test("fixed Equip cost is identical before and after every legal target") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, fixedEquipment.name)
                .withCardOnBattlefield(1, lowPowerTarget.name)
                .withCardOnBattlefield(1, highPowerTarget.name)
                .build()
            val sourceId = game.findPermanent(fixedEquipment.name)!!
            val ability = fixedEquipment.activatedAbilities.single()
            val expected = manaCost("{1}")
            val calculator = calculator()

            calculator.calculate(game.state, sourceId, game.player1Id, ability) shouldBe expected
            calculator.calculate(
                game.state,
                sourceId,
                game.player1Id,
                ability,
                targets = listOf(ChosenTarget.Permanent(game.findPermanent(lowPowerTarget.name)!!)),
            ) shouldBe expected
            calculator.calculate(
                game.state,
                sourceId,
                game.player1Id,
                ability,
                targets = listOf(ChosenTarget.Permanent(game.findPermanent(highPowerTarget.name)!!)),
            ) shouldBe expected
        }

        test("target-power generic reduction is evaluated against the chosen target") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, targetPowerEquipment.name)
                .withCardOnBattlefield(1, lowPowerTarget.name)
                .withCardOnBattlefield(1, highPowerTarget.name)
                .build()
            val sourceId = game.findPermanent(targetPowerEquipment.name)!!
            val ability = targetPowerEquipment.activatedAbilities.single()
            val lowTargetId = game.findPermanent(lowPowerTarget.name)!!
            val highTargetId = game.findPermanent(highPowerTarget.name)!!
            val calculator = calculator()

            calculator.calculate(game.state, sourceId, game.player1Id, ability) shouldBe manaCost("{3}")
            calculator.calculate(
                game.state,
                sourceId,
                game.player1Id,
                ability,
                targets = listOf(ChosenTarget.Permanent(lowTargetId)),
            ) shouldBe manaCost("{2}")
            calculator.calculate(
                game.state,
                sourceId,
                game.player1Id,
                ability,
                targets = listOf(ChosenTarget.Permanent(highTargetId)),
            ) shouldBe zeroManaCost()
        }

        test("target-restricted equip reduction follows the selected target") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, restrictedEquipment.name)
                .withCardOnBattlefield(1, restrictedGrant.name)
                .withCardOnBattlefield(1, lowPowerTarget.name)
                .build()
            val sourceId = game.findPermanent(restrictedEquipment.name)!!
            val grantingTargetId = game.findPermanent(restrictedGrant.name)!!
            val otherTargetId = game.findPermanent(lowPowerTarget.name)!!
            val ability = restrictedEquipment.activatedAbilities.single()
            val calculator = calculator()

            calculator.calculate(game.state, sourceId, game.player1Id, ability) shouldBe manaCost("{1}")
            calculator.calculate(
                game.state,
                sourceId,
                game.player1Id,
                ability,
                targets = listOf(ChosenTarget.Permanent(grantingTargetId)),
            ) shouldBe manaCost("{1}")
            calculator.calculate(
                game.state,
                sourceId,
                game.player1Id,
                ability,
                targets = listOf(ChosenTarget.Permanent(otherTargetId)),
            ) shouldBe manaCost("{2}")
        }

        test("a non-mana Equip cost remains a complete non-mana AbilityCost") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, fixedEquipment.name)
                .build()
            val sourceId = game.findPermanent(fixedEquipment.name)!!
            val nonManaCost = AbilityCost.Composite(
                listOf(
                    manaCost("{1}"),
                    AbilityCost.Tap,
                ),
            )
            val ability = ActivatedAbility(
                cost = nonManaCost,
                effect = Effects.GainLife(1),
                isEquipAbility = true,
            )

            calculator().calculate(game.state, sourceId, game.player1Id, ability) shouldBe nonManaCost
        }
    }

    private fun calculator(): ActivatedAbilityCostCalculator = ActivatedAbilityCostCalculator(
        CastPermissionUtils(cardRegistry, PredicateEvaluator(), ConditionEvaluator()),
    )

    private fun manaCost(symbols: String): AbilityCost =
        AbilityCost.Atom(CostAtom.Mana(ManaCost.parse(symbols)))

    private fun zeroManaCost(): AbilityCost =
        AbilityCost.Atom(CostAtom.Mana(ManaCost.ZERO))
}

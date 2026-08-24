package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.legalactions.utils.CastPermissionUtils
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.TextReplacementComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.EquipPaymentChoice
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.costs.manaCostOrNull

/**
 * Computes the exact effective cost of one activated-ability action.
 *
 * This is shared by legal-action enumeration, public payment-domain proof, and the trusted
 * activation handler. Callers provide the targets and any explicitly selected equip payment
 * mode; this class never chooses either one.
 */
class ActivatedAbilityCostCalculator(
    private val castPermissionUtils: CastPermissionUtils,
    private val dynamicAmountEvaluator: DynamicAmountEvaluator = DynamicAmountEvaluator(),
) {
    fun calculate(
        state: GameState,
        sourceId: EntityId,
        controllerId: EntityId,
        ability: ActivatedAbility,
        targets: List<ChosenTarget> = emptyList(),
        equipPayment: EquipPaymentChoice? = null,
    ): AbilityCost {
        val textReplacement = state.getEntity(sourceId)?.get<TextReplacementComponent>()
        val rawCost = textReplacement?.let(ability.cost::applyTextReplacement) ?: ability.cost
        val genericReduced = applyGenericCostReduction(
            cost = rawCost,
            ability = ability,
            state = state,
            sourceId = sourceId,
            controllerId = controllerId,
            targets = targets,
        )
        val activatedReduced = castPermissionUtils.applyActivatedAbilityCostReduction(
            genericReduced,
            state,
            sourceId,
            ability.isExhaust,
            ability.isPowerUp,
        )
        val equipTargetId = targets
            .filterIsInstance<ChosenTarget.Permanent>()
            .firstOrNull()
            ?.entityId
        val equipReduced = castPermissionUtils.applyEquipCostReduction(
            activatedReduced,
            ability,
            state,
            controllerId,
            equipTargetId,
            abilitySourceId = sourceId,
        )
        val freeFirstEquipApplied = castPermissionUtils.applyFreeFirstEquipDiscount(
            equipReduced,
            ability,
            state,
            controllerId,
            equipPayment,
        )
        return castPermissionUtils.relaxAbilityCostColorsIfAny(
            state,
            sourceId,
            freeFirstEquipApplied,
        )
    }

    private fun applyGenericCostReduction(
        cost: AbilityCost,
        ability: ActivatedAbility,
        state: GameState,
        sourceId: EntityId,
        controllerId: EntityId,
        targets: List<ChosenTarget>,
    ): AbilityCost {
        val reduction = ability.genericCostReduction ?: return cost
        val reductionContext = EffectContext(
            sourceId = sourceId,
            controllerId = controllerId,
            targets = targets,
        )
        val amount = dynamicAmountEvaluator.evaluate(state, reduction, reductionContext)
        if (amount <= 0) return cost
        return reduceGenericInCost(cost, amount)
    }

    private fun reduceGenericInCost(cost: AbilityCost, amount: Int): AbilityCost = when (cost) {
        is AbilityCost.Atom -> cost.manaCostOrNull
            ?.let { AbilityCost.Atom(CostAtom.Mana(it.reduceGeneric(amount))) } ?: cost
        is AbilityCost.Composite -> {
            var applied = false
            AbilityCost.Composite(cost.costs.map { sub ->
                val subMana = sub.manaCostOrNull
                if (!applied && subMana != null) {
                    applied = true
                    AbilityCost.Atom(CostAtom.Mana(subMana.reduceGeneric(amount)))
                } else sub
            })
        }
        else -> cost
    }
}

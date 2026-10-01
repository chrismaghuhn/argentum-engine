package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.actions.ability.ActivationCostTotaller
import com.wingedsheep.engine.legalactions.utils.CastPermissionUtils
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.TextChanges
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.EquipPaymentChoice

/**
 * Computes the exact effective cost of one activated-ability action.
 *
 * This is shared by legal-action enumeration, public payment-domain proof, and the trusted
 * activation handler. Callers provide the targets and any explicitly selected equip payment
 * mode; this class never chooses either one.
 *
 * It is a thin public seam over [ActivationCostTotaller], the handler's own effective-cost
 * computation, so every caller charges the same number for every cost feature (defined X,
 * conditional and generic reductions, equip reductions, the free first equip, color relaxation).
 */
class ActivatedAbilityCostCalculator(
    castPermissionUtils: CastPermissionUtils,
    conditionEvaluator: ConditionEvaluator,
) {
    private val totaller = ActivationCostTotaller(castPermissionUtils, conditionEvaluator)

    fun calculate(
        state: GameState,
        sourceId: EntityId,
        controllerId: EntityId,
        ability: ActivatedAbility,
        targets: List<ChosenTarget> = emptyList(),
        equipPayment: EquipPaymentChoice? = null,
    ): AbilityCost = totaller.total(
        state = state,
        sourceId = sourceId,
        controllerId = controllerId,
        ability = ability,
        textReplacement = TextChanges.of(state, sourceId),
        targets = targets,
        equipPayment = equipPayment,
    )
}

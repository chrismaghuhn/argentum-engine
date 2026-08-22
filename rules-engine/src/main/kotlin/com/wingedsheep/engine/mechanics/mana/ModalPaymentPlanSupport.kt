package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.mechanics.ModalChooseCounts
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.scripting.effects.ModalEffect

/**
 * Eligibility gate for the fixed-cost modal slice of PaymentPlanV1.
 *
 * This is deliberately a predicate, not a payment model or executor. Both the public Gym
 * observation boundary and CastSpellHandler use it so they cannot disagree about which already
 * selected modal casts are representable by the existing explicit payment plan.
 */
object ModalPaymentPlanSupport {
    fun supportsFixedChooseOne(
        state: GameState,
        cardDef: CardDefinition,
        action: CastSpell,
        conditionEvaluator: ConditionEvaluator,
    ): Boolean {
        if (action.chosenModes.size != 1 || action.modeTargetsOrdered.size > 1) return false

        val modalEffect = cardDef.script.spellEffect as? ModalEffect ?: return false
        if (
            modalEffect.chooseCount != 1 ||
            modalEffect.minChooseCount != 1 ||
            modalEffect.dynamicChooseCount != null ||
            modalEffect.dynamicMinChooseCount != null ||
            modalEffect.chooseAllIfBlightPaid ||
            modalEffect.additionalManaCostPerExtraMode != null
        ) return false

        val chooseRange = ModalChooseCounts.forCast(
            state = state,
            modalEffect = modalEffect,
            cardId = action.cardId,
            controllerId = action.playerId,
            declaredCostSlot = action.declaredCostSlot,
            blightPaid = action.additionalCostPayment?.blightTargets?.isNotEmpty() == true,
            conditionEvaluator = conditionEvaluator,
        )
        if (chooseRange.first != 1 || chooseRange.last != 1) return false

        val selectedMode = modalEffect.modes.getOrNull(action.chosenModes.single()) ?: return false
        return selectedMode.additionalManaCost == null && selectedMode.additionalCosts == null
    }
}

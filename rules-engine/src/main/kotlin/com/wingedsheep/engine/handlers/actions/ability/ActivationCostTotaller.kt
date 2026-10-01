package com.wingedsheep.engine.handlers.actions.ability

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.legalactions.utils.AbilityCostReduction
import com.wingedsheep.engine.legalactions.utils.CastPermissionUtils
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.TextReplacementComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.EquipPaymentChoice

/**
 * Determines an activation's total cost (CR 601.2f, via CR 602.2b): the printed cost with
 * text-changing effects applied, a *defined* {X} fixed, then every reduction and relaxation, in the
 * order the validate path, the execute path and the enumerator all agree on.
 */
internal class ActivationCostTotaller(
    private val castPermissionUtils: CastPermissionUtils,
    private val conditionEvaluator: ConditionEvaluator
) {
    private val amountEvaluator: DynamicAmountEvaluator get() = conditionEvaluator.amounts


    /**
     * The cost this activation will be charged.
     *
     * Resolve a *defined* {X} (CR 107.3c) before anything else reads the cost, so validation
     * sees the same fixed cost enumeration offered and payment will charge. Then apply the
     * ability's own conditional pip-wise reduction (Kami of Jealous Thirst), then its
     * ability-specific generic cost reduction (e.g., The Dominion Bracelet's
     * "{X} less, where X is this creature's power"). Per Scryfall ruling, the reduced
     * cost is locked in here, before costs are paid. Then apply generic equip-cost reduction
     * (Éowyn) and Forge Anew's free-first-equip discount. Finally relax colored requirements when
     * "mana of any type can be spent" applies (Sharkey).
     *
     * Order matters: each step prices the cost the previous one produced.
     *
     * Forge Anew's free-first-equip is an explicit payment mode: the discount applies only when the
     * action chose [EquipPaymentChoice.FREE_FIRST_EQUIP] on its alternative payment; the engine never
     * infers the mode (validation rejects an equip action that omits it while it is available).
     *
     * This is the *execute* total. The validate path additionally lowers an attached-permanent
     * mana cost (Merseine) to a plain mana atom via [totalForValidation]; payment leaves that atom
     * for `CostHandler` to price itself.
     */
    fun total(
        state: GameState,
        action: ActivateAbility,
        ability: ActivatedAbility,
        textReplacement: TextReplacementComponent?,
    ): AbilityCost = total(
        state = state,
        sourceId = action.sourceId,
        controllerId = action.playerId,
        ability = ability,
        textReplacement = textReplacement,
        targets = action.targets,
        equipPayment = action.alternativePayment?.equipPayment,
    )

    /**
     * [total] for a not-yet-built action: the same steps, priced from the activation's parts. This is
     * the one effective-cost computation shared by the handler and the public effective-cost seam
     * (`ActivatedAbilityCostCalculator`, used by legal-action enumeration and payment-domain proof),
     * so all of them charge the same number. Callers supply the targets and any explicitly selected
     * equip payment mode; this never chooses either one.
     */
    fun total(
        state: GameState,
        sourceId: EntityId,
        controllerId: EntityId,
        ability: ActivatedAbility,
        textReplacement: TextReplacementComponent?,
        targets: List<ChosenTarget> = emptyList(),
        equipPayment: EquipPaymentChoice? = null,
    ): AbilityCost {
        // Apply text-changing effects to cost
        val rawCost = if (textReplacement != null) {
            ability.cost.applyTextReplacement(textReplacement)
        } else {
            ability.cost
        }
        val chosenPermanentIds = targets.filterIsInstance<ChosenTarget.Permanent>().map { it.entityId }
        val equipTargetIdForCost = chosenPermanentIds.firstOrNull()
        val costWithDefinedX =
            castPermissionUtils.applyDefinedXValue(rawCost, ability, state, sourceId, controllerId)
        val costAfterConditionalReduction = AbilityCostReduction.applyConditional(
            costWithDefinedX, ability, state, sourceId, controllerId, conditionEvaluator
        )
        val costAfterGenericReduction = applyGenericCostReduction(
            costAfterConditionalReduction, ability, state, sourceId, controllerId, targets
        )
        val costAfterAbilityReduction = castPermissionUtils.applyActivatedAbilityCostReduction(
            costAfterGenericReduction, state, sourceId, ability.isExhaust, ability.isPowerUp,
            ability.isManaAbility, chosenTargetIds = chosenPermanentIds
        )
        val costAfterEquipReduction = castPermissionUtils.applyEquipCostReduction(
            costAfterAbilityReduction, ability, state, controllerId, equipTargetIdForCost,
            abilitySourceId = sourceId
        )
        val costAfterEquipDiscount = castPermissionUtils.applyFreeFirstEquipDiscount(
            costAfterEquipReduction, ability, state, controllerId, equipPayment
        )
        return castPermissionUtils.relaxAbilityCostColorsIfAny(
            state, sourceId, costAfterEquipDiscount, controllerId
        )
    }

    /**
     * [total], then — last of all — lower an attached-permanent mana cost (Merseine) to a plain
     * mana atom, so nothing downstream in validation has to know that shape existed.
     */
    fun totalForValidation(
        state: GameState,
        action: ActivateAbility,
        ability: ActivatedAbility,
        textReplacement: TextReplacementComponent?,
    ): AbilityCost = lowerForValidation(state, action, total(state, action, ability, textReplacement))

    /**
     * The validation form of an already-computed execute [total]: an attached-permanent mana cost
     * (Merseine) lowered to a plain mana atom. Lets the validator keep both forms without pricing
     * the cost twice.
     */
    fun lowerForValidation(
        state: GameState,
        action: ActivateAbility,
        executeTotal: AbilityCost,
    ): AbilityCost = castPermissionUtils.lowerAttachedManaCost(state, action.sourceId, executeTotal)

    /**
     * Apply [ActivatedAbility.genericCostReduction] to the mana portion of [cost].
     * The reduction is evaluated against the activating entity (e.g., the equipped creature
     * for The Dominion Bracelet, whose granted ability reduces by the creature's power) and,
     * when present, the chosen [targets] — so reductions that read the target the player picked
     * (e.g. Dragonfire Blade's "costs {1} less to activate for each color of the creature it
     * targets") resolve against that target. Per Scryfall ruling, this is locked in before costs
     * are paid.
     */
    private fun applyGenericCostReduction(
        cost: AbilityCost,
        ability: ActivatedAbility,
        state: GameState,
        sourceId: EntityId,
        controllerId: EntityId,
        targets: List<ChosenTarget>
    ): AbilityCost {
        val reduction = ability.genericCostReduction ?: return cost
        val reductionContext = EffectContext(
            sourceId = sourceId,
            controllerId = controllerId,
            targets = targets
        )
        val amount = amountEvaluator.evaluate(state, reduction, reductionContext)
        if (amount <= 0) return cost
        return cost.reduceGenericMana(amount)
    }
}

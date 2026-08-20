package com.wingedsheep.sdk.scripting

import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import com.wingedsheep.sdk.scripting.text.TextReplaceable
import com.wingedsheep.sdk.scripting.text.TextReplacer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The cast-time payment rail that establishes a [CostPaidLinkedTrigger].
 *
 * The payment itself remains an [AdditionalCost] / [costs.CostAtom] concern. This type only
 * describes the linked ability created after the payment completes under CR 603.11 and
 * CR 607.2h/607.2i, so the shared payable atom does not acquire card-specific rider behavior.
 */
@Serializable
sealed interface CostPaidLinkedTriggerCost {
    val description: String

    /** A completed `CostAtom.VariablePermanents(SACRIFICE)` payment. */
    @SerialName("VariablePermanentsSacrifice")
    @Serializable
    data object VariablePermanentsSacrifice : CostPaidLinkedTriggerCost {
        override val description: String = "a variable permanent sacrifice cost"
    }
}

/**
 * A generic triggered ability linked to a completed cast-time cost payment.
 *
 * This is not a CR 603.12 reflexive trigger: the sacrifice is paid during casting (CR 601.2h).
 * The descriptor models the linked static/additional-cost relationship described by CR 603.11
 * and CR 607.2h/607.2i. The engine lowers it into the ordinary pending-trigger and stack rails,
 * including the normal CR 603.3b ordering window and a frozen source-LKI copy payload.
 */
@Serializable
data class CostPaidLinkedTrigger(
    val cost: CostPaidLinkedTriggerCost = CostPaidLinkedTriggerCost.VariablePermanentsSacrifice,
    val effect: Effect,
    val targetRequirements: List<TargetRequirement> = emptyList(),
    val descriptionOverride: String? = null,
) : TextReplaceable<CostPaidLinkedTrigger> {
    val description: String
        get() = descriptionOverride ?: "When you pay ${cost.description}, " +
            effect.description.replaceFirstChar { it.lowercase() }

    override fun applyTextReplacement(replacer: TextReplacer): CostPaidLinkedTrigger {
        val newEffect = effect.applyTextReplacement(replacer)
        return if (newEffect !== effect) copy(effect = newEffect) else this
    }
}

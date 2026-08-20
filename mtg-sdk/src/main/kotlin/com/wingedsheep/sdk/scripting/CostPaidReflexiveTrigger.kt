package com.wingedsheep.sdk.scripting

import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import com.wingedsheep.sdk.scripting.text.TextReplaceable
import com.wingedsheep.sdk.scripting.text.TextReplacer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The cast-time payment rail that establishes a [CostPaidReflexiveTrigger].
 *
 * The payment itself remains an [AdditionalCost] / [costs.CostAtom] concern. This type only
 * describes the CR 603.12 linkage that is created after the payment completes, so the shared
 * payable atom does not acquire card-specific rider behavior.
 */
@Serializable
sealed interface CostPaidReflexiveTriggerCost {
    val description: String

    /** A completed `CostAtom.VariablePermanents(SACRIFICE)` payment. */
    @SerialName("VariablePermanentsSacrifice")
    @Serializable
    data object VariablePermanentsSacrifice : CostPaidReflexiveTriggerCost {
        override val description: String = "a variable permanent sacrifice cost"
    }
}

/**
 * A generic CR 603.12 "when you do" ability established by a completed cast-time cost payment.
 *
 * The engine lowers this descriptor into the normal [com.wingedsheep.engine.event.PendingTrigger]
 * / [com.wingedsheep.engine.event.TriggerStage.REFLEXIVE] rail. Its effect is therefore a real
 * triggered ability with the ordinary priority window, target locking, serialization, and replay
 * behavior; it is not resolved inline from the cost handler.
 */
@Serializable
data class CostPaidReflexiveTrigger(
    val cost: CostPaidReflexiveTriggerCost = CostPaidReflexiveTriggerCost.VariablePermanentsSacrifice,
    val effect: Effect,
    val targetRequirements: List<TargetRequirement> = emptyList(),
    val descriptionOverride: String? = null,
) : TextReplaceable<CostPaidReflexiveTrigger> {
    val description: String
        get() = descriptionOverride ?: "When you pay ${cost.description}, " +
            effect.description.replaceFirstChar { it.lowercase() }

    override fun applyTextReplacement(replacer: TextReplacer): CostPaidReflexiveTrigger {
        val newEffect = effect.applyTextReplacement(replacer)
        return if (newEffect !== effect) copy(effect = newEffect) else this
    }
}

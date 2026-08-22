package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.scripting.effects.AddColorlessManaEffect
import com.wingedsheep.sdk.scripting.effects.AddManaEffect
import com.wingedsheep.sdk.scripting.effects.AddManaOfChoiceEffect
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.values.DynamicAmount

/**
 * Rules-owned description of the currently resolved output of one selected mana ability.
 *
 * This is deliberately separate from [ManaSource]'s legacy aggregate fields. Those fields are
 * also used for auto-payment bonuses and cannot tell a controller whether a second output is a
 * fixed leaf of the selected ability or an externally granted bonus.
 */
sealed interface PaymentManaProductionProfile {
    /** One mana unit whose color is selected explicitly by the submitted production choice. */
    data class SelectableSingleOutput(
        val allowedColors: Set<PaymentManaColor>,
    ) : PaymentManaProductionProfile

    /** Ordered, deterministic mana units produced by one tap of the selected ability. */
    data class FixedOutputBundle(
        val outputs: List<PaymentManaOutput>,
    ) : PaymentManaProductionProfile

    /** The current source shape is intentionally not representable by PaymentPlanV1. */
    data class Unsupported(
        val reason: String,
    ) : PaymentManaProductionProfile
}

data class PaymentManaOutput(
    val color: PaymentManaColor,
)

/**
 * Reconciles raw ability profiles with the final aggregate [ManaSource] semantics.
 *
 * Source discovery intentionally keeps its existing aggregate representation for auto-pay. A
 * profile may therefore only cross the PaymentPlanV1 boundary when that representation can spell
 * out the same output units. In particular, a raw composite effect must not manufacture a tail
 * output that source discovery dropped before it reached [ManaSource].
 */
internal fun ManaSource.authorizePaymentManaProductionProfiles(): ManaSource = copy(
    paymentManaProductionProfiles = paymentManaProductionProfiles.mapValues { (_, profile) ->
        when (profile) {
            is PaymentManaProductionProfile.Unsupported -> profile
            is PaymentManaProductionProfile.SelectableSingleOutput ->
                if (profile.allowedColors.all(::representsSingleOutput)) {
                    profile
                } else {
                    PaymentManaProductionProfile.Unsupported(
                        "Current ManaSource semantics do not represent every selectable output"
                    )
                }

            is PaymentManaProductionProfile.FixedOutputBundle ->
                if (paymentManaProductionProfiles.size == 1 && representsFixedBundle(profile)) {
                    profile
                } else {
                    PaymentManaProductionProfile.Unsupported(
                        "Current ManaSource semantics do not represent the fixed output bundle"
                    )
                }
        }
    }
)

private fun ManaSource.representsSingleOutput(color: PaymentManaColor): Boolean = when (color) {
    PaymentManaColor.COLORLESS -> producesColorless
    else -> color.asEngineColor() in producesColors
}

private fun ManaSource.representsFixedBundle(
    profile: PaymentManaProductionProfile.FixedOutputBundle,
): Boolean {
    val outputs = profile.outputs.map(PaymentManaOutput::color)
    if (outputs.size < 2 || manaAmount <= 0) return false

    val primary = outputs.first()
    if (!representsSingleOutput(primary)) return false
    if (bonusManaIsAnyColor || (bonusManaPerTap > 0 && bonusManaColor == null)) return false

    val currentOutputs = buildList {
        repeat(manaAmount) { add(primary) }
        if (bonusManaPerTap > 0) {
            repeat(bonusManaPerTap) { add(PaymentManaColor.fromEngine(bonusManaColor!!)) }
        }
        repeat(bonusManaColorlessPerTap) { add(PaymentManaColor.COLORLESS) }
    }
    return currentOutputs == outputs
}

/**
 * Resolves only unconditional fixed leaves of the selected ability. The caller supplies the
 * colors already resolved by source discovery for a single-output color-choice effect; this keeps
 * this helper on the same Rules-owned resolution path as [ManaSolver].
 */
object PaymentManaProductionProfileResolver {
    fun resolve(
        effect: Effect,
        resolvedChoiceColors: Set<Color>,
    ): PaymentManaProductionProfile {
        val leaves = flatten(effect)
        if (leaves.isEmpty()) {
            return PaymentManaProductionProfile.Unsupported("The mana ability has no production leaf")
        }

        if (leaves.any { it is AddManaOfChoiceEffect }) {
            if (leaves.size != 1) {
                return PaymentManaProductionProfile.Unsupported(
                    "A multi-output ability cannot contain an unresolved color choice"
                )
            }
            val choice = leaves.single() as AddManaOfChoiceEffect
            if (choice.restriction != null || choice.amount !is DynamicAmount.Fixed ||
                (choice.amount as DynamicAmount.Fixed).amount != 1 || resolvedChoiceColors.isEmpty()
            ) {
                return PaymentManaProductionProfile.Unsupported(
                    "Selectable mana production is not a fixed single output"
                )
            }
            return PaymentManaProductionProfile.SelectableSingleOutput(
                allowedColors = resolvedChoiceColors.map(PaymentManaColor::fromEngine).toSet()
            )
        }

        val outputs = mutableListOf<PaymentManaOutput>()
        for (leaf in leaves) {
            when (leaf) {
                is AddManaEffect -> {
                    if (leaf.restriction != null) {
                        return PaymentManaProductionProfile.Unsupported(
                            "Fixed mana output has a spending restriction"
                        )
                    }
                    val amount = (leaf.amount as? DynamicAmount.Fixed)?.amount
                        ?: return PaymentManaProductionProfile.Unsupported(
                            "Mana output amount is dynamic"
                        )
                    if (amount <= 0) {
                        return PaymentManaProductionProfile.Unsupported(
                            "Mana output amount must be positive"
                        )
                    }
                    repeat(amount) {
                        outputs += PaymentManaOutput(PaymentManaColor.fromEngine(leaf.color))
                    }
                }

                is AddColorlessManaEffect -> {
                    if (leaf.restriction != null) {
                        return PaymentManaProductionProfile.Unsupported(
                            "Fixed mana output has a spending restriction"
                        )
                    }
                    val amount = (leaf.amount as? DynamicAmount.Fixed)?.amount
                        ?: return PaymentManaProductionProfile.Unsupported(
                            "Mana output amount is dynamic"
                        )
                    if (amount <= 0) {
                        return PaymentManaProductionProfile.Unsupported(
                            "Mana output amount must be positive"
                        )
                    }
                    repeat(amount) {
                        outputs += PaymentManaOutput(PaymentManaColor.COLORLESS)
                    }
                }

                else -> return PaymentManaProductionProfile.Unsupported(
                    "Mana ability contains a non-fixed or non-mana production effect"
                )
            }
        }

        return if (outputs.size == 1) {
            PaymentManaProductionProfile.SelectableSingleOutput(setOf(outputs.single().color))
        } else {
            PaymentManaProductionProfile.FixedOutputBundle(outputs)
        }
    }

    private fun flatten(effect: Effect): List<Effect> = when (effect) {
        is CompositeEffect -> effect.effects.flatMap(::flatten)
        else -> listOf(effect)
    }
}

internal fun ManaSource.invalidatePaymentManaProductionProfiles(reason: String): ManaSource =
    copy(
        paymentManaProductionProfiles = if (paymentManaProductionProfiles.isEmpty()) {
            mapOf("__source__" to PaymentManaProductionProfile.Unsupported(reason))
        } else {
            paymentManaProductionProfiles.mapValues { PaymentManaProductionProfile.Unsupported(reason) }
        }
    )

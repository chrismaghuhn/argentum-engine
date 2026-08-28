package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.scripting.effects.AddColorlessManaEffect
import com.wingedsheep.sdk.scripting.effects.AddManaEffect
import com.wingedsheep.sdk.scripting.effects.AddManaOfChoiceEffect
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.DealDamageEffect
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.effects.ManaExpiry
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget

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

private fun flattenPaymentManaEffect(effect: Effect): List<Effect> = when (effect) {
    is CompositeEffect -> effect.effects.flatMap(::flattenPaymentManaEffect)
    else -> listOf(effect)
}

private fun isPaymentManaProductionLeaf(effect: Effect): Boolean = when (effect) {
    is AddManaEffect,
    is AddColorlessManaEffect,
    is AddManaOfChoiceEffect,
    -> true
    else -> false
}

/**
 * Exact support certificate for the non-mana part of one selected mana ability.
 *
 * This is a closure proof only. It never authorizes or executes an effect; the selected
 * [com.wingedsheep.sdk.scripting.ActivatedAbility] remains the sole execution input to
 * [ManaAbilitySideEffectExecutor].
 */
sealed interface PaymentManaSideEffectCertificate {
    /** The exact ability has no non-mana effect. */
    data object NoSideEffect : PaymentManaSideEffectCertificate

    /** The exact ability deals this fixed amount of damage to its controller. */
    data class FixedSelfDamage(
        val amount: Int,
    ) : PaymentManaSideEffectCertificate

    /** The ability's non-mana effect is outside the narrow exact support slice. */
    data class Unsupported(
        val reason: String,
    ) : PaymentManaSideEffectCertificate
}

/**
 * Whether this exact side-effect certificate is representable by the V3 ordered payment program.
 *
 * [PaymentManaSideEffectCertificate.FixedSelfDamage] is only a production-side capability marker;
 * the V3 execution-stability certificate must additionally prove that the life mutation cannot
 * change any later payment fact before the source is published.
 */
fun PaymentManaSideEffectCertificate.isSupportedByPaymentProgramV3(): Boolean = when (this) {
    PaymentManaSideEffectCertificate.NoSideEffect -> true
    is PaymentManaSideEffectCertificate.FixedSelfDamage -> amount > 0
    is PaymentManaSideEffectCertificate.Unsupported -> false
}

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
 * Resolves only the unconditional fixed mana-production leaves of the selected ability. Non-mana
 * leaves are deliberately left to [PaymentManaSideEffectCertificateResolver]. The caller supplies
 * the colors already resolved by source discovery for a single-output color-choice effect; this
 * keeps this helper on the same Rules-owned resolution path as [ManaSolver].
 */
object PaymentManaProductionProfileResolver {
    fun resolve(
        effect: Effect,
        resolvedChoiceColors: Set<Color>,
    ): PaymentManaProductionProfile {
        val leaves = flattenPaymentManaEffect(effect)
        val productionLeaves = leaves.filter(::isPaymentManaProductionLeaf)
        if (productionLeaves.isEmpty()) {
            return PaymentManaProductionProfile.Unsupported("The mana ability has no production leaf")
        }

        if (productionLeaves.any { it is AddManaOfChoiceEffect }) {
            if (productionLeaves.size != 1) {
                return PaymentManaProductionProfile.Unsupported(
                    "A multi-output ability cannot contain an unresolved color choice"
                )
            }
            val choice = productionLeaves.single() as AddManaOfChoiceEffect
            if (choice.restriction != null || choice.amount !is DynamicAmount.Fixed ||
                (choice.amount as DynamicAmount.Fixed).amount != 1 || resolvedChoiceColors.isEmpty() ||
                choice.riders.isNotEmpty() || choice.recipient != EffectTarget.Controller
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
        for (leaf in productionLeaves) {
            when (leaf) {
                is AddManaEffect -> {
                    if (leaf.restriction != null || leaf.riders.isNotEmpty() ||
                        leaf.expiry != ManaExpiry.END_OF_TURN
                    ) {
                        return PaymentManaProductionProfile.Unsupported(
                            "Fixed mana output has an unsupported restriction, rider, or expiry"
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

}

/**
 * Certifies only the exact deterministic self-damage tail supported by the payment domain.
 * Mana production is intentionally ignored here; that is [PaymentManaProductionProfileResolver]'s
 * separate responsibility.
 */
object PaymentManaSideEffectCertificateResolver {
    fun resolve(effect: Effect): PaymentManaSideEffectCertificate {
        val nonManaLeaves = flattenPaymentManaEffect(effect).filterNot(::isPaymentManaProductionLeaf)
        return when {
            nonManaLeaves.isEmpty() -> PaymentManaSideEffectCertificate.NoSideEffect
            nonManaLeaves.size != 1 -> PaymentManaSideEffectCertificate.Unsupported(
                "Mana ability has multiple non-mana side effects"
            )

            else -> certifySingle(nonManaLeaves.single())
        }
    }

    private fun certifySingle(effect: Effect): PaymentManaSideEffectCertificate = when (effect) {
        is DealDamageEffect -> {
            val target = effect.target
            val amount = (effect.amount as? DynamicAmount.Fixed)?.amount
            when {
                target !is EffectTarget.PlayerRef || target.player != Player.You ->
                    PaymentManaSideEffectCertificate.Unsupported(
                        "Self-damage certificate requires a fixed damage effect targeting Player.You"
                    )

                amount == null || amount <= 0 ->
                    PaymentManaSideEffectCertificate.Unsupported(
                        "Self-damage certificate requires a positive fixed amount"
                    )

                effect.cantBePrevented || effect.damageSource != null || effect.excessToController ->
                    PaymentManaSideEffectCertificate.Unsupported(
                        "Self-damage certificate does not support damage modifiers or source overrides"
                    )

                else -> PaymentManaSideEffectCertificate.FixedSelfDamage(amount)
            }
        }

        else -> PaymentManaSideEffectCertificate.Unsupported(
            "Mana ability contains an unsupported non-mana side effect"
        )
    }

}

internal fun ManaSource.invalidatePaymentManaProductionProfiles(reason: String): ManaSource =
    copy(
        paymentManaProductionProfiles = if (paymentManaProductionProfiles.isEmpty()) {
            mapOf("__source__" to PaymentManaProductionProfile.Unsupported(reason))
        } else {
            paymentManaProductionProfiles.mapValues { PaymentManaProductionProfile.Unsupported(reason) }
        },
        paymentManaSideEffectCertificates = if (paymentManaSideEffectCertificates.isEmpty()) {
            mapOf("__source__" to PaymentManaSideEffectCertificate.Unsupported(reason))
        } else {
            paymentManaSideEffectCertificates.mapValues {
                PaymentManaSideEffectCertificate.Unsupported(reason)
            }
        },
    )

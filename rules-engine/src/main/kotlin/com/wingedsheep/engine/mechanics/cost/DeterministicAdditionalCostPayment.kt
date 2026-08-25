package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.engine.mechanics.mana.isFixedOrdinaryManaCost

/**
 * Rules-owned certificate for the narrow deterministic activated-cost slice.
 *
 * It accepts only the authoritative effective ability cost and activated source ID. The certificate
 * proves both the canonical public acknowledgement for source-bound Tap/Sacrifice costs and that
 * every other leaf is deterministic and externally choice-free. It does not inspect legal-action
 * metadata or complete a client payload. Selection-bearing costs remain uncertified and therefore
 * fail closed at public payment-domain publication.
 */
object DeterministicAdditionalCostPayment {

    /** The non-wire proof consumed by Rules and regression tests; Gym only receives its projection. */
    internal data class Certificate(
        val additionalCostPayment: AdditionalCostPayment,
        val deterministicPayLifeExpressions: List<DynamicAmount>,
    )

    /**
     * Certify the supported activated-cost shape.
     *
     * [DynamicAmount.CommanderColorIdentityCount] is deliberately the only PayLife expression
     * admitted here. The actual amount is still resolved and paid by the existing Rules path.
     */
    internal fun certify(cost: AbilityCost, sourceId: EntityId): Certificate? {
        val counts = collect(cost) ?: return null
        if (counts.manaCount != 1 || counts.tapCount > 1 || counts.sacrificeCount > 1) return null

        return Certificate(
            additionalCostPayment = AdditionalCostPayment(
                tappedPermanents = List(counts.tapCount) { sourceId },
                sacrificedPermanents = List(counts.sacrificeCount) { sourceId },
            ),
            deterministicPayLifeExpressions = counts.deterministicPayLifeExpressions,
        )
    }

    /** Preserve the existing public acknowledgement projection for current Rules/Gym callers. */
    fun expectedFor(cost: AbilityCost, sourceId: EntityId): AdditionalCostPayment? =
        certify(cost, sourceId)?.additionalCostPayment

    private fun collect(cost: AbilityCost): Counts? = when (cost) {
        is AbilityCost.Atom -> {
            val atom = cost.atom
            when (atom) {
                is CostAtom.Mana -> if (
                    atom.cost.cmc > 0 && atom.cost.isFixedOrdinaryManaCost()
                ) {
                    Counts(manaCount = 1)
                } else {
                    null
                }

                is CostAtom.PayLife -> if (
                    atom.amount == DynamicAmount.CommanderColorIdentityCount
                ) {
                    Counts(deterministicPayLifeExpressions = listOf(atom.amount))
                } else {
                    null
                }

                else -> null
            }
        }

        AbilityCost.Tap -> Counts(tapCount = 1)
        AbilityCost.SacrificeSelf -> Counts(sacrificeCount = 1)

        is AbilityCost.Composite -> {
            // The existing activated-ability enumerator and handler treat this cost as a flat
            // composite when exposing/excluding mana sources. Keep the public certificate aligned
            // with that authoritative shape instead of certifying a recursively different path.
            if (cost.costs.any { it is AbilityCost.Composite }) return null
            cost.costs.fold(Counts()) { accumulated, child ->
                val next = collect(child) ?: return null
                Counts(
                    manaCount = accumulated.manaCount + next.manaCount,
                    tapCount = accumulated.tapCount + next.tapCount,
                    sacrificeCount = accumulated.sacrificeCount + next.sacrificeCount,
                    deterministicPayLifeExpressions = accumulated.deterministicPayLifeExpressions +
                        next.deterministicPayLifeExpressions,
                )
            }
        }

        else -> null
    }

    private data class Counts(
        val manaCount: Int = 0,
        val tapCount: Int = 0,
        val sacrificeCount: Int = 0,
        val deterministicPayLifeExpressions: List<DynamicAmount> = emptyList(),
    )
}

package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.engine.mechanics.mana.isFixedOrdinaryManaCost

/**
 * Derives the canonical public acknowledgement for the narrow source-bound activated-cost slice.
 *
 * This is deliberately a Rules-owned certificate: it accepts only the authoritative effective
 * ability cost and the activated source ID. It does not inspect legal-action metadata or complete a
 * client payload. Selection-bearing costs remain uncertified and therefore fail closed at public
 * payment-domain publication.
 */
object DeterministicAdditionalCostPayment {

    fun expectedFor(cost: AbilityCost, sourceId: EntityId): AdditionalCostPayment? {
        val counts = collect(cost) ?: return null
        if (counts.manaCount != 1 || counts.tapCount > 1 || counts.sacrificeCount > 1) return null

        return AdditionalCostPayment(
            tappedPermanents = List(counts.tapCount) { sourceId },
            sacrificedPermanents = List(counts.sacrificeCount) { sourceId },
        )
    }

    private fun collect(cost: AbilityCost): Counts? = when (cost) {
        is AbilityCost.Atom -> {
            val atom = cost.atom
            if (atom is CostAtom.Mana &&
                atom.cost.cmc > 0 &&
                atom.cost.isFixedOrdinaryManaCost()
            ) Counts(manaCount = 1) else null
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
                )
            }
        }

        else -> null
    }

    private data class Counts(
        val manaCount: Int = 0,
        val tapCount: Int = 0,
        val sacrificeCount: Int = 0,
    )
}

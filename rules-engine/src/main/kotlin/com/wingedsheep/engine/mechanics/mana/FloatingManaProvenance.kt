package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId

/** A provenance fact that is exact because the aggregate state contains only one unit. */
data class CertifiedFloatingManaUnit(
    val poolColor: PaymentManaColor,
    val sourceId: EntityId,
    val sourceSubtypes: Set<Subtype>,
)

/**
 * Rules-owned classification of the provenance metadata currently present in an ordinary pool.
 *
 * [NoTrackedProvenance] deliberately describes the state representation only. It does not claim
 * that the mana has no rules-level source identity; some producers currently do not populate the
 * aggregate provenance maps.
 */
sealed interface FloatingManaProvenanceClassification {
    data object NoTrackedProvenance : FloatingManaProvenanceClassification

    data class CertifiedSingleUnit(
        val candidate: CertifiedFloatingManaUnit,
    ) : FloatingManaProvenanceClassification

    data class Ambiguous(val reason: String) : FloatingManaProvenanceClassification

    companion object {
        fun classify(pool: ManaPoolComponent): FloatingManaProvenanceClassification {
            if (pool.restrictedMana.isNotEmpty()) {
                return Ambiguous("restricted mana is outside certified unrestricted provenance")
            }
            if (pool.manaBySource.isEmpty() && pool.manaBySubtype.isEmpty()) {
                return NoTrackedProvenance
            }

            if (pool.manaBySource.isEmpty() || pool.manaBySubtype.isEmpty()) {
                return Ambiguous("source and subtype provenance must both identify the unit")
            }

            val colorCounts = mapOf(
                PaymentManaColor.WHITE to pool.white,
                PaymentManaColor.BLUE to pool.blue,
                PaymentManaColor.BLACK to pool.black,
                PaymentManaColor.RED to pool.red,
                PaymentManaColor.GREEN to pool.green,
                PaymentManaColor.COLORLESS to pool.colorless,
            )
            if (colorCounts.values.any { it < 0 }) {
                return Ambiguous("unrestricted color counts must be non-negative")
            }
            if (pool.manaBySource.values.any { it <= 0 } || pool.manaBySubtype.values.any { it <= 0 }) {
                return Ambiguous("provenance counts must be positive")
            }

            val total = colorCounts.values.sum()
            if (total != 1) {
                return Ambiguous("certification supports exactly one unrestricted mana unit")
            }

            val color = colorCounts.entries.singleOrNull { it.value == 1 }?.key
                ?: return Ambiguous("exactly one unrestricted color must be present")
            if (colorCounts.values.count { it != 0 } != 1) {
                return Ambiguous("the single unit must have exactly one mana color")
            }

            val source = pool.manaBySource.entries.singleOrNull { it.value == 1 }
                ?: return Ambiguous("exactly one producing source must be present")
            if (pool.manaBySource.size != 1) {
                return Ambiguous("multiple producing sources are not unit-identifiable")
            }
            if (pool.manaBySubtype.values.any { it != 1 }) {
                return Ambiguous("each subtype tag must belong to the single unit")
            }

            return CertifiedSingleUnit(
                candidate = CertifiedFloatingManaUnit(
                    poolColor = color,
                    sourceId = source.key,
                    sourceSubtypes = pool.manaBySubtype.keys,
                ),
            )
        }
    }
}

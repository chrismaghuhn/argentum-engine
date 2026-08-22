package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId

/** One source partition of a certified homogeneous floating-mana pool. */
data class CertifiedFloatingManaSourceBucket(
    val sourceId: EntityId,
    val amount: Int,
)

/**
 * A complete homogeneous proof derived only from the aggregate pool counters.
 *
 * Every bucket has [poolColor] and [sourceSubtypes]. The buckets partition all current unrestricted
 * floating units by producing source; no per-unit ordering or source/subtype matrix is implied.
 */
data class CertifiedHomogeneousFloatingMana(
    val poolColor: PaymentManaColor,
    val sourceSubtypes: Set<Subtype>,
    val sourceBuckets: List<CertifiedFloatingManaSourceBucket>,
) {
    val total: Int get() = sourceBuckets.sumOf { it.amount }
}

/**
 * Rules-owned classification of the provenance metadata currently present in an ordinary pool.
 *
 * [NoTrackedProvenance] deliberately describes the state representation only. It does not claim
 * that the mana has no rules-level source identity; some producers currently do not populate the
 * aggregate provenance maps.
 */
sealed interface FloatingManaProvenanceClassification {
    data object NoTrackedProvenance : FloatingManaProvenanceClassification

    data class CertifiedHomogeneous(
        val candidate: CertifiedHomogeneousFloatingMana,
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
                return Ambiguous("source and subtype provenance must both identify the pool")
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
            if (total < 1) {
                return Ambiguous("certification requires at least one unrestricted mana unit")
            }

            val nonZeroColors = colorCounts.filterValues { it != 0 }
            if (nonZeroColors.size != 1 || nonZeroColors.values.single() != total) {
                return Ambiguous("all certified units must share one unrestricted color")
            }
            val poolColor = nonZeroColors.keys.single()

            if (pool.manaBySource.values.sum() != total) {
                return Ambiguous("source provenance must partition every unrestricted unit")
            }
            if (pool.manaBySubtype.values.any { it != total }) {
                return Ambiguous("every recorded subtype must be carried by every unit")
            }

            return CertifiedHomogeneous(
                candidate = CertifiedHomogeneousFloatingMana(
                    poolColor = poolColor,
                    sourceSubtypes = pool.manaBySubtype.keys,
                    sourceBuckets = pool.manaBySource.entries
                        .sortedBy { it.key.value }
                        .map { (sourceId, amount) ->
                            CertifiedFloatingManaSourceBucket(sourceId, amount)
                        },
                ),
            )
        }
    }
}

package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId

/** One source partition of a certified homogeneous floating-mana pool. */
data class CertifiedFloatingManaSourceBucket(
    val sourceId: EntityId,
    val amount: Int,
)

/** One exact source/color partition of a certified heterogeneous floating-mana pool. */
data class CertifiedFloatingManaSourceColorBucket(
    val sourceId: EntityId,
    val poolColor: PaymentManaColor,
    val amount: Int,
)

/** Explicit controller selection key for one Rules-owned source/color bucket. */
data class FloatingManaSourceColorKey(
    val sourceId: EntityId,
    val poolColor: PaymentManaColor,
)

/**
 * A complete homogeneous proof derived from aggregate counters or a validated detailed map.
 * Every bucket has [poolColor] and [sourceSubtypes].
 */
data class CertifiedHomogeneousFloatingMana(
    val poolColor: PaymentManaColor,
    val sourceSubtypes: Set<Subtype>,
    val sourceBuckets: List<CertifiedFloatingManaSourceBucket>,
) {
    val total: Int get() = sourceBuckets.sumOf { it.amount }
}

/**
 * A complete source/color proof. The bucket list is the authoritative external allocation domain;
 * no source/color relationship is inferred from aggregate source profiles.
 */
data class CertifiedHeterogeneousFloatingMana(
    val sourceColorBuckets: List<CertifiedFloatingManaSourceColorBucket>,
    val sourceSubtypes: Set<Subtype> = emptySet(),
) {
    val total: Int get() = sourceColorBuckets.sumOf { it.amount }
}

/** Rules-owned classification of the provenance metadata currently present in an ordinary pool. */
sealed interface FloatingManaProvenanceClassification {
    data object NoTrackedProvenance : FloatingManaProvenanceClassification

    data class CertifiedHomogeneous(
        val candidate: CertifiedHomogeneousFloatingMana,
    ) : FloatingManaProvenanceClassification

    data class CertifiedHeterogeneous(
        val candidate: CertifiedHeterogeneousFloatingMana,
    ) : FloatingManaProvenanceClassification

    data class Ambiguous(val reason: String) : FloatingManaProvenanceClassification

    companion object {
        fun classify(pool: ManaPoolComponent): FloatingManaProvenanceClassification {
            if (pool.restrictedMana.isNotEmpty()) {
                return Ambiguous("restricted mana is outside certified unrestricted provenance")
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
            val detailPresent = pool.manaBySourceAndColor.isNotEmpty()
            if (total == 0) {
                if (detailPresent || pool.manaProvenanceCompleteness == ManaProvenanceCompleteness.COMPLETE) {
                    return Ambiguous("empty detail cannot certify a complete provenance state")
                }
                return if (pool.manaBySource.isEmpty() && pool.manaBySubtype.isEmpty()) {
                    NoTrackedProvenance
                } else {
                    Ambiguous("provenance metadata has no unrestricted mana backing")
                }
            }

            val nonZeroColors = colorCounts.filterValues { it != 0 }
            if (detailPresent || pool.manaProvenanceCompleteness == ManaProvenanceCompleteness.COMPLETE) {
                if (pool.manaProvenanceCompleteness != ManaProvenanceCompleteness.COMPLETE) {
                    return Ambiguous("source/color detail is present without COMPLETE status")
                }
                val detailedBuckets = validateDetailed(pool, colorCounts)
                    ?: return Ambiguous("source/color detail does not match authoritative totals")
                val sourceSubtypes = commonSubtypeProof(pool, total)
                return if (nonZeroColors.size > 1) {
                    CertifiedHeterogeneous(
                        CertifiedHeterogeneousFloatingMana(
                            sourceColorBuckets = detailedBuckets,
                            sourceSubtypes = sourceSubtypes,
                        ),
                    )
                } else {
                    val poolColor = nonZeroColors.keys.single()
                    CertifiedHomogeneous(
                        CertifiedHomogeneousFloatingMana(
                            poolColor = poolColor,
                            sourceSubtypes = sourceSubtypes,
                            sourceBuckets = pool.manaBySource.entries
                                .sortedBy { it.key.value }
                                .map { (sourceId, amount) ->
                                    CertifiedFloatingManaSourceBucket(sourceId, amount)
                                },
                        ),
                    )
                }
            }

            if (pool.manaBySource.isEmpty() && pool.manaBySubtype.isEmpty()) {
                return NoTrackedProvenance
            }
            if (pool.manaBySource.isEmpty() || pool.manaBySubtype.isEmpty()) {
                return Ambiguous("source and subtype provenance must both identify the pool")
            }
            if (nonZeroColors.size != 1 || nonZeroColors.values.single() != total) {
                return Ambiguous("heterogeneous source/color provenance is not present")
            }
            if (pool.manaBySource.values.sum() != total) {
                return Ambiguous("source provenance must partition every unrestricted unit")
            }
            if (pool.manaBySubtype.values.any { it != total }) {
                return Ambiguous("every recorded subtype must be carried by every unit")
            }

            val poolColor = nonZeroColors.keys.single()
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

        private fun validateDetailed(
            pool: ManaPoolComponent,
            colorCounts: Map<PaymentManaColor, Int>,
        ): List<CertifiedFloatingManaSourceColorBucket>? {
            if (pool.manaBySourceAndColor.isEmpty()) return null
            if (pool.manaBySourceAndColor.values.any {
                    it.isEmpty() || it.values.any { amount -> amount <= 0 }
                }
            ) return null

            val bySource = mutableMapOf<EntityId, Int>()
            val byColor = mutableMapOf<PaymentManaColor, Int>()
            val buckets = pool.manaBySourceAndColor.entries.flatMap { (sourceId, colors) ->
                colors.entries.map { (color, amount) ->
                    bySource[sourceId] = (bySource[sourceId] ?: 0) + amount
                    byColor[color] = (byColor[color] ?: 0) + amount
                    CertifiedFloatingManaSourceColorBucket(sourceId, color, amount)
                }
            }
            if (bySource != pool.manaBySource) return null
            if (PaymentManaColor.entries.any { (byColor[it] ?: 0) != colorCounts[it] }) return null
            return buckets.sortedWith(compareBy({ it.sourceId.value }, { it.poolColor.name }))
        }

        private fun commonSubtypeProof(pool: ManaPoolComponent, total: Int): Set<Subtype> =
            if (pool.manaBySubtype.values.all { it == total }) pool.manaBySubtype.keys else emptySet()
    }
}

package com.wingedsheep.engine.state.components.player

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/**
 * Structural check shared by state mutation and payment publication. A COMPLETE marker is
 * meaningful only when the detail map partitions both the unrestricted source totals and the
 * unrestricted W/U/B/R/G/C totals exactly.
 */
internal fun hasCompleteSourceColorProvenance(
    colorCounts: Map<PaymentManaColor, Int>,
    manaBySource: Map<EntityId, Int>,
    manaBySourceAndColor: Map<EntityId, Map<PaymentManaColor, Int>>,
): Boolean {
    if (colorCounts.values.any { it < 0 } || manaBySourceAndColor.isEmpty()) return false
    if (manaBySource.values.any { it <= 0 }) return false
    if (manaBySourceAndColor.values.any {
            it.isEmpty() || it.values.any { amount -> amount <= 0 }
        }
    ) return false

    val bySource = mutableMapOf<EntityId, Int>()
    val byColor = mutableMapOf<PaymentManaColor, Int>()
    manaBySourceAndColor.forEach { (sourceId, colors) ->
        colors.forEach { (color, amount) ->
            bySource[sourceId] = (bySource[sourceId] ?: 0) + amount
            byColor[color] = (byColor[color] ?: 0) + amount
        }
    }
    return bySource == manaBySource &&
        PaymentManaColor.entries.all { (byColor[it] ?: 0) == colorCounts[it] }
}

internal fun ManaPoolComponent.hasCompleteSourceColorProvenance(): Boolean =
    manaProvenanceCompleteness == ManaProvenanceCompleteness.COMPLETE &&
        hasCompleteSourceColorProvenance(
            colorCounts = mapOf(
                PaymentManaColor.WHITE to white,
                PaymentManaColor.BLUE to blue,
                PaymentManaColor.BLACK to black,
                PaymentManaColor.RED to red,
                PaymentManaColor.GREEN to green,
                PaymentManaColor.COLORLESS to colorless,
            ),
            manaBySource = manaBySource,
            manaBySourceAndColor = manaBySourceAndColor,
        )

/**
 * Whether the Rules-owned source/color buckets account for every unrestricted unit in a pool.
 *
 * `UNKNOWN` is the safe legacy/default state. `INCOMPLETE` records that a nonempty pool passed
 * through a path that could not preserve the detailed buckets. Neither state can be used to
 * publish a heterogeneous source/color choice.
 */
@Serializable
enum class ManaProvenanceCompleteness {
    UNKNOWN,
    COMPLETE,
    INCOMPLETE,
}

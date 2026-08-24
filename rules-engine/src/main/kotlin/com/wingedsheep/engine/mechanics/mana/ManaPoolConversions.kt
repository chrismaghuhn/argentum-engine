package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.engine.state.components.player.hasCompleteFloatingManaProvenance

/**
 * The only conversion seam between the immutable ECS component and the transient payment value.
 * Keep the authoritative source/color map and completeness marker in both directions.
 */
internal fun ManaPoolComponent.toManaPool(): ManaPool = ManaPool(
    white = white,
    blue = blue,
    black = black,
    red = red,
    green = green,
    colorless = colorless,
    restrictedMana = restrictedMana,
    manaBySubtype = manaBySubtype,
    manaBySource = manaBySource,
    manaBySourceAndColor = manaBySourceAndColor,
    manaByFloatingBucket = manaByFloatingBucket,
    manaProvenanceCompleteness = manaProvenanceCompleteness,
    manaProvenanceKnownTo = manaProvenanceKnownTo,
)

internal fun fromManaPool(pool: ManaPool): ManaPoolComponent {
    if (pool.unrestrictedTotal == 0) {
        return ManaPoolComponent(
            restrictedMana = pool.restrictedMana,
        )
    }
    val complete = pool.manaProvenanceCompleteness == ManaProvenanceCompleteness.COMPLETE &&
        hasCompleteFloatingManaProvenance(
            colorCounts = mapOf(
                PaymentManaColor.WHITE to pool.white,
                PaymentManaColor.BLUE to pool.blue,
                PaymentManaColor.BLACK to pool.black,
                PaymentManaColor.RED to pool.red,
                PaymentManaColor.GREEN to pool.green,
                PaymentManaColor.COLORLESS to pool.colorless,
            ),
            manaBySource = pool.manaBySource,
            manaBySourceAndColor = pool.manaBySourceAndColor,
            manaBySubtype = pool.manaBySubtype,
            manaByFloatingBucket = pool.manaByFloatingBucket,
        )
    val completeness = when {
        complete -> ManaProvenanceCompleteness.COMPLETE
        pool.manaProvenanceCompleteness == ManaProvenanceCompleteness.UNKNOWN &&
            pool.manaBySource.isEmpty() && pool.manaBySubtype.isEmpty() &&
            pool.manaBySourceAndColor.isEmpty() && pool.manaByFloatingBucket.isEmpty() ->
            ManaProvenanceCompleteness.UNKNOWN
        else -> ManaProvenanceCompleteness.INCOMPLETE
    }
    return ManaPoolComponent(
        white = pool.white,
        blue = pool.blue,
        black = pool.black,
        red = pool.red,
        green = pool.green,
        colorless = pool.colorless,
        restrictedMana = pool.restrictedMana,
        manaBySubtype = pool.manaBySubtype,
        manaBySource = pool.manaBySource,
        manaBySourceAndColor = if (complete) pool.manaBySourceAndColor else emptyMap(),
        manaByFloatingBucket = if (complete) pool.manaByFloatingBucket else emptyMap(),
        manaProvenanceCompleteness = completeness,
        manaProvenanceKnownTo = if (complete) pool.manaProvenanceKnownTo else emptySet(),
    )
}

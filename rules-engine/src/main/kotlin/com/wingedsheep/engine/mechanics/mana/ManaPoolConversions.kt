package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.engine.state.components.player.hasCompleteSourceColorProvenance

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
    manaProvenanceCompleteness = manaProvenanceCompleteness,
)

internal fun fromManaPool(pool: ManaPool): ManaPoolComponent {
    if (pool.unrestrictedTotal == 0) {
        return ManaPoolComponent(
            restrictedMana = pool.restrictedMana,
        )
    }
    val complete = pool.manaProvenanceCompleteness == ManaProvenanceCompleteness.COMPLETE &&
        hasCompleteSourceColorProvenance(
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
        )
    val completeness = when {
        complete -> ManaProvenanceCompleteness.COMPLETE
        pool.manaProvenanceCompleteness == ManaProvenanceCompleteness.UNKNOWN &&
            pool.manaBySource.isEmpty() && pool.manaBySubtype.isEmpty() ->
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
        manaProvenanceCompleteness = completeness,
    )
}

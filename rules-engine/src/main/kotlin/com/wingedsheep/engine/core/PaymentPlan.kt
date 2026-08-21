package com.wingedsheep.engine.core

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The public mana colors used by [PaymentPlanV1]. `COLORLESS` is intentionally separate from the
 * engine's five colored [Color] values so a submitted plan cannot confuse {C} with generic mana.
 */
@Serializable
enum class PaymentManaColor {
    WHITE,
    BLUE,
    BLACK,
    RED,
    GREEN,
    COLORLESS;

    fun asEngineColor(): Color? = when (this) {
        WHITE -> Color.WHITE
        BLUE -> Color.BLUE
        BLACK -> Color.BLACK
        RED -> Color.RED
        GREEN -> Color.GREEN
        COLORLESS -> null
    }

    companion object {
        fun fromEngine(color: Color?): PaymentManaColor = when (color) {
            Color.WHITE -> WHITE
            Color.BLUE -> BLUE
            Color.BLACK -> BLACK
            Color.RED -> RED
            Color.GREEN -> GREEN
            null -> COLORLESS
        }
    }
}

/** A single explicit source activation in a submitted payment plan. */
@Serializable
data class SourceActivation(
    val sourceId: EntityId,
    /** Stable semantic identity; this is never the runtime [com.wingedsheep.sdk.scripting.AbilityId]. */
    val manaAbilityKey: String,
    val productionChoice: ProductionChoice,
    /** Reserved for a future V1 extension; non-null values are rejected by the current slice. */
    val secondaryChoices: JsonObject? = null,
)

/** Exact production selected for one [SourceActivation]. */
@Serializable
data class ProductionChoice(
    val producedColor: PaymentManaColor,
    val amount: Int = 1,
    /** Reserved for an explicit bonus/any-color choice; unsupported in the first slice. */
    val bonusChoice: PaymentManaColor? = null,
)

/**
 * Explicit amount of unrestricted pool mana selected by a plan. The [restricted] map is keyed by
 * a future stable public bucket identity; the ordinary V1 slice rejects non-empty restricted data.
 */
@Serializable
data class PoolSpend(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
    val restricted: Map<String, Int> = emptyMap(),
) {
    fun amount(color: PaymentManaColor): Int = when (color) {
        PaymentManaColor.WHITE -> white
        PaymentManaColor.BLUE -> blue
        PaymentManaColor.BLACK -> black
        PaymentManaColor.RED -> red
        PaymentManaColor.GREEN -> green
        PaymentManaColor.COLORLESS -> colorless
    }

    fun total(): Int = white + blue + black + red + green + colorless

    companion object {
        fun fromAmounts(amounts: Map<PaymentManaColor, Int>): PoolSpend = PoolSpend(
            white = amounts[PaymentManaColor.WHITE] ?: 0,
            blue = amounts[PaymentManaColor.BLUE] ?: 0,
            black = amounts[PaymentManaColor.BLACK] ?: 0,
            red = amounts[PaymentManaColor.RED] ?: 0,
            green = amounts[PaymentManaColor.GREEN] ?: 0,
            colorless = amounts[PaymentManaColor.COLORLESS] ?: 0,
        )
    }
}

/** One or more mana units allocated to one concrete cost symbol. */
@Serializable
data class CostUnitAllocation(
    val symbolIndex: Int,
    val spends: List<ManaSpendReference>,
)

/**
 * Exact spend/allocation choices. The first slice consumes only [costUnits]. The other fields are
 * deliberately present in the contract so a future extension cannot silently move an X,
 * restricted-mana, or rider decision back into the rules solver.
 */
@Serializable
data class SpendAllocation(
    val costUnits: List<CostUnitAllocation> = emptyList(),
    val x: List<ManaSpendReference> = emptyList(),
    val restricted: List<ManaSpendReference> = emptyList(),
    val riderBearingSourceIds: List<EntityId> = emptyList(),
)

/** A concrete origin for one unit of mana spent on a cost symbol. */
@Serializable
data class ManaSpendReference(
    val sourceId: EntityId? = null,
    val poolColor: PaymentManaColor? = null,
    val amount: Int = 1,
    val restrictedBucketKey: String? = null,
)

/**
 * Complete externally selected payment for the ordinary fixed-cost Gym slice.
 *
 * The rules engine validates every field and then materializes the exact source activations and
 * pool spends. It never fills in a missing production color, source, or generic allocation.
 */
@Serializable
data class PaymentPlanV1(
    val sourceActivations: List<SourceActivation> = emptyList(),
    val poolSpend: PoolSpend = PoolSpend(),
    val spendAllocation: SpendAllocation = SpendAllocation(),
)

package com.wingedsheep.sdk.scripting

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/**
 * Represents the player's choice for alternative payment methods like Delve, Convoke, Improvise
 * and Waterbend.
 *
 * These are specified when casting a spell or activating an ability and affect how the mana
 * cost is paid:
 * - **Delve**: Exile cards from graveyard, each pays {1} generic mana
 * - **Convoke**: Tap creatures, each pays {1} or one mana of the creature's color
 * - **Harmonize**: Tap a single creature you control to reduce the (harmonize) cost
 *   by an amount of generic mana equal to its power
 * - **Tap-for-generic** ([tapForGenericPermanents]): the one rail shared by every "tap untapped
 *   permanents you control, each paying {1} *generic*" mechanic. Which permanents are eligible
 *   and how many taps are allowed is decided by the mechanic that is being paid, not by this
 *   field:
 *   - **Improvise** (CR 702.126) — artifacts only, bounded by the generic mana in the spell's
 *     total cost.
 *   - **Waterbend** (Avatar: The Last Airbender) — artifacts *and* creatures, bounded by the
 *     waterbend amount {N}.
 *   Unlike Convoke, a permanent tapped this way never pays a colored pip.
 *
 * @property delvedCards Cards to exile from graveyard for Delve payment
 * @property convokedCreatures Creatures to tap for Convoke payment, with color choice
 * @property harmonizeCreature Single creature tapped for Harmonize, reducing the generic
 *           portion of the cost by its power. Null when no creature is tapped.
 * @property tapForGenericPermanents Untapped permanents you control tapped to pay generic mana —
 *           each pays {1} generic. Used by Improvise (artifacts) and Waterbend
 *           (artifacts/creatures); the paying mechanic supplies the eligibility filter and cap.
 * @property equipPayment Explicit payment mode for an equip ability when a static permission
 *           offers an alternative equip cost. This is kept alongside the other action-level
 *           payment choices so the selected mode is serialized with the action and replayed
 *           verbatim.
 */
@Serializable
data class AlternativePaymentChoice(
    val delvedCards: List<EntityId> = emptyList(),
    val convokedCreatures: Map<EntityId, ConvokePayment> = emptyMap(),
    val harmonizeCreature: EntityId? = null,
    val tapForGenericPermanents: Set<EntityId> = emptySet(),
    val equipPayment: EquipPaymentChoice? = null
) {
    /** Whether this choice contains a Delve/Convoke/Harmonize/tap-for-generic payment. */
    val hasResourcePayment: Boolean
        get() = delvedCards.isNotEmpty() || convokedCreatures.isNotEmpty() ||
            harmonizeCreature != null || tapForGenericPermanents.isNotEmpty()

    /**
     * Whether no payment choice was supplied.
     */
    val isEmpty: Boolean
        get() = !hasResourcePayment && equipPayment == null

    /**
     * Total generic mana reduction from Delve.
     */
    val delveReduction: Int
        get() = delvedCards.size

    /**
     * Total generic mana reduction from Convoke (creatures paying generic).
     */
    val convokeGenericReduction: Int
        get() = convokedCreatures.values.count { it.color == null }

    /**
     * Get Convoke reduction for a specific color.
     */
    fun convokeColorReduction(color: Color): Int =
        convokedCreatures.values.count { it.color == color }

    companion object {
        val NONE = AlternativePaymentChoice()
    }
}

/**
 * Explicit mode for an equip ability's cost when a [FreeFirstEquipEachTurn] permission is active.
 * [NORMAL] pays the effective printed equip cost; [FREE_FIRST_EQUIP] replaces the entire equip
 * cost with {0}. The engine never infers this choice from the available mana.
 */
@Serializable
enum class EquipPaymentChoice {
    NORMAL,
    FREE_FIRST_EQUIP,
}

/**
 * Represents how a creature is being used to pay via Convoke.
 *
 * @property color The color of mana this creature is paying for.
 *                 If null, the creature pays for {1} generic mana instead.
 */
@Serializable
data class ConvokePayment(
    val color: Color? = null  // null = pays for generic
)

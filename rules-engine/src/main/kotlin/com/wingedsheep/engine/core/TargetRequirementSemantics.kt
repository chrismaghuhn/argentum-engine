package com.wingedsheep.engine.core

import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.predicates.CardPredicate
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.targets.TargetOther
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import com.wingedsheep.sdk.scripting.values.DynamicAmount

/**
 * The semantic fields shared by pending target metadata and the public target domain.
 *
 * This is deliberately built at the [TargetRequirement] source boundary. A pending decision may
 * wrap a [TargetObject] in [TargetOther], so consumers must not infer these fields by reading only
 * the outer requirement or by treating absent values as authoritative defaults.
 */
internal data class TargetRequirementSemantics(
    val targetZone: String?,
    val mustDifferFromEarlier: Boolean,
    val sameController: Boolean,
    val sameOwner: Boolean,
    val sameCreatureType: Boolean,
    val sameCardType: Boolean,
    val totalManaValueAtMost: Int?,
    val differentNames: Boolean,
    val xConstrainsManaValue: Boolean,
    val xConstrainsManaValueExactly: Boolean,
    val xConstrainsPower: Boolean,
    val xConstrainsCount: Boolean,
) {
    companion object {
        fun inspect(
            requirement: TargetRequirement,
            resolvedTotalManaValueAtMost: Int? = null,
        ): TargetRequirementSemantics {
            val targetObject = requirement.targetObjectOrNull()
            val totalManaValueAtMost = targetObject?.totalManaValueAtMost?.let { dynamicAmount ->
                resolvedTotalManaValueAtMost ?: when (dynamicAmount) {
                    is DynamicAmount.Fixed -> dynamicAmount.amount
                    else -> error(
                        "Cannot publish pending target metadata: total mana value cap " +
                            "${dynamicAmount.description} has not been resolved"
                    )
                }
            }
            require(totalManaValueAtMost == null || totalManaValueAtMost >= 0) {
                "Pending target metadata requires a non-negative total mana value cap"
            }

            return TargetRequirementSemantics(
                targetZone = targetObject?.filter?.publicTargetZone(),
                mustDifferFromEarlier = requirement.containsTargetOther(),
                sameController = targetObject?.sameController == true,
                sameOwner = targetObject?.sameOwner == true,
                sameCreatureType = targetObject?.sameCreatureType == true,
                sameCardType = targetObject?.sameCardType == true,
                totalManaValueAtMost = totalManaValueAtMost,
                differentNames = targetObject?.differentNames == true,
                xConstrainsManaValue = targetObject?.filter?.containsTargetCardPredicate {
                    it == CardPredicate.ManaValueAtMostX
                } == true,
                xConstrainsManaValueExactly = targetObject?.filter?.containsTargetCardPredicate {
                    it == CardPredicate.ManaValueEqualsX
                } == true,
                xConstrainsPower = targetObject?.filter?.containsTargetCardPredicate {
                    it == CardPredicate.PowerEqualsX
                } == true,
                xConstrainsCount = targetObject?.dynamicMaxCount == DynamicAmount.XValue,
            )
        }
    }
}

private fun TargetRequirement.targetObjectOrNull(): TargetObject? = when (this) {
    is TargetObject -> this
    is TargetOther -> baseRequirement.targetObjectOrNull()
    else -> null
}

private fun TargetRequirement.containsTargetOther(): Boolean = when (this) {
    is TargetOther -> true
    else -> false
}

private fun TargetFilter.publicTargetZone(): String? {
    if (isUnion) return null
    return when (zone) {
        Zone.GRAVEYARD -> "Graveyard"
        Zone.STACK -> "Stack"
        Zone.EXILE -> "Exile"
        Zone.HAND -> "Hand"
        Zone.LIBRARY -> "Library"
        Zone.COMMAND -> "Command"
        Zone.BATTLEFIELD, Zone.SIDEBOARD -> null
    }
}

private fun TargetFilter.containsTargetCardPredicate(predicate: (CardPredicate) -> Boolean): Boolean =
    clauses().any { clause -> clause.baseFilter.containsTargetCardPredicate(predicate) }

private fun GameObjectFilter.containsTargetCardPredicate(
    predicate: (CardPredicate) -> Boolean,
): Boolean =
    cardPredicates.any { it.containsTargetCardPredicate(predicate) } ||
        anyOf.any { it.containsTargetCardPredicate(predicate) }

private fun CardPredicate.containsTargetCardPredicate(predicate: (CardPredicate) -> Boolean): Boolean =
    predicate(this) || when (this) {
        is CardPredicate.And -> predicates.any { it.containsTargetCardPredicate(predicate) }
        is CardPredicate.Or -> predicates.any { it.containsTargetCardPredicate(predicate) }
        is CardPredicate.Not -> this.predicate.containsTargetCardPredicate(predicate)
        else -> false
    }

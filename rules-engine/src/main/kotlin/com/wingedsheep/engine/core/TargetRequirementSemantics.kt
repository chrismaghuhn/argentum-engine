package com.wingedsheep.engine.core

import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.predicates.CardPredicate
import com.wingedsheep.sdk.scripting.targets.AnyTarget
import com.wingedsheep.sdk.scripting.targets.TargetCreatureOrPlaneswalker
import com.wingedsheep.sdk.scripting.targets.TargetCreatureOrPlayer
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.targets.TargetOther
import com.wingedsheep.sdk.scripting.targets.TargetOpponent
import com.wingedsheep.sdk.scripting.targets.TargetOpponentOrPlaneswalker
import com.wingedsheep.sdk.scripting.targets.TargetPermanentOrPlayer
import com.wingedsheep.sdk.scripting.targets.TargetPlayer
import com.wingedsheep.sdk.scripting.targets.TargetPlayerOrPlaneswalker
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import com.wingedsheep.sdk.scripting.targets.TargetSpellOrPermanent
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
            semanticSource: TargetRequirement = requirement,
            resolvedTotalManaValueAtMost: ResolvedTotalManaValueAtMost? = null,
        ): TargetRequirementSemanticsResult {
            val targetObject = semanticSource.targetObjectOrNull()
            val requirementAggregate = requirement.totalManaValueAtMostOrNull()
            val semanticSourceAggregate = semanticSource.totalManaValueAtMostOrNull()
            val hasUnresolvedAggregate = listOfNotNull(
                requirementAggregate,
                semanticSourceAggregate,
            ).any { it !is DynamicAmount.Fixed }
            if (hasUnresolvedAggregate && resolvedTotalManaValueAtMost == null) {
                return TargetRequirementSemanticsResult.Unsupported(
                    TargetRequirementUnsupportedReason.UNRESOLVED_TOTAL_MANA_VALUE
                )
            }
            val aggregateSource = semanticSourceAggregate ?: requirementAggregate
            val totalManaValueAtMost = aggregateSource?.let { dynamicAmount ->
                resolvedTotalManaValueAtMost?.value ?: when (dynamicAmount) {
                    is DynamicAmount.Fixed -> dynamicAmount.amount
                    else -> return TargetRequirementSemanticsResult.Unsupported(
                        TargetRequirementUnsupportedReason.UNRESOLVED_TOTAL_MANA_VALUE
                    )
                }
            }
            if (totalManaValueAtMost != null && totalManaValueAtMost < 0) {
                return TargetRequirementSemanticsResult.Unsupported(
                    TargetRequirementUnsupportedReason.INVALID_TOTAL_MANA_VALUE
                )
            }

            return TargetRequirementSemanticsResult.Supported(TargetRequirementSemantics(
                targetZone = targetObject?.filter?.publicTargetZone(),
                mustDifferFromEarlier = semanticSource.containsTargetOther(),
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
            ))
        }
    }
}

internal sealed interface TargetRequirementSemanticsResult {
    data class Supported(val semantics: TargetRequirementSemantics) : TargetRequirementSemanticsResult

    data class Unsupported(val reason: TargetRequirementUnsupportedReason) : TargetRequirementSemanticsResult
}

private fun TargetRequirement.targetObjectOrNull(): TargetObject? = when (this) {
    is TargetObject -> this
    is TargetOther -> baseRequirement.targetObjectOrNull()
    is TargetPlayer,
    is TargetOpponent,
    is AnyTarget,
    is TargetCreatureOrPlayer,
    is TargetPermanentOrPlayer,
    is TargetOpponentOrPlaneswalker,
    is TargetPlayerOrPlaneswalker,
    is TargetCreatureOrPlaneswalker,
    is TargetSpellOrPermanent -> null
}

/** Read the source aggregate cap without losing a TargetObject wrapped by TargetOther. */
internal fun TargetRequirement.totalManaValueAtMostOrNull(): DynamicAmount? =
    targetObjectOrNull()?.totalManaValueAtMost

/** True when the requirement still carries a dynamic cap that has not been snapshotted. */
internal fun TargetRequirement.hasUnresolvedDynamicMaxCount(): Boolean =
    targetObjectOrNull()?.dynamicMaxCount != null

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

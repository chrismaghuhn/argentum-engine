package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.sdk.core.ManaCost

/**
 * Shared structural qualification for the narrow ExplicitV3 alternative-cast slice. The Rules
 * enumerator must already have selected and resolved the alternative cost; callers additionally
 * provide the Rules-owned proof that the chosen targets do not change the payment cost.
 */
fun isResolvedFixedAlternativeCastPayment(
    action: CastSpell,
    effectiveCost: ManaCost,
    hasTargetDependentCost: Boolean = false,
    hasApplicableAdditionalCost: Boolean = false,
): Boolean {
    if (!action.useAlternativeCost || action.alternativeCostType == null) return false
    if (!effectiveCost.isFixedOrdinaryManaCost()) return false
    if (hasTargetDependentCost || hasApplicableAdditionalCost) return false
    if (action.alternativePayment != null ||
        action.additionalCostPayment != null ||
        action.xValue != null ||
        action.castFaceDown ||
        action.declaredCostSlot != null ||
        action.wasWaterbendPaid ||
        action.giftRecipient != null ||
        action.splicedCardIds.isNotEmpty() ||
        action.chosenModes.isNotEmpty() ||
        action.modeTargetsOrdered.isNotEmpty() ||
        action.modeTargetRequirementsOrdered.isNotEmpty() ||
        action.conspiredCreatures.isNotEmpty() ||
        action.casualtyCreature != null ||
        action.graveyardCastRider != null ||
        action.faceIndex != null ||
        action.useWithoutPayingManaCost
    ) return false

    return true
}

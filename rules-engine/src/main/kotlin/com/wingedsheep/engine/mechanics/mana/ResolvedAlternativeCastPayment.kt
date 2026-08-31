package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.sdk.core.ManaCost

/**
 * Shared structural qualification for the narrow ExplicitV3 alternative-cast slice. The Rules
 * enumerator must already have selected and resolved the alternative cost; this helper only proves
 * that the resulting action/cost shape contains no applicable additional payment.
 */
fun isResolvedFixedAlternativeCastPayment(
    action: CastSpell,
    effectiveCost: ManaCost,
    hasUnresolvedTargetChoice: Boolean = false,
    hasApplicableAdditionalCost: Boolean = false,
): Boolean {
    if (!action.useAlternativeCost || action.alternativeCostType == null) return false
    if (!effectiveCost.isFixedOrdinaryManaCost()) return false
    if (hasUnresolvedTargetChoice || hasApplicableAdditionalCost) return false
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
        action.useWithoutPayingManaCost ||
        action.targets.isNotEmpty()
    ) return false

    return true
}

package com.wingedsheep.gym.contract

import com.wingedsheep.engine.legalactions.LegalAction

/**
 * Identifies legal-action entries whose engine action is a template and therefore cannot be
 * committed by an action ID alone. The caller must copy [LegalActionView.actionSemantics], fill
 * the exposed choice fields, and use the structured step input. No target, payment, mode, or
 * other player choice is selected here.
 */
object ActionPayloadRequirements {
    fun requiresStructuredAction(action: LegalAction): Boolean =
        action.requiresTargets ||
            action.manaCostString != null ||
            action.hasXCost ||
            action.additionalCostInfo != null ||
            action.hasConvoke ||
            action.hasDelve ||
            action.hasTapForGeneric ||
            action.hasHarmonize ||
            action.requiresManaColorChoice ||
            action.requiresDamageDistribution ||
            action.tapForPower ||
            action.maxRepeatableActivations != null ||
            action.requiresForage ||
            action.additionalLifeCost > 0 ||
            action.modalEnumeration != null ||
            action.autoTapPreview != null
}

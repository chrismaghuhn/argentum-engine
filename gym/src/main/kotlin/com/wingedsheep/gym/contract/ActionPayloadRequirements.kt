package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.legalactions.LegalAction
import kotlinx.serialization.json.JsonObject

/**
 * Identifies legal-action entries whose engine action is a template and therefore cannot be
 * committed by an action ID alone. The caller must copy [LegalActionView.actionSemantics], fill
 * every required choice field, and use the structured step input. No target, payment, mode,
 * combat declaration, or other player choice is selected here.
 */
object ActionPayloadRequirements {

    /** The external JSON fields that must be present, even when their value is an explicit empty choice. */
    fun requiredPayloadFields(action: LegalAction): Set<String> = buildSet {
        if (action.requiresTargets) add("targets")
        if (action.hasXCost) add("xValue")
        if (action.manaCostString != null || action.autoTapPreview != null) add("paymentStrategy")
        if (action.additionalCostInfo != null) add(additionalPaymentField(action.action))
        if (action.hasConvoke || action.hasDelve || action.hasTapForGeneric || action.hasHarmonize) {
            add("alternativePayment")
        }
        val activateAbility = action.action as? ActivateAbility
        if (activateAbility?.alternativePayment?.equipPayment != null) {
            add("alternativePayment")
        }
        if (action.requiresManaColorChoice) add("manaColorChoice")
        if (action.requiresDamageDistribution) add("damageDistribution")
        when (action.action) {
            // Crew and Saddle are their own action shapes. Their selected creatures are not an
            // AdditionalCostPayment, so mapping tapForPower through the generic spell/ability
            // payment field would advertise a payload that the action decoder silently ignores.
            is CrewVehicle -> add("crewCreatures")
            is SaddleMount -> add("saddleCreatures")
            else -> if (action.tapForPower) add(additionalPaymentField(action.action))
        }
        if (action.maxRepeatableActivations != null) add("repeatCount")
        if (action.requiresForage) add("additionalCostPayment")
        if (action.additionalLifeCost > 0) add("graveyardLifeCost")
        if (action.modalEnumeration != null) {
            add("chosenModes")
            add("modeTargetsOrdered")
        }

        // Empty combat maps/lists are valid choices, but they must be submitted explicitly. The
        // default constructors otherwise silently declare no attackers/blockers/order.
        when (action.action) {
            is DeclareAttackers -> {
                add("attackers")
                add("bands")
            }
            is DeclareBlockers -> add("blockers")
            is OrderBlockers -> add("orderedBlockers")
            else -> Unit
        }
    }

    fun requiresStructuredAction(action: LegalAction): Boolean =
        requiredPayloadFields(action).isNotEmpty()

    /** Returns missing keys without decoding or executing the candidate. */
    fun missingRequiredFields(action: LegalAction, payload: JsonObject): List<String> =
        requiredPayloadFields(action).filterNot(payload::containsKey)

    private fun additionalPaymentField(action: GameAction): String = when (action) {
        is ActivateAbility -> "costPayment"
        is CastSpell -> "additionalCostPayment"
        else -> "additionalCostPayment"
    }
}

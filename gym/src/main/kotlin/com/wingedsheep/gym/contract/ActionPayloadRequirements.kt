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
import com.wingedsheep.engine.legalactions.TargetDomainSupport
import kotlinx.serialization.json.JsonObject

/**
 * Gym-owned canonical payload-requirement projection over the Rules-owned [LegalAction] contract.
 * It identifies legal-action entries whose engine action is a template and therefore cannot be
 * committed by an action ID alone. The caller must copy [LegalActionView.actionSemantics], fill
 * every required choice field, and use the structured step input. No target, payment, mode,
 * combat declaration, or other player choice is selected here.
 */
object ActionPayloadRequirements {

    /**
     * Stable wire order for structured payload fields. The collector below deliberately remains a
     * set because multiple independent rules can require the same field; this list makes the public
     * result deterministic without coupling its order to conditional-branch order.
     */
    private val canonicalFieldOrder = listOf(
        "targets",
        "xValue",
        "paymentStrategy",
        "additionalCostPayment",
        "costPayment",
        "alternativePayment",
        "manaColorChoice",
        "damageDistribution",
        "crewCreatures",
        "saddleCreatures",
        "repeatCount",
        "graveyardLifeCost",
        "chosenModes",
        "modeTargetsOrdered",
        "attackers",
        "bands",
        "blockers",
        "orderedBlockers",
    )
    private val canonicalFieldSet = canonicalFieldOrder.toSet()

    /** The external JSON fields that must be present, even when their value is an explicit empty choice. */
    fun requiredPayloadFields(action: LegalAction): List<String> {
        return canonicalizeRequiredPayloadFields(requiredFieldSet(action))
    }

    /**
     * Applies stable wire ordering and rejects fields that have no canonical wire position.
     * Keeping this check at the shared projection seam makes both observation and trusted
     * validation fail closed when a new requirement is added without updating the wire contract.
     */
    internal fun canonicalizeRequiredPayloadFields(requiredFields: Set<String>): List<String> {
        val unknown = requiredFields - canonicalFieldSet
        check(unknown.isEmpty()) {
            "Missing canonical required-payload field(s): ${unknown.sorted()}"
        }
        return canonicalFieldOrder.filter(requiredFields::contains)
    }

    private fun requiredFieldSet(action: LegalAction): Set<String> = buildSet {
        when (val targetPayload = TargetPayloadPartition.certify(action)) {
            is TargetPayloadPartition.Certification.Supported -> {
                if (targetPayload.acceptsNonEmptyPayload) add("targets")
            }
            is TargetPayloadPartition.Certification.Unsupported -> {
                // Keep the field discoverable for an incomplete legacy template, but do not
                // treat it as executable. The trusted execution seam rejects the action below.
                if (action.requiresTargets || action.targetRequirements.isNotEmpty()) add("targets")
            }
        }
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

    /**
     * Enforce the V1 target contract at the server-owned action seam.
     *
     * The policy does not echo a target domain. The registry's [LegalAction] is authoritative;
     * both its exhaustive Rules projection and the pure flat-payload certification must pass
     * before an existing `GameAction.targets` payload is decoded or executed.
     */
    fun requireTargetDomainSupported(action: LegalAction) {
        when (val support = action.targetDomainSupport) {
            TargetDomainSupport.SUPPORTED -> Unit
            is TargetDomainSupport.UNSUPPORTED ->
                throw IllegalArgumentException(
                    "Action target domain is unsupported: ${support.reason.name}"
                )
        }

        when (val certification = TargetPayloadPartition.certify(action)) {
            is TargetPayloadPartition.Certification.Supported -> Unit
            is TargetPayloadPartition.Certification.Unsupported ->
                throw IllegalArgumentException(
                    "Action target payload partition is unsupported: ${certification.reason.name}"
                )
        }
    }

    /**
     * Validate the target list carried by a caller-completed [GameAction] against the registered
     * action's certified flat payload partition. This is structural validation only; final target
     * identity and rules legality remain the responsibility of the engine.
     */
    fun requireTargetPayloadPartition(action: LegalAction, submitted: GameAction) {
        val payloadLength = when (submitted) {
            is CastSpell -> submitted.targets.size
            is ActivateAbility -> submitted.targets.size
            else -> 0
        }
        when (val partition = TargetPayloadPartition.partition(action.targetRequirements, payloadLength)) {
            is TargetPayloadPartition.PayloadPartition.Accepted -> Unit
            is TargetPayloadPartition.PayloadPartition.Rejected ->
                throw IllegalArgumentException(
                    "Submitted target payload does not match the certified action partition: " +
                        partition.reason.name
                )
        }
    }

    private fun additionalPaymentField(action: GameAction): String = when (action) {
        is ActivateAbility -> "costPayment"
        is CastSpell -> "additionalCostPayment"
        else -> "additionalCostPayment"
    }
}

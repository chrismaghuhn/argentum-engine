package com.wingedsheep.gym

import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.gym.contract.CardSelectionDomain
import com.wingedsheep.gym.contract.ActionTargetComposition
import com.wingedsheep.gym.contract.BLOCKER_DECLARATION_DOMAIN_VERSION
import com.wingedsheep.gym.contract.BlockRequirementV1
import com.wingedsheep.gym.contract.BlockerDeclarationDomainV1
import com.wingedsheep.gym.contract.CombatResolutionDomain
import com.wingedsheep.gym.contract.DistributionDomain
import com.wingedsheep.gym.contract.ATTACK_DECLARATION_DOMAIN_V2_VERSION
import com.wingedsheep.gym.contract.AttackDeclarationDomainV2
import com.wingedsheep.gym.contract.ACTION_TARGET_DOMAIN_VERSION
import com.wingedsheep.gym.contract.ModeSelectionDomain
import com.wingedsheep.gym.contract.ManaSourcesDomain
import com.wingedsheep.gym.contract.MANA_SOURCES_DOMAIN_VERSION
import com.wingedsheep.gym.contract.OrderingDomain
import com.wingedsheep.gym.contract.PendingDecisionView
import com.wingedsheep.gym.contract.ReorderLibraryDomain
import com.wingedsheep.gym.contract.ReplacementDomain
import com.wingedsheep.gym.contract.SearchLibraryDomain
import com.wingedsheep.gym.contract.SplitPilesDomain
import com.wingedsheep.gym.contract.StructuredCardInfo
import com.wingedsheep.gym.contract.StructuredDecisionDomain
import com.wingedsheep.gym.contract.TargetsDomain
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AlternativePaymentChoice
import com.wingedsheep.sdk.scripting.EquipPaymentChoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * State held by the test-only external chooser.
 *
 * The seed is deliberately explicit even though this policy currently uses a stable ordering
 * rather than random sampling. That makes the policy contract ready for a seeded tie-breaker
 * without introducing an implicit source of entropy.
 */
data class DeterministicPolicyState(
    val policySeed: Long,
    val choiceOrdinal: Long = 0L,
) {
    fun afterChoice(): DeterministicPolicyState = copy(choiceOrdinal = choiceOrdinal + 1L)
}

/**
 * Neutral semantic choice values returned by the observation-only policy.
 *
 * The action handle is carried only as a transport reference for the current observation. The
 * semantic key is computed from public action semantics and is what the policy uses to order
 * candidates. Decision IDs are intentionally absent here; the harness binds the public choice to
 * the current decision nonce immediately before submission.
 */
sealed interface SemanticChoice {
    val family: String

    data class Action(
        val actionId: Int,
        val semanticKey: String,
        val kind: String,
        val payload: JsonObject?,
    ) : SemanticChoice {
        override val family: String = if (kind == "DECISION") "DECISION" else kind
    }

    data class Structured(
        override val family: String,
        val selection: SemanticDecision,
    ) : SemanticChoice

    data class Gap(
        override val family: String,
        val code: String,
        val reason: String,
        val actionKind: String? = null,
        val classification: String = "A5_DECISION_GAP",
        val diagnostic: String = code,
        val publicDomain: String = "not captured",
        val proposedFollowUp: String = "Expose a complete public domain for this choice",
    ) : SemanticChoice
}

/** Decision payloads independent of engine decision IDs. */
sealed interface SemanticDecision {
    data class Targets(val selected: Map<Int, List<EntityId>>) : SemanticDecision
    data class Cards(val selected: List<EntityId>) : SemanticDecision
    data class Modes(val selected: List<Int>) : SemanticDecision
    data class Color(val selected: com.wingedsheep.sdk.core.Color) : SemanticDecision
    data class Number(val selected: Int) : SemanticDecision
    data class Distribution(val selected: Map<EntityId, Int>) : SemanticDecision
    data class Ordered(val selected: List<EntityId>) : SemanticDecision
    data class Piles(val selected: List<List<EntityId>>) : SemanticDecision
    data class Option(val selected: Int) : SemanticDecision
    data class Replacement(val from: Int, val to: Int) : SemanticDecision
    data class Budget(val selected: List<Int>) : SemanticDecision
    data class Damage(val selected: List<EdgeAmount>) : SemanticDecision
    /** Complete public pending-payment program; the live decision nonce is bound only on submit. */
    data class Payment(val paymentPlan: PaymentPlanV3) : SemanticDecision
}

/** Bind a public semantic pending-payment choice to one current live decision nonce at submission. */
internal fun SemanticDecision.Payment.toDecisionResponse(
    decisionId: String,
): ManaSourcesSelectedResponse = ManaSourcesSelectedResponse(
    decisionId = decisionId,
    paymentPlan = paymentPlan,
)

data class EdgeAmount(val edgeId: String, val amount: Int)

/**
 * Required payload fields for which this deliberately small acceptance policy has an explicit
 * public construction rule.  Keeping this allowlist separate from the generic semantic payload
 * echo is important: a newly added required field must not become accepted merely because the
 * transport template happens to contain a value for it.
 */
internal val EXTERNAL_POLICY_SUPPORTED_REQUIRED_PAYLOAD_FIELDS = setOf(
    "attackers",
    "bands",
    "blockers",
    "paymentStrategy",
    "xValue",
    "targets",
    "manaColorChoice",
    "additionalCostPayment",
    "costPayment",
    "repeatCount",
)

private const val MAX_PUBLIC_BLOCKER_OPTIONS_PER_BLOCKER = 1_000_000
private const val MAX_PUBLIC_BLOCKER_SEARCH_NODES = 4_000_000L

/**
 * Deterministic controller for the exact-pair acceptance corpus.
 *
 * This class intentionally has no environment, rules state, registry, action registry, or
 * diagnostic-ledger dependency. Its only input is the wire observation and a small deterministic
 * policy state.
 */
class DeterministicExternalPolicy {

    fun choose(
        observation: TrainingObservation,
        policyState: DeterministicPolicyState,
    ): SemanticChoice {
        if (observation.agentToAct == null) {
            return SemanticChoice.Gap(
                family = "TERMINAL",
                code = "A5_DECISION_GAP",
                reason = "No acting player was published for a nonterminal policy call",
            )
        }

        val pending = observation.pendingDecision
        if (pending != null && pending.requiresStructuredResponse) {
            return chooseStructured(observation, pending, policyState)
        }

        val candidates = observation.legalActions
            .filter { it.affordable || it.isDecisionOption }
            .sortedWith(
                compareBy(
                    { if (isPass(it.kind, it.description)) 1 else 0 },
                    { it.kind },
                    { canonical(it.actionSemantics) },
                    { it.sourceEntityId?.value ?: "" },
                    { it.targetEntityIds.joinToString(",") { id -> id.value } },
                )
            )
        val selected = candidates.firstOrNull()
            ?: return SemanticChoice.Gap(
                family = pending?.kind?.name ?: "PRIORITY",
                code = "A5_DECISION_GAP",
                reason = "No externally selectable legal action was published",
            )

        return chooseAction(observation, selected)
    }

    private fun chooseAction(
        observation: TrainingObservation,
        action: com.wingedsheep.gym.contract.LegalActionView,
    ): SemanticChoice {
        val semanticKey = canonical(action.actionSemantics)
        val requiredFields = action.requiredPayloadFields
        val requiredFieldSet = requiredFields.toSet()
        if (action.requiresStructuredAction != requiredFields.isNotEmpty()) {
            return SemanticChoice.Gap(
                family = action.kind,
                code = "A5_DECISION_GAP",
                reason =
                    "Public structured-action marker disagrees with requiredPayloadFields",
                actionKind = action.kind,
                publicDomain = "requiredPayloadFields=$requiredFields",
            )
        }
        if (!action.requiresStructuredAction) {
            return SemanticChoice.Action(action.actionId, semanticKey, action.kind, null)
        }

        if ("damageDistribution" in requiredFieldSet) {
            return SemanticChoice.Gap(
                family = "DAMAGE_ASSIGNMENT",
                code = "A5_DECISION_GAP",
                reason = "Flat action does not publish a complete damage-distribution domain",
                actionKind = action.kind,
                publicDomain = "requiredPayloadFields=$requiredFields",
            )
        }

        val candidateBoundAlternativePayment = if ("alternativePayment" in requiredFieldSet) {
            candidateBoundEquipAlternativePayment(action)
        } else {
            null
        }
        val supportedRequiredFields = EXTERNAL_POLICY_SUPPORTED_REQUIRED_PAYLOAD_FIELDS +
            if (candidateBoundAlternativePayment != null) setOf("alternativePayment") else emptySet()
        val unsupportedRequiredFields = requiredFieldSet - supportedRequiredFields
        if (unsupportedRequiredFields.isNotEmpty()) {
            return SemanticChoice.Gap(
                family = action.kind,
                code = "A5_DECISION_GAP",
                reason = "Public requiredPayloadFields contain an unsupported field",
                actionKind = action.kind,
                publicDomain =
                    "requiredPayloadFields=$requiredFields; " +
                        "unsupported=$unsupportedRequiredFields",
                proposedFollowUp =
                    "Publish a public domain and add an explicit policy handler for every required field",
            )
        }

        val payload = linkedMapOf<String, JsonElement>().apply {
            action.actionSemantics?.forEach { (key, value) -> put(key, value) }
        }
        var completedChoice = false

        if (candidateBoundAlternativePayment != null) {
            // The candidate already carries the engine-bound choice. Re-assign the exact public
            // element rather than choosing or reconstructing an alternative payment here.
            payload["alternativePayment"] = candidateBoundAlternativePayment
            completedChoice = true
        }

        if (action.targetPaymentDomain != null) {
            val targetPaymentPayload = publicTargetPaymentPayload(observation, action)
                ?: return SemanticChoice.Gap(
                    family = "TARGET_PAYMENT",
                    code = "PAYMENT_DOMAIN_UNSUPPORTED",
                    reason = "Published target-payment relation is missing or inconsistent",
                    actionKind = action.kind,
                    diagnostic = "PAYMENT_DOMAIN_UNSUPPORTED",
                    publicDomain =
                        "targetDomain=${action.targetDomain}; " +
                            "targetPaymentDomain=${action.targetPaymentDomain}",
                )
            targetPaymentPayload.forEach { (key, value) -> payload[key] = value }
            completedChoice = true
        }

        if ("attackers" in requiredFieldSet || "bands" in requiredFieldSet) {
            val attackFields = setOf("attackers", "bands")
            if (action.kind != "DeclareAttackers" || !attackFields.all { it in requiredFieldSet }) {
                return SemanticChoice.Gap(
                    family = "DECLARE_ATTACKERS",
                    code = "A5_DECISION_GAP",
                    reason = "DeclareAttackers required fields are not the supported public V2 shape",
                    actionKind = action.kind,
                    publicDomain = "requiredPayloadFields=$requiredFields",
                )
            }
            val attackPayload = publicAttackDeclarationPayload(action.attackDeclarationDomain)
                ?: return SemanticChoice.Gap(
                    family = "DECLARE_ATTACKERS",
                    code = "A5_DECISION_GAP",
                    reason = "DeclareAttackers published no complete AttackDeclarationDomainV2",
                    actionKind = action.kind,
                    publicDomain =
                        "requiredPayloadFields=$requiredFields; " +
                            "attackDeclarationDomain=${action.attackDeclarationDomain}",
                    proposedFollowUp =
                        "Publish a complete perspective-safe AttackDeclarationDomainV2",
                )
            payload["attackers"] = attackPayload["attackers"]
                ?: return SemanticChoice.Gap(
                    family = "DECLARE_ATTACKERS",
                    code = "A5_DECISION_GAP",
                    reason = "Public attack declaration omitted attackers",
                    actionKind = action.kind,
                )
            payload["bands"] = attackPayload["bands"]
                ?: return SemanticChoice.Gap(
                    family = "DECLARE_ATTACKERS",
                    code = "A5_DECISION_GAP",
                    reason = "Public attack declaration omitted bands",
                    actionKind = action.kind,
                )
            completedChoice = true
        }

        if ("blockers" in requiredFieldSet) {
            if (action.kind != "DeclareBlockers" || requiredFields != listOf("blockers")) {
                return SemanticChoice.Gap(
                    family = "DECLARE_BLOCKERS",
                    code = "A5_DECISION_GAP",
                    reason = "DeclareBlockers required fields are not the supported public V1 shape",
                    actionKind = action.kind,
                    publicDomain = "requiredPayloadFields=$requiredFields",
                )
            }
            val blockerPayload = publicBlockerDeclarationPayload(action.blockerDeclarationDomain)
                ?: return SemanticChoice.Gap(
                    family = "DECLARE_BLOCKERS",
                    code = "A5_DECISION_GAP",
                    reason = "DeclareBlockers published no complete BlockerDeclarationDomainV1",
                    actionKind = action.kind,
                    publicDomain =
                        "requiredPayloadFields=$requiredFields; " +
                            "blockerDeclarationDomain=${action.blockerDeclarationDomain}",
                    proposedFollowUp =
                        "Publish a complete perspective-safe BlockerDeclarationDomainV1",
                )
            payload["blockers"] = blockerPayload["blockers"]
                ?: return SemanticChoice.Gap(
                    family = "DECLARE_BLOCKERS",
                    code = "A5_DECISION_GAP",
                    reason = "Public blocker declaration omitted blockers",
                    actionKind = action.kind,
                )
            completedChoice = true
        }

        if (action.targetPaymentDomain == null && "paymentStrategy" in requiredFieldSet) {
            val domain = action.paymentDomain
                ?: return SemanticChoice.Gap(
                    family = "PAYMENT",
                    code = "PAYMENT_DOMAIN_UNSUPPORTED",
                    reason = "Structured mana action published no PaymentDomainV5",
                    actionKind = action.kind,
                    diagnostic = "PAYMENT_DOMAIN_UNSUPPORTED",
                    publicDomain = "LegalActionView.paymentDomain=null; manaCost=${action.manaCost}",
                    proposedFollowUp = "Publish a complete PaymentDomainV5 for this legal action",
                )
            val paymentPlan = paymentPlanV3FromPublic(domain)
                ?: return SemanticChoice.Gap(
                    family = "PAYMENT",
                    code = "PAYMENT_DOMAIN_UNSUPPORTED",
                    reason = "Published PaymentDomainV5 cannot be completed deterministically",
                    actionKind = action.kind,
                    diagnostic = "PAYMENT_DOMAIN_UNSUPPORTED",
                    publicDomain = domain.toString(),
                    proposedFollowUp =
                        "Extend PaymentDomainV5 until source, production, pool, and allocation choices are representable",
                )
            payload["paymentStrategy"] = paymentJson.encodeToJsonElement(
                PaymentStrategy.serializer(),
                PaymentStrategy.ExplicitV3(paymentPlan = paymentPlan),
            )
            completedChoice = true
        }

        if ("xValue" in requiredFieldSet) {
            val maxX = action.maxAffordableX
                ?: return SemanticChoice.Gap(
                    family = action.kind,
                    code = "A5_DECISION_GAP",
                    reason = "X action has no published upper bound",
                    actionKind = action.kind,
                )
            payload["xValue"] = JsonPrimitive(maxX.coerceAtLeast(0))
            completedChoice = true
        }

        if ("repeatCount" in requiredFieldSet) {
            val repeatCountDomain = action.repeatCountDomain
                ?: return SemanticChoice.Gap(
                    family = action.kind,
                    code = "A5_DECISION_GAP",
                    reason = "repeatCount is required but is absent from its public domain",
                    actionKind = action.kind,
                    publicDomain =
                        "requiredPayloadFields=$requiredFields; " +
                            "repeatCountDomain=${action.repeatCountDomain}",
                )
            val publicRepeatCount = repeatCountDomain.minCount
            payload["repeatCount"] = JsonPrimitive(publicRepeatCount)
            completedChoice = true
        }

        if (action.targetPaymentDomain == null && "targets" in requiredFieldSet) {
            val targetValues = publicTargetSelection(observation, action)
                ?: return SemanticChoice.Gap(
                    family = "TARGETS",
                    code = "A5_DECISION_GAP",
                    reason = "Published target candidates/domain cannot be completed deterministically",
                    actionKind = action.kind,
                    publicDomain =
                        "requiredPayloadFields=$requiredFields; targetDomain=${action.targetDomain}; " +
                            "targetEntityIds=${action.targetEntityIds}; " +
                            "minTargets=${action.minTargets}; maxTargets=${action.maxTargets}",
                    proposedFollowUp = "Publish a complete public target candidate/domain",
                )
            payload["targets"] = JsonArray(targetValues)
            completedChoice = true
        }

        if ("manaColorChoice" in requiredFieldSet) {
            val colors = publicManaColorDomain(action)
                ?: return SemanticChoice.Gap(
                    family = "MANA_COLOR",
                    code = "A5_DECISION_GAP",
                    reason = "Public mana color choice has no supported color-set domain",
                    actionKind = action.kind,
                    publicDomain =
                        "requiredPayloadFields=$requiredFields; actionSemantics=${action.actionSemantics}",
                    proposedFollowUp = "Publish a complete public mana color-set domain",
                )
            val selectedColor = colors.firstOrNull()
                ?: return SemanticChoice.Gap(
                    family = "MANA_COLOR",
                    code = "A5_DECISION_GAP",
                    reason = "Public mana color choice has no legal color",
                    actionKind = action.kind,
                    publicDomain = "requiredPayloadFields=$requiredFields; colors=$colors",
                )
            payload["manaColorChoice"] = JsonPrimitive(selectedColor.name)
            completedChoice = true
        }

        if ("additionalCostPayment" in requiredFieldSet) {
            val choices = publicSacrificeSelection(action, allowEmpty = true)
                ?: return SemanticChoice.Gap(
                    family = "ADDITIONAL_COST",
                    code = "A5_DECISION_GAP",
                    reason = "Published sacrifice domain cannot satisfy its cardinality",
                    actionKind = action.kind,
                    publicDomain = "requiredPayloadFields=$requiredFields",
                )
            payload["additionalCostPayment"] = buildJsonObject {
                put(
                    "sacrificedPermanents",
                    JsonArray(choices.map { id -> JsonPrimitive(id.value) }),
                )
            }
            completedChoice = true
        }

        if ("costPayment" in requiredFieldSet) {
            val costPayment = sourceBoundCostPayment(action)
                ?: publicSacrificeSelection(action, allowEmpty = false)?.let { choices ->
                    buildJsonObject {
                        put(
                            "sacrificedPermanents",
                            JsonArray(choices.map { id -> JsonPrimitive(id.value) }),
                        )
                        put("tappedPermanents", JsonArray(emptyList()))
                    }
                }
                ?: return SemanticChoice.Gap(
                    family = "COST_PAYMENT",
                    code = "A5_DECISION_GAP",
                    reason =
                        "Public costPayment is neither deterministic source-bound nor a complete " +
                            "published sacrifice domain",
                    actionKind = action.kind,
                    publicDomain =
                        "requiredPayloadFields=$requiredFields; " +
                            "sourceEntityId=${action.sourceEntityId}; " +
                            "validSacrificeTargets=${action.validSacrificeTargets}; " +
                            "sacrificeCount=${action.sacrificeCount}; " +
                            "sacrificeMinCount=${action.sacrificeMinCount}; " +
                            "sacrificeMaxCount=${action.sacrificeMaxCount}; " +
                            "actionSemantics=${action.actionSemantics}",
                    proposedFollowUp =
                        "Publish a complete public domain for non-source-bound cost payment",
                )
            payload["costPayment"] = costPayment
            completedChoice = true
        }

        val missingFields = requiredFields.filterNot(payload::containsKey)
        if (missingFields.isNotEmpty()) {
            return SemanticChoice.Gap(
                family = action.kind,
                code = "A5_DECISION_GAP",
                reason = "Public requiredPayloadFields were not completed",
                actionKind = action.kind,
                publicDomain = "requiredPayloadFields=$requiredFields; missing=$missingFields",
            )
        }

        /*
         * A structured action still needs at least one concrete public choice in addition to its
         * semantic identity. Descriptions and opaque engine-shaped payloads are not choices.
         */
        if (action.requiresStructuredAction && !completedChoice) {
            return SemanticChoice.Gap(
                family = action.kind,
                code = "A5_DECISION_GAP",
                reason = "Structured action has no complete public choice domain",
                actionKind = action.kind,
                publicDomain = publicActionDomain(action),
            )
        }

        return SemanticChoice.Action(
            actionId = action.actionId,
            semanticKey = semanticKey,
            kind = action.kind,
            payload = JsonObject(payload),
        )
    }

    /**
     * Accept only the already-bound equip choice carried by an ActivateAbility candidate. The
     * policy never chooses the mode and never interprets a card name, description, or mana cost;
     * malformed, future, or resource-payment alternatives remain unsupported.
     */
    private fun candidateBoundEquipAlternativePayment(
        action: com.wingedsheep.gym.contract.LegalActionView,
    ): JsonObject? {
        if (action.kind != "ActivateAbility") return null
        val element = action.actionSemantics?.get("alternativePayment") as? JsonObject ?: return null
        val decoded = runCatching {
            paymentJson.decodeFromJsonElement(AlternativePaymentChoice.serializer(), element)
        }.getOrNull() ?: return null
        if (decoded.hasResourcePayment) return null
        return when (decoded.equipPayment) {
            EquipPaymentChoice.NORMAL,
            EquipPaymentChoice.FREE_FIRST_EQUIP -> element
            null -> null
        }
    }

    private fun publicSacrificeSelection(
        action: com.wingedsheep.gym.contract.LegalActionView,
        allowEmpty: Boolean,
    ): List<EntityId>? {
        val min = action.sacrificeMinCount
        val max = action.sacrificeMaxCount
        val count = action.sacrificeCount.takeIf { it > 0 } ?: min
        if (min < 0 || max < 0 || count < min || count > max) return null

        val targets = action.validSacrificeTargets
        if (targets.distinct().size != targets.size) return null
        if (!allowEmpty && count == 0 && targets.isEmpty()) return null

        val choices = targets.sortedBy { it.value }.take(count)
        return choices.takeIf { it.size == count }
    }

    /**
     * Selects an attack declaration only from the versioned public certificate. This adapter does
     * not evaluate combat rules; it searches the published attacker relation and constraints in a
     * published attacker order, then chooses the first defender in each producer-owned relation
     * list for each selected attacker.
     */
    private fun publicAttackDeclarationPayload(
        domain: AttackDeclarationDomainV2?,
    ): JsonObject? {
        if (domain == null || domain.version != ATTACK_DECLARATION_DOMAIN_V2_VERSION) return null

        val attackers = domain.attackerOrder
        if (attackers.distinct().size != attackers.size) return null
        if (domain.attackerToDefenders.keys != attackers.toSet()) return null
        if (domain.attackerToDefenders.values.any { defenders ->
                defenders.isEmpty() || defenders.distinct().size != defenders.size
            }
        ) {
            return null
        }

        val mandatory = domain.mandatoryAttackers
        if (mandatory.distinct().size != mandatory.size || mandatory.any { it !in attackers }) {
            return null
        }

        val requirements = domain.coAttackerRequirements
        if (requirements.keys.any { it !in attackers } || requirements.values.any { entries ->
                entries.any { requirement ->
                    requirement.anyOf.isEmpty() ||
                        requirement.anyOf.distinct().size != requirement.anyOf.size ||
                        requirement.anyOf.any { it !in attackers }
                }
            }
        ) {
            return null
        }

        val maximum = (domain.maxAttackers ?: attackers.size).coerceAtMost(attackers.size)
        if (maximum < 0 || mandatory.size > maximum) return null

        val selected = if (domain.canDeclareZeroAttackers && mandatory.isEmpty()) {
            emptyList()
        } else {
            val minimum = mandatory.size.coerceAtLeast(1)
            (minimum..maximum).firstNotNullOfOrNull { size ->
                firstAttackSubset(
                    attackers = attackers,
                    size = size,
                    mandatory = mandatory.toSet(),
                    requirements = requirements,
                )
            } ?: return null
        }

        val attackerPayload = linkedMapOf<String, JsonElement>()
        for (attacker in selected) {
            val defender = domain.attackerToDefenders.getValue(attacker)
                .firstOrNull()
                ?: return null
            attackerPayload[attacker.value] = JsonPrimitive(defender.value)
        }

        return buildJsonObject {
            put("attackers", JsonObject(attackerPayload))
            // V2 lets this deterministic acceptance policy choose the explicit empty band list.
            put("bands", JsonArray(emptyList()))
        }
    }

    /**
     * Construct one legal blocker declaration using only the published V1 certificate. This is a
     * test-only external controller, not a Rules validator: it searches the finite public choice
     * space and applies only the constraints explicitly published by Rules. It never sees
     * private engine state, registry internals, engine IDs outside the DTO, or a native combat
     * policy.
     */
    private fun publicBlockerDeclarationPayload(
        domain: BlockerDeclarationDomainV1?,
    ): JsonObject? {
        if (domain == null || domain.version != BLOCKER_DECLARATION_DOMAIN_VERSION) return null
        if (domain.blockerOrder.distinct().size != domain.blockerOrder.size ||
            domain.attackerOrder.distinct().size != domain.attackerOrder.size
        ) return null
        if (domain.blockerToAttackers.keys.toList() != domain.blockerOrder ||
            domain.maxAttackersByBlocker.keys.toList() != domain.blockerOrder
        ) return null

        val options = domain.blockerOrder.map { blockerId ->
            publicBlockerAssignmentOptions(
                domain.blockerToAttackers[blockerId] ?: return null,
                domain.maxAttackersByBlocker[blockerId] ?: return null,
            ) ?: return null
        }

        val selected = if (domain.canDeclareZeroBlockers) {
            linkedMapOf()
        } else {
            val result = linkedMapOf<EntityId, List<EntityId>>()
            var nodes = 0L
            fun search(index: Int, selectedBlockerCount: Int): Boolean {
                if (nodes++ >= MAX_PUBLIC_BLOCKER_SEARCH_NODES) return false
                if (index == domain.blockerOrder.size) {
                    return publicBlockerDeclarationSatisfies(domain, result)
                }
                val blockerId = domain.blockerOrder[index]
                for (assignment in options[index]) {
                    val nextCount = selectedBlockerCount + if (assignment.isEmpty()) 0 else 1
                    if (domain.globalMaxBlockers != null && nextCount > domain.globalMaxBlockers) continue
                    if (assignment.isEmpty()) {
                        if (search(index + 1, nextCount)) return true
                    } else {
                        result[blockerId] = assignment
                        if (search(index + 1, nextCount)) return true
                        result.remove(blockerId)
                    }
                }
                return false
            }
            if (!search(0, 0)) return null
            result.toMap()
        }

        return buildJsonObject {
            put("blockers", buildJsonObject {
                domain.blockerOrder.forEach { blockerId ->
                    selected[blockerId]?.let { attackers ->
                        put(
                            blockerId.value,
                            JsonArray(attackers.map { attackerId -> JsonPrimitive(attackerId.value) }),
                        )
                    }
                }
            })
        }
    }

    private fun publicBlockerAssignmentOptions(
        candidates: List<EntityId>,
        maxCount: Int,
    ): List<List<EntityId>>? {
        if (candidates.distinct().size != candidates.size || maxCount < 0) return null
        val cap = minOf(maxCount, candidates.size)
        val result = mutableListOf<List<EntityId>>()
        val current = mutableListOf<EntityId>()
        fun visit(index: Int) {
            if (result.size >= MAX_PUBLIC_BLOCKER_OPTIONS_PER_BLOCKER) return
            if (index == candidates.size) {
                result += current.toList()
                return
            }
            visit(index + 1)
            if (current.size < cap) {
                current += candidates[index]
                visit(index + 1)
                current.removeAt(current.lastIndex)
            }
        }
        visit(0)
        return result.takeIf { it.isNotEmpty() &&
            (candidates.size < 20 || result.size < MAX_PUBLIC_BLOCKER_OPTIONS_PER_BLOCKER)
        }
    }

    private fun publicBlockerDeclarationSatisfies(
        domain: BlockerDeclarationDomainV1,
        blockers: Map<EntityId, List<EntityId>>,
    ): Boolean {
        if (blockers.keys.any { it !in domain.blockerOrder }) return false
        if (blockers.any { (blockerId, attackers) ->
                attackers.isEmpty() ||
                    attackers.distinct().size != attackers.size ||
                    attackers.any { it !in domain.blockerToAttackers.getValue(blockerId) } ||
                    attackers.size > domain.maxAttackersByBlocker.getValue(blockerId)
            }
        ) return false

        val counts = blockers.values.flatten().groupingBy { it }.eachCount()
        if (domain.minBlockersByAttacker.any { (attackerId, minimum) ->
                counts.getOrDefault(attackerId, 0).let { count ->
                    count > 0 && count < minimum
                }
            }
        ) return false
        if (counts.any { (attackerId, count) ->
                count > domain.maxBlockersByAttacker.getOrDefault(attackerId, Int.MAX_VALUE)
            }
        ) return false
        if (domain.globalMaxBlockers != null && blockers.keys.size > domain.globalMaxBlockers) return false
        if (domain.coBlockerRequirements.any { (blockerId, requirements) ->
                blockerId in blockers && requirements.any { requirement ->
                    requirement.eligibleCoBlockers.none { it in blockers }
                }
            }
        ) return false

        val satisfied = domain.requirements.count { requirement ->
            when (requirement) {
                is BlockRequirementV1.BlockSpecific ->
                    requirement.attackerId in blockers[requirement.blockerId].orEmpty()
                is BlockRequirementV1.BlockOneOf ->
                    blockers[requirement.blockerId].orEmpty().any { it in requirement.attackerIds }
                is BlockRequirementV1.AttackerMustBeBlockedIfAble ->
                    domain.blockerOrder.none {
                        requirement.attackerId in domain.blockerToAttackers.getValue(it)
                    } || blockers.values.any { requirement.attackerId in it }
                is BlockRequirementV1.AttackerMustBeBlockedByAll ->
                    domain.blockerOrder
                        .filter { requirement.attackerId in domain.blockerToAttackers.getValue(it) }
                        .all { requirement.attackerId in blockers[it].orEmpty() }
                is BlockRequirementV1.BlockerMustBlockIfAble ->
                    !blockers[requirement.blockerId].isNullOrEmpty()
            }
        }
        return satisfied >= domain.minimumSatisfiedRequirementCount
    }

    private fun firstAttackSubset(
        attackers: List<EntityId>,
        size: Int,
        mandatory: Set<EntityId>,
        requirements: Map<EntityId, List<com.wingedsheep.gym.contract.AttackCoAttackerRequirementV1>>,
    ): List<EntityId>? {
        val selected = ArrayList<EntityId>(size)

        fun search(nextIndex: Int): List<EntityId>? {
            if (selected.size == size) {
                if (!selected.containsAll(mandatory)) return null
                val selectedSet = selected.toSet()
                val satisfiesRequirements = requirements.all { (attacker, entries) ->
                    attacker !in selectedSet || entries.all { requirement ->
                        requirement.anyOf.any { it in selectedSet }
                    }
                }
                return selected.toList().takeIf { satisfiesRequirements }
            }

            val remaining = size - selected.size
            val lastStart = attackers.size - remaining
            for (index in nextIndex..lastStart) {
                selected += attackers[index]
                search(index + 1)?.let { return it }
                selected.removeAt(selected.lastIndex)
            }
            return null
        }

        return search(0)
    }

    private fun publicActionDomain(
        action: com.wingedsheep.gym.contract.LegalActionView,
    ): String = listOf(
        "requiredPayloadFields=${action.requiredPayloadFields}",
        "sourceEntityId=${action.sourceEntityId}",
        "targetEntityIds=${action.targetEntityIds}",
        "minTargets=${action.minTargets}",
        "maxTargets=${action.maxTargets}",
        "validSacrificeTargets=${action.validSacrificeTargets}",
        "sacrificeCount=${action.sacrificeCount}",
        "sacrificeMinCount=${action.sacrificeMinCount}",
        "sacrificeMaxCount=${action.sacrificeMaxCount}",
        "requiresDamageDistribution=${action.requiresDamageDistribution}",
        "actionSemantics=${action.actionSemantics}",
        ).joinToString("; ")

    private fun publicTargetDomainSelection(
        observation: TrainingObservation,
        action: com.wingedsheep.gym.contract.LegalActionView,
    ): List<JsonObject>? {
        val domain = action.targetDomain ?: return null
        if (domain.version != ACTION_TARGET_DOMAIN_VERSION ||
            domain.composition != com.wingedsheep.gym.contract.ActionTargetComposition.FIXED
        ) {
            return null
        }

        val requirements = domain.requirements.sortedBy { it.index }
        if (requirements.map { it.index } != requirements.indices.toList()) return null

        val selectedIds = mutableSetOf<EntityId>()
        val selectedTargets = mutableListOf<JsonObject>()
        for (requirement in requirements) {
            val choices = targetRequirementChoices(requirement, selectedIds)
                ?: return null
            for (id in choices) {
                val target = publicTarget(observation, id) ?: return null
                selectedIds += id
                selectedTargets += target
            }
        }
        return selectedTargets
    }

    private fun publicTargetSelection(
        observation: TrainingObservation,
        action: com.wingedsheep.gym.contract.LegalActionView,
    ): List<JsonObject>? = if (action.targetDomain != null) {
        publicTargetDomainSelection(observation, action)
    } else {
        val targetCount = action.minTargets.coerceAtLeast(0)
        if (targetCount > action.maxTargets || targetCount > action.targetEntityIds.size) {
            null
        } else {
            action.targetEntityIds
                .sortedBy { it.value }
                .take(targetCount)
                .map { target -> publicTarget(observation, target) }
                .takeIf { values -> values.all { it != null } }
                ?.map { value -> value!! }
        }
    }

    /**
     * Consumes a complete target-to-payment relation without reconstructing target cost or
     * affordability. The binding list is already in the producer's public candidate order, so the
     * first affordable member is a policy choice from the published domain, not a Rules heuristic.
     */
    private fun publicTargetPaymentPayload(
        observation: TrainingObservation,
        action: com.wingedsheep.gym.contract.LegalActionView,
    ): JsonObject? {
        val targetDomain = action.targetDomain ?: return null
        if (targetDomain.version != ACTION_TARGET_DOMAIN_VERSION ||
            targetDomain.requirements.size != 1 ||
            targetDomain.composition != ActionTargetComposition.FIXED
        ) return null
        val requirement = targetDomain.requirements.single()
        if (requirement.minTargets != 1 || requirement.maxTargets != 1 ||
            requirement.candidates.distinct().size != requirement.candidates.size
        ) return null

        val relation = action.targetPaymentDomain ?: return null
        if (relation.targetBindings.map { it.target } != requirement.candidates) return null
        val binding = relation.targetBindings.firstOrNull { it.affordable } ?: return null
        val target = publicTarget(observation, binding.target) ?: return null
        val plan = paymentPlanV3FromPublic(binding.paymentDomain) ?: return null
        return buildJsonObject {
            put("targets", JsonArray(listOf(target)))
            put(
                "paymentStrategy",
                paymentJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(paymentPlan = plan),
                ),
            )
        }
    }

    private fun targetRequirementChoices(
        requirement: TargetRequirementDomain,
        previouslySelected: Set<EntityId>,
    ): List<EntityId>? {
        val min = requirement.minTargets
        val max = requirement.maxTargets
        if (min < 0 || max < min || requirement.candidates.distinct().size != requirement.candidates.size) {
            return null
        }
        val candidates = requirement.candidates.sortedBy { it.value }
            .filterNot { requirement.mustDifferFromEarlier && it in previouslySelected }
        return candidates.take(min).takeIf { it.size == min }
    }

    private fun chooseStructured(
        observation: TrainingObservation,
        pending: PendingDecisionView,
        policyState: DeterministicPolicyState,
    ): SemanticChoice {
        val domain = pending.structuredDomain
            ?: return SemanticChoice.Gap(
                    family = pending.kind.name,
                    code = "A5_DECISION_GAP",
                    reason = "Structured decision is published without structuredDomain",
                    actionKind = "DECISION",
            )

        val selection: SemanticDecision = when (domain) {
            is TargetsDomain -> {
                val selected = linkedMapOf<Int, List<EntityId>>()
                for (requirement in domain.requirements.sortedBy { it.index }) {
                    val min = requirement.minTargets.coerceAtLeast(0)
                    if (min == 0) {
                        selected[requirement.index] = emptyList()
                        continue
                    }
                    if (requirement.candidates.size != 1 &&
                        (requirement.sameOwner ||
                            requirement.totalManaValueAtMost != null ||
                            requirement.differentNames)
                    ) {
                        return SemanticChoice.Gap(
                            family = "TARGETS",
                            code = "A5_DECISION_GAP",
                            reason = "Target metadata is insufficient to prove a constrained choice",
                        )
                    }
                    val candidates = requirement.candidates.sortedBy { it.value }
                    if (candidates.size < min || min > requirement.maxTargets) {
                        return SemanticChoice.Gap(
                            family = "TARGETS",
                            code = "A5_DECISION_GAP",
                            reason = "Target domain has insufficient candidates",
                        )
                    }
                    selected[requirement.index] = candidates.take(min)
                }
                SemanticDecision.Targets(selected)
            }

            is CardSelectionDomain -> {
                val result = selectCards(domain)
                    ?: return SemanticChoice.Gap(
                        family = "CARD_SELECTION",
                        code = "A5_DECISION_GAP",
                        reason = "Published card-selection domain cannot be completed deterministically",
                    )
                SemanticDecision.Cards(result)
            }

            is ModeSelectionDomain -> {
                val available = domain.modes
                    .filter { it.available }
                    .sortedBy { it.index }
                if (available.size < domain.minModes) {
                    return SemanticChoice.Gap(
                        family = "MODES",
                        code = "A5_DECISION_GAP",
                        reason = "Mode domain has fewer available modes than its minimum",
                    )
                }
                SemanticDecision.Modes(available.take(domain.minModes).map { it.index })
            }

            is DistributionDomain -> {
                val targets = domain.targets.sortedBy { it.value }
                val assignment = linkedMapOf<EntityId, Int>()
                targets.forEach { id -> assignment[id] = 0 }
                val minimum = domain.minPerTarget.coerceAtLeast(0)
                val capacity = targets.sumOf { id ->
                    (domain.maxPerTarget[id] ?: domain.totalAmount).coerceAtLeast(0)
                }
                val requiredMinimum = minimum * targets.size
                if (requiredMinimum > domain.totalAmount && !domain.allowPartial) {
                    return SemanticChoice.Gap(
                        family = "DISTRIBUTION",
                        code = "A5_DECISION_GAP",
                        reason = "Distribution minimum exceeds the published total",
                    )
                }
                var remaining = if (domain.allowPartial) {
                    domain.totalAmount.coerceAtMost(capacity)
                } else {
                    domain.totalAmount
                }
                for (id in targets) {
                    val max = (domain.maxPerTarget[id] ?: domain.totalAmount).coerceAtLeast(0)
                    val amount = minimum.coerceAtMost(max).coerceAtMost(remaining)
                    assignment[id] = amount
                    remaining -= amount
                }
                var index = 0
                while (remaining > 0 && targets.isNotEmpty()) {
                    val id = targets[index % targets.size]
                    val max = (domain.maxPerTarget[id] ?: domain.totalAmount).coerceAtLeast(0)
                    val next = (assignment[id] ?: 0) + 1
                    if (next <= max) {
                        assignment[id] = next
                        remaining--
                    }
                    index++
                    if (index > targets.size * (domain.totalAmount + 1)) break
                }
                if (remaining > 0 && !domain.allowPartial) {
                    return SemanticChoice.Gap(
                        family = "DISTRIBUTION",
                        code = "A5_DECISION_GAP",
                        reason = "Distribution capacity cannot satisfy the published total",
                    )
                }
                SemanticDecision.Distribution(assignment)
            }

            is OrderingDomain -> SemanticDecision.Ordered(domain.objects)

            is SplitPilesDomain -> {
                if (domain.numberOfPiles <= 0) {
                    return SemanticChoice.Gap(
                        family = "SPLIT_PILES",
                        code = "A5_DECISION_GAP",
                        reason = "Split-piles domain publishes no positive pile count",
                    )
                }
                val piles = List(domain.numberOfPiles) { mutableListOf<EntityId>() }
                domain.cards.forEachIndexed { index, id ->
                    piles[index % domain.numberOfPiles].add(id)
                }
                SemanticDecision.Piles(piles.map { it.toList() })
            }

            is SearchLibraryDomain -> {
                val options = domain.options.sortedBy { it.value }
                if (options.size < domain.minSelections) {
                    return SemanticChoice.Gap(
                        family = "SEARCH",
                        code = "A5_DECISION_GAP",
                        reason = "Search domain has fewer options than its minimum",
                    )
                }
                SemanticDecision.Cards(options.take(domain.minSelections))
            }

            is ReorderLibraryDomain -> SemanticDecision.Ordered(domain.cards)

            is CombatResolutionDomain -> {
                val actor = pending.playerId
                val edges = domain.edges
                    .filter { it.editableBy == actor }
                    .sortedBy { it.id }
                    .map { edge -> EdgeAmount(edge.id, edge.amount.coerceIn(0, edge.maximum)) }
                SemanticDecision.Damage(edges)
            }

            is ManaSourcesDomain -> {
                if (domain.version != MANA_SOURCES_DOMAIN_VERSION) {
                    return SemanticChoice.Gap(
                        family = "PAYMENT",
                        code = "PAYMENT_DOMAIN_UNSUPPORTED",
                        reason = "Pending payment has an unsupported public domain version",
                        actionKind = "DECISION",
                        diagnostic = "PAYMENT_DOMAIN_UNSUPPORTED",
                        publicDomain = domain.toString(),
                        proposedFollowUp = "Publish a supported complete pending PaymentDomainV5",
                    )
                }
                val paymentPlan = paymentPlanV3FromPublic(domain.paymentDomain)
                    ?: return SemanticChoice.Gap(
                        family = "PAYMENT",
                        code = "PAYMENT_DOMAIN_UNSUPPORTED",
                        reason = "Published pending PaymentDomainV5 cannot be completed deterministically",
                        actionKind = "DECISION",
                        diagnostic = "PAYMENT_DOMAIN_UNSUPPORTED",
                        publicDomain = domain.paymentDomain.toString(),
                        proposedFollowUp =
                            "Extend PaymentDomainV5 until source, production, pool, and allocation choices are representable",
                    )
                SemanticDecision.Payment(paymentPlan)
            }

            is ReplacementDomain -> {
                if (domain.fromOptions.isEmpty() || domain.toOptions.isEmpty()) {
                    return SemanticChoice.Gap(
                        family = "REPLACEMENT",
                        code = "A5_DECISION_GAP",
                        reason = "Replacement domain has an empty side",
                    )
                }
                val from = domain.defaultFromIndex
                    ?.takeIf { it in domain.fromOptions.indices }
                    ?: 0
                val allowed = domain.allowedToByFrom.getOrNull(from)
                val to = (allowed?.sorted()?.firstOrNull() ?: 0)
                    .takeIf { it in domain.toOptions.indices }
                    ?: return SemanticChoice.Gap(
                        family = "REPLACEMENT",
                        code = "A5_DECISION_GAP",
                        reason = "Replacement domain has no valid TO option",
                    )
                SemanticDecision.Replacement(from, to)
            }

            is com.wingedsheep.gym.contract.BudgetModalDomain -> {
                val selected = domain.modes
                    .mapIndexed { index, mode -> index to mode }
                    .filter { (_, mode) -> mode.cost <= domain.budget }
                    .firstOrNull()
                    ?.first
                SemanticDecision.Budget(if (selected == null) emptyList() else listOf(selected))
            }
        }

        return SemanticChoice.Structured(pending.kind.name, selection)
    }

    private fun selectCards(domain: CardSelectionDomain): List<EntityId>? {
        val options = domain.options
            .filterNot { it in domain.nonSelectableOptions }
            .distinct()
            .sortedBy { it.value }
        val needsInfo = domain.onePerCardType ||
            domain.onePerColor ||
            domain.onePerCardName ||
            domain.onePerBasicLandType ||
            domain.onePerPower ||
            domain.maxTotalManaValue != null ||
            domain.minTotalManaValue != null ||
            domain.maxTotalPower != null ||
            domain.conditionalMinimums.isNotEmpty()
        if (needsInfo && domain.cardInfo == null && domain.minSelections > 0) return null

        val selected = mutableListOf<EntityId>()
        val usedTypes = mutableSetOf<String>()
        val usedColors = mutableSetOf<String>()
        val usedNames = mutableSetOf<String>()
        val usedBasicTypes = mutableSetOf<String>()
        val usedPowers = mutableSetOf<Int>()
        var manaValue = 0
        var power = 0

        fun info(id: EntityId): StructuredCardInfo? = domain.cardInfo?.get(id)

        fun canAdd(id: EntityId): Boolean {
            val card = info(id)
            if (needsInfo && card == null) return false
            if (card == null) return true
            val typeKey = card.typeLine.substringBefore("—").trim()
            val basicTypes = card.typeLine
                .substringAfter("—", "")
                .split(" ", ",")
                .map { it.trim().uppercase() }
                .filter { it in BASIC_LAND_TYPES }
                .toSet()
            if (domain.onePerCardType && !usedTypes.add(typeKey)) return false
            if (domain.onePerColor && card.colors.any { it.uppercase() in usedColors }) return false
            if (domain.onePerCardName && !usedNames.add(card.name)) return false
            if (domain.onePerBasicLandType &&
                (basicTypes.isEmpty() || basicTypes.any { it in usedBasicTypes })
            ) return false
            if (domain.onePerPower && (card.power == null || !usedPowers.add(card.power))) return false
            val nextManaValue = manaValue + manaValue(card.manaCost)
            val nextPower = power + (card.power ?: 0)
            if (domain.maxTotalManaValue != null && nextManaValue > domain.maxTotalManaValue) return false
            if (domain.maxTotalPower != null && nextPower > domain.maxTotalPower) return false
            return true
        }

        fun commit(id: EntityId) {
            val card = info(id)
            selected += id
            if (card != null) {
                usedTypes += card.typeLine.substringBefore("—").trim()
                usedColors += card.colors.map { it.uppercase() }
                usedNames += card.name
                usedBasicTypes += card.typeLine
                    .substringAfter("—", "")
                    .split(" ", ",")
                    .map { it.trim().uppercase() }
                    .filter { it in BASIC_LAND_TYPES }
                card.power?.let { usedPowers += it }
                manaValue += manaValue(card.manaCost)
                power += card.power ?: 0
            }
        }

        for (id in options) {
            if (selected.size >= domain.maxSelections) break
            if (canAdd(id)) commit(id)
        }

        if (selected.size < domain.minSelections) return null
        if (domain.minTotalManaValue != null && manaValue < domain.minTotalManaValue) return null
        for (minimum in domain.conditionalMinimums) {
            if (selected.size >= minimum.requiredSelections) {
                val matches = selected.count { it in minimum.matchingOptions }
                if (matches < minimum.requiredMatches) return null
            }
        }
        return selected
    }

    /**
     * Consume the complete Rules-owned color domain already attached to the action view. The
     * policy deliberately does not interpret action semantics or reconstruct state-dependent
     * sets such as CommanderIdentity from visible zones.
     */
    private fun publicManaColorDomain(
        action: com.wingedsheep.gym.contract.LegalActionView,
    ): List<Color>? {
        val colors = action.availableManaColors ?: return null
        if (colors.distinct().size != colors.size) return null
        return colors
    }

    /**
     * Confirms only source-bound cost legs that are explicit in the published ability-cost JSON.
     * Mana, life, and every other additional-cost leg remain owned by their respective public
     * domains or Rules execution; this helper never invents them.
     */
    private fun sourceBoundCostPayment(
        action: com.wingedsheep.gym.contract.LegalActionView,
    ): JsonObject? {
        val sourceId = action.sourceEntityId ?: return null
        val cost = (((action.actionSemantics?.get("abilityKey") as? JsonObject)
            ?.get("ability") as? JsonObject)
            ?.get("cost")) ?: return null
        val shape = sourceBoundCostShape(cost) ?: return null
        if (!shape.tapSelf && !shape.sacrificeSelf) return null

        return buildJsonObject {
            put(
                "sacrificedPermanents",
                JsonArray(
                    if (shape.sacrificeSelf) {
                        listOf(JsonPrimitive(sourceId.value))
                    } else {
                        emptyList()
                    },
                ),
            )
            put(
                "tappedPermanents",
                JsonArray(
                    if (shape.tapSelf) {
                        listOf(JsonPrimitive(sourceId.value))
                    } else {
                        emptyList()
                    },
                ),
            )
        }
    }

    /**
     * Reads only serialized public cost-node types. Non-source-bound cost nodes are deliberately
     * ignored; malformed public structure fails closed instead of being completed heuristically.
     */
    private fun sourceBoundCostShape(element: JsonElement): SourceBoundCostShape? {
        val objectValue = element as? JsonObject ?: return null
        val type = (objectValue["type"] as? JsonPrimitive)?.content ?: return null
        return when (type) {
            "CostTap" -> SourceBoundCostShape(tapSelf = true)
            "CostSacrificeSelf" -> SourceBoundCostShape(sacrificeSelf = true)
            "CostComposite" -> {
                val costs = objectValue["costs"] as? JsonArray ?: return null
                costs.fold(SourceBoundCostShape()) { accumulated, child ->
                    val childShape = sourceBoundCostShape(child) ?: return null
                    accumulated + childShape
                }
            }
            else -> SourceBoundCostShape()
        }
    }

    private fun publicTarget(observation: TrainingObservation, id: EntityId): JsonObject? {
        if (observation.players.any { it.id == id }) {
            return buildJsonObject {
                put("type", "Player")
                put("playerId", id.value)
            }
        }
        if (observation.stack.any { it.entityId == id }) {
            return buildJsonObject {
                put("type", "Spell")
                put("spellEntityId", id.value)
            }
        }
        val feature = observation.zones
            .asSequence()
            .flatMap { it.cards.asSequence() }
            .firstOrNull { it.entityId == id }
            ?: return null
        return if (feature.zone == Zone.BATTLEFIELD) {
            buildJsonObject {
                put("type", "Permanent")
                put("entityId", id.value)
            }
        } else {
            val owner = feature.ownerId ?: return null
            val wireZone = paymentJson
                .encodeToJsonElement(Zone.serializer(), feature.zone)
                .jsonPrimitive
                .content
            buildJsonObject {
                put("type", "Card")
                put("cardId", id.value)
                put("ownerId", owner.value)
                put("zone", wireZone)
            }
        }
    }

    private fun isPass(kind: String, description: String): Boolean =
        kind.contains("Pass", ignoreCase = true) ||
            description.contains("pass priority", ignoreCase = true)

    private fun canonical(element: JsonElement?): String = when (element) {
        null, JsonNull -> "null"
        is JsonObject -> element.keys.sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { key -> key + ":" + canonical(element[key]) }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonical(it)
        }
        is JsonPrimitive -> element.toString()
    }

    private fun manaValue(manaCost: String): Int =
        TOKEN_REGEX.findAll(manaCost).sumOf { match ->
            val token = match.groupValues[1]
            token.toIntOrNull() ?: if (token == "X") 0 else 1
        }

    private companion object {
        val paymentJson = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }
        val TOKEN_REGEX = Regex("\\{([^}]+)}")
        val BASIC_LAND_TYPES = setOf("PLAINS", "ISLAND", "SWAMP", "MOUNTAIN", "FOREST")
    }

    private data class SourceBoundCostShape(
        val tapSelf: Boolean = false,
        val sacrificeSelf: Boolean = false,
    ) {
        operator fun plus(other: SourceBoundCostShape): SourceBoundCostShape = SourceBoundCostShape(
            tapSelf = tapSelf || other.tapSelf,
            sacrificeSelf = sacrificeSelf || other.sacrificeSelf,
        )
    }

}

package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.legalactions.*
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Version of the durable chosen-action contract. */
const val CHOSEN_SEMANTIC_ACTION_V1_VERSION: Int = 1

/** Stable identity of one durable chosen action. */
const val CHOSEN_SEMANTIC_ACTION_V1_SCHEMA_IDENTITY: String =
    "argentum-trajectory-chosen-action@v1"

/** Version of the durable chosen-response contract. */
const val CHOSEN_SEMANTIC_RESPONSE_V1_VERSION: Int = 1

/** Stable identity of one durable chosen response. */
const val CHOSEN_SEMANTIC_RESPONSE_V1_SCHEMA_IDENTITY: String =
    "argentum-trajectory-chosen-response@v1"

private const val ORDERING_REFERENCE_TYPE_FIELD = "referenceType"
private const val ORDERING_ENTITY_REFERENCE = "ENTITY"
private const val ORDERING_TRIGGER_REFERENCE = "TRIGGER"

/** Closed semantic decision vocabulary used by the durable identity preimage. */
@Serializable
enum class SemanticDecisionKindV1 {
    PRIORITY,
    GENERIC,
    CHOOSE_TARGETS,
    SELECT_CARDS,
    YES_NO,
    CHOOSE_MODE,
    CHOOSE_COLOR,
    CHOOSE_NUMBER,
    DISTRIBUTE,
    ORDER_OBJECTS,
    SPLIT_PILES,
    CHOOSE_OPTION,
    CHOOSE_REPLACEMENT,
    SEARCH_LIBRARY,
    REORDER_LIBRARY,
    ASSIGN_DAMAGE,
    COMBAT_RESOLUTION,
    SELECT_MANA_SOURCES,
    BUDGET_MODAL,
}

/** A full transport-free action candidate plus the externally supplied semantic choice. */
@ConsistentCopyVisibility
@Serializable
data class ChosenSemanticActionV1 private constructor(
    val version: Int = CHOSEN_SEMANTIC_ACTION_V1_VERSION,
    val schemaIdentity: String = CHOSEN_SEMANTIC_ACTION_V1_SCHEMA_IDENTITY,
    val candidate: JsonObject,
    val choicePayload: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(version == CHOSEN_SEMANTIC_ACTION_V1_VERSION) {
            "Unsupported chosen semantic-action version"
        }
        require(schemaIdentity == CHOSEN_SEMANTIC_ACTION_V1_SCHEMA_IDENTITY) {
            "Unsupported chosen semantic-action identity"
        }
        A3SemanticJson.requireNoForbiddenKeys(candidate, "chosen semantic action candidate")
        A3SemanticJson.requireNoForbiddenKeys(choicePayload, "chosen semantic action payload")
        require(choicePayload.values.none { it is JsonNull }) {
            "Chosen semantic action payload cannot contain null choices"
        }
        Companion.requireAffordableCandidate(candidate)
        CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )
        Companion.validateChoicePayload(candidate, choicePayload)
    }

    /** Canonical full chosen-action JSON; the candidate is never reduced to a digest. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(semanticElement())

    fun replaySemanticElement(): JsonObject = buildJsonObject {
        put("type", "chosen-action")
        put("candidate", candidate)
        put("choicePayload", choicePayload)
    }

    private fun semanticElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("candidate", candidate)
        put("choicePayload", choicePayload)
    }

    companion object {
        /** Select and validate exactly one stored action candidate without consulting GameState. */
        fun from(
            domain: CompleteLegalDomainV1,
            candidate: JsonObject,
            choicePayload: JsonObject = JsonObject(emptyMap()),
        ): ChosenSemanticActionV1 {
            require(domain.kind == CompleteLegalDomainKind.ACTION_CANDIDATES) {
                "Chosen semantic action requires an action-candidate domain"
            }
            val matches = domain.candidates.filter { stored ->
                A3SemanticJson.canonicalJson(stored) == A3SemanticJson.canonicalJson(candidate)
            }
            require(matches.size == 1) {
                "Chosen semantic action must match exactly one stored candidate"
            }
            val storedCandidate = matches.single()
            requireAffordableCandidate(storedCandidate)
            validateChoicePayload(storedCandidate, choicePayload)
            return ChosenSemanticActionV1(
                candidate = storedCandidate,
                choicePayload = choicePayload,
            )
        }

        /**
         * Project an already-bound Rules action into the same chosen-action contract used by A5.
         * The stored public candidate remains the domain authority; only fields explicitly
         * advertised by that candidate as required payload are copied from the recorded action.
         * Runtime routing fields such as an action ID or generated ability handle therefore never
         * become a second semantic projection.
         */
        fun fromRecordedAction(
            domain: CompleteLegalDomainV1,
            candidate: JsonObject,
            action: GameAction,
        ): ChosenSemanticActionV1 {
            when (action) {
                is ActivateAbility,
                is BottomCards,
                is CastSpell,
                is ChooseManaColor,
                is Concede,
                is CrewVehicle,
                is CycleCard,
                is DeclareAttackers,
                is DeclareBlockers,
                is ForetellCard,
                is KeepHand,
                is OrderBlockers,
                is PassPriority,
                is PlayLand,
                is PlotCard,
                is SaddleMount,
                is SuspendCardFromHand,
                is TakeMulligan,
                is TurnFaceUp,
                is TypecycleCard,
                is UnlockRoomDoor -> Unit
                is SubmitDecision -> throw IllegalArgumentException(
                    "Recorded decision responses must use the chosen semantic-response contract"
                )
            }
            val encoded = A3SemanticJson.strictJson
                .encodeToJsonElement(GameAction.serializer(), action)
                .jsonObject
            val choicePayload = buildJsonObject {
                requiredPayloadFields(candidate).forEach { field ->
                    val value = encoded[field]
                        ?: throw IllegalArgumentException(
                            "Recorded action has no value for required choice field: $field",
                        )
                    require(value !is JsonNull) {
                        "Recorded action has a null required choice field: $field"
                    }
                    put(field, value)
                }
            }
            return from(domain, candidate, choicePayload)
        }

        private fun requireAffordableCandidate(candidate: JsonObject) {
            require(candidate["affordable"]?.let { value ->
                value is JsonPrimitive && !value.isString && value.content == "true"
            } == true) {
                "Chosen semantic action must select an affordable stored candidate"
            }
        }

        private fun validateChoicePayload(candidate: JsonObject, payload: JsonObject) {
            val requiredFields = requiredPayloadFields(candidate)
            require(payload.keys == requiredFields.toSet()) {
                "Chosen semantic action payload must contain exactly the required fields"
            }
            payload.values.forEach { value ->
                require(value !is JsonNull) {
                    "Chosen semantic action payload cannot omit a required choice"
                }
            }
            payload["paymentStrategy"]?.let { paymentStrategy ->
                val strategy = A3SemanticJson.decodeStrict(
                    PaymentStrategy.serializer(),
                    paymentStrategy,
                    "payment strategy",
                )
                require(strategy is PaymentStrategy.ExplicitV3 && strategy.paymentPlan != null) {
                    "Chosen payment must be an explicit V3 plan"
                }
            }
            StoredActionPayloadValidator.requireWithinCandidate(candidate, payload)
        }

        private fun requiredPayloadFields(candidate: JsonObject): List<String> {
            val element = candidate["requiredPayloadFields"]
                ?: throw IllegalArgumentException("Stored candidate has no required-payload field")
            val fields = (element as? JsonArray)
                ?.map { primitive ->
                    val value = primitive as? JsonPrimitive
                    require(value != null && value !is JsonNull && value.isString) {
                        "Stored candidate has malformed required-payload fields"
                    }
                    value.content
                }
                ?: throw IllegalArgumentException("Stored candidate has malformed required-payload fields")
            require(fields.distinct().size == fields.size) {
                "Stored candidate duplicates required-payload fields"
            }
            return fields
        }
    }
}

/** Pure membership checks over the public action-domain data retained by A2. */
internal object StoredActionPayloadValidator {
    private val fieldsWithCompleteStoredDomainValidation = setOf(
        "targets",
        "xValue",
        "paymentStrategy",
        "manaColorChoice",
        "attackers",
        "bands",
        "blockers",
        "orderedBlockers",
    )

    private data class TargetRequirement(
        val minTargets: Int,
        val maxTargets: Int,
        val candidates: Set<EntityId>,
        val mustDifferFromEarlier: Boolean,
        val hasUnresolvedStateConstraint: Boolean,
    )

    fun requireWithinCandidate(candidate: JsonObject, payload: JsonObject) {
        val unsupportedFields = payload.keys - fieldsWithCompleteStoredDomainValidation
        require(unsupportedFields.isEmpty()) {
            "Chosen action payload has no complete stored-domain validator for: " +
                unsupportedFields.sorted().joinToString(",")
        }
        payload["targets"]?.let { requireTargets(candidate, it) }
        payload["xValue"]?.let { requireXValue(candidate, it) }
        payload["manaColorChoice"]?.let { requireManaColor(candidate, it) }
        if (payload.containsKey("attackers") || payload.containsKey("bands")) {
            requireAttackDeclaration(candidate, payload)
        }
        if (payload.containsKey("blockers")) {
            requireBlockerDeclaration(candidate, payload)
        }
        if (payload.containsKey("orderedBlockers")) {
            requireBlockerOrder(candidate, payload)
        }
        payload["paymentStrategy"]?.let { requirePayment(candidate, payload, it) }
    }

    private fun requireTargets(candidate: JsonObject, value: JsonElement): List<EntityId> {
        val domain = candidate["targetDomain"]?.requireObject("target domain")
            ?: throw IllegalArgumentException("Target choice has no stored target domain")
        require(domain.required("version").intValue() == ACTION_TARGET_DOMAIN_VERSION) {
            "Unsupported stored target domain version"
        }
        require(domain.required("composition").stringValue() == ActionTargetComposition.FIXED.name) {
            "Unsupported stored target domain composition"
        }
        val requirements = domain.required("requirements").requireArray("target requirements")
            .mapIndexed { expectedIndex, element ->
                val requirement = element.requireObject("target requirement")
                require(requirement.required("index").intValue() == expectedIndex) {
                    "Stored target requirements are not in producer order"
                }
                val min = requirement.required("minTargets").nonNegativeIntValue()
                val max = requirement.required("maxTargets").nonNegativeIntValue()
                require(max >= min) { "Stored target requirement has an invalid cardinality" }
                val targets = requirement.required("candidates")
                    .requireStringArray("target candidates")
                    .map(::EntityId)
                require(targets.distinct().size == targets.size) {
                    "Stored target requirement contains duplicate candidates"
                }
                TargetRequirement(
                    minTargets = min,
                    maxTargets = max,
                    candidates = targets.toSet(),
                    mustDifferFromEarlier = requirement.required("mustDifferFromEarlier").booleanValue(),
                    hasUnresolvedStateConstraint = listOf(
                        "sameController",
                        "sameOwner",
                        "sameCreatureType",
                        "sameCardType",
                        "totalManaValueAtMost",
                        "differentNames",
                        "xConstrainsManaValue",
                        "xConstrainsManaValueExactly",
                        "xConstrainsPower",
                        "xConstrainsCount",
                    ).any { key ->
                        requirement[key]?.let { elementValue ->
                            if (key == "totalManaValueAtMost") {
                                elementValue !is JsonNull
                            } else {
                                elementValue.booleanValue()
                            }
                        } == true
                    },
                )
            }
        val selected = decodeTargetIds(value)
        val variableIndices = requirements.indices.filter { index ->
            requirements[index].minTargets != requirements[index].maxTargets
        }
        require(variableIndices.size <= 1) {
            "Stored target domain does not identify a unique flat payload partition"
        }
        val minTotal = requirements.sumOf { it.minTargets.toLong() }
        val maxTotal = requirements.sumOf { it.maxTargets.toLong() }
        require(minTotal <= Int.MAX_VALUE && maxTotal <= Int.MAX_VALUE) {
            "Stored target domain cardinality overflows the payload contract"
        }
        require(selected.size.toLong() in minTotal..maxTotal) {
            "Target choice has an outside-domain cardinality"
        }
        val variableIndex = variableIndices.singleOrNull()
        val fixedMinimum = requirements.indices
            .filter { it != variableIndex }
            .sumOf { requirements[it].minTargets }
        val counts = requirements.mapIndexed { index, requirement ->
            if (index == variableIndex) selected.size - fixedMinimum else requirement.minTargets
        }
        require(counts.withIndex().all { (index, count) ->
            count in requirements[index].minTargets..requirements[index].maxTargets
        }) { "Target choice cannot be partitioned into the stored requirements" }

        var offset = 0
        val earlier = mutableSetOf<EntityId>()
        for ((index, count) in counts.withIndex()) {
            val requirement = requirements[index]
            require(!requirement.hasUnresolvedStateConstraint) {
                "Target choice requires public state metadata absent from the stored domain"
            }
            val slot = selected.subList(offset, offset + count)
            require(slot.distinct().size == slot.size) {
                "Target choice duplicates a semantic target"
            }
            require(slot.all { it in requirement.candidates }) {
                "Target choice contains a target outside the stored domain"
            }
            if (requirement.mustDifferFromEarlier) {
                require(slot.none(earlier::contains)) {
                    "Target choice violates the stored distinct-target relation"
                }
            }
            earlier += slot
            offset += count
        }
        require(offset == selected.size) { "Target choice contains an unassigned target" }
        return selected
    }

    private fun decodeTargetIds(value: JsonElement): List<EntityId> =
        A3SemanticJson.decodeStrict(
            ListSerializer(ChosenTarget.serializer()),
            value,
            "chosen target payload",
        ).map { target ->
            when (target) {
                is ChosenTarget.Player -> target.playerId
                is ChosenTarget.Permanent -> target.entityId
                is ChosenTarget.Card -> target.cardId
                is ChosenTarget.Spell -> target.spellEntityId
            }
        }

    private fun requireXValue(candidate: JsonObject, value: JsonElement) {
        require(candidate.required("hasXCost").booleanValue()) {
            "X value is not part of the stored action domain"
        }
        val max = candidate["maxAffordableX"]
            ?.takeUnless { it is JsonNull }
            ?.nonNegativeIntValue()
            ?: throw IllegalArgumentException("X value has no stored upper bound")
        val xValue = value.intValue("X value")
        require(xValue in 0..max) { "X value is outside the stored action bound" }
    }

    private fun requireManaColor(candidate: JsonObject, value: JsonElement) {
        val available = candidate["availableManaColors"]
            ?.requireStringArray("available mana colors")
            ?.toSet()
            ?: throw IllegalArgumentException("Mana-color choice has no stored color domain")
        require(value.stringValue() in available) {
            "Mana-color choice is outside the stored action domain"
        }
    }

    private fun requireAttackDeclaration(candidate: JsonObject, payload: JsonObject) {
        val domain = candidate["attackDeclarationDomain"]?.let {
            A3SemanticJson.decodeStrict(
                AttackDeclarationDomainV2.serializer(),
                it,
                "attack declaration domain",
            )
        } ?: throw IllegalArgumentException("Attack declaration has no stored domain")
        payload["bands"]?.requireArray("attack bands")?.forEach { band ->
            val members = band.requireStringArray("attack band")
            require(members.distinct().size == members.size) {
                "Attack band contains duplicate members"
            }
        }
        val declaration = decodeGameAction("DeclareAttackers", payload) as? DeclareAttackers
            ?: throw IllegalArgumentException("Stored attack choice has the wrong action type")
        val defenderOrder = domain.attackerOrder
            .flatMap { domain.attackerToDefenders[it].orEmpty() }
            .distinct()
        val rulesDomain = RulesAttackDeclarationDomain(
            attackerOrder = domain.attackerOrder,
            defenderOrder = defenderOrder,
            attackerToDefenders = domain.attackerToDefenders,
            mandatoryAttackers = domain.mandatoryAttackers,
            canDeclareZeroAttackers = domain.canDeclareZeroAttackers,
            maxAttackers = domain.maxAttackers,
            coAttackerRequirements = domain.coAttackerRequirements.mapValues { (_, requirements) ->
                requirements.map { requirement -> RulesCoAttackerRequirement(requirement.anyOf) }
            },
            bandConstraints = RulesAttackBandConstraints(
                bandingAttackersByDefender = domain.bandConstraints.bandingAttackersByDefender,
                nonBandingAttackersByDefender = domain.bandConstraints.nonBandingAttackersByDefender,
            ),
        )
        require(AttackDeclarationDomainValidator.validate(rulesDomain, declaration) is
            AttackDeclarationValidationResult.Accepted) {
            "Attack choice is outside the stored declaration domain"
        }
    }

    private fun requireBlockerDeclaration(candidate: JsonObject, payload: JsonObject) {
        val domain = candidate["blockerDeclarationDomain"]?.let {
            A3SemanticJson.decodeStrict(
                BlockerDeclarationDomainV1.serializer(),
                it,
                "blocker declaration domain",
            )
        } ?: throw IllegalArgumentException("Blocker declaration has no stored domain")
        val declaration = decodeGameAction("DeclareBlockers", payload) as? DeclareBlockers
            ?: throw IllegalArgumentException("Stored blocker choice has the wrong action type")
        val rulesDomain = RulesBlockerDeclarationDomain(
            blockerOrder = domain.blockerOrder,
            attackerOrder = domain.attackerOrder,
            blockerToAttackers = domain.blockerToAttackers,
            maxAttackersByBlocker = domain.maxAttackersByBlocker,
            minBlockersByAttacker = domain.minBlockersByAttacker,
            maxBlockersByAttacker = domain.maxBlockersByAttacker,
            globalMaxBlockers = domain.globalMaxBlockers,
            coBlockerRequirements = domain.coBlockerRequirements.mapValues { (_, requirements) ->
                requirements.map { requirement ->
                    RulesCoBlockerRequirement(requirement.eligibleCoBlockers)
                }
            },
            requirements = domain.requirements.map(::toRulesRequirement),
            minimumSatisfiedRequirementCount = domain.minimumSatisfiedRequirementCount,
            canDeclareZeroBlockers = domain.canDeclareZeroBlockers,
        )
        require(BlockerDeclarationDomainValidator.validate(rulesDomain, declaration) is
            BlockerDeclarationValidationResult.Accepted) {
            "Blocker choice is outside the stored declaration domain"
        }
    }

    private fun requireBlockerOrder(candidate: JsonObject, payload: JsonObject) {
        val domain = candidate["blockerDeclarationDomain"]?.let {
            A3SemanticJson.decodeStrict(
                BlockerDeclarationDomainV1.serializer(),
                it,
                "blocker declaration domain",
            )
        } ?: throw IllegalArgumentException("Blocker order has no stored domain")
        val semantics = candidate["actionSemantics"]?.requireObject("action semantics")
            ?: throw IllegalArgumentException("Blocker order has no stored attacker identity")
        val attackerId = semantics["attackerId"]?.stringValue("attacker identity")
            ?.let(::EntityId)
            ?: throw IllegalArgumentException("Blocker order has no stored attacker identity")
        val order = decodeGameAction(
            "OrderBlockers",
            payload,
            buildJsonObject { put("attackerId", attackerId.value) },
        ) as? OrderBlockers ?: throw IllegalArgumentException("Stored blocker order has the wrong action type")
        val expected = domain.blockerOrder.filter { blockerId ->
            attackerId in domain.blockerToAttackers[blockerId].orEmpty()
        }
        require(order.orderedBlockers == expected) {
            "Blocker order is not the producer-owned order for the stored attacker"
        }
    }

    private fun toRulesRequirement(requirement: BlockRequirementV1): RulesBlockRequirement = when (requirement) {
        is BlockRequirementV1.BlockSpecific ->
            RulesBlockRequirement.BlockSpecific(requirement.blockerId, requirement.attackerId)
        is BlockRequirementV1.BlockOneOf ->
            RulesBlockRequirement.BlockOneOf(requirement.blockerId, requirement.attackerIds)
        is BlockRequirementV1.AttackerMustBeBlockedIfAble ->
            RulesBlockRequirement.AttackerMustBeBlockedIfAble(requirement.attackerId)
        is BlockRequirementV1.AttackerMustBeBlockedByAll ->
            RulesBlockRequirement.AttackerMustBeBlockedByAll(requirement.attackerId)
        is BlockRequirementV1.BlockerMustBlockIfAble ->
            RulesBlockRequirement.BlockerMustBlockIfAble(requirement.blockerId)
    }

    private fun decodeGameAction(
        type: String,
        payload: JsonObject,
        extra: JsonObject = JsonObject(emptyMap()),
    ): GameAction {
        val encoded = buildJsonObject {
            put("type", type)
            put("playerId", "a3-validation-player")
            extra.forEach { (key, value) -> put(key, value) }
            payload.forEach { (key, value) -> put(key, value) }
        }
        return A3SemanticJson.decodeStrict(GameAction.serializer(), encoded, "action choice")
    }

    private fun requirePayment(candidate: JsonObject, payload: JsonObject, value: JsonElement) {
        val strategy = A3SemanticJson.decodeStrict(PaymentStrategy.serializer(), value, "payment strategy")
        val explicit = strategy as? PaymentStrategy.ExplicitV3
            ?: throw IllegalArgumentException("Only explicit V3 payment is a trusted chosen action")
        val plan = explicit.paymentPlan
            ?: throw IllegalArgumentException("Explicit V3 payment has no complete plan")
        val paymentDomains = buildList {
            candidate["paymentDomain"]?.let {
                add(
                    A3SemanticJson.decodeStrict(
                        PaymentDomainV5.serializer(),
                        it,
                        "payment domain",
                    )
                )
            }
            candidate["targetPaymentDomain"]?.let { element ->
                val targetPayment = A3SemanticJson.decodeStrict(
                    TargetPaymentDomainV1.serializer(),
                    element,
                    "target-payment domain",
                )
                val targetIds = payload["targets"]?.let(::decodeTargetIds)
                    ?: throw IllegalArgumentException("Target-bound payment has no selected target")
                require(targetIds.size == 1) {
                    "Target-bound payment requires exactly one selected target"
                }
                val binding = targetPayment.targetBindings.singleOrNull { it.target == targetIds.single() }
                    ?: throw IllegalArgumentException("Target-bound payment target is outside the stored domain")
                require(binding.affordable) { "Target-bound payment binding is unaffordable" }
                add(binding.paymentDomain)
            }
        }
        require(paymentDomains.isNotEmpty()) {
            "Explicit payment has no stored payment domain"
        }
        paymentDomains.forEach { domain -> requirePaymentPlan(domain, plan) }
    }

    /** Shared A3 membership check for action-level and pending V3 payment programs. */
    internal fun requirePaymentPlan(domain: PaymentDomainV5, plan: PaymentPlanV3) {
        val optionsByKey = domain.sourceActivationOptions.associateBy {
            it.sourceId to it.manaAbilityKey
        }
        require(optionsByKey.size == domain.sourceActivationOptions.size) {
            "Stored payment domain contains duplicate source options"
        }
        val selectedSources = mutableSetOf<EntityId>()
        val selectedOptions = mutableListOf<com.wingedsheep.gym.contract.PaymentSourceActivationDomainV2>()
        var fixedSelfDamage = 0L
        for (activation in plan.activations) {
            require(selectedSources.add(activation.sourceId)) {
                "Payment plan activates a source more than once"
            }
            val option = optionsByKey[activation.sourceId to activation.manaAbilityKey]
                ?: throw IllegalArgumentException("Payment plan selects a source outside the stored domain")
            require(activation.productionChoice in option.productionChoices) {
                "Payment plan selects a production choice outside the stored domain"
            }
            require(activation.activationCostOrder in option.activationCostOrderOptions) {
                "Payment plan selects an activation-cost order outside the stored domain"
            }
            outputColors(activation.productionChoice)
            selectedOptions += option
            fixedSelfDamage += option.fixedSelfDamageAmount.toLong()
        }
        domain.fixedSelfDamageBudget?.let { budget ->
            require(fixedSelfDamage <= budget.toLong()) {
                "Payment plan exceeds the stored fixed self-damage budget"
            }
        }

        val expectedTargets = linkedMapOf<PaymentTargetV1, AtomicManaCostUnitV1>()
        for ((activationIndex, option) in selectedOptions.withIndex()) {
            for (unit in option.atomicActivationManaCostUnits) {
                val target = PaymentTargetV1.ActivationCostUnit(
                    activationIndex = activationIndex,
                    symbolIndex = unit.symbolIndex,
                    unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                )
                require(expectedTargets.put(target, unit) == null) {
                    "Stored payment plan exposes duplicate activation cost targets"
                }
            }
        }
        for (unit in domain.outerAtomicCostUnits) {
            val target = PaymentTargetV1.OuterCostUnit(
                symbolIndex = unit.symbolIndex,
                unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
            )
            require(expectedTargets.put(target, unit) == null) {
                "Stored payment domain exposes duplicate outer cost targets"
            }
        }

        val initialCapacity = domain.initialPoolBuckets.associate { it.key to it.availableAmount }
        val initialUsed = mutableMapOf<InitialPoolBucketKeyV1, Int>()
        val outputs = plan.activations.map { activation -> outputColors(activation.productionChoice) }
        val usedOutputs = mutableSetOf<ManaResourceRefV1.ActivationOutputUnit>()
        val seenTargets = mutableSetOf<PaymentTargetV1>()

        fun resourceColor(resource: ManaResourceRefV1, currentActivationIndex: Int?): PaymentManaColor {
            return when (resource) {
                is ManaResourceRefV1.InitialPoolResource -> {
                    val capacity = initialCapacity[resource.bucketKey]
                        ?: throw IllegalArgumentException("Payment plan references an unknown initial pool bucket")
                    val used = initialUsed.getOrDefault(resource.bucketKey, 0)
                    require(used < capacity) { "Payment plan exceeds an initial pool bucket capacity" }
                    initialUsed[resource.bucketKey] = used + 1
                    when (val key = resource.bucketKey) {
                        is InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> key.color
                        is InitialPoolBucketKeyV1.CertifiedFloatingBucket -> key.key.poolColor
                    }
                }

                is ManaResourceRefV1.ActivationOutputUnit -> {
                    require(resource.activationIndex in outputs.indices) {
                        "Payment plan references an unknown activation output"
                    }
                    if (currentActivationIndex != null) {
                        require(resource.activationIndex < currentActivationIndex) {
                            "Activation payment cannot reference a future output"
                        }
                    }
                    require(usedOutputs.add(resource)) {
                        "Payment plan consumes an activation output more than once"
                    }
                    outputs[resource.activationIndex].getOrNull(resource.outputIndex)
                        ?: throw IllegalArgumentException("Payment plan references an unknown activation output unit")
                }
            }
        }

        fun consume(allocation: PaymentAllocationV1, currentActivationIndex: Int?) {
            val expected = expectedTargets[allocation.target]
                ?: throw IllegalArgumentException("Payment plan allocates an unknown cost target")
            when (val target = allocation.target) {
                is PaymentTargetV1.ActivationCostUnit -> require(
                    currentActivationIndex != null && target.activationIndex == currentActivationIndex
                ) { "Activation payment allocation targets the wrong node" }
                is PaymentTargetV1.OuterCostUnit -> require(currentActivationIndex == null) {
                    "Outer payment allocation appears in an activation-cost allocation"
                }
            }
            require(seenTargets.add(allocation.target)) {
                "Payment plan allocates a cost target more than once"
            }
            val color = resourceColor(allocation.resource, currentActivationIndex)
            when (expected.kind) {
                PaymentCostKindV1.COLORED -> require(color in expected.allowedColors) {
                    "Payment resource does not satisfy the colored cost unit"
                }
                PaymentCostKindV1.COLORLESS -> require(color == PaymentManaColor.COLORLESS) {
                    "Payment resource does not satisfy the colorless cost unit"
                }
                PaymentCostKindV1.GENERIC -> Unit
            }
        }

        for ((activationIndex, activation) in plan.activations.withIndex()) {
            activation.activationCostAllocation.forEach { allocation ->
                consume(allocation, activationIndex)
            }
        }
        plan.outerAllocation.forEach { allocation -> consume(allocation, null) }
        require(seenTargets == expectedTargets.keys) {
            "Payment plan does not allocate every stored cost unit exactly once"
        }
    }

    private fun outputColors(choice: ProductionChoice): List<PaymentManaColor> {
        val fixed = choice.fixedOutputs
        if (fixed != null) {
            require(choice.bonusChoice == null) { "Fixed production cannot carry a bonus choice" }
            require(fixed.isNotEmpty()) { "Fixed production must contain output units" }
            require(fixed.map { it.index } == fixed.indices.toList()) {
                "Fixed production output indices are not canonical"
            }
            require(fixed.all { it.amount == 1 }) {
                "Fixed production output amounts are not in the V5 contract"
            }
            require(choice.producedColor == fixed.first().color) {
                "Fixed production does not identify its first output"
            }
            return fixed.map { output -> output.color }
        }
        require(choice.amount == 1 && choice.bonusChoice == null) {
            "Single-output production is not canonical"
        }
        return listOf(choice.producedColor)
    }

    private fun JsonObject.required(key: String): JsonElement =
        get(key) ?: throw IllegalArgumentException("Malformed stored action payload domain")

    private fun JsonElement.requireObject(label: String): JsonObject =
        this as? JsonObject ?: throw IllegalArgumentException("Malformed $label")

    private fun JsonElement.requireArray(label: String): JsonArray =
        this as? JsonArray ?: throw IllegalArgumentException("Malformed $label")

    private fun JsonElement.stringValue(label: String = "string"): String {
        val primitive = this as? JsonPrimitive
        require(primitive != null && primitive !is JsonNull && primitive.isString) {
            "Malformed $label"
        }
        return primitive.content
    }

    private fun JsonElement.booleanValue(label: String = "boolean"): Boolean {
        val primitive = this as? JsonPrimitive
        require(primitive != null && primitive !is JsonNull && !primitive.isString) {
            "Malformed $label"
        }
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Malformed $label")
        }
    }

    private fun JsonElement.intValue(label: String = "integer"): Int {
        val primitive = this as? JsonPrimitive
        require(primitive != null && primitive !is JsonNull && !primitive.isString) {
            "Malformed $label"
        }
        return primitive.content.toIntOrNull()
            ?: throw IllegalArgumentException("Malformed $label")
    }

    private fun JsonElement.nonNegativeIntValue(label: String = "non-negative integer"): Int =
        intValue(label).also { require(it >= 0) { "Malformed $label" } }

    private fun JsonElement.requireStringArray(label: String): List<String> =
        requireArray(label).map { it.stringValue(label) }
}

/** A full semantic response validated against one stored folded or structured domain. */
@ConsistentCopyVisibility
@Serializable
data class ChosenSemanticResponseV1 private constructor(
    val version: Int = CHOSEN_SEMANTIC_RESPONSE_V1_VERSION,
    val schemaIdentity: String = CHOSEN_SEMANTIC_RESPONSE_V1_SCHEMA_IDENTITY,
    val response: JsonObject,
) {
    init {
        require(version == CHOSEN_SEMANTIC_RESPONSE_V1_VERSION) {
            "Unsupported chosen semantic-response version"
        }
        require(schemaIdentity == CHOSEN_SEMANTIC_RESPONSE_V1_SCHEMA_IDENTITY) {
            "Unsupported chosen semantic-response identity"
        }
        A3SemanticJson.requireSemanticObject(response, "chosen semantic response")
        A3SemanticJson.requireNoOpaqueTriggerHandles(response, "chosen semantic response")
        if (Companion.isSemanticOrderingResponse(response)) {
            Companion.validateSemanticOrderingResponse(response)
        } else {
            Companion.decodeResponse(response)
        }
    }

    /** Canonical full chosen-response JSON without the live decision ID. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(semanticElement())

    fun replaySemanticElement(): JsonObject = buildJsonObject {
        put("type", "chosen-response")
        put("response", response)
    }

    private fun semanticElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("response", response)
    }

    companion object {
        /** Encode an existing Rules response and remove only its live routing decisionId. */
        fun from(
            domain: CompleteLegalDomainV1,
            response: DecisionResponse,
        ): ChosenSemanticResponseV1 {
            val encoded = A3SemanticJson.strictJson
                .encodeToJsonElement(DecisionResponse.serializer(), response)
                .jsonObject
            val semantic = JsonObject(encoded.filterKeys { it != "decisionId" })
            return from(domain, semantic)
        }

        /** Validate an already transport-free response JSON against the stored public domain. */
        fun from(
            domain: CompleteLegalDomainV1,
            response: JsonObject,
        ): ChosenSemanticResponseV1 {
            val semanticInput = removeManualPaymentRoutingFlag(response)
            A3SemanticJson.requireSemanticObject(semanticInput, "chosen semantic response")
            val semanticOrdering = isSemanticOrderingResponse(semanticInput)
            val decoded = if (semanticOrdering) null else decodeResponse(semanticInput)
            val normalized = when (domain.kind) {
                CompleteLegalDomainKind.ACTION_CANDIDATES ->
                    throw IllegalArgumentException("Action-candidate domains require a chosen action")

                CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS -> {
                    val normalized = semanticResponseJson(
                        requireNotNull(decoded) { "Folded response has an unsupported semantic shape" },
                        domain,
                    )
                    requireFoldedMembership(domain, normalized)
                    normalized
                }

                CompleteLegalDomainKind.STRUCTURED_DECISION -> {
                    if (semanticOrdering) {
                        val ordering = domain.structuredDomain as? OrderingDomain
                            ?: throw IllegalArgumentException("Semantic ordering response requires an ordering domain")
                        validateSemanticOrderingMembership(ordering, semanticInput)
                        semanticInput
                    } else {
                        val responseValue = requireNotNull(decoded)
                        validateStructuredMembership(domain, responseValue)
                        semanticResponseJson(responseValue, domain)
                    }
                }
            }
            return ChosenSemanticResponseV1(response = normalized)
        }

        private fun decodeResponse(response: JsonObject): DecisionResponse {
            val withRoutingId = buildJsonObject {
                response.forEach { (key, value) -> put(key, value) }
                put("decisionId", "a3-validation-routing-id")
            }
            return A3SemanticJson.decodeStrict(
                DecisionResponse.serializer(),
                withRoutingId,
                "decision response",
            )
        }

        private fun semanticResponseJson(
            response: DecisionResponse,
            domain: CompleteLegalDomainV1,
        ): JsonObject {
            val encoded = A3SemanticJson.strictJson
                .encodeToJsonElement(DecisionResponse.serializer(), response)
                .jsonObject
            val excluded = buildSet {
                add("decisionId")
                if (response is ManaSourcesSelectedResponse) {
                    add("autoPay")
                    if (response.paymentPlan != null) {
                        // V3 is the complete pending-payment choice. The legacy empty/default
                        // carriers must not become a second durable choice vocabulary.
                        add("selectedSources")
                        add("waterbendPermanents")
                        if (!response.declined) add("declined")
                    }
                }
            }
            val semantic = JsonObject(encoded.filterKeys { it !in excluded })
            val cardSelection = domain.structuredDomain as? CardSelectionDomain
            if (response is CardsSelectedResponse && cardSelection != null && !cardSelection.ordered) {
                val selected = response.selectedCards.toSet()
                val producerOrdered = cardSelection.options.filter(selected::contains)
                require(producerOrdered.size == selected.size) {
                    "Unordered card selection contains an outside-domain card"
                }
                return JsonObject(
                    semantic +
                        ("selectedCards" to JsonArray(producerOrdered.map { JsonPrimitive(it.value) }))
                )
            }

            val ordering = domain.structuredDomain as? OrderingDomain
            if (response !is OrderedResponse || ordering == null) return semantic

            val references = response.orderedObjects.map { objectId ->
                semanticOrderingReference(ordering, objectId)
            }
            require(references.map(A3SemanticJson::canonicalJson).distinct().size == references.size) {
                "Ordering response contains indistinguishable public trigger objects"
            }
            return JsonObject(semantic + ("orderedObjects" to JsonArray(references)))
        }

        /**
         * Replace an ordering reference with a tagged semantic value. The tag makes trigger
         * semantics structurally disjoint from arbitrary ordinary EntityId values.
         */
        private fun semanticOrderingReference(domain: OrderingDomain, objectId: EntityId): JsonObject {
            val label = domain.objectLabels?.get(objectId)?.takeIf(String::isNotBlank)
            val cardInfo = domain.cardInfo?.get(objectId)
            val semanticAlias = ObservationCanonicalizer.semanticOrderingObject(
                objectId = objectId.value,
                label = label,
                cardInfo = cardInfo?.let {
                    A3SemanticJson.strictJson.encodeToJsonElement(StructuredCardInfo.serializer(), it)
                },
            )
            if ("entityId" in semanticAlias) {
                return buildJsonObject {
                    put(ORDERING_REFERENCE_TYPE_FIELD, ORDERING_ENTITY_REFERENCE)
                    put("entityId", objectId.value)
                }
            }
            require(semanticAlias.isNotEmpty()) {
                "Trigger ordering object has no stable public semantics"
            }
            return buildJsonObject {
                put(ORDERING_REFERENCE_TYPE_FIELD, ORDERING_TRIGGER_REFERENCE)
                put("semantic", semanticAlias)
            }
        }

        private fun isSemanticOrderingResponse(response: JsonObject): Boolean =
            response["type"]?.let(A3SemanticJson::stringOrNull) == "OrderedResponse" &&
                response["orderedObjects"] is JsonArray &&
                response.getValue("orderedObjects").jsonArray.any { it is JsonObject }

        private fun validateSemanticOrderingResponse(response: JsonObject) {
            require(response.keys == setOf("type", "orderedObjects")) {
                "Semantic ordering response has an unsupported shape"
            }
            response.getValue("orderedObjects").jsonArray.forEach { element ->
                val reference = element as? JsonObject
                    ?: throw IllegalArgumentException("Semantic ordering response has a malformed reference")
                val referenceType = reference[ORDERING_REFERENCE_TYPE_FIELD]
                    ?.let(A3SemanticJson::stringOrNull)
                    ?: throw IllegalArgumentException("Semantic ordering response has no reference type")
                when (referenceType) {
                    ORDERING_ENTITY_REFERENCE -> {
                        require(reference.keys == setOf(ORDERING_REFERENCE_TYPE_FIELD, "entityId")) {
                            "Semantic entity ordering reference has an unsupported shape"
                        }
                        require(A3SemanticJson.stringOrNull(reference.getValue("entityId"))?.isNotBlank() == true) {
                            "Semantic entity ordering reference has no entity identity"
                        }
                    }

                    ORDERING_TRIGGER_REFERENCE -> {
                        require(reference.keys == setOf(ORDERING_REFERENCE_TYPE_FIELD, "semantic")) {
                            "Semantic trigger ordering reference has an unsupported shape"
                        }
                        val semantic = reference["semantic"] as? JsonObject
                            ?: throw IllegalArgumentException("Semantic trigger ordering reference has no semantics")
                        require(semantic.isNotEmpty() && semantic.keys.all { it == "label" || it == "cardInfo" }) {
                            "Semantic trigger ordering reference has malformed public semantics"
                        }
                        semantic["label"]?.let {
                            require(A3SemanticJson.stringOrNull(it)?.isNotBlank() == true) {
                                "Semantic trigger ordering reference has a malformed label"
                            }
                        }
                        semantic["cardInfo"]?.let {
                            A3SemanticJson.decodeStrict(
                                StructuredCardInfo.serializer(),
                                it,
                                "semantic trigger ordering card info",
                            )
                        }
                    }

                    else -> throw IllegalArgumentException("Unsupported semantic ordering reference type")
                }
            }
        }

        private fun validateSemanticOrderingMembership(
            domain: OrderingDomain,
            response: JsonObject,
        ) {
            validateSemanticOrderingResponse(response)
            val expected = domain.objects.map { objectId -> semanticOrderingReference(domain, objectId) }
            require(expected.map(A3SemanticJson::canonicalJson).distinct().size == expected.size) {
                "Ordering domain contains indistinguishable public objects"
            }
            val actual = response.getValue("orderedObjects").jsonArray
            require(actual.map { A3SemanticJson.canonicalJson(it) }.distinct().size == actual.size) {
                "Semantic ordering response duplicates an object"
            }
            require(actual.map(A3SemanticJson::canonicalJson).toSet() ==
                expected.map(A3SemanticJson::canonicalJson).toSet()) {
                "Semantic ordering response is outside the stored domain"
            }
        }

        /** Remove the live manual-payment discriminator after rejecting the auto-pay branch. */
        private fun removeManualPaymentRoutingFlag(response: JsonObject): JsonObject {
            if (response["type"]?.let(A3SemanticJson::stringOrNull) != "ManaSourcesSelectedResponse") {
                return response
            }
            val autoPay = response["autoPay"] ?: return response
            val primitive = autoPay as? JsonPrimitive
            require(primitive != null && !primitive.isString && primitive.content == "false") {
                "AutoPay is not a trusted semantic choice"
            }
            return JsonObject(response.filterKeys { it != "autoPay" })
        }

        private fun requireFoldedMembership(
            domain: CompleteLegalDomainV1,
            response: JsonObject,
        ) {
            val matches = domain.candidates.filter { candidate ->
                val semantic = candidate["actionSemantics"] as? JsonObject ?: return@filter false
                val exact = A3SemanticJson.canonicalJson(semantic) == A3SemanticJson.canonicalJson(response)
                if (exact) return@filter true
                // OptionChosenResponse gains stored option metadata from the pending decision;
                // that metadata is domain context, not a missing response choice.
                val withoutMetadata = JsonObject(semantic.filterKeys { it != "optionMetadata" })
                A3SemanticJson.canonicalJson(withoutMetadata) == A3SemanticJson.canonicalJson(response)
            }
            require(matches.size == 1) {
                "Chosen folded response must match exactly one stored option"
            }
        }

        private fun validateStructuredMembership(
            domain: CompleteLegalDomainV1,
            response: DecisionResponse,
        ) {
            val structured = requireNotNull(domain.structuredDomain) {
                "Structured domain is missing"
            }
            when (structured) {
                is TargetsDomain -> validateTargets(structured, response)
                is CardSelectionDomain -> validateCardSelection(structured, response)
                is ModeSelectionDomain -> validateModes(structured, response)
                is DistributionDomain -> validateDistribution(structured, response)
                is OrderingDomain -> validateOrdering(structured, response)
                is SplitPilesDomain -> validateSplitPiles(structured, response)
                is SearchLibraryDomain -> validateSearch(structured, response)
                is ReorderLibraryDomain -> validateReorder(structured, response)
                is CombatResolutionDomain -> validateCombat(structured, response)
                is ManaSourcesDomain -> validateManaSources(structured, response)
                is ReplacementDomain -> validateReplacement(structured, response)
                is BudgetModalDomain -> validateBudget(structured, response)
            }
        }

        private fun validateTargets(domain: TargetsDomain, response: DecisionResponse) {
            if (response is CancelDecisionResponse) {
                require(domain.canCancel) { "Target decision cannot be cancelled" }
                return
            }
            val targets = response as? TargetsResponse
                ?: throw IllegalArgumentException("Expected target selection response")
            val requirementsByIndex = domain.requirements.associateBy { it.index }
            require(targets.selectedTargets.keys == requirementsByIndex.keys) {
                "Target response must explicitly cover every stored requirement"
            }
            val requirementIndices = domain.requirements.map { it.index }
            require(requirementIndices == requirementIndices.distinct().sorted()) {
                "Stored target requirements are not in producer order"
            }
            require(domain.requirements.all { requirement ->
                requirement.minTargets >= 0 &&
                    requirement.maxTargets >= requirement.minTargets &&
                    requirement.candidates.distinct().size == requirement.candidates.size
            }) { "Stored target requirements are malformed" }
            val previouslySelected = mutableSetOf<EntityId>()
            domain.requirements.forEach { requirement ->
                val selected = targets.selectedTargets[requirement.index]
                    ?: throw IllegalArgumentException("Target response has an unknown requirement")
                require(
                    !requirement.sameController &&
                        !requirement.sameOwner &&
                        !requirement.sameCreatureType &&
                        !requirement.sameCardType &&
                        requirement.totalManaValueAtMost == null &&
                        !requirement.differentNames &&
                        !requirement.xConstrainsManaValue &&
                        !requirement.xConstrainsManaValueExactly &&
                        !requirement.xConstrainsPower &&
                        !requirement.xConstrainsCount
                ) {
                    "Target response requires public state metadata absent from the stored domain"
                }
                require(selected.distinct().size == selected.size) {
                    "Target response duplicates a selected target"
                }
                require(selected.all { it in requirement.candidates }) {
                    "Target response contains an outside-domain target"
                }
                require(selected.size in requirement.minTargets..requirement.maxTargets) {
                    "Target response violates the stored target cardinality"
                }
                if (requirement.mustDifferFromEarlier) {
                    require(selected.none(previouslySelected::contains)) {
                        "Target response violates the stored distinct-target relation"
                    }
                }
                previouslySelected += selected
            }
        }

        private fun validateCardSelection(domain: CardSelectionDomain, response: DecisionResponse) {
            val cards = response as? CardsSelectedResponse
                ?: throw IllegalArgumentException("Expected card-selection response")
            val availableColors = domain.availableColors?.also { colors ->
                require(colors.distinct() == colors.sorted()) {
                    "Stored card-selection colors are not in producer order"
                }
                require(colors.all { color -> color in Color.entries.map(Color::name) }) {
                    "Stored card-selection colors contain an unsupported color"
                }
            }?.toSet()
            require(cards.selectedCards.distinct().size == cards.selectedCards.size) {
                "Card-selection response duplicates a card"
            }
            require(cards.selectedCards.all { it in domain.options }) {
                "Card-selection response contains an outside-domain card"
            }
            require(cards.selectedCards.none { it in domain.nonSelectableOptions }) {
                "Card-selection response contains a non-selectable card"
            }
            require(
                domain.minSelections >= 0 &&
                    domain.maxSelections >= domain.minSelections
            ) { "Stored card-selection domain is malformed" }
            require(cards.selectedCards.size in domain.minSelections..domain.maxSelections) {
                "Card-selection response violates the stored cardinality"
            }

            val needsCardInfo = domain.onePerCardType ||
                domain.onePerColor ||
                domain.onePerCardName ||
                domain.onePerBasicLandType ||
                domain.onePerPower ||
                domain.maxTotalManaValue != null ||
                domain.minTotalManaValue != null ||
                domain.maxTotalPower != null ||
                availableColors != null
            val selectedInfo = if (needsCardInfo && cards.selectedCards.isNotEmpty()) {
                val cardInfo = domain.cardInfo
                    ?: throw IllegalArgumentException("Card-selection constraints have no public card metadata")
                cards.selectedCards.map { cardId ->
                    cardInfo[cardId]
                        ?: throw IllegalArgumentException("Card-selection metadata is incomplete")
                }
            } else {
                emptyList()
            }

            if (domain.onePerCardType && selectedInfo.isNotEmpty()) {
                requireNoOverlappingCardProperty(selectedInfo, ::cardTypes, "card type")
            }
            if (domain.onePerColor && selectedInfo.isNotEmpty()) {
                require(selectedInfo.flatMap { info ->
                    require(info.colors.distinct().size == info.colors.size) {
                        "Card-selection metadata contains duplicate colors"
                    }
                    info.colors
                }.let { colors -> colors.distinct().size == colors.size }) {
                    "Card-selection response contains duplicate colors"
                }
            }
            if (availableColors != null && selectedInfo.isNotEmpty()) {
                require(selectedInfo.all { info ->
                    info.colors.any { color -> color in availableColors }
                }) {
                    "Card-selection response contains a color outside the stored budget"
                }
            }
            if (domain.onePerCardName && selectedInfo.isNotEmpty()) {
                require(selectedInfo.map { it.name }.also { names ->
                    require(names.all(String::isNotBlank)) { "Card-selection metadata has no card name" }
                }.distinct().size == selectedInfo.size) {
                    "Card-selection response contains duplicate card names"
                }
            }
            if (domain.onePerBasicLandType && selectedInfo.isNotEmpty()) {
                requireNoOverlappingCardProperty(selectedInfo, ::basicLandTypes, "basic land type")
            }
            if (domain.onePerPower && selectedInfo.isNotEmpty()) {
                val powers = selectedInfo.map {
                    requireNotNull(it.power) { "Card-selection metadata has no power" }
                }
                require(powers.distinct().size == powers.size) {
                    "Card-selection response contains duplicate powers"
                }
            }

            val manaValues = if (needsCardInfo && selectedInfo.isNotEmpty()) {
                selectedInfo.map(::manaValue)
            } else {
                emptyList()
            }
            domain.maxTotalManaValue?.let { maximum ->
                require(manaValues.sumOf(Int::toLong) <= maximum.toLong()) {
                    "Card-selection response exceeds the total mana-value maximum"
                }
            }
            domain.minTotalManaValue?.let { minimum ->
                if (cards.selectedCards.isNotEmpty()) {
                    require(manaValues.sumOf(Int::toLong) >= minimum.toLong()) {
                        "Card-selection response does not meet the total mana-value minimum"
                    }
                }
            }
            domain.maxTotalPower?.let { maximum ->
                val totalPower = selectedInfo.sumOf { (it.power ?: 0).toLong() }
                require(totalPower <= maximum.toLong()) {
                    "Card-selection response exceeds the total power maximum"
                }
            }
            validateConditionalMinimums(domain, cards.selectedCards)
        }

        private fun validateConditionalMinimums(
            domain: CardSelectionDomain,
            selectedCards: List<EntityId>,
        ) {
            domain.conditionalMinimums.forEach { minimum ->
                require(minimum.matchingOptions.all { it in domain.options }) {
                    "Card-selection conditional constraint references an outside option"
                }
                require(
                    minimum.requiredSelections >= 0 &&
                        minimum.minimumSelections >= 0 &&
                        minimum.requiredMatches >= 0
                ) { "Card-selection conditional constraint has an invalid cardinality" }
            }
            val unmet = domain.conditionalMinimums.filter { minimum ->
                selectedCards.size < minimum.requiredSelections
            }
            if (unmet.isNotEmpty()) {
                require(unmet.any { minimum ->
                    val matchingCount = selectedCards.count { it in minimum.matchingOptions }
                    selectedCards.size >= minimum.minimumSelections &&
                        matchingCount >= minimum.requiredMatches
                }) {
                    "Card-selection response violates every conditional minimum alternative"
                }
            }
        }

        private fun requireNoOverlappingCardProperty(
            infos: List<StructuredCardInfo>,
            property: (StructuredCardInfo) -> Set<String>,
            label: String,
        ) {
            val seen = mutableSetOf<String>()
            infos.forEach { info ->
                val values = property(info)
                require(values.isNotEmpty()) { "Card-selection metadata has no $label" }
                require(values.none { !seen.add(it) }) {
                    "Card-selection response contains duplicate $label values"
                }
            }
        }

        private fun cardTypes(info: StructuredCardInfo): Set<String> = info.typeLine
            .substringBefore('—')
            .trim()
            .split(Regex("\\s+"))
            .filter { it in CARD_TYPE_NAMES }
            .toSet()

        private fun basicLandTypes(info: StructuredCardInfo): Set<String> = info.typeLine
            .substringAfter('—', "")
            .trim()
            .split(Regex("\\s+"))
            .filter { it in setOf("Plains", "Island", "Swamp", "Mountain", "Forest") }
            .toSet()

        private val CARD_TYPE_NAMES: Set<String> = CardType.entries
            .mapTo(mutableSetOf()) { it.displayName }

        private fun manaValue(info: StructuredCardInfo): Int = runCatching {
            ManaCost.parse(info.manaCost).cmc
        }.getOrElse {
            throw IllegalArgumentException("Card-selection metadata has a malformed mana cost")
        }

        private fun validateModes(domain: ModeSelectionDomain, response: DecisionResponse) {
            val modes = response as? ModesChosenResponse
                ?: throw IllegalArgumentException("Expected mode-selection response")
            require(
                domain.minModes >= 0 &&
                    domain.maxModes >= domain.minModes &&
                    domain.maxModes <= domain.modes.size
            ) { "Stored mode-selection domain is malformed" }
            require(modes.selectedModes.distinct().size == modes.selectedModes.size) {
                "Mode response duplicates a mode"
            }
            val byIndex = domain.modes.associateBy { it.index }
            require(modes.selectedModes.all { byIndex[it]?.available == true }) {
                "Mode response contains an unavailable or unknown mode"
            }
            require(modes.selectedModes.size in domain.minModes..domain.maxModes) {
                "Mode response violates the stored cardinality"
            }
        }

        private fun validateDistribution(domain: DistributionDomain, response: DecisionResponse) {
            val distribution = response as? DistributionResponse
                ?: throw IllegalArgumentException("Expected distribution response")
            require(domain.totalAmount >= 0 && domain.minPerTarget >= 0) {
                "Stored distribution domain is malformed"
            }
            require(domain.maxPerTarget.keys.all { it in domain.targets }) {
                "Stored distribution maximum references an unknown target"
            }
            require(domain.maxPerTarget.values.all { it >= domain.minPerTarget }) {
                "Stored distribution maximum is below its minimum"
            }
            val total = distribution.distribution.values.fold(0L) { sum, amount ->
                require(amount >= 0) { "Distribution response contains a negative amount" }
                sum + amount.toLong()
            }
            if (domain.allowPartial) {
                require(total <= domain.totalAmount.toLong()) {
                    "Distribution response exceeds the stored total"
                }
            } else {
                require(total == domain.totalAmount.toLong()) {
                    "Distribution response does not cover the stored total"
                }
            }
            distribution.distribution.forEach { (target, amount) ->
                require(target in domain.targets) { "Distribution response contains an unknown target" }
                require(amount >= domain.minPerTarget) {
                    "Distribution response violates the stored minimum"
                }
                domain.maxPerTarget[target]?.let { max ->
                    require(amount <= max) { "Distribution response exceeds the stored maximum" }
                }
            }
            if (domain.minPerTarget > 0) {
                require(domain.targets.all { target ->
                    distribution.distribution[target]?.let { it >= domain.minPerTarget } == true
                }) {
                    "Distribution response must provide the stored minimum for every target"
                }
            }
        }

        private fun validateOrdering(domain: OrderingDomain, response: DecisionResponse) {
            val ordered = response as? OrderedResponse
                ?: throw IllegalArgumentException("Expected ordering response")
            require(ordered.orderedObjects.distinct().size == ordered.orderedObjects.size) {
                "Ordering response duplicates an object"
            }
            require(ordered.orderedObjects.toSet() == domain.objects.toSet()) {
                "Ordering response must contain exactly the stored objects"
            }
        }

        private fun validateSplitPiles(domain: SplitPilesDomain, response: DecisionResponse) {
            val piles = response as? PilesSplitResponse
                ?: throw IllegalArgumentException("Expected split-piles response")
            require(domain.numberOfPiles > 0 && domain.cards.distinct().size == domain.cards.size) {
                "Stored split-piles domain is malformed"
            }
            val flattened = piles.piles.flatten()
            require(piles.piles.size == domain.numberOfPiles) {
                "Split-piles response has the wrong number of piles"
            }
            require(flattened.distinct().size == flattened.size && flattened.toSet() == domain.cards.toSet()) {
                "Split-piles response must contain exactly the stored cards"
            }
        }

        private fun validateSearch(domain: SearchLibraryDomain, response: DecisionResponse) {
            val selected = response as? CardsSelectedResponse
                ?: throw IllegalArgumentException("Expected library-search response")
            require(
                domain.minSelections >= 0 &&
                    domain.maxSelections >= domain.minSelections
            ) { "Stored library-search domain is malformed" }
            require(selected.selectedCards.distinct().size == selected.selectedCards.size) {
                "Library-search response duplicates a card"
            }
            require(selected.selectedCards.all { it in domain.options }) {
                "Library-search response contains an outside-domain card"
            }
            require(selected.selectedCards.size in domain.minSelections..domain.maxSelections) {
                "Library-search response violates the stored cardinality"
            }
        }

        private fun validateReorder(domain: ReorderLibraryDomain, response: DecisionResponse) {
            val ordered = response as? OrderedResponse
                ?: throw IllegalArgumentException("Expected library-reorder response")
            require(ordered.orderedObjects.distinct().size == ordered.orderedObjects.size) {
                "Library-reorder response duplicates a card"
            }
            require(ordered.orderedObjects.toSet() == domain.cards.toSet()) {
                "Library-reorder response must contain exactly the stored cards"
            }
        }

        private fun validateCombat(domain: CombatResolutionDomain, response: DecisionResponse) {
            val combat = response as? CombatResolutionResponse
                ?: throw IllegalArgumentException("Expected combat-resolution response")
            val editableOwners = domain.edges.map { it.editableBy }.distinct()
            require(editableOwners.size <= 1) {
                "Combat response ownership is not identifiable from the stored domain"
            }
            val attackersById = domain.attackers.associateBy { it.id }
            val blockersById = domain.blockers.associateBy { it.id }
            val defendersById = domain.defenders.associateBy { it.id }
            val edgesById = domain.edges.associateBy { it.id }
            require(edgesById.size == domain.edges.size) {
                "Combat domain contains duplicate damage edges"
            }
            domain.edges.forEach { edge ->
                require(edge.maximum >= 0 && edge.amount in 0..edge.maximum && edge.lethal >= 0) {
                    "Combat domain contains an invalid damage edge bound"
                }
                when (edge.direction) {
                    CombatDamageDirection.ATTACKER_TO_BLOCKER -> {
                        val attacker = attackersById[edge.sourceId]
                            ?: throw IllegalArgumentException("Combat edge source is not an attacker")
                        val blocker = blockersById[edge.targetId]
                            ?: throw IllegalArgumentException("Combat edge target is not a blocker")
                        require(
                            edge.targetId in attacker.blockedByIds &&
                                edge.sourceId in blocker.blockedAttackerIds
                        ) {
                            "Combat edge does not match the stored attacker/blocker relation"
                        }
                    }

                    CombatDamageDirection.BLOCKER_TO_ATTACKER -> {
                        val blocker = blockersById[edge.sourceId]
                            ?: throw IllegalArgumentException("Combat edge source is not a blocker")
                        val attacker = attackersById[edge.targetId]
                            ?: throw IllegalArgumentException("Combat edge target is not an attacker")
                        require(
                            edge.targetId in blocker.blockedAttackerIds &&
                                edge.sourceId in attacker.blockedByIds
                        ) {
                            "Combat edge does not match the stored blocker/attacker relation"
                        }
                    }

                    CombatDamageDirection.ATTACKER_TO_PLAYER,
                    CombatDamageDirection.ATTACKER_TO_PLANESWALKER,
                    CombatDamageDirection.ATTACKER_TO_BATTLE -> {
                        val attacker = attackersById[edge.sourceId]
                            ?: throw IllegalArgumentException("Combat drain source is not an attacker")
                        val defender = defendersById[edge.targetId]
                            ?: throw IllegalArgumentException("Combat drain target is not a defender")
                        require(attacker.attackedDefenderId == edge.targetId) {
                            "Combat drain does not target the attacked defender"
                        }
                        val expectedDirection = when (defender.kind) {
                            CombatTargetKind.PLAYER -> CombatDamageDirection.ATTACKER_TO_PLAYER
                            CombatTargetKind.PLANESWALKER -> CombatDamageDirection.ATTACKER_TO_PLANESWALKER
                            CombatTargetKind.BATTLE -> CombatDamageDirection.ATTACKER_TO_BATTLE
                        }
                        require(edge.direction == expectedDirection) {
                            "Combat drain direction does not match the stored defender kind"
                        }
                    }
                }
            }
            require(combat.edges.map { it.edgeId }.distinct().size == combat.edges.size) {
                "Combat response duplicates a damage edge"
            }
            combat.edges.forEach { amount ->
                val edge = edgesById[amount.edgeId]
                    ?: throw IllegalArgumentException("Combat response contains an unknown edge")
                require(amount.amount in 0..edge.maximum) {
                    "Combat response violates the stored edge range"
                }
            }
            val amounts = domain.edges.associate { edge ->
                edge.id to (combat.edges.firstOrNull { it.edgeId == edge.id }?.amount ?: edge.amount)
            }
            domain.edges.groupBy { it.sourceId }.forEach { (sourceId, sourceEdges) ->
                require(sourceEdges.map { it.maximum }.distinct().size == 1) {
                    "Combat domain contains inconsistent source totals"
                }
                require(sourceEdges.sumOf { amounts.getValue(it.id).toLong() } == sourceEdges.first().maximum.toLong()) {
                    "Combat response does not assign every source's damage exactly"
                }
            }
            domain.edges.filter { it.isTrampleDrain && amounts.getValue(it.id) > 0 }.forEach { drain ->
                val attacker = attackersById[drain.sourceId]
                    ?: throw IllegalArgumentException("Combat trample source is not an attacker")
                require(attacker.hasTrample) { "Combat trample drain has no trample source" }
                val blockerEdges = domain.edges.filter {
                    it.sourceId == drain.sourceId &&
                        it.direction == CombatDamageDirection.ATTACKER_TO_BLOCKER
                }
                attacker.blockedByIds.forEach { blockerId ->
                    val blockerEdge = blockerEdges.singleOrNull { it.targetId == blockerId }
                        ?: throw IllegalArgumentException("Combat trample drain is missing a blocker edge")
                    val blocker = blockersById[blockerId]
                        ?: throw IllegalArgumentException("Combat trample blocker is missing")
                    val deathtouch = blockerEdges.any { edge ->
                        edge.targetId == blockerId &&
                            amounts.getValue(edge.id) > 0 &&
                            attackersById[edge.sourceId]?.hasDeathtouch == true
                    }
                    require(
                        blocker.markedDamage.toLong() + amounts.getValue(blockerEdge.id).toLong() >=
                            blocker.toughness.toLong() || deathtouch
                    ) { "Combat trample drain is not preceded by lethal blocker damage" }
                }
            }
        }

        private fun validateManaSources(domain: ManaSourcesDomain, response: DecisionResponse) {
            val mana = response as? ManaSourcesSelectedResponse
                ?: throw IllegalArgumentException("Expected mana-source response")
            require(!mana.autoPay) { "AutoPay is not a trusted semantic choice" }
            require(!mana.declined || domain.canDecline) {
                "Mana-source response declines a non-declinable payment"
            }
            if (mana.declined) {
                require(mana.paymentPlan == null) {
                    "A declined pending payment cannot include a payment plan"
                }
                require(mana.selectedSources.isEmpty() && mana.waterbendPermanents.isEmpty()) {
                    "A declined pending payment cannot include legacy payment selections"
                }
                return
            }
            require(mana.selectedSources.isEmpty() && mana.waterbendPermanents.isEmpty()) {
                "Trusted pending payment must use only its explicit V3 payment plan"
            }
            val plan = requireNotNull(mana.paymentPlan) {
                "Trusted pending payment requires a complete explicit V3 payment plan"
            }
            StoredActionPayloadValidator.requirePaymentPlan(domain.paymentDomain, plan)
        }

        private fun validateReplacement(domain: ReplacementDomain, response: DecisionResponse) {
            val replacement = response as? ReplacementChosenResponse
                ?: throw IllegalArgumentException("Expected replacement response")
            require(domain.fromMetadata.size == domain.fromOptions.size) {
                "Stored replacement domain has incomplete FROM metadata"
            }
            require(domain.toMetadata.size == domain.toOptions.size) {
                "Stored replacement domain has incomplete TO metadata"
            }
            require(domain.defaultFromIndex == null || domain.defaultFromIndex in domain.fromOptions.indices) {
                "Stored replacement domain has an invalid default"
            }
            require(replacement.fromIndex in domain.fromOptions.indices) {
                "Replacement response has an outside-domain from index"
            }
            require(replacement.toIndex in domain.toOptions.indices) {
                "Replacement response has an outside-domain to index"
            }
            require(replacement.toIndex in domain.allowedToByFrom[replacement.fromIndex]) {
                "Replacement response violates the stored relation"
            }
        }

        private fun validateBudget(domain: BudgetModalDomain, response: DecisionResponse) {
            val budget = response as? BudgetModalResponse
                ?: throw IllegalArgumentException("Expected budget-modal response")
            require(domain.budget >= 0 && domain.modes.all { it.cost >= 0 }) {
                "Stored budget-modal domain is malformed"
            }
            require(budget.selectedModeIndices.all { it in domain.modes.indices }) {
                "Budget response contains an outside-domain mode"
            }
            require(budget.selectedModeIndices.sumOf { domain.modes[it].cost } <= domain.budget) {
                "Budget response exceeds the stored budget"
            }
        }
    }
}

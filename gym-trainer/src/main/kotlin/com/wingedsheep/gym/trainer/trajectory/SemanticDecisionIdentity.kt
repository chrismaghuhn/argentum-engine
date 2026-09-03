package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.engine.core.*
import com.wingedsheep.gym.contract.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
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

/** Version of the semantic decision-identity input contract. */
const val SEMANTIC_DECISION_IDENTITY_V1_VERSION: Int = 1

/** Exact schema identity used in the semanticDecisionId preimage. */
const val SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY: String =
    "argentum-trajectory-semantic-decision@v1"

/** A full transport-free action candidate plus the externally supplied semantic choice. */
@Serializable
data class ChosenSemanticActionV1(
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
        CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )
        Companion.validateChoicePayload(candidate, choicePayload)
    }

    /** Canonical full chosen-action JSON; the candidate is never reduced to a digest. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(semanticElement())

    internal fun replaySemanticElement(): JsonObject = buildJsonObject {
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
            validateChoicePayload(storedCandidate, choicePayload)
            return ChosenSemanticActionV1(
                candidate = storedCandidate,
                choicePayload = choicePayload,
            )
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

/** A full semantic response validated against one stored folded or structured domain. */
@Serializable
data class ChosenSemanticResponseV1(
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
        Companion.decodeResponse(response)
    }

    /** Canonical full chosen-response JSON without the live decision ID. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(semanticElement())

    internal fun replaySemanticElement(): JsonObject = buildJsonObject {
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
            val decoded = decodeResponse(semanticInput)
            val normalized = semanticResponseJson(decoded)
            when (domain.kind) {
                CompleteLegalDomainKind.ACTION_CANDIDATES ->
                    throw IllegalArgumentException("Action-candidate domains require a chosen action")

                CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS ->
                    requireFoldedMembership(domain, normalized)

                CompleteLegalDomainKind.STRUCTURED_DECISION ->
                    validateStructuredMembership(domain, decoded)
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

        private fun semanticResponseJson(response: DecisionResponse): JsonObject {
            val encoded = A3SemanticJson.strictJson
                .encodeToJsonElement(DecisionResponse.serializer(), response)
                .jsonObject
            val excluded = buildSet {
                add("decisionId")
                if (response is ManaSourcesSelectedResponse) add("autoPay")
            }
            return JsonObject(encoded.filterKeys { it !in excluded })
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
            targets.selectedTargets.forEach { (index, selected) ->
                val requirement = requirementsByIndex[index]
                    ?: throw IllegalArgumentException("Target response has an unknown requirement")
                require(selected.distinct().size == selected.size) {
                    "Target response duplicates a selected target"
                }
                require(selected.all { it in requirement.candidates }) {
                    "Target response contains an outside-domain target"
                }
                require(selected.size in requirement.minTargets..requirement.maxTargets) {
                    "Target response violates the stored target cardinality"
                }
            }
        }

        private fun validateCardSelection(domain: CardSelectionDomain, response: DecisionResponse) {
            val cards = response as? CardsSelectedResponse
                ?: throw IllegalArgumentException("Expected card-selection response")
            require(cards.selectedCards.distinct().size == cards.selectedCards.size) {
                "Card-selection response duplicates a card"
            }
            require(cards.selectedCards.all { it in domain.options }) {
                "Card-selection response contains an outside-domain card"
            }
            require(cards.selectedCards.size in domain.minSelections..domain.maxSelections) {
                "Card-selection response violates the stored cardinality"
            }
        }

        private fun validateModes(domain: ModeSelectionDomain, response: DecisionResponse) {
            val modes = response as? ModesChosenResponse
                ?: throw IllegalArgumentException("Expected mode-selection response")
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
            val edgesById = domain.edges.associateBy { it.id }
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
        }

        private fun validateManaSources(domain: ManaSourcesDomain, response: DecisionResponse) {
            val mana = response as? ManaSourcesSelectedResponse
                ?: throw IllegalArgumentException("Expected mana-source response")
            require(!mana.autoPay) { "AutoPay is not a trusted semantic choice" }
            require(mana.selectedSources.distinct().size == mana.selectedSources.size) {
                "Mana-source response duplicates a source"
            }
            require(mana.selectedSources.all { selected ->
                domain.availableSources.any { it.entityId == selected }
            }) { "Mana-source response contains an outside-domain source" }
            require(mana.waterbendPermanents.all { selected ->
                domain.waterbendPermanents.any { it.entityId == selected }
            }) { "Mana-source response contains an outside-domain Waterbend permanent" }
            require(!mana.declined || domain.canDecline) {
                "Mana-source response declines a non-declinable payment"
            }
        }

        private fun validateReplacement(domain: ReplacementDomain, response: DecisionResponse) {
            val replacement = response as? ReplacementChosenResponse
                ?: throw IllegalArgumentException("Expected replacement response")
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
            require(budget.selectedModeIndices.all { it in domain.modes.indices }) {
                "Budget response contains an outside-domain mode"
            }
            require(budget.selectedModeIndices.sumOf { domain.modes[it].cost } <= domain.budget) {
                "Budget response exceeds the stored budget"
            }
        }
    }
}

/** Exact A3 preimage fields; policy and collection provenance are intentionally absent. */
@Serializable
data class SemanticDecisionIdentityV1(
    val version: Int = SEMANTIC_DECISION_IDENTITY_V1_VERSION,
    val schemaIdentity: String = SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY,
    val semanticEpisodeId: String,
    val replayPrefixDigest: String,
    val replayActionIndex: Int,
    val perspectivePlayerId: String,
    val decisionKind: String,
    val observationDigest: String,
    val candidateDomainDigest: String,
) {
    init {
        require(version == SEMANTIC_DECISION_IDENTITY_V1_VERSION) {
            "Unsupported semantic decision-identity version"
        }
        require(schemaIdentity == SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY) {
            "Unsupported semantic decision-identity schema"
        }
        A3SemanticJson.requireSha256(semanticEpisodeId, "semantic episode identity")
        A3SemanticJson.requireSha256(replayPrefixDigest, "replay-prefix digest")
        A3SemanticJson.requireSha256(observationDigest, "observation digest")
        A3SemanticJson.requireSha256(candidateDomainDigest, "candidate-domain digest")
        require(replayActionIndex >= 0) { "Replay action index must be non-negative" }
        require(perspectivePlayerId.isNotBlank()) { "Perspective player identity is required" }
        require(decisionKind.isNotBlank()) { "Decision kind is required" }
    }

    /** Canonical JSON with exactly the seven semantic identity inputs plus schema. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(buildJsonObject {
        put("schema", SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY)
        put("semanticEpisodeId", semanticEpisodeId)
        put("replayPrefixDigest", replayPrefixDigest)
        put("replayActionIndex", replayActionIndex)
        put("perspectivePlayerId", perspectivePlayerId)
        put("decisionKind", decisionKind)
        put("observationDigest", observationDigest)
        put("candidateDomainDigest", candidateDomainDigest)
    })

    fun semanticDecisionId(): SemanticDecisionIdV1 = SemanticDecisionIdV1.from(this)

    fun compute(): SemanticDecisionIdV1 = semanticDecisionId()

    companion object {
        fun from(
            semanticEpisodeId: String,
            prefix: SemanticReplayPrefixV1,
            replayActionIndex: Int = prefix.inputs.size,
            perspectivePlayerId: String,
            decisionKind: String,
            observation: PlayerObservationV1,
            domain: CompleteLegalDomainV1,
        ): SemanticDecisionIdentityV1 {
            require(replayActionIndex == prefix.inputs.size) {
                "Replay action index must equal the supplied prefix length"
            }
            return SemanticDecisionIdentityV1(
                semanticEpisodeId = semanticEpisodeId,
                replayPrefixDigest = prefix.digest().value,
                replayActionIndex = replayActionIndex,
                perspectivePlayerId = perspectivePlayerId,
                decisionKind = decisionKind,
                observationDigest = observation.observationDigest,
                candidateDomainDigest = CandidateDomainDigestV1.from(domain).value,
            )
        }
    }
}

/** Versioned content-addressed semantic decision identity. */
@Serializable
data class SemanticDecisionIdV1(
    val version: Int = SEMANTIC_DECISION_IDENTITY_V1_VERSION,
    val schemaIdentity: String = SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY,
    val value: String,
) {
    init {
        require(version == SEMANTIC_DECISION_IDENTITY_V1_VERSION) {
            "Unsupported semantic decision-id version"
        }
        require(schemaIdentity == SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY) {
            "Unsupported semantic decision-id schema"
        }
        A3SemanticJson.requireSha256(value, "semantic decision identity")
    }

    companion object {
        fun from(input: SemanticDecisionIdentityV1): SemanticDecisionIdV1 = SemanticDecisionIdV1(
            value = A3SemanticJson.sha256(input.canonicalJson().toByteArray(Charsets.UTF_8))
        )
    }
}

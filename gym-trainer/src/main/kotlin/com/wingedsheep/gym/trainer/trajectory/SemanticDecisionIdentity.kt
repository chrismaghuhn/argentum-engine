package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.legalactions.*
import com.wingedsheep.gym.contract.*
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

/** Version of the semantic decision-identity input contract. */
const val SEMANTIC_DECISION_IDENTITY_V1_VERSION: Int = 1

/** Exact schema identity used in the semanticDecisionId preimage. */
const val SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY: String =
    "argentum-trajectory-semantic-decision@v1"

/** Exact A3 preimage fields; policy and collection provenance are intentionally absent. */
@ConsistentCopyVisibility
@Serializable
data class SemanticDecisionIdentityV1 private constructor(
    val version: Int = SEMANTIC_DECISION_IDENTITY_V1_VERSION,
    val schemaIdentity: String = SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY,
    val semanticEpisodeId: String,
    val replayPrefixDigest: String,
    val replayActionIndex: Int,
    val perspectivePlayerId: String,
    val decisionKind: SemanticDecisionKindV1,
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
    }

    /** Canonical JSON with exactly the seven semantic identity inputs plus schema. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(buildJsonObject {
        put("schema", SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY)
        put("semanticEpisodeId", semanticEpisodeId)
        put("replayPrefixDigest", replayPrefixDigest)
        put("replayActionIndex", replayActionIndex)
        put("perspectivePlayerId", perspectivePlayerId)
        put("decisionKind", decisionKind.name)
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
            observation: PlayerObservationV1,
            domain: CompleteLegalDomainV1,
            perspectivePlayerId: String? = null,
            decisionKind: SemanticDecisionKindV1? = null,
        ): SemanticDecisionIdentityV1 {
            require(replayActionIndex == prefix.inputs.size) {
                "Replay action index must equal the supplied prefix length"
            }
            return fromDigest(
                semanticEpisodeId = semanticEpisodeId,
                prefixDigest = prefix.digest(),
                prefixInputCount = prefix.inputs.size,
                replayActionIndex = replayActionIndex,
                observation = observation,
                domain = domain,
                perspectivePlayerId = perspectivePlayerId,
                decisionKind = decisionKind,
            )
        }

        /** Build the same frozen A3 identity from a real linear prefix accumulator. */
        internal fun from(
            semanticEpisodeId: String,
            prefixAccumulator: SemanticReplayPrefixAccumulatorV1,
            replayActionIndex: Int = prefixAccumulator.inputCount,
            observation: PlayerObservationV1,
            domain: CompleteLegalDomainV1,
            perspectivePlayerId: String? = null,
            decisionKind: SemanticDecisionKindV1? = null,
        ): SemanticDecisionIdentityV1 {
            return fromDigest(
                semanticEpisodeId = semanticEpisodeId,
                prefixDigest = prefixAccumulator.currentPrefixDigest(),
                prefixInputCount = prefixAccumulator.inputCount,
                replayActionIndex = replayActionIndex,
                observation = observation,
                domain = domain,
                perspectivePlayerId = perspectivePlayerId,
                decisionKind = decisionKind,
            )
        }

        private fun fromDigest(
            semanticEpisodeId: String,
            prefixDigest: SemanticReplayPrefixDigestV1,
            prefixInputCount: Int,
            replayActionIndex: Int,
            observation: PlayerObservationV1,
            domain: CompleteLegalDomainV1,
            perspectivePlayerId: String?,
            decisionKind: SemanticDecisionKindV1?,
        ): SemanticDecisionIdentityV1 {
            require(replayActionIndex == prefixInputCount) {
                "Replay action index must equal the supplied prefix accumulator input count"
            }
            val derivedPerspectivePlayerId = observation.perspectivePlayerId.value
            require(perspectivePlayerId == null || perspectivePlayerId == derivedPerspectivePlayerId) {
                "Perspective identity does not match the public observation"
            }
            val derivedDecisionKind = deriveDecisionKind(observation, domain)
            require(decisionKind == null || decisionKind == derivedDecisionKind) {
                "Decision kind does not match the public observation/domain"
            }
            return SemanticDecisionIdentityV1(
                semanticEpisodeId = semanticEpisodeId,
                replayPrefixDigest = prefixDigest.value,
                replayActionIndex = replayActionIndex,
                perspectivePlayerId = derivedPerspectivePlayerId,
                decisionKind = derivedDecisionKind,
                observationDigest = observation.observationDigest,
                candidateDomainDigest = CandidateDomainDigestV1.from(domain).value,
            )
        }

        private fun deriveDecisionKind(
            observation: PlayerObservationV1,
            domain: CompleteLegalDomainV1,
        ): SemanticDecisionKindV1 {
            val derived = when (domain.kind) {
                CompleteLegalDomainKind.ACTION_CANDIDATES -> {
                    require(observation.pendingDecision == null) {
                        "Action-candidate identity cannot carry a pending decision"
                    }
                    SemanticDecisionKindV1.PRIORITY
                }

                CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS ->
                    fromPendingKind(requireNotNull(domain.decisionKind))

                CompleteLegalDomainKind.STRUCTURED_DECISION -> {
                    val kind = fromPendingKind(requireNotNull(domain.decisionKind))
                    requireStructuredDomainKind(domain.structuredDomain, kind)
                    kind
                }
            }

            observation.pendingDecision?.let { pending ->
                val pendingKind = fromPendingKind(pending.kind)
                require(pendingKind == derived) {
                    "Pending observation kind does not match the stored domain"
                }
                when (domain.kind) {
                    CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS ->
                        require(!pending.requiresStructuredResponse) {
                            "Folded domain conflicts with a structured pending response"
                        }

                    CompleteLegalDomainKind.STRUCTURED_DECISION ->
                        require(pending.requiresStructuredResponse) {
                            "Structured domain conflicts with a folded pending response"
                        }

                    CompleteLegalDomainKind.ACTION_CANDIDATES ->
                        error("Unreachable action-candidate pending decision")
                }
            }
            return derived
        }

        private fun fromPendingKind(kind: PendingDecisionKind): SemanticDecisionKindV1 =
            SemanticDecisionKindV1.values().firstOrNull { it.name == kind.name }
                ?: throw IllegalArgumentException("Unsupported semantic decision kind")

        private fun requireStructuredDomainKind(
            domain: StructuredDecisionDomain?,
            decisionKind: SemanticDecisionKindV1,
        ) {
            val expected = when (domain) {
                is TargetsDomain -> SemanticDecisionKindV1.CHOOSE_TARGETS
                is CardSelectionDomain -> SemanticDecisionKindV1.SELECT_CARDS
                is ModeSelectionDomain -> SemanticDecisionKindV1.CHOOSE_MODE
                is DistributionDomain -> SemanticDecisionKindV1.DISTRIBUTE
                is OrderingDomain -> SemanticDecisionKindV1.ORDER_OBJECTS
                is SplitPilesDomain -> SemanticDecisionKindV1.SPLIT_PILES
                is SearchLibraryDomain -> SemanticDecisionKindV1.SEARCH_LIBRARY
                is ReorderLibraryDomain -> SemanticDecisionKindV1.REORDER_LIBRARY
                is CombatResolutionDomain -> SemanticDecisionKindV1.COMBAT_RESOLUTION
                is ManaSourcesDomain -> SemanticDecisionKindV1.SELECT_MANA_SOURCES
                is ReplacementDomain -> SemanticDecisionKindV1.CHOOSE_REPLACEMENT
                is BudgetModalDomain -> SemanticDecisionKindV1.BUDGET_MODAL
                null -> throw IllegalArgumentException("Structured decision has no stored domain")
            }
            require(expected == decisionKind) {
                "Stored structured domain kind does not match the decision kind"
            }
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

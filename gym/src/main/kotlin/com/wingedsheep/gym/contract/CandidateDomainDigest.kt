package com.wingedsheep.gym.contract

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Version of the complete public-domain contract. */
const val COMPLETE_LEGAL_DOMAIN_VERSION: Int = 1

/** Stable identity for the complete action/decision domain contract. */
const val COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY: String =
    "argentum-gym-action-domain@v1"

/** Version of the standalone candidate-domain digest contract. */
const val CANDIDATE_DOMAIN_DIGEST_VERSION: Int = 1

/** Stable identity for the standalone candidate-domain digest contract. */
const val CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY: String =
    "argentum-gym-candidate-domain-digest@v1"

/** Prefix separating the digest contract from the canonical domain payload. */
const val CANDIDATE_DOMAIN_DIGEST_PREIMAGE_PREFIX: String =
    "argentum-gym-candidate-domain-digest@v1\n"

/** The three public legal-domain shapes emitted by the A2 boundary. */
@Serializable
enum class CompleteLegalDomainKind {
    ACTION_CANDIDATES,
    FOLDED_DECISION_OPTIONS,
    STRUCTURED_DECISION,
}

/**
 * The single durable representation of the public legal domain at one decision boundary.
 *
 * Action and folded-decision candidates are transport-free semantic action fingerprints sourced
 * from [LegalActionView]. Structured decisions retain the exact typed
 * [StructuredDecisionDomain] supplied by [PendingDecisionView]. No constructor accepts Rules
 * state, raw actions, or raw pending decisions.
 */
@Serializable
data class CompleteLegalDomainV1(
    val version: Int = COMPLETE_LEGAL_DOMAIN_VERSION,
    val schemaIdentity: String = COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY,
    val kind: CompleteLegalDomainKind,
    val decisionKind: PendingDecisionKind? = null,
    val shape: DecisionShape? = null,
    val candidates: List<JsonObject> = emptyList(),
    val structuredDomain: StructuredDecisionDomain? = null,
) {
    init {
        require(version == COMPLETE_LEGAL_DOMAIN_VERSION) {
            "Unsupported complete legal-domain version: $version"
        }
        require(schemaIdentity == COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY) {
            "Unsupported complete legal-domain identity: $schemaIdentity"
        }
        validateCandidateList(candidates)
        when (kind) {
            CompleteLegalDomainKind.ACTION_CANDIDATES -> {
                require(decisionKind == null && shape == null && structuredDomain == null) {
                    "Action candidate domains cannot carry pending-decision fields"
                }
            }

            CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS -> {
                require(decisionKind != null && shape != null) {
                    "Folded decision domains require decision kind and shape"
                }
                require(structuredDomain == null) {
                    "Folded decision domains cannot carry a structured domain"
                }
                require(candidates.all { it["isDecisionOption"]?.jsonPrimitive?.content == "true" }) {
                    "Folded decision domains require decision-option candidates"
                }
            }

            CompleteLegalDomainKind.STRUCTURED_DECISION -> {
                require(decisionKind != null && shape != null) {
                    "Structured decision domains require decision kind and shape"
                }
                require(candidates.isEmpty()) {
                    "Structured decision domains cannot carry folded candidates"
                }
                requireSupportedStructuredDomain(checkNotNull(structuredDomain))
            }
        }
    }

    /** Canonical semantic JSON; candidate-list and Rules-significant sequence order is retained. */
    fun canonicalJson(): String = CompleteLegalDomainCanonicalizer.canonicalJson(this)

    companion object {
        /**
         * Normalize the current actor-facing public observation into exactly one complete domain.
         * Non-acting observations have no policy domain and therefore normalize to an explicit
         * empty action-candidate domain. An acting structured decision without its typed domain is
         * unsupported and fails closed.
         */
        fun from(observation: TrainingObservation): CompleteLegalDomainV1 {
            val pending = observation.pendingDecision
            if (pending == null) {
                return CompleteLegalDomainV1(
                    kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
                    candidates = semanticCandidates(observation.legalActions),
                )
            }

            val isActingPerspective = observation.agentToAct == observation.perspectivePlayerId
            if (!isActingPerspective) {
                return CompleteLegalDomainV1(
                    kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
                    candidates = emptyList(),
                )
            }

            if (pending.requiresStructuredResponse) {
                require(observation.legalActions.isEmpty()) {
                    "Structured decisions cannot carry folded legal-action candidates"
                }
                val structuredDomain = requireNotNull(pending.structuredDomain) {
                    "Acting structured decision has no supported structured domain"
                }
                return CompleteLegalDomainV1(
                    kind = CompleteLegalDomainKind.STRUCTURED_DECISION,
                    decisionKind = pending.kind,
                    shape = pending.shape,
                    structuredDomain = structuredDomain,
                )
            }

            require(pending.structuredDomain == null) {
                "Folded decision cannot carry a structured domain"
            }
            return CompleteLegalDomainV1(
                kind = CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
                decisionKind = pending.kind,
                shape = pending.shape,
                candidates = semanticCandidates(observation.legalActions),
            )
        }

        private fun semanticCandidates(actions: List<LegalActionView>): List<JsonObject> =
            actions.map { action ->
                ObservationCanonicalizer.canonicalElement(
                    ObservationCanonicalizer.semanticActionFingerprint(action)
                ).jsonObject
            }
    }
}

/** Versioned, content-addressed digest of one complete public legal domain. */
@Serializable
data class CandidateDomainDigestV1(
    val version: Int = CANDIDATE_DOMAIN_DIGEST_VERSION,
    val schemaIdentity: String = CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY,
    val value: String,
) {
    init {
        require(version == CANDIDATE_DOMAIN_DIGEST_VERSION) {
            "Unsupported candidate-domain digest version: $version"
        }
        require(schemaIdentity == CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY) {
            "Unsupported candidate-domain digest identity: $schemaIdentity"
        }
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Candidate-domain digest must be lowercase SHA-256 hex"
        }
    }

    companion object {
        fun from(domain: CompleteLegalDomainV1): CandidateDomainDigestV1 {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(
                    (CANDIDATE_DOMAIN_DIGEST_PREIMAGE_PREFIX + domain.canonicalJson())
                        .toByteArray(StandardCharsets.UTF_8)
                )
            val value = bytes.joinToString("") { "%02x".format(it) }
            return CandidateDomainDigestV1(value = value)
        }

        fun from(observation: TrainingObservation): CandidateDomainDigestV1 =
            from(CompleteLegalDomainV1.from(observation))
    }
}

private val completeLegalDomainJson = Json {
    encodeDefaults = true
    explicitNulls = true
    classDiscriminator = "type"
    allowStructuredMapKeys = true
}

private object CompleteLegalDomainCanonicalizer {

    fun canonicalJson(domain: CompleteLegalDomainV1): String {
        val raw = buildJsonObject {
            put("version", domain.version)
            put("schemaIdentity", domain.schemaIdentity)
            put("kind", domain.kind.name)
            domain.decisionKind?.let { put("decisionKind", it.name) }
            domain.shape?.let {
                put("shape", completeLegalDomainJson.encodeToJsonElement(DecisionShape.serializer(), it))
            }
            put("candidates", buildJsonArray { domain.candidates.forEach(::add) })
            domain.structuredDomain?.let { structured ->
                val encoded = completeLegalDomainJson.encodeToJsonElement(
                    StructuredDecisionDomain.serializer(),
                    structured,
                ).jsonObject
                put(
                    "structuredDomain",
                    ObservationCanonicalizer.semanticStructuredDomain(encoded),
                )
            }
        }
        return ObservationCanonicalizer.canonicalDomainJson(raw)
    }
}

private val supportedStructuredDomainVersions = mapOf(
    "targets" to TARGETS_DOMAIN_VERSION,
    "card-selection" to STRUCTURED_DECISION_DOMAIN_VERSION,
    "mode-selection" to STRUCTURED_DECISION_DOMAIN_VERSION,
    "distribution" to STRUCTURED_DECISION_DOMAIN_VERSION,
    "ordering" to STRUCTURED_DECISION_DOMAIN_VERSION,
    "split-piles" to STRUCTURED_DECISION_DOMAIN_VERSION,
    "search-library" to STRUCTURED_DECISION_DOMAIN_VERSION,
    "reorder-library" to STRUCTURED_DECISION_DOMAIN_VERSION,
    "combat-resolution" to STRUCTURED_DECISION_DOMAIN_VERSION,
    "mana-sources" to MANA_SOURCES_DOMAIN_VERSION,
    "replacement" to STRUCTURED_DECISION_DOMAIN_VERSION,
    "budget-modal" to STRUCTURED_DECISION_DOMAIN_VERSION,
)

private val candidateForbiddenKeys = setOf(
    "actionId",
    "decisionId",
    "description",
    "prompt",
    "sourceName",
    "effectHint",
)

private fun validateCandidateList(candidates: List<JsonObject>) {
    require(candidates.all { candidate -> candidate.keys.none(candidateForbiddenKeys::contains) }) {
        "Complete legal-domain candidates cannot contain transport or presentation fields"
    }
    val canonical = candidates.map(ObservationCanonicalizer::canonicalJson)
    require(canonical.distinct().size == canonical.size) {
        "Complete legal-domain candidates cannot contain duplicate semantic candidates"
    }
}

private fun requireSupportedStructuredDomain(domain: StructuredDecisionDomain) {
    val json = completeLegalDomainJson
        .encodeToJsonElement(StructuredDecisionDomain.serializer(), domain)
        .jsonObject
    val type = json["type"]?.jsonPrimitive?.content
    val expectedVersion = supportedStructuredDomainVersions[type]
    require(expectedVersion != null) {
        "Unsupported structured legal-domain kind: $type"
    }
    val version = json["version"]?.jsonPrimitive?.content?.toIntOrNull()
    require(version == expectedVersion) {
        "Unsupported $type structured legal-domain version: $version"
    }
    validateStructuredDomainRelations(domain)
}

private fun validateStructuredDomainRelations(domain: StructuredDecisionDomain) {
    fun <T> requireDistinct(values: List<T>, label: String) {
        require(values.distinct().size == values.size) {
            "$label cannot contain duplicate semantic members"
        }
    }

    when (domain) {
        is TargetsDomain -> {
            requireDistinct(domain.requirements.map { it.index }, "Target requirements")
            domain.requirements.forEach { requireDistinct(it.candidates, "Target candidates") }
        }

        is CardSelectionDomain -> {
            requireDistinct(domain.options, "Card-selection options")
            requireDistinct(domain.nonSelectableOptions, "Non-selectable options")
        }

        is ModeSelectionDomain -> requireDistinct(domain.modes.map { it.index }, "Mode options")
        is DistributionDomain -> requireDistinct(domain.targets, "Distribution targets")
        is OrderingDomain -> requireDistinct(domain.objects, "Ordering objects")
        is SplitPilesDomain -> requireDistinct(domain.cards, "Split-pile cards")
        is SearchLibraryDomain -> requireDistinct(domain.options, "Search-library options")
        is ReorderLibraryDomain -> requireDistinct(domain.cards, "Reorder-library cards")
        is CombatResolutionDomain -> {
            requireDistinct(domain.attackers.map { it.id }, "Combat attackers")
            requireDistinct(domain.blockers.map { it.id }, "Combat blockers")
            requireDistinct(domain.defenders.map { it.id }, "Combat defenders")
            requireDistinct(domain.edges.map { it.id }, "Combat damage edges")
        }

        is ManaSourcesDomain -> {
            requireDistinct(
                domain.availableSources.map { it.entityId to it.manaAbilityKey },
                "Mana source options",
            )
            requireDistinct(domain.waterbendPermanents.map { it.entityId }, "Waterbend permanents")
        }

        is ReplacementDomain -> {
            require(domain.allowedToByFrom.size == domain.fromOptions.size) {
                "Replacement relation must have one row per from option"
            }
            domain.allowedToByFrom.forEach { allowed ->
                requireDistinct(allowed, "Replacement relation row")
                require(allowed.all { it in domain.toOptions.indices }) {
                    "Replacement relation contains an out-of-domain target index"
                }
            }
        }

        is BudgetModalDomain -> Unit
    }
}

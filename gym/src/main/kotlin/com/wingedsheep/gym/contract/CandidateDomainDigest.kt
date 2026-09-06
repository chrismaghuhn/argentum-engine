package com.wingedsheep.gym.contract

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
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
                CandidateSemanticValidator.requireProducerCanonical(action)
                val fingerprint = ObservationCanonicalizer.semanticActionFingerprint(action)
                CandidateSemanticValidator.requireValid(fingerprint)
                fingerprint
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

private val candidateKeys = setOf(
    "kind",
    "affordable",
    "sourceEntityId",
    "targetEntityIds",
    "targetDomain",
    "attackDeclarationDomain",
    "blockerDeclarationDomain",
    "manaCost",
    "paymentDomain",
    "targetPaymentDomain",
    "hasXCost",
    "maxAffordableX",
    "minTargets",
    "maxTargets",
    "repeatCountDomain",
    "validSacrificeTargets",
    "sacrificeCount",
    "sacrificeMinCount",
    "sacrificeMaxCount",
    "requiresDamageDistribution",
    "isManaAbility",
    "availableManaColors",
    "requiresStructuredAction",
    "requiredPayloadFields",
    "actionSemantics",
    "isDecisionOption",
)

private val candidateRequiredKeys = setOf(
    "kind",
    "affordable",
    "sourceEntityId",
    "targetEntityIds",
    "manaCost",
    "hasXCost",
    "maxAffordableX",
    "minTargets",
    "maxTargets",
    "validSacrificeTargets",
    "sacrificeCount",
    "sacrificeMinCount",
    "sacrificeMaxCount",
    "requiresDamageDistribution",
    "isManaAbility",
    "requiresStructuredAction",
    "requiredPayloadFields",
    "actionSemantics",
    "isDecisionOption",
)

private fun validateCandidateList(candidates: List<JsonObject>) {
    candidates.forEach(CandidateSemanticValidator::requireValid)
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
    CandidateSemanticValidator.requireStructuredDomainProducerCanonical(domain)
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
                domain.paymentDomain.sourceActivationOptions.map { option ->
                    option.sourceId to option.manaAbilityKey
                },
                "Pending payment source options",
            )
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

/**
 * Validates the serialized semantic candidate shape independently of the source DTO constructors.
 * A CompleteLegalDomainV1 is a durable boundary, so its JsonObject candidates must not become a
 * weakly typed escape hatch after deserialization.
 */
private object CandidateSemanticValidator {

    fun requireProducerCanonical(action: LegalActionView) {
        requireCanonicalEntityIds(action.targetEntityIds, "targetEntityIds")
        requireCanonicalEntityIds(action.validSacrificeTargets, "validSacrificeTargets")
        action.availableManaColors?.let { colors ->
            require(colors == colors.distinct().sortedBy(Color::ordinal)) {
                "Producer returned noncanonical availableManaColors"
            }
        }
        action.targetDomain?.let(::requireProducerCanonical)
        action.attackDeclarationDomain?.let(::requireProducerCanonical)
        action.blockerDeclarationDomain?.let(::requireProducerCanonical)
        action.paymentDomain?.let(::requirePaymentDomain)
        action.targetPaymentDomain?.let(::requireTargetPaymentDomain)
        action.repeatCountDomain?.let { domain ->
            decodeRepeatCountDomain(
                completeLegalDomainJson.encodeToJsonElement(
                    RepeatCountDomainV1.serializer(),
                    domain,
                ),
            )
        }
    }

    fun requireValid(candidate: JsonObject) {
        require(candidate.keys.none(candidateForbiddenKeys::contains)) {
            "Complete legal-domain candidates cannot contain transport or presentation fields"
        }
        require(candidate.keys.all(candidateKeys::contains)) {
            "Complete legal-domain candidate has an unsupported field shape"
        }
        require(candidateRequiredKeys.all(candidate::containsKey)) {
            "Complete legal-domain candidate is missing a required semantic field"
        }

        candidate.required("kind").requireString()
        candidate.required("affordable").requireBoolean()
        candidate.required("sourceEntityId").requireNullableString()
        candidate.required("targetEntityIds").requireCanonicalEntityArray("targetEntityIds")
        candidate.required("manaCost").requireNullableString()
        candidate.required("hasXCost").requireBoolean()
        candidate.required("maxAffordableX").requireNullableNonNegativeInt()
        candidate.required("minTargets").requireNonNegativeInt()
        candidate.required("maxTargets").requireNonNegativeInt()
        require(candidate.required("maxTargets").intValue() >= candidate.required("minTargets").intValue()) {
            "Complete legal-domain candidate has an invalid target range"
        }
        candidate["repeatCountDomain"]?.let { decodeRepeatCountDomain(it) }
        candidate.required("validSacrificeTargets").requireCanonicalEntityArray("validSacrificeTargets")
        candidate.required("sacrificeCount").requireNonNegativeInt()
        candidate.required("sacrificeMinCount").requireNonNegativeInt()
        candidate.required("sacrificeMaxCount").requireNonNegativeInt()
        require(
            candidate.required("sacrificeMaxCount").intValue() >=
                candidate.required("sacrificeMinCount").intValue()
        ) {
            "Complete legal-domain candidate has an invalid sacrifice range"
        }
        candidate.required("requiresDamageDistribution").requireBoolean()
        candidate.required("isManaAbility").requireBoolean()
        candidate["availableManaColors"]?.let { colors ->
            val names = colors.requireStringArray("availableManaColors")
            val parsed = names.map { name ->
                runCatching { Color.valueOf(name) }.getOrElse {
                    throw IllegalArgumentException("Complete legal-domain candidate has an unsupported color")
                }
            }
            require(parsed == parsed.distinct().sortedBy(Color::ordinal)) {
                "Complete legal-domain candidate has noncanonical availableManaColors"
            }
        }
        candidate.required("requiresStructuredAction").requireBoolean()
        requireCanonicalRequiredPayloadFields(candidate.required("requiredPayloadFields"))
        requireSemanticActionPayload(candidate.required("actionSemantics").requireObject("actionSemantics"))
        candidate.required("isDecisionOption").requireBoolean()

        candidate["targetDomain"]?.let { requireTargetDomain(it.requireObject("targetDomain")) }
        candidate["attackDeclarationDomain"]?.let {
            requireAttackDomain(it.requireObject("attackDeclarationDomain"))
        }
        candidate["blockerDeclarationDomain"]?.let {
            requireBlockerDomain(it.requireObject("blockerDeclarationDomain"))
        }
        candidate["paymentDomain"]?.let { requirePaymentDomainJson(it.requireObject("paymentDomain")) }
        candidate["targetPaymentDomain"]?.let {
            requireTargetPaymentDomainJson(it.requireObject("targetPaymentDomain"))
        }
    }

    private fun requireProducerCanonical(domain: ActionTargetDomainV1) {
        require(domain.version == ACTION_TARGET_DOMAIN_VERSION) {
            "Unsupported action target domain version"
        }
        require(domain.composition == ActionTargetComposition.FIXED) {
            "Unsupported action target domain composition"
        }
        require(domain.requirements.map { it.index } == domain.requirements.indices.toList()) {
            "Producer returned noncanonical target requirement order"
        }
        domain.requirements.forEach { requirement ->
            requireCanonicalEntityIds(requirement.candidates, "target candidates")
            require(requirement.minTargets >= 0 && requirement.maxTargets >= requirement.minTargets) {
                "Producer returned an invalid target range"
            }
            require(requirement.candidates.size >= requirement.minTargets) {
                "Producer returned too few target candidates"
            }
        }
    }

    private fun requireProducerCanonical(domain: AttackDeclarationDomainV2) {
        requireAttackDomain(
            completeLegalDomainJson
                .encodeToJsonElement(AttackDeclarationDomainV2.serializer(), domain)
                .jsonObject
        )
    }

    private fun requireProducerCanonical(domain: BlockerDeclarationDomainV1) {
        requireBlockerDomain(
            completeLegalDomainJson
                .encodeToJsonElement(BlockerDeclarationDomainV1.serializer(), domain)
                .jsonObject
        )
    }

    fun requireStructuredDomainProducerCanonical(domain: StructuredDecisionDomain) {
        validateStructuredDomainProducerOrder(domain)
    }

    private fun requirePaymentDomain(domain: PaymentDomainV5) {
        // PaymentDomainV5's constructor is the producer's typed validation boundary. Re-encode
        // and decode with the strict A2 codec so the same nested shape is also durable-safe.
        requirePaymentDomainJson(
            completeLegalDomainJson.encodeToJsonElement(PaymentDomainV5.serializer(), domain).jsonObject
        )
    }

    private fun requireTargetPaymentDomain(domain: TargetPaymentDomainV1) {
        requireTargetPaymentDomainJson(
            completeLegalDomainJson.encodeToJsonElement(TargetPaymentDomainV1.serializer(), domain).jsonObject
        )
    }

    private fun requireTargetDomain(value: JsonObject) {
        value.requireKeys(
            setOf(
                "version",
                "composition",
                "requirements",
            )
        )
        require(value.required("version").intValue() == ACTION_TARGET_DOMAIN_VERSION) {
            "Unsupported action target domain version"
        }
        require(value.required("composition").stringValue() == ActionTargetComposition.FIXED.name) {
            "Unsupported action target domain composition"
        }
        val requirements = value.required("requirements").requireArray("target requirements")
        val indices = requirements.map { requirement ->
            val objectValue = requirement.requireObject("target requirement")
            objectValue.requireKeys(
                setOf(
                    "index",
                    "minTargets",
                    "maxTargets",
                    "candidates",
                    "targetZone",
                    "mustDifferFromEarlier",
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
                )
            )
            val index = objectValue.required("index").intValue()
            require(index == requirements.indexOf(requirement)) {
                "Target requirements are not in producer order"
            }
            val min = objectValue.required("minTargets").nonNegativeIntValue()
            val max = objectValue.required("maxTargets").nonNegativeIntValue()
            require(max >= min) { "Target requirement has an invalid range" }
            val candidates = objectValue.required("candidates").requireEntityArray("target candidates")
            require(candidates == candidates.distinct().sorted()) {
                "Target candidates are not in producer-canonical order"
            }
            require(candidates.size >= min) { "Target requirement has too few candidates" }
            objectValue.required("targetZone").requireNullableString()
            listOf(
                "mustDifferFromEarlier",
                "sameController",
                "sameOwner",
                "sameCreatureType",
                "sameCardType",
                "differentNames",
                "xConstrainsManaValue",
                "xConstrainsManaValueExactly",
                "xConstrainsPower",
                "xConstrainsCount",
            ).forEach { objectValue.required(it).requireBoolean() }
            objectValue.required("totalManaValueAtMost").requireNullableInt()
            index
        }
        require(indices == indices.indices.toList()) { "Target requirements have invalid indices" }
    }

    private fun requireAttackDomain(value: JsonObject) {
        value.requireKeys(
            setOf(
                "version",
                "attackerOrder",
                "attackerToDefenders",
                "mandatoryAttackers",
                "canDeclareZeroAttackers",
                "maxAttackers",
                "coAttackerRequirements",
                "bandConstraints",
            )
        )
        require(value.required("version").intValue() == ATTACK_DECLARATION_DOMAIN_V2_VERSION) {
            "Unsupported attack declaration domain version"
        }
        val attackers = value.required("attackerOrder").requireEntityArray("attacker order")
        requireDistinct(attackers, "attacker order")
        val attackerSet = attackers.toSet()
        val relation = value.required("attackerToDefenders").requireObject("attacker-to-defender relation")
        require(relation.keys == attackerSet) { "Attack domain is missing an attacker relation" }
        val defenders = relation.values.flatMap { it.requireEntityArray("attacker defenders") }
        relation.forEach { (_, related) ->
            requireDistinct(related.requireEntityArray("attacker defenders"), "attacker defenders")
        }
        val mandatory = value.required("mandatoryAttackers").requireEntityArray("mandatory attackers")
        requireDistinct(mandatory, "mandatory attackers")
        require(mandatory.all { it in attackerSet }) { "Mandatory attacker is outside the domain" }
        value.required("canDeclareZeroAttackers").requireBoolean()
        value.required("maxAttackers").requireNullableNonNegativeInt()
        val co = value.required("coAttackerRequirements").requireObject("co-attacker requirements")
        require(co.keys.all { it in attackerSet }) { "Co-attacker key is outside the domain" }
        co.values.forEach { requirements ->
            requirements.requireArray("co-attacker requirement list").forEach { requirement ->
                val anyOf = requirement.requireEntityArray("co-attacker any-of")
                require(anyOf.isNotEmpty()) { "Co-attacker any-of cannot be empty" }
                requireDistinct(anyOf, "co-attacker any-of")
                require(anyOf.all { it in attackerSet }) { "Co-attacker is outside the domain" }
            }
        }
        val bands = value.required("bandConstraints").requireObject("band constraints")
        bands.requireKeys(setOf("bandingAttackersByDefender", "nonBandingAttackersByDefender"))
        bands.values.forEach { mappingElement ->
            val mapping = mappingElement.requireObject("band constraint relation")
            require(mapping.keys.all { it in defenders }) { "Band constraint defender is outside the domain" }
            mapping.values.forEach { attackersElement ->
                val related = attackersElement.requireEntityArray("band constraint attackers")
                requireDistinct(related, "band constraint attackers")
                require(related.all { it in attackerSet }) { "Band constraint attacker is outside the domain" }
            }
        }
    }

    private fun requireBlockerDomain(value: JsonObject) {
        value.requireKeys(
            setOf(
                "version",
                "blockerOrder",
                "attackerOrder",
                "blockerToAttackers",
                "maxAttackersByBlocker",
                "minBlockersByAttacker",
                "maxBlockersByAttacker",
                "globalMaxBlockers",
                "coBlockerRequirements",
                "requirements",
                "minimumSatisfiedRequirementCount",
                "canDeclareZeroBlockers",
            )
        )
        require(value.required("version").intValue() == BLOCKER_DECLARATION_DOMAIN_VERSION) {
            "Unsupported blocker declaration domain version"
        }
        val blockers = value.required("blockerOrder").requireEntityArray("blocker order")
        val attackers = value.required("attackerOrder").requireEntityArray("attacker order")
        requireDistinct(blockers, "blocker order")
        requireDistinct(attackers, "attacker order")
        val blockerSet = blockers.toSet()
        val attackerSet = attackers.toSet()

        val relations = value.required("blockerToAttackers").requireObject("blocker-to-attacker relation")
        require(relations.keys == blockerSet) { "Blocker domain is missing a blocker relation" }
        relations.values.forEach { relatedElement ->
            val related = relatedElement.requireEntityArray("blocker attackers")
            requireDistinct(related, "blocker attackers")
            require(related.all { it in attackerSet }) { "Blocker relation attacker is outside the domain" }
        }
        val maxByBlocker = value.required("maxAttackersByBlocker").requireObject("blocker maxima")
        require(maxByBlocker.keys == blockerSet) { "Blocker domain is missing a blocker maximum" }
        maxByBlocker.values.forEach { it.nonNegativeIntValue() }
        requireBoundMap(value.required("minBlockersByAttacker"), attackerSet, "minimum blocker bounds")
        requireBoundMap(value.required("maxBlockersByAttacker"), attackerSet, "maximum blocker bounds")
        value.required("globalMaxBlockers").requireNullableNonNegativeInt()
        val co = value.required("coBlockerRequirements").requireObject("co-blocker requirements")
        require(co.keys.all { it in blockerSet }) { "Co-blocker key is outside the domain" }
        co.values.forEach { requirements ->
            requirements.requireArray("co-blocker requirement list").forEach { requirement ->
                val eligible = requirement.requireEntityArray("co-blocker eligibility")
                require(eligible.isNotEmpty()) { "Co-blocker eligibility cannot be empty" }
                requireDistinct(eligible, "co-blocker eligibility")
                require(eligible.all { it in blockerSet }) { "Co-blocker is outside the domain" }
            }
        }
        val requirements = value.required("requirements").requireArray("block requirements")
        requirements.forEach { requirementElement ->
            val requirement = requirementElement.requireObject("block requirement")
            when (requirement.required("type").stringValue()) {
                "block-specific" -> {
                    requirement.requireKeys(setOf("type", "blockerId", "attackerId"))
                    require(requirement.required("blockerId").stringValue() in blockerSet) {
                        "Block-specific blocker is outside the domain"
                    }
                    require(requirement.required("attackerId").stringValue() in attackerSet) {
                        "Block-specific attacker is outside the domain"
                    }
                }

                "block-one-of" -> {
                    requirement.requireKeys(setOf("type", "blockerId", "attackerIds"))
                    require(requirement.required("blockerId").stringValue() in blockerSet) {
                        "Block-one-of blocker is outside the domain"
                    }
                    val eligible = requirement.required("attackerIds").requireEntityArray("block-one-of attackers")
                    require(eligible.isNotEmpty()) { "Block-one-of attackers cannot be empty" }
                    requireDistinct(eligible, "block-one-of attackers")
                    require(eligible.all { it in attackerSet }) { "Block-one-of attacker is outside the domain" }
                }

                "attacker-must-be-blocked-if-able",
                "attacker-must-be-blocked-by-all" -> {
                    requirement.requireKeys(setOf("type", "attackerId"))
                    require(requirement.required("attackerId").stringValue() in attackerSet) {
                        "Block requirement attacker is outside the domain"
                    }
                }

                "blocker-must-block-if-able" -> {
                    requirement.requireKeys(setOf("type", "blockerId"))
                    require(requirement.required("blockerId").stringValue() in blockerSet) {
                        "Block requirement blocker is outside the domain"
                    }
                }

                else -> throw IllegalArgumentException("Unsupported block requirement kind")
            }
        }
        val minimumSatisfied = value.required("minimumSatisfiedRequirementCount").nonNegativeIntValue()
        require(minimumSatisfied <= requirements.size) {
            "Blocker requirement satisfaction count is outside the domain"
        }
        value.required("canDeclareZeroBlockers").requireBoolean()
    }

    private fun requireBoundMap(value: JsonElement, allowedKeys: Set<String>, label: String) {
        val map = value.requireObject(label)
        require(map.keys.all { it in allowedKeys }) { "$label contains an out-of-domain key" }
        map.values.forEach { it.nonNegativeIntValue() }
    }

    private fun requirePaymentDomainJson(value: JsonObject) {
        decodeStrict("PaymentDomainV5", PaymentDomainV5.serializer(), value)
    }

    private fun requireTargetPaymentDomainJson(value: JsonObject) {
        decodeStrict("TargetPaymentDomainV1", TargetPaymentDomainV1.serializer(), value)
    }

    private fun requireCanonicalRequiredPayloadFields(value: JsonElement) {
        val fields = value.requireStringArray("requiredPayloadFields")
        require(fields.distinct().size == fields.size) {
            "Complete legal-domain candidate cannot duplicate required payload fields"
        }
        val canonical = try {
            ActionPayloadRequirements.canonicalizeRequiredPayloadFields(fields.toSet())
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Complete legal-domain candidate has an unsupported payload field")
        }
        require(fields == canonical) {
            "Complete legal-domain candidate has noncanonical required payload field order"
        }
    }

    private val semanticRoutingKeys = setOf("actionId", "decisionId", "abilityId")

    private fun requireSemanticActionPayload(value: JsonObject) {
        val type = value.required("type").stringValue("action semantic type")
        fun rejectRoutingKeys(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    require(element.keys.none(semanticRoutingKeys::contains)) {
                        "Complete legal-domain action semantics contain a routing field"
                    }
                    element.values.forEach(::rejectRoutingKeys)
                }

                is JsonArray -> element.forEach(::rejectRoutingKeys)
                else -> Unit
            }
        }
        rejectRoutingKeys(value)
        if (type == "ActivateAbility") {
            require(value["abilityKey"]?.let { it is JsonObject } == true) {
                "ActivateAbility action semantics require a stable abilityKey"
            }
        }
    }

    private fun <T> decodeStrict(label: String, serializer: KSerializer<T>, value: JsonObject): T =
        try {
            completeLegalDomainJson.decodeFromJsonElement(serializer, value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Malformed $label")
        }

    private fun requireCanonicalEntityIds(ids: List<EntityId>, label: String) {
        val values = ids.map { it.value }
        require(values == values.distinct().sorted()) {
            "Producer returned noncanonical $label"
        }
    }

    private fun requireDistinct(values: List<String>, label: String) {
        require(values.distinct().size == values.size) { "$label cannot contain duplicate members" }
    }

    private fun validateStructuredDomainProducerOrder(domain: StructuredDecisionDomain) {
        fun requireSortedEntityIds(ids: List<EntityId>, label: String) =
            requireCanonicalEntityIds(ids, label)

        when (domain) {
            is TargetsDomain -> {
                val indices = domain.requirements.map { it.index }
                require(indices == indices.distinct().sorted()) {
                    "Producer returned noncanonical target requirement order"
                }
                domain.requirements.forEach { requireSortedEntityIds(it.candidates, "target candidates") }
            }

            is CardSelectionDomain -> {
                requireSortedEntityIds(domain.options, "card-selection options")
                requireSortedEntityIds(domain.nonSelectableOptions, "non-selectable options")
                domain.conditionalMinimums.forEach {
                    requireSortedEntityIds(it.matchingOptions, "conditional matching options")
                }
                domain.availableColors?.let {
                    require(it == it.distinct().sorted()) {
                        "Producer returned noncanonical card-selection colors"
                    }
                }
                domain.cardInfo?.values?.forEach { info ->
                    require(info.colors == info.colors.distinct().sorted()) {
                        "Producer returned noncanonical card-info colors"
                    }
                }
            }

            is ModeSelectionDomain -> {
                val indices = domain.modes.map { it.index }
                require(indices == indices.distinct().sorted()) {
                    "Producer returned noncanonical mode order"
                }
            }
            is DistributionDomain -> requireSortedEntityIds(domain.targets, "distribution targets")
            is OrderingDomain -> requireSortedEntityIds(domain.objects, "ordering objects")
            is SplitPilesDomain -> requireSortedEntityIds(domain.cards, "split-pile cards")
            is SearchLibraryDomain -> requireSortedEntityIds(domain.options, "search-library options")
            is ReorderLibraryDomain -> Unit
            is CombatResolutionDomain -> {
                requireSortedEntityIds(domain.attackers.map { it.id }, "combat attackers")
                requireSortedEntityIds(domain.blockers.map { it.id }, "combat blockers")
                requireSortedEntityIds(domain.defenders.map { it.id }, "combat defenders")
                require(domain.edges.map { it.id } == domain.edges.map { it.id }.distinct().sorted()) {
                    "Producer returned noncanonical combat damage edges"
                }
                domain.attackers.forEach { requireSortedEntityIds(it.blockedByIds, "blocked-by attackers") }
                domain.blockers.forEach { requireSortedEntityIds(it.blockedAttackerIds, "blocked attacker IDs") }
            }

            // PaymentDomainV5 owns source/ability order and validates its own canonical identities.
            is ManaSourcesDomain -> Unit

            is ReplacementDomain -> Unit
            is BudgetModalDomain -> Unit
        }
    }

    private fun JsonObject.requireKeys(expected: Set<String>) {
        require(keys == expected) { "Malformed A2 domain component" }
    }

    private fun JsonObject.required(key: String): JsonElement =
        get(key) ?: throw IllegalArgumentException("Malformed A2 domain component")

    private fun JsonElement.requireObject(label: String): JsonObject =
        this as? JsonObject ?: throw IllegalArgumentException("Malformed $label")

    private fun JsonElement.requireArray(label: String): JsonArray =
        this as? JsonArray ?: throw IllegalArgumentException("Malformed $label")

    private fun JsonElement.requireString(label: String = "string") {
        stringValue(label)
    }

    private fun JsonElement.requireNullableString(label: String = "nullable string") {
        if (this !is kotlinx.serialization.json.JsonNull) requireString(label)
    }

    private fun JsonElement.requireBoolean(label: String = "boolean") {
        val primitive = this as? JsonPrimitive
            ?: throw IllegalArgumentException("Malformed $label")
        require(this !is kotlinx.serialization.json.JsonNull && !primitive.isString &&
            (primitive.content == "true" || primitive.content == "false")) {
            "Malformed $label"
        }
    }

    private fun JsonElement.requireNullableInt(label: String = "nullable integer") {
        if (this !is kotlinx.serialization.json.JsonNull) intValue(label)
    }

    private fun JsonElement.requireNullableNonNegativeInt(label: String = "nullable integer") {
        if (this !is kotlinx.serialization.json.JsonNull) nonNegativeIntValue(label)
    }

    private fun JsonElement.requireNonNegativeInt(label: String = "non-negative integer") {
        nonNegativeIntValue(label)
    }

    private fun JsonElement.intValue(label: String = "integer"): Int {
        val primitive = this as? JsonPrimitive
            ?: throw IllegalArgumentException("Malformed $label")
        require(this !is kotlinx.serialization.json.JsonNull && !primitive.isString) {
            "Malformed $label"
        }
        return primitive.content.toIntOrNull()
            ?: throw IllegalArgumentException("Malformed $label")
    }

    private fun JsonElement.nonNegativeIntValue(label: String = "non-negative integer"): Int =
        intValue(label).also { require(it >= 0) { "Malformed $label" } }

    private fun JsonElement.stringValue(label: String = "string"): String {
        val primitive = this as? JsonPrimitive
            ?: throw IllegalArgumentException("Malformed $label")
        require(this !is kotlinx.serialization.json.JsonNull && primitive.isString) {
            "Malformed $label"
        }
        return primitive.content
    }

    private fun JsonElement.requireStringArray(label: String): List<String> =
        requireArray(label).map { it.stringValue(label) }

    private fun JsonElement.requireEntityArray(label: String): List<String> =
        requireStringArray(label).also { values ->
            require(values == values.distinct()) { "$label cannot contain duplicate members" }
        }

    private fun JsonElement.requireCanonicalEntityArray(label: String): List<String> =
        requireEntityArray(label).also { values ->
            require(values == values.sorted()) { "$label is not in producer-canonical order" }
        }
}

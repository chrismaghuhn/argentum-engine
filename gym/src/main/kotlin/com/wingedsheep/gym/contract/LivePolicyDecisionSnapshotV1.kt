package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.core.UnsupportedPathFailure
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val LIVE_SELECTION_ADDRESS_CONTRACT_VERSION: Int = 1
const val LIVE_SELECTION_ADDRESS_CONTRACT_IDENTITY: String =
    "argentum-ml-live-selection-address@v1"
const val LIVE_POLICY_DECISION_SNAPSHOT_VERSION: Int = 1
const val LIVE_POLICY_DECISION_SNAPSHOT_SCHEMA_IDENTITY: String =
    "argentum-gym-live-policy-decision-snapshot@v1"
const val LIVE_STRUCTURED_CHOICE_DOMAIN_VERSION: Int = 1
const val LIVE_STRUCTURED_CHOICE_DOMAIN_SCHEMA_IDENTITY: String =
    "argentum-ml-live-structured-choice-domain@v1"

private const val LIVE_SELECTION_BINDING_DIGEST_PREFIX: String =
    "argentum-ml-live-selection-binding@v1\n"
private const val LIVE_EXACT_BINDING_DIGEST_PREFIX: String =
    "argentum-ml-live-exact-source-binding@v1\n"

/**
 * Alias-only selection control data. It contains addresses and validated control metadata, never
 * a Rules action, a decision response, or a raw entity identity.
 */
@Serializable
data class LiveSelectionBindingChannelV1(
    val version: Int = LIVE_SELECTION_ADDRESS_CONTRACT_VERSION,
    val schemaIdentity: String = LIVE_SELECTION_ADDRESS_CONTRACT_IDENTITY,
    val sourceBindingOrdinals: List<Int>,
    @SerialName("presentMask")
    val presentMask: List<Boolean>,
    @SerialName("executableSupportMask")
    val executableSupportMask: List<Boolean>,
    val semanticTieDiscriminators: Map<String, String> = emptyMap(),
) {
    init {
        require(version == LIVE_SELECTION_ADDRESS_CONTRACT_VERSION) {
            "Unsupported live selection-address version: $version"
        }
        require(schemaIdentity == LIVE_SELECTION_ADDRESS_CONTRACT_IDENTITY) {
            "Unsupported live selection-address identity: $schemaIdentity"
        }
        require(sourceBindingOrdinals.isNotEmpty()) {
            "Live selection binding channel requires at least one candidate"
        }
        require(sourceBindingOrdinals.all { it >= 0 }) {
            "Live source binding ordinals must be non-negative"
        }
        require(sourceBindingOrdinals.distinct().size == sourceBindingOrdinals.size) {
            "Live source binding ordinals must be unique"
        }
        require(presentMask.size == sourceBindingOrdinals.size) {
            "Live present mask must match source binding ordinal count"
        }
        require(executableSupportMask.size == sourceBindingOrdinals.size) {
            "Live executable-support mask must match source binding ordinal count"
        }
        require(executableSupportMask.indices.all { index ->
            !executableSupportMask[index] || presentMask[index]
        }) {
            "An absent live candidate cannot have executable support"
        }
        semanticTieDiscriminators.forEach { (rawOrdinal, discriminator) ->
            val ordinal = rawOrdinal.toIntOrNull()
            require(ordinal != null && ordinal >= 0 && rawOrdinal == ordinal.toString()) {
                "Live semantic tie discriminator keys must be decimal ordinals"
            }
            require(ordinal in sourceBindingOrdinals) {
                "Live semantic tie discriminator addresses an unknown ordinal"
            }
            requireCanonicalTieDiscriminator(discriminator)
        }
    }

    fun canonicalJson(): String = A3SemanticJson.canonicalJson(
        A3SemanticJson.strictJson.encodeToJsonElement(
            serializer(),
            this,
        ),
    )

    fun semanticDigest(): String = A3SemanticJson.sha256(
        (LIVE_SELECTION_BINDING_DIGEST_PREFIX + canonicalJson())
            .toByteArray(StandardCharsets.UTF_8),
    )

    companion object {
        fun fromFlat(
            domain: CompleteLegalDomainV1,
            projection: C1ModelFacingProjectionResultV1,
        ): LiveSelectionBindingChannelV1 {
            require(domain.kind != CompleteLegalDomainKind.STRUCTURED_DECISION) {
                "Flat live selection channel cannot represent a structured domain"
            }
            require(projection.sourceBindingOrdinals == domain.candidates.indices.toList()) {
                "Flat live projection ordinals do not match the complete domain"
            }
            val executable = domain.candidates.mapIndexed { index, candidate ->
                val affordable = candidate["affordable"] as? JsonPrimitive
                require(affordable != null && !affordable.isString) {
                    "Live candidate $index has no authoritative affordable flag"
                }
                require(affordable.content == "true" || affordable.content == "false") {
                    "Live candidate $index has a malformed affordable flag"
                }
                affordable.content == "true"
            }
            return LiveSelectionBindingChannelV1(
                sourceBindingOrdinals = projection.sourceBindingOrdinals,
                presentMask = projection.sourceBindingOrdinals.map { true },
                executableSupportMask = executable,
                semanticTieDiscriminators = projection.semanticTieDiscriminators.mapValues {
                    val discriminator = it.value as? JsonPrimitive
                    require(discriminator != null && discriminator.isString) {
                        "Live semantic tie discriminator must be canonical JSON text"
                    }
                    discriminator.content
                },
            )
        }

        fun fromStructured(
            choices: LiveStructuredChoiceDomainV1,
        ): LiveSelectionBindingChannelV1 {
            val ordinals = choices.alternatives.map { it.sourceBindingOrdinal }
            return LiveSelectionBindingChannelV1(
                sourceBindingOrdinals = ordinals,
                presentMask = ordinals.map { true },
                executableSupportMask = ordinals.map { true },
                semanticTieDiscriminators = choices.alternatives
                    .mapNotNull { alternative ->
                        alternative.semanticTieDiscriminator?.let {
                            alternative.sourceBindingOrdinal.toString() to it
                        }
                    }
                    .toMap(),
            )
        }
    }
}

/**
 * JVM-only exact source table. The value may be a LegalAction or DecisionResponse, but this type
 * is deliberately not serializable and is never part of the Python-facing snapshot.
 */
data class LiveExactSourceBindingEntry<T>(
    val sourceBindingOrdinal: Int,
    val value: T,
) {
    init {
        require(sourceBindingOrdinal >= 0) {
            "Exact source binding ordinal must be non-negative"
        }
    }
}

class LiveExactSourceBindingTable<T> private constructor(
    private val valuesByOrdinal: Map<Int, T>,
    val sourceDomainDigest: CandidateDomainDigestV1?,
    private val semanticBindingsByOrdinal: Map<Int, String>,
    val bindingDigest: String,
) {
    val sourceBindingOrdinals: Set<Int>
        get() = valuesByOrdinal.keys

    fun exactBindingFor(sourceBindingOrdinal: Int): T =
        valuesByOrdinal[sourceBindingOrdinal]
            ?: throw IllegalArgumentException(
                "Selected source binding ordinal is absent from the exact JVM table",
            )

    fun requireCompatible(
        domain: CompleteLegalDomainV1,
        channel: LiveSelectionBindingChannelV1,
        expectedSemanticBindings: Map<Int, JsonObject>? = null,
    ) {
        require(sourceDomainDigest == CandidateDomainDigestV1.from(domain)) {
            "Exact JVM source bindings were not built from this current legal domain"
        }
        require(sourceBindingOrdinals == channel.sourceBindingOrdinals.toSet()) {
            "Exact JVM source bindings do not cover the live selection channel"
        }
        val expected = expectedSemanticBindings ?: when (domain.kind) {
            CompleteLegalDomainKind.ACTION_CANDIDATES,
            CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            -> domain.candidates.mapIndexed { ordinal, candidate -> ordinal to candidate }.toMap()

            CompleteLegalDomainKind.STRUCTURED_DECISION -> null
        }
        expected?.let(::requireSemanticBindings)
    }

    private fun requireSemanticBindings(expected: Map<Int, JsonObject>) {
        require(expected.keys == sourceBindingOrdinals) {
            "Expected semantic bindings do not cover the exact JVM source table"
        }
        expected.forEach { (ordinal, expectedBinding) ->
            require(semanticBindingsByOrdinal[ordinal] == A3SemanticJson.canonicalJson(expectedBinding)) {
                "Exact JVM source binding is not the authoritative current-domain entry"
            }
        }
    }

    companion object {
        fun <T> empty(): LiveExactSourceBindingTable<T> = LiveExactSourceBindingTable(
            valuesByOrdinal = emptyMap(),
            sourceDomainDigest = null,
            semanticBindingsByOrdinal = emptyMap(),
            bindingDigest = digestFor(
                sourceDomainDigest = null,
                exactCanonicalValues = emptyMap(),
                semanticCanonicalValues = emptyMap(),
            ),
        )

        /**
         * Build an exact table from the current flat/folded domain. The source-owned mapper
         * projects each LegalAction/DecisionResponse to the authoritative semantic candidate;
         * ordinal membership is checked against the actual current [CompleteLegalDomainV1]
         * entry, not merely against a caller-supplied ordinal set.
         */
        fun <T> fromCurrentDomain(
            domain: CompleteLegalDomainV1,
            entries: List<LiveExactSourceBindingEntry<T>>,
            canonicalizeValue: (T) -> String,
            semanticBindingOf: (T) -> JsonObject,
        ): LiveExactSourceBindingTable<T> {
            require(domain.kind != CompleteLegalDomainKind.STRUCTURED_DECISION) {
                "Flat exact source binding factory cannot accept a structured domain"
            }
            val expectedSemanticBindings = domain.candidates
                .mapIndexed { ordinal, candidate -> ordinal to candidate }
                .toMap()
            return fromAuthoritativeDomain(
                domain = domain,
                expectedSemanticBindings = expectedSemanticBindings,
                entries = entries,
                canonicalizeValue = canonicalizeValue,
                semanticBindingOf = semanticBindingOf,
            )
        }

        /**
         * Build an exact table for a source-owned, fully enumerated structured-choice list.
         * The authoritative semantic binding stored on each alternative remains JVM-only;
         * this factory compares the exact response mapper to that binding for every ordinal.
         * Completeness is proved separately by [LiveStructuredChoiceCompletenessSource].
         */
        fun <T> fromStructuredChoices(
            domain: CompleteLegalDomainV1,
            alternatives: List<LiveStructuredChoiceAlternativeV1>,
            entries: List<LiveExactSourceBindingEntry<T>>,
            canonicalizeValue: (T) -> String,
            semanticBindingOf: (T) -> JsonObject,
        ): LiveExactSourceBindingTable<T> {
            require(domain.kind == CompleteLegalDomainKind.STRUCTURED_DECISION) {
                "Structured exact source binding factory requires a structured domain"
            }
            require(domain.structuredDomain != null) {
                "Structured exact source binding factory requires a typed source domain"
            }
            require(alternatives.isNotEmpty()) {
                "Structured exact source binding factory requires complete alternatives"
            }
            require(
                alternatives.map { it.sourceBindingOrdinal }.distinct().size == alternatives.size,
            ) {
                "Structured exact source binding alternatives must have unique ordinals"
            }
            val expectedSemanticBindings = alternatives.associate {
                it.sourceBindingOrdinal to it.authoritativeSemanticBinding
            }
            return fromAuthoritativeDomain(
                domain = domain,
                expectedSemanticBindings = expectedSemanticBindings,
                entries = entries,
                canonicalizeValue = canonicalizeValue,
                semanticBindingOf = semanticBindingOf,
            )
        }

        private fun <T> fromAuthoritativeDomain(
            domain: CompleteLegalDomainV1,
            expectedSemanticBindings: Map<Int, JsonObject>,
            entries: List<LiveExactSourceBindingEntry<T>>,
            canonicalizeValue: (T) -> String,
            semanticBindingOf: (T) -> JsonObject,
        ): LiveExactSourceBindingTable<T> {
            require(expectedSemanticBindings.isNotEmpty()) {
                "Exact JVM source binding table requires at least one current candidate"
            }
            require(entries.map { it.sourceBindingOrdinal }.distinct().size == entries.size) {
                "Exact JVM source binding ordinals must be unique"
            }
            require(entries.map { it.sourceBindingOrdinal }.toSet() == expectedSemanticBindings.keys) {
                "Exact JVM source bindings must cover every current-domain entry exactly once"
            }
            val canonicalValues = entries.map { entry ->
                val canonical = canonicalizeValue(entry.value)
                require(canonical.isNotBlank()) {
                    "Exact JVM source binding canonical value must not be blank"
                }
                entry to canonical
            }
            require(canonicalValues.map { it.second }.distinct().size == entries.size) {
                "Exact JVM source binding inverse map must be injective"
            }
            val semanticCanonicalValues = entries.associate { entry ->
                val semanticBinding = semanticBindingOf(entry.value)
                val canonical = A3SemanticJson.canonicalJson(semanticBinding)
                require(canonical.isNotBlank()) {
                    "Exact JVM source binding semantic value must not be blank"
                }
                val expected = expectedSemanticBindings.getValue(entry.sourceBindingOrdinal)
                require(canonical == A3SemanticJson.canonicalJson(expected)) {
                    "Exact JVM source binding is not the authoritative current-domain entry"
                }
                entry.sourceBindingOrdinal to canonical
            }
            require(semanticCanonicalValues.values.distinct().size == entries.size) {
                "Exact JVM semantic source binding inverse map must be injective"
            }
            val values = entries.associate { it.sourceBindingOrdinal to it.value }
            val sourceDomainDigest = CandidateDomainDigestV1.from(domain)
            val digest = digestFor(
                sourceDomainDigest = sourceDomainDigest,
                exactCanonicalValues = canonicalValues.associate {
                    it.first.sourceBindingOrdinal to it.second
                },
                semanticCanonicalValues = semanticCanonicalValues,
            )
            return LiveExactSourceBindingTable(
                valuesByOrdinal = values,
                sourceDomainDigest = sourceDomainDigest,
                semanticBindingsByOrdinal = semanticCanonicalValues,
                bindingDigest = digest,
            )
        }

        private fun digestFor(
            sourceDomainDigest: CandidateDomainDigestV1?,
            exactCanonicalValues: Map<Int, String>,
            semanticCanonicalValues: Map<Int, String>,
        ): String {
            val fingerprintPayload = buildJsonObject {
                put("sourceDomainDigest", sourceDomainDigest?.value ?: "UNBOUND")
                put("bindings", buildJsonArray {
                    exactCanonicalValues.keys.sorted().forEach { ordinal ->
                        add(buildJsonObject {
                            put("sourceBindingOrdinal", ordinal)
                            put("canonicalBinding", exactCanonicalValues.getValue(ordinal))
                            put("semanticBinding", semanticCanonicalValues.getValue(ordinal))
                        })
                    }
                })
            }
            return A3SemanticJson.sha256(
                (
                    LIVE_EXACT_BINDING_DIGEST_PREFIX +
                        A3SemanticJson.canonicalJson(fingerprintPayload)
                    ).toByteArray(StandardCharsets.UTF_8),
            )
        }
    }
}

@Serializable
data class LivePolicyRngStateV1(
    val streamKeyHex: String,
    val cursor: ULong,
) {
    init {
        require(streamKeyHex.matches(Regex("[0-9a-f]{64}"))) {
            "Live PolicyTieRng stream key must be lowercase 32-byte hex"
        }
    }
}

@Serializable
data class LivePolicyDecisionRequestV1(
    val version: Int = LIVE_POLICY_DECISION_SNAPSHOT_VERSION,
    val schemaIdentity: String = "argentum-ml-live-policy-decision-request@v1",
    val requestId: String,
    val observationDigest: String,
    val candidateDomainDigest: CandidateDomainDigestV1,
    val modelInput: JsonObject,
    val candidateFeatureViews: List<JsonObject>,
    val selectionBindingChannel: LiveSelectionBindingChannelV1,
    val policyRngState: LivePolicyRngStateV1,
) {
    init {
        require(version == LIVE_POLICY_DECISION_SNAPSHOT_VERSION) {
            "Unsupported live policy request version: $version"
        }
        require(schemaIdentity == "argentum-ml-live-policy-decision-request@v1") {
            "Unsupported live policy request identity: $schemaIdentity"
        }
        require(requestId.isNotBlank()) {
            "Live policy request ID must not be blank"
        }
        A3SemanticJson.requireSha256(observationDigest, "Live request observation digest")
        require(candidateFeatureViews.isNotEmpty()) {
            "Live policy request requires candidate feature views"
        }
        require(
            candidateFeatureViews.size ==
                selectionBindingChannel.sourceBindingOrdinals.size,
        ) {
            "Live request feature views do not match selection addresses"
        }
        A3SemanticJson.requireNoForbiddenKeys(modelInput, "live policy model input")
        C1ModelFacingProjectionV1.requireModelFacingFeatureView(
            modelInput,
            emptySet(),
        )
        candidateFeatureViews.forEach { featureView ->
            C1ModelFacingProjectionV1.requireModelFacingFeatureView(
                featureView,
                emptySet(),
            )
        }
    }
}

@Serializable
data class LivePolicyDecisionResponseV1(
    val version: Int = LIVE_SELECTION_ADDRESS_CONTRACT_VERSION,
    val schemaIdentity: String = "argentum-ml-live-policy-decision-response@v1",
    val requestId: String,
    val selectedSourceBindingOrdinal: Int,
    val policyRngState: LivePolicyRngStateV1,
    val rngCursorBefore: ULong,
    val rngCursorAfter: ULong,
    val rngDrawCount: ULong,
) {
    init {
        require(version == LIVE_SELECTION_ADDRESS_CONTRACT_VERSION) {
            "Unsupported live policy response version: $version"
        }
        require(schemaIdentity == "argentum-ml-live-policy-decision-response@v1") {
            "Unsupported live policy response identity: $schemaIdentity"
        }
        require(requestId.isNotBlank()) {
            "Live policy response request ID must not be blank"
        }
        require(selectedSourceBindingOrdinal >= 0) {
            "Live selected source binding ordinal must be non-negative"
        }
        require(rngCursorBefore <= rngCursorAfter) {
            "Live PolicyTieRng response cursor moved backwards"
        }
        require(rngCursorAfter == policyRngState.cursor) {
            "Live PolicyTieRng response cursor does not match returned state"
        }
        require(rngCursorAfter - rngCursorBefore == rngDrawCount) {
            "Live PolicyTieRng response draw count does not match cursor advancement"
        }
    }

    fun requireCompatible(request: LivePolicyDecisionRequestV1): LivePolicyDecisionResponseV1 {
        require(requestId == request.requestId) {
            "Live policy response request ID does not match its request"
        }
        val selectedIndex = request.selectionBindingChannel.sourceBindingOrdinals
            .indexOf(selectedSourceBindingOrdinal)
        require(selectedIndex >= 0) {
            "Live policy response selected ordinal is outside the request domain"
        }
        require(request.selectionBindingChannel.presentMask[selectedIndex]) {
            "Live policy response selected an absent candidate"
        }
        require(request.selectionBindingChannel.executableSupportMask[selectedIndex]) {
            "Live policy response selected a non-executable candidate"
        }
        require(policyRngState.streamKeyHex == request.policyRngState.streamKeyHex) {
            "Live policy response changed the PolicyTieRng stream"
        }
        require(rngCursorBefore == request.policyRngState.cursor) {
            "Live policy response cursor-before does not match its request"
        }
        require(rngCursorAfter == policyRngState.cursor) {
            "Live policy response cursor-after does not match its state"
        }
        return this
    }
}

/**
 * A source-owned structured candidate list. Alternatives contain model-facing feature views plus
 * a JVM-only authoritative semantic response binding; exact responses remain in
 * [LiveExactSourceBindingTable]. The factory is intentionally explicit: a caller must provide
 * every complete, injective alternative from the authoritative Rules boundary. There is no
 * generic Cartesian-product expansion.
 */
data class LiveStructuredChoiceAlternativeV1(
    val sourceBindingOrdinal: Int,
    val featureView: JsonObject,
    val semanticTieDiscriminator: String? = null,
    /** JVM-only semantic response binding; never serialized across the Python seam. */
    val authoritativeSemanticBinding: JsonObject,
) {
    init {
        require(sourceBindingOrdinal >= 0) {
            "Structured choice source binding ordinal must be non-negative"
        }
        C1ModelFacingProjectionV1.requireModelFacingFeatureView(
            featureView,
            emptySet(),
        )
        requireCanonicalSemanticBinding(authoritativeSemanticBinding)
        semanticTieDiscriminator?.let(::requireCanonicalTieDiscriminator)
    }
}

/**
 * Source-owned proof that a structured alternative list is complete for one current typed
 * domain. The source must enumerate the complete injective response/prefix set independently;
 * the generic contract only accepts the resulting witness and checks it against the alternatives.
 */
fun interface LiveStructuredChoiceCompletenessSource {
    fun witness(domain: CompleteLegalDomainV1): LiveStructuredChoiceCompletenessWitnessV1
}

data class LiveStructuredChoiceCompletenessWitnessV1(
    val sourceDomainDigest: CandidateDomainDigestV1,
    val sourceBindingOrdinals: Set<Int>,
    val semanticBindingsByOrdinal: Map<Int, JsonObject>,
) {
    init {
        require(sourceBindingOrdinals.isNotEmpty()) {
            "Structured-choice completeness witness requires alternatives"
        }
        require(sourceBindingOrdinals.all { it >= 0 }) {
            "Structured-choice completeness witness ordinals must be non-negative"
        }
        require(semanticBindingsByOrdinal.keys == sourceBindingOrdinals) {
            "Structured-choice completeness witness must bind every ordinal exactly once"
        }
        semanticBindingsByOrdinal.values.forEach(::requireCanonicalSemanticBinding)
        require(
            semanticBindingsByOrdinal.values
                .map(A3SemanticJson::canonicalJson)
                .distinct()
                .size == semanticBindingsByOrdinal.size,
        ) {
            "Structured-choice completeness witness must be injective"
        }
    }
}

class LiveStructuredChoiceDomainV1 private constructor(
    val version: Int = LIVE_STRUCTURED_CHOICE_DOMAIN_VERSION,
    val schemaIdentity: String = LIVE_STRUCTURED_CHOICE_DOMAIN_SCHEMA_IDENTITY,
    val sourceDomainDigest: CandidateDomainDigestV1,
    val alternatives: List<LiveStructuredChoiceAlternativeV1>,
    private val completenessWitness: LiveStructuredChoiceCompletenessWitnessV1,
) {
    init {
        require(version == LIVE_STRUCTURED_CHOICE_DOMAIN_VERSION) {
            "Unsupported live structured-choice version: $version"
        }
        require(schemaIdentity == LIVE_STRUCTURED_CHOICE_DOMAIN_SCHEMA_IDENTITY) {
            "Unsupported live structured-choice identity: $schemaIdentity"
        }
        require(alternatives.isNotEmpty()) {
            "Complete structured-choice domain requires at least one alternative"
        }
        require(alternatives.map { it.sourceBindingOrdinal }.distinct().size == alternatives.size) {
            "Structured-choice alternatives must have unique source binding ordinals"
        }
        require(
            completenessWitness.sourceDomainDigest == sourceDomainDigest &&
                completenessWitness.sourceBindingOrdinals ==
                alternatives.map { it.sourceBindingOrdinal }.toSet() &&
                completenessWitness.semanticBindingsByOrdinal ==
                alternatives.associate {
                    it.sourceBindingOrdinal to it.authoritativeSemanticBinding
                },
        ) {
            "Structured-choice completeness witness does not match alternatives"
        }
        require(
            alternatives.map { A3SemanticJson.canonicalJson(it.authoritativeSemanticBinding) }
                .distinct()
                .size == alternatives.size,
        ) {
            "Structured-choice alternatives must have injective semantic bindings"
        }
    }

    fun requireModelFacingFeatureViews(forbiddenRawValues: Set<String>) {
        alternatives.forEach { alternative ->
            C1ModelFacingProjectionV1.requireModelFacingFeatureView(
                alternative.featureView,
                forbiddenRawValues,
            )
        }
    }

    fun requireSemanticTieDiscriminators(forbiddenRawValues: Set<String>) {
        alternatives.forEach { alternative ->
            alternative.semanticTieDiscriminator?.let { discriminator ->
                requireCanonicalTieDiscriminator(discriminator, forbiddenRawValues)
            }
        }
    }

    internal fun authoritativeSemanticBindings(): Map<Int, JsonObject> = alternatives.associate {
        it.sourceBindingOrdinal to it.authoritativeSemanticBinding
    }

    companion object {
        fun <T> from(
            domain: CompleteLegalDomainV1,
            alternatives: List<LiveStructuredChoiceAlternativeV1>,
            exactSourceBindings: LiveExactSourceBindingTable<T>,
            completenessSource: LiveStructuredChoiceCompletenessSource,
        ): LiveStructuredChoiceDomainV1 {
            require(domain.kind == CompleteLegalDomainKind.STRUCTURED_DECISION) {
                "Structured-choice alternatives require a structured complete domain"
            }
            require(domain.structuredDomain != null) {
                "Structured-choice alternatives require a typed source domain"
            }
            val sourceDomainDigest = CandidateDomainDigestV1.from(domain)
            val completenessWitness = completenessSource.witness(domain)
            val choices = LiveStructuredChoiceDomainV1(
                sourceDomainDigest = sourceDomainDigest,
                alternatives = alternatives,
                completenessWitness = completenessWitness,
            )
            exactSourceBindings.requireCompatible(
                domain = domain,
                channel = LiveSelectionBindingChannelV1.fromStructured(choices),
                expectedSemanticBindings = choices.authoritativeSemanticBindings(),
            )
            choices.requireModelFacingFeatureViews(
                C1ModelFacingProjectionV1.sourceIdentityValues(domain),
            )
            choices.requireSemanticTieDiscriminators(
                C1ModelFacingProjectionV1.sourceIdentityValues(domain),
            )
            return choices
        }
    }
}

/**
 * One lock-coherent, perspective-safe live policy input. The caller must construct it from one
 * observation/domain/binding generation while holding its authoritative state lock. This type
 * stores no exact binding value; the JVM keeps that value in the separate exact table.
 */
data class LivePolicyDecisionSnapshotV1(
    val version: Int = LIVE_POLICY_DECISION_SNAPSHOT_VERSION,
    val schemaIdentity: String = LIVE_POLICY_DECISION_SNAPSHOT_SCHEMA_IDENTITY,
    val playerObservation: PlayerObservationV1,
    val completeLegalDomain: CompleteLegalDomainV1,
    val modelInput: JsonObject,
    val candidateFeatureViews: List<JsonObject>,
    val selectionBindingChannel: LiveSelectionBindingChannelV1,
    val observationDigest: String,
    val candidateDomainDigest: CandidateDomainDigestV1,
    val bindingDigest: String,
    val structuredChoiceDomain: LiveStructuredChoiceDomainV1? = null,
) {
    init {
        require(version == LIVE_POLICY_DECISION_SNAPSHOT_VERSION) {
            "Unsupported live policy snapshot version: $version"
        }
        require(schemaIdentity == LIVE_POLICY_DECISION_SNAPSHOT_SCHEMA_IDENTITY) {
            "Unsupported live policy snapshot identity: $schemaIdentity"
        }
        A3SemanticJson.requireSha256(observationDigest, "Live observation digest")
        A3SemanticJson.requireSha256(bindingDigest, "Live exact binding digest")
        require(candidateDomainDigest == CandidateDomainDigestV1.from(completeLegalDomain)) {
            "Live candidate-domain digest does not match the complete domain"
        }
        require(
            StateDigest.compute(playerObservation, completeLegalDomain) == observationDigest,
        ) {
            "Live observation/domain digest does not match the player observation"
        }
        val projection = C1ModelFacingProjectionV1.project(
            observation = playerObservation,
            domain = completeLegalDomain,
        )
        require(A3SemanticJson.canonicalJson(modelInput) ==
            A3SemanticJson.canonicalJson(projection.input)
        ) {
            "Live model input is not the shared C1 projection"
        }
        val expectedSelectionBindingChannel = when (completeLegalDomain.kind) {
            CompleteLegalDomainKind.ACTION_CANDIDATES,
            CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            -> LiveSelectionBindingChannelV1.fromFlat(completeLegalDomain, projection)

            CompleteLegalDomainKind.STRUCTURED_DECISION ->
                LiveSelectionBindingChannelV1.fromStructured(
                    requireNotNull(structuredChoiceDomain),
                )
        }
        require(selectionBindingChannel == expectedSelectionBindingChannel) {
            "Live selection binding channel is not derived from the current domain"
        }
        when (completeLegalDomain.kind) {
            CompleteLegalDomainKind.ACTION_CANDIDATES,
            CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            -> {
                require(structuredChoiceDomain == null) {
                    "Flat live snapshot cannot carry structured alternatives"
                }
                val projectedCandidates = modelInput["domain"]
                    ?.jsonObject
                    ?.get("candidates")
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    ?: error("Flat live model input has no projected candidates")
                require(candidateFeatureViews == projectedCandidates) {
                    "Flat live candidate feature views do not match the shared projection"
                }
                require(
                    selectionBindingChannel.sourceBindingOrdinals ==
                        completeLegalDomain.candidates.indices.toList(),
                ) {
                    "Flat live selection ordinals do not match the complete domain"
                }
            }

            CompleteLegalDomainKind.STRUCTURED_DECISION -> {
                val choices = requireNotNull(structuredChoiceDomain) {
                    "Structured live snapshot requires complete selection alternatives"
                }
                require(choices.sourceDomainDigest == candidateDomainDigest) {
                    "Structured choices belong to a different complete domain"
                }
                choices.requireModelFacingFeatureViews(
                    C1ModelFacingProjectionV1.sourceIdentityValues(
                        playerObservation,
                        completeLegalDomain,
                    ),
                )
                choices.requireSemanticTieDiscriminators(
                    C1ModelFacingProjectionV1.sourceIdentityValues(
                        playerObservation,
                        completeLegalDomain,
                    ),
                )
                require(candidateFeatureViews == choices.alternatives.map { it.featureView }) {
                    "Structured live candidate feature views do not match alternatives"
                }
                require(
                    selectionBindingChannel.sourceBindingOrdinals ==
                        choices.alternatives.map { it.sourceBindingOrdinal },
                ) {
                    "Structured live selection ordinals do not match alternatives"
                }
            }
        }
    }

    fun toRequest(
        requestId: String,
        policyRngState: LivePolicyRngStateV1,
    ): LivePolicyDecisionRequestV1 = LivePolicyDecisionRequestV1(
        requestId = requestId,
        observationDigest = observationDigest,
        candidateDomainDigest = candidateDomainDigest,
        modelInput = modelInput,
        candidateFeatureViews = candidateFeatureViews,
        selectionBindingChannel = selectionBindingChannel,
        policyRngState = policyRngState,
    )

    /**
     * Rebuild and compare a fresh lock-coherent snapshot before executing a response.
     */
    fun <T> requireCurrent(
        currentObservation: TrainingObservation,
        currentExactSourceBindings: LiveExactSourceBindingTable<T>,
        currentStructuredChoices: LiveStructuredChoiceDomainV1? = structuredChoiceDomain,
    ): LivePolicyDecisionSnapshotV1 {
        val current = from(
            observation = currentObservation,
            exactSourceBindings = currentExactSourceBindings,
            structuredChoices = currentStructuredChoices,
        )
        require(current.observationDigest == observationDigest) {
            "Live policy response observation digest is stale"
        }
        require(current.candidateDomainDigest == candidateDomainDigest) {
            "Live policy response candidate-domain digest is stale"
        }
        require(current.bindingDigest == bindingDigest) {
            "Live policy response exact binding digest is stale"
        }
        return current
    }

    companion object {
        fun <T> from(
            observation: TrainingObservation,
            exactSourceBindings: LiveExactSourceBindingTable<T>,
            structuredChoices: LiveStructuredChoiceDomainV1? = null,
        ): LivePolicyDecisionSnapshotV1 {
            require(!observation.terminated && !observation.truncated) {
                "Live policy snapshot requires a non-terminal, non-truncated observation"
            }
            require(observation.agentToAct == observation.perspectivePlayerId) {
                "Live policy snapshot requires the perspective player to be the acting player"
            }
            val playerObservation = PlayerObservationV1.from(observation)
            val domain = completeDomainOrFailClosed(observation)
            val observationDigest = StateDigest.compute(playerObservation, domain)
            require(observation.stateDigest == observationDigest) {
                "Live source observation digest is not self-consistent"
            }
            val projection = try {
                C1ModelFacingProjectionV1.project(
                    observation = playerObservation,
                    domain = domain,
                )
            } catch (failure: IllegalArgumentException) {
                failClosedForStructuredProjection(observation, failure)
            }
            val choices = when (domain.kind) {
                CompleteLegalDomainKind.ACTION_CANDIDATES,
                CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
                -> null

                CompleteLegalDomainKind.STRUCTURED_DECISION ->
                    structuredChoices ?: throw UnsupportedPathFailure(
                        listOf(DiagnosticSignal(DiagnosticCode.STRUCTURED_DOMAIN_UNSUPPORTED)),
                        "Structured live selection requires complete alternatives",
                    )
            }
            val channel = when (domain.kind) {
                CompleteLegalDomainKind.ACTION_CANDIDATES,
                CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
                -> LiveSelectionBindingChannelV1.fromFlat(domain, projection)

                CompleteLegalDomainKind.STRUCTURED_DECISION ->
                    LiveSelectionBindingChannelV1.fromStructured(checkNotNull(choices))
            }
            exactSourceBindings.requireCompatible(
                domain = domain,
                channel = channel,
                expectedSemanticBindings = choices?.authoritativeSemanticBindings(),
            )
            val candidateFeatureViews = when (domain.kind) {
                CompleteLegalDomainKind.ACTION_CANDIDATES,
                CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
                -> projection.input["domain"]!!.jsonObject["candidates"]!!.jsonArray
                    .map { it.jsonObject }

                CompleteLegalDomainKind.STRUCTURED_DECISION ->
                    requireNotNull(structuredChoices).alternatives.map { it.featureView }
            }
            return LivePolicyDecisionSnapshotV1(
                playerObservation = playerObservation,
                completeLegalDomain = domain,
                modelInput = projection.input,
                candidateFeatureViews = candidateFeatureViews,
                selectionBindingChannel = channel,
                observationDigest = observationDigest,
                candidateDomainDigest = CandidateDomainDigestV1.from(domain),
                bindingDigest = exactSourceBindings.bindingDigest,
                structuredChoiceDomain = structuredChoices,
            )
        }
    }
}

private fun completeDomainOrFailClosed(
    observation: TrainingObservation,
): CompleteLegalDomainV1 = try {
    CompleteLegalDomainV1.from(observation)
} catch (failure: IllegalArgumentException) {
    val pending = observation.pendingDecision
    if (pending?.requiresStructuredResponse != true) {
        throw failure
    }
    val code = when (pending.kind) {
        PendingDecisionKind.SELECT_MANA_SOURCES -> DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        PendingDecisionKind.ASSIGN_DAMAGE -> DiagnosticCode.STRUCTURED_DECISION_DOMAIN_MISSING
        else -> DiagnosticCode.STRUCTURED_DECISION_DOMAIN_MISSING
    }
    throw UnsupportedPathFailure(listOf(DiagnosticSignal(code)), failure.message ?: code.name)
}

private fun failClosedForStructuredProjection(
    observation: TrainingObservation,
    failure: IllegalArgumentException,
): Nothing {
    val code = when (observation.pendingDecision?.kind) {
        PendingDecisionKind.SELECT_MANA_SOURCES -> DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        else -> DiagnosticCode.STRUCTURED_DOMAIN_UNSUPPORTED
    }
    throw UnsupportedPathFailure(listOf(DiagnosticSignal(code)), failure.message ?: code.name)
}

private fun requireCanonicalSemanticBinding(value: JsonObject) {
    require(A3SemanticJson.canonicalJson(value).isNotBlank()) {
        "Exact JVM semantic source binding must not be blank"
    }
}

private fun requireCanonicalTieDiscriminator(
    value: String,
    forbiddenRawValues: Set<String> = emptySet(),
) {
    val parsed = try {
        A3SemanticJson.strictJson.parseToJsonElement(value)
    } catch (failure: Exception) {
        throw IllegalArgumentException("Live semantic tie discriminator is not JSON", failure)
    }
    require(parsed is JsonObject) {
        "Live semantic tie discriminator must be a JSON object"
    }
    require(A3SemanticJson.canonicalJson(parsed) == value) {
        "Live semantic tie discriminator must be canonical JSON"
    }
    C1ModelFacingProjectionV1.requireModelFacingFeatureView(
        parsed,
        forbiddenRawValues,
    )
}

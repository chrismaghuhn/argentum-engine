package com.wingedsheep.gym.trainer.trajectory

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Version of the transport-free semantic replay input contract. */
const val SEMANTIC_REPLAY_INPUT_V1_VERSION: Int = 1

/** Stable identity of one semantic replay input. */
const val SEMANTIC_REPLAY_INPUT_V1_SCHEMA_IDENTITY: String =
    "argentum-trajectory-semantic-replay-input@v1"

/** Version of the ordered semantic replay-prefix contract. */
const val SEMANTIC_REPLAY_PREFIX_V1_VERSION: Int = 1

/** Stable identity of an ordered semantic replay prefix. */
const val SEMANTIC_REPLAY_PREFIX_V1_SCHEMA_IDENTITY: String =
    "argentum-trajectory-semantic-replay-prefix@v1"

/** Version of a content-addressed semantic replay-prefix digest. */
const val SEMANTIC_REPLAY_PREFIX_DIGEST_V1_VERSION: Int = 1

/** Stable identity of a semantic replay-prefix digest. */
const val SEMANTIC_REPLAY_PREFIX_DIGEST_V1_SCHEMA_IDENTITY: String =
    "argentum-trajectory-semantic-replay-prefix-digest@v1"

internal const val A3_TRIGGER_ORDER_OBJECT_HANDLE_PREFIX = "trigger-order-object-"

/** The semantic input kinds that can precede a later decision boundary. */
@Serializable
enum class SemanticReplayInputKind {
    ACTION,
    RESPONSE,
}

/** One fully transport-free semantic action or response in replay order. */
@Serializable
data class SemanticReplayInputV1(
    val version: Int = SEMANTIC_REPLAY_INPUT_V1_VERSION,
    val schemaIdentity: String = SEMANTIC_REPLAY_INPUT_V1_SCHEMA_IDENTITY,
    val kind: SemanticReplayInputKind,
    val semanticValue: JsonObject,
) {
    init {
        require(version == SEMANTIC_REPLAY_INPUT_V1_VERSION) {
            "Unsupported semantic replay input version"
        }
        require(schemaIdentity == SEMANTIC_REPLAY_INPUT_V1_SCHEMA_IDENTITY) {
            "Unsupported semantic replay input identity"
        }
        A3SemanticJson.requireSemanticObject(semanticValue, "semantic replay input")
        A3SemanticJson.requireNoOpaqueTriggerHandles(semanticValue, "semantic replay input")
        val expectedType = when (kind) {
            SemanticReplayInputKind.ACTION -> "chosen-action"
            SemanticReplayInputKind.RESPONSE -> "chosen-response"
        }
        require(semanticValue["type"]?.let(A3SemanticJson::stringOrNull) == expectedType) {
            "Semantic replay input does not contain a chosen A3 value"
        }
        val expectedKeys = when (kind) {
            SemanticReplayInputKind.ACTION -> setOf("type", "candidate", "choicePayload")
            SemanticReplayInputKind.RESPONSE -> setOf("type", "response")
        }
        require(semanticValue.keys == expectedKeys) {
            "Semantic replay input has an unsupported value shape"
        }
    }

    /** Canonical semantic JSON for this input. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(semanticElement())

    internal fun semanticElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("kind", kind.name)
        put("semanticValue", semanticValue)
    }

    companion object {
        fun action(chosen: ChosenSemanticActionV1): SemanticReplayInputV1 = SemanticReplayInputV1(
            kind = SemanticReplayInputKind.ACTION,
            semanticValue = chosen.replaySemanticElement(),
        )

        fun response(chosen: ChosenSemanticResponseV1): SemanticReplayInputV1 = SemanticReplayInputV1(
            kind = SemanticReplayInputKind.RESPONSE,
            semanticValue = chosen.replaySemanticElement(),
        )
    }
}

/** Ordered semantic replay history before a current decision boundary. */
@Serializable
data class SemanticReplayPrefixV1(
    val version: Int = SEMANTIC_REPLAY_PREFIX_V1_VERSION,
    val schemaIdentity: String = SEMANTIC_REPLAY_PREFIX_V1_SCHEMA_IDENTITY,
    val inputs: List<SemanticReplayInputV1> = emptyList(),
) {
    init {
        require(version == SEMANTIC_REPLAY_PREFIX_V1_VERSION) {
            "Unsupported semantic replay-prefix version"
        }
        require(schemaIdentity == SEMANTIC_REPLAY_PREFIX_V1_SCHEMA_IDENTITY) {
            "Unsupported semantic replay-prefix identity"
        }
    }

    /** Canonical semantic JSON preserving input order exactly. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(semanticElement())

    /** Content digest of this ordered semantic prefix. */
    fun digest(): SemanticReplayPrefixDigestV1 = SemanticReplayPrefixDigestV1.from(this)

    internal fun semanticElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("inputs", JsonArray(inputs.map(SemanticReplayInputV1::semanticElement)))
    }
}

/** Versioned content digest for one ordered semantic replay prefix. */
@Serializable
data class SemanticReplayPrefixDigestV1(
    val version: Int = SEMANTIC_REPLAY_PREFIX_DIGEST_V1_VERSION,
    val schemaIdentity: String = SEMANTIC_REPLAY_PREFIX_DIGEST_V1_SCHEMA_IDENTITY,
    val value: String,
) {
    init {
        require(version == SEMANTIC_REPLAY_PREFIX_DIGEST_V1_VERSION) {
            "Unsupported semantic replay-prefix digest version"
        }
        require(schemaIdentity == SEMANTIC_REPLAY_PREFIX_DIGEST_V1_SCHEMA_IDENTITY) {
            "Unsupported semantic replay-prefix digest identity"
        }
        A3SemanticJson.requireSha256(value, "semantic replay-prefix digest")
    }

    companion object {
        fun from(prefix: SemanticReplayPrefixV1): SemanticReplayPrefixDigestV1 =
            SemanticReplayPrefixDigestV1(
                value = A3SemanticJson.sha256(prefix.canonicalJson().toByteArray(Charsets.UTF_8))
            )
    }
}

/** Shared strict JSON and fail-closed rules for the A3 transport-free contracts. */
internal object A3SemanticJson {
    val strictJson: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
        classDiscriminator = "type"
        allowStructuredMapKeys = true
        ignoreUnknownKeys = false
    }

    private val forbiddenKeys = setOf(
        "actionId",
        "decisionId",
        "pendingDecisionId",
        "nonce",
        "continuationNonce",
        "projectionGeneration",
        "recordingRevision",
        "sessionId",
        "gameSessionId",
        "envId",
        "EnvId",
        "abilityId",
        "runtimeAbilityId",
        "autoPay",
        "autoPaySuggestion",
        "workerId",
        "pid",
        "wallTime",
        "timestamp",
        "collectionJobId",
        "datasetId",
        "trajectoryId",
        "policyIdentity",
        "policySeed",
    )

    fun requireSemanticObject(value: JsonObject, label: String) {
        require(value["type"]?.let(::stringOrNull) != null) {
            "$label requires a string type"
        }
        requireNoForbiddenKeys(value, label)
    }

    fun requireNoForbiddenKeys(value: JsonElement, label: String) {
        when (value) {
            is JsonObject -> {
                require(value.keys.none(forbiddenKeys::contains)) {
                    "$label contains a transport or provenance field"
                }
                value.values.forEach { child -> requireNoForbiddenKeys(child, label) }
            }

            is JsonArray -> value.forEach { child -> requireNoForbiddenKeys(child, label) }
            else -> Unit
        }
    }

    fun requireNoOpaqueTriggerHandles(value: JsonElement, label: String) {
        when (value) {
            is JsonObject -> value.values.forEach { child ->
                requireNoOpaqueTriggerHandles(child, label)
            }

            is JsonArray -> value.forEach { child -> requireNoOpaqueTriggerHandles(child, label) }
            is JsonPrimitive -> if (value.isString) {
                require(!value.content.startsWith(A3_TRIGGER_ORDER_OBJECT_HANDLE_PREFIX)) {
                    "$label contains an opaque trigger-order handle"
                }
            }
        }
    }

    fun <T> decodeStrict(serializer: KSerializer<T>, value: JsonElement, label: String): T =
        try {
            strictJson.decodeFromJsonElement(serializer, value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Malformed $label")
        }

    fun canonicalJson(value: JsonElement): String = canonicalElement(value).toString()

    fun canonicalElement(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { (key, child) ->
            key to canonicalElement(child)
        })

        is JsonArray -> JsonArray(value.map(::canonicalElement))
        else -> value
    }

    fun sha256(bytes: ByteArray): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    fun requireSha256(value: String, label: String) {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "$label must be lowercase SHA-256 hex"
        }
    }

    fun stringOrNull(value: JsonElement): String? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull || !primitive.isString) return null
        return primitive.content
    }
}

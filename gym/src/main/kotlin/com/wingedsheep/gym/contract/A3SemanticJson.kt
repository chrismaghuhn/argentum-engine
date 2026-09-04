package com.wingedsheep.gym.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared strict JSON and fail-closed rules for the A3 transport-free contracts. */
private const val A3_TRIGGER_ORDER_OBJECT_HANDLE_PREFIX = "trigger-order-object-"

object A3SemanticJson {
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

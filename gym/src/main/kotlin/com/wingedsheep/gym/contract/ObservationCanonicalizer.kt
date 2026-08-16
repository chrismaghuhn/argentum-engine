package com.wingedsheep.gym.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Deterministic projections of an already perspective-safe observation.
 *
 * This object is deliberately downstream of [ObservationBuilder]. It accepts no [GameState],
 * performs no visibility decisions, and is not a second public observation model.
 */
internal object ObservationCanonicalizer {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
        classDiscriminator = "type"
    }

    /** Serialize the actual wire DTO, retaining transport IDs and presentation fields. */
    fun wireJson(observation: TrainingObservation): String =
        canonicalize(json.encodeToJsonElement(TrainingObservation.serializer(), observation)).toString()

    /**
     * Produce the deterministic internal semantic projection used for equality and digesting.
     * Transport handles and presentation-only text are intentionally absent.
     */
    fun semanticJson(observation: TrainingObservation): String {
        val encoded = json.encodeToJsonElement(TrainingObservation.serializer(), observation).jsonObject
        val semantic = encoded.toMutableMap()
        semantic.remove("stateDigest")

        encoded["pendingDecision"]?.let { pending ->
            semantic["pendingDecision"] = if (pending is JsonObject) {
                JsonObject(
                    pending.filterKeys {
                        it !in setOf("decisionId", "prompt", "sourceName", "effectHint")
                    }
                )
            } else {
                pending
            }
        }

        semantic["legalActions"] = JsonArray(
            observation.legalActions
                .map(::semanticActionFingerprint)
                .sortedBy { canonicalize(it).toString() }
        )

        return canonicalize(JsonObject(semantic)).toString()
    }

    /** The structured, transport-ID-free semantic identity of one legal action. */
    fun semanticActionFingerprint(action: LegalActionView): JsonObject = buildJsonObject {
        put("kind", action.kind)
        put("affordable", action.affordable)
        put("sourceEntityId", action.sourceEntityId?.value)
        put(
            "targetEntityIds",
            buildJsonArray {
                action.targetEntityIds.forEach { add(JsonPrimitive(it.value)) }
            }
        )
        put("manaCost", action.manaCost)
        put("hasXCost", action.hasXCost)
        put("maxAffordableX", action.maxAffordableX)
        put("minTargets", action.minTargets)
        put("maxTargets", action.maxTargets)
        put("requiresDamageDistribution", action.requiresDamageDistribution)
        put("isManaAbility", action.isManaAbility)
        put("isDecisionOption", action.isDecisionOption)
    }

    private val unorderedArrayKeys = setOf("types", "subtypes", "colors", "keywords", "availableColors")

    private fun canonicalize(element: JsonElement, propertyName: String? = null): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value, key) }
        )

        is JsonArray -> {
            val values = element.map { canonicalize(it) }
            if (propertyName in unorderedArrayKeys) {
                JsonArray(values.sortedBy { it.toString() })
            } else {
                JsonArray(values)
            }
        }
        else -> element
    }
}

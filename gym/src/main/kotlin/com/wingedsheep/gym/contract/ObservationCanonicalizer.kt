package com.wingedsheep.gym.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        allowStructuredMapKeys = true
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
                val pendingSemantic = pending.filterKeys {
                    it !in setOf("decisionId", "prompt", "sourceName", "effectHint")
                }.toMutableMap()
                pending["structuredDomain"]?.let { domain ->
                    if (domain is JsonObject) {
                        pendingSemantic["structuredDomain"] = semanticStructuredDomain(domain)
                    }
                }
                JsonObject(pendingSemantic)
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
        put(
            "validSacrificeTargets",
            buildJsonArray {
                action.validSacrificeTargets.forEach { add(JsonPrimitive(it.value)) }
            }
        )
        put("sacrificeCount", action.sacrificeCount)
        put("sacrificeMinCount", action.sacrificeMinCount)
        put("sacrificeMaxCount", action.sacrificeMaxCount)
        put("requiresDamageDistribution", action.requiresDamageDistribution)
        put("isManaAbility", action.isManaAbility)
        put("requiresStructuredAction", action.requiresStructuredAction)
        action.actionSemantics?.let { put("actionSemantics", it) }
        put("isDecisionOption", action.isDecisionOption)
    }

    /**
     * Remove opaque routing handles from the semantic identity of ordering domains.  Ordinary
     * entity IDs remain established public references; trigger ordering uses generated
     * `trigger-order-object-*` handles whose stable actor-facing labels are the semantic value.
     */
    private fun semanticStructuredDomain(domain: JsonObject): JsonObject {
        val type = domain["type"]?.jsonPrimitive?.content
        if (type == "mana-sources") {
            return stripStructuredPresentation(
                JsonObject(domain.entries
                    .filter { (key, _) -> key != "autoPaySuggestion" }
                    .associate { (key, value) -> key to value })
            ).jsonObject
        }
        if (type != "ordering") return stripStructuredPresentation(domain).jsonObject

        val objectIds = domain["objects"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val labels = domain["objectLabels"]?.jsonObject
        val cardInfo = domain["cardInfo"]?.jsonObject
        val objectSemantics = objectIds.map { id ->
            buildJsonObject {
                val opaque = id.startsWith("trigger-order-object-")
                if (!opaque) put("entityId", id)
                if (opaque) labels?.get(id)?.let { put("label", it) }
                if (opaque) {
                    cardInfo?.get(id)?.let {
                        put("cardInfo", stripStructuredPresentation(it))
                    }
                }
            }
        }.sortedBy { canonicalize(it).toString() }

        return stripStructuredPresentation(buildJsonObject {
            domain.entries
                .filter { (key, _) -> key !in setOf("objects", "cardInfo", "objectLabels") }
                .forEach { (key, value) -> put(key, value) }
            put("objectSemantics", JsonArray(objectSemantics))
        }).jsonObject
    }

    /**
     * Keep legal constraints and visible card characteristics in semantic identity, but remove
     * fields that only select a renderer or provide human-facing explanatory copy. These fields
     * remain on the wire for clients; they must not make equivalent information sets hash apart.
     */
    private fun stripStructuredPresentation(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .filter { (key, _) -> key !in structuredPresentationKeys }
                .associate { (key, value) -> key to stripStructuredPresentation(value) }
        )

        is JsonArray -> JsonArray(element.map(::stripStructuredPresentation))
        else -> element
    }

    private val structuredPresentationKeys = setOf(
        "description",
        "filterDescription",
        "iconKey",
        "imageUri",
        "remainderLabel",
        "selectedLabel",
        "text",
        "useTargetingUI"
    )

    private val unorderedArrayKeys = setOf(
        "types",
        "subtypes",
        "colors",
        "keywords",
        "availableColors",
        "attachments",
        "targetEntityIds",
        "validSacrificeTargets",
        "candidates",
        "nonSelectableOptions",
        "matchingOptions",
        "availableSources",
        "waterbendPermanents",
        "producesColors",
        "blockedByIds",
        "blockedAttackerIds"
    )

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

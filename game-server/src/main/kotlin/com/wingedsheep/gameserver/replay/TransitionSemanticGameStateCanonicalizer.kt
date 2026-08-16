package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.persistence.persistenceJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Canonical v3 representation of the transition-semantic GameState.
 *
 * JSON object keys are sorted, known unordered collections are sorted by their canonical element
 * bytes, and semantically ordered arrays (notably library, stack, turn order, and options) retain
 * their source order. Runtime decision nonces are replaced only in the typed pending-decision and
 * continuation-reference slots. Presentation-only decision text is omitted after the replay field
 * audit; semantic decision shape remains in the representation.
 */
internal object TransitionSemanticGameStateCanonicalizer {

    private val presentationOnlyKeys = setOf("prompt", "effectHint")

    /** JSON array fields whose Kotlin source types are unordered sets. */
    private val unorderedArrayKeys = setOf(
        "priorityPassedBy",
        "pendingSacrificeIds",
        "playersWhoCommittedCrimeThisTurn",
        "lastCastSpellColors",
        "activeReplacementChain",
        "colors",
        "subtypes",
        "supertypes",
        "types",
        "keywords",
    )

    fun canonicalJson(state: GameState): String {
        val serialized = persistenceJson.encodeToJsonElement(GameState.serializer(), state)
        val aliases = DecisionNonceAliasTable()
        val canonical = canonicalize(serialized, emptyList(), aliases)
        return persistenceJson.encodeToString(JsonElement.serializer(), canonical)
    }

    private fun canonicalize(
        element: JsonElement,
        path: List<String>,
        aliases: DecisionNonceAliasTable,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val decisionTree = path.contains("pendingDecision") || path.contains("continuationStack")
            val sorted = element.entries
                .asSequence()
                .filterNot { (key, _) -> decisionTree && key in presentationOnlyKeys }
                .map { (key, value) ->
                    key to canonicalize(value, path + key, aliases)
                }
                .sortedBy { it.first }
                .toList()
            val ordered = LinkedHashMap<String, JsonElement>(sorted.size)
            sorted.forEach { (key, value) -> ordered[key] = value }
            JsonObject(ordered)
        }

        is JsonArray -> {
            val canonicalElements = element.mapIndexed { index, child ->
                canonicalize(child, path + index.toString(), aliases)
            }
            if (path.lastOrNull() in unorderedArrayKeys) {
                JsonArray(canonicalElements.sortedBy { render(it) })
            } else {
                JsonArray(canonicalElements)
            }
        }

        is JsonPrimitive -> canonicalizePrimitive(element, path, aliases)
    }

    private fun canonicalizePrimitive(
        primitive: JsonPrimitive,
        path: List<String>,
        aliases: DecisionNonceAliasTable,
    ): JsonPrimitive {
        if (!primitive.isString) return primitive
        val key = path.lastOrNull() ?: return primitive
        val pendingDecisionId = key == "id" && path.contains("pendingDecision")
        val continuationDecisionId = key == "decisionId" && path.contains("continuationStack")
        if (!pendingDecisionId && !continuationDecisionId) return primitive
        return JsonPrimitive(aliases.alias(primitive.content))
    }

    private fun render(element: JsonElement): String =
        persistenceJson.encodeToString(JsonElement.serializer(), element)
}

package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.model.EntityId
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Version of the additive committed perspective-event batch contract. */
const val PERSPECTIVE_EVENT_BATCH_V1_VERSION: Int = 1

/** Stable identity of the additive committed perspective-event batch contract. */
const val PERSPECTIVE_EVENT_BATCH_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-perspective-event-batch@v1"

/**
 * Event families that A can currently project without a knowledge ledger or semantic object alias.
 *
 * The enum describes only the public DTO vocabulary. Raw [com.wingedsheep.engine.core.GameEvent]
 * families that need object references, historical knowledge, or uncharacterized semantics are
 * reported as unsupported and never enter this enum.
 */
@Serializable
enum class PerspectiveEventFamily {
    PHASE_CHANGED,
    STEP_CHANGED,
    TURN_CHANGED,
    DAY_NIGHT_CHANGED,
    PRIORITY_CHANGED,
    LIFE_CHANGED,
    DAMAGE_TO_PLAYER,
    SPELL_CAST,
    ABILITY_ACTIVATED,
    LAND_PLAYED,
    ZONE_CHANGED,
    CARDS_DRAWN,
    CARD_REVEALED_FROM_DRAW,
    DRAW_FAILED,
    CARDS_DISCARDED,
    DISCARD_REQUIRED,
    LIBRARY_SHUFFLED,
    LIBRARY_SEARCHED,
    SCRY_COMPLETED,
    SURVEIL_COMPLETED,
    PUBLIC_HAND_REVEALED,
    PUBLIC_CARDS_REVEALED,
    PRIVATE_HAND_LOOKED_AT,
    PRIVATE_CARDS_LOOKED_AT,
    ATTACKERS_DECLARED,
    BLOCKERS_DECLARED,
    DAMAGE_ASSIGNED,
    CREATURE_TYPE_CHOSEN,
    GAME_ENDED,
    PLAYER_LOST,
    PLAYER_LEFT,
    TURNED_FACE_UP,
    TURNED_FACE_DOWN,
    TRANSFORMED,
    SPELL_COPIED,
    RESOLVED,
}

internal val PerspectiveEventFamily.payloadType: String
    get() = name.lowercase()

/**
 * One event that is safe for one perspective after event-time projection.
 *
 * [semanticPayload] contains only facts, roles, and scalar values. It deliberately has no raw
 * object/player references or source coordinates; those belong to later B/C contracts or to an
 * internal provenance envelope.
 */
@Serializable
data class PerspectiveEventV1(
    val perspectiveEventOrdinal: Int,
    val eventFamily: PerspectiveEventFamily,
    val semanticPayload: JsonObject,
) {
    init {
        require(perspectiveEventOrdinal >= 0) {
            "Perspective event ordinal must not be negative"
        }
        A3SemanticJson.requireSemanticObject(semanticPayload, "Perspective event payload")
        A3SemanticJson.requireNoOpaqueTriggerHandles(semanticPayload, "Perspective event payload")
        require(semanticPayload["type"]?.jsonPrimitive?.content == eventFamily.payloadType) {
            "Perspective event payload type must match its event family"
        }
        requireNoRuntimeIdentityKeys(semanticPayload, "Perspective event payload")
    }
}

/**
 * Immutable perspective projection for the raw events from one committed transition.
 *
 * The batch is a safe partial projection when [isComplete] is false, but consumers must reject it
 * as a complete history unit until every raw event has an accepted classification. Diagnostics are
 * operational evidence and are intentionally not serialized into the batch.
 */
@Serializable
data class PerspectiveEventBatchV1(
    val version: Int = PERSPECTIVE_EVENT_BATCH_V1_VERSION,
    val schemaIdentity: String = PERSPECTIVE_EVENT_BATCH_V1_SCHEMA_IDENTITY,
    val perspectivePlayerId: EntityId,
    val entries: List<PerspectiveEventV1>,
) {
    init {
        require(version == PERSPECTIVE_EVENT_BATCH_V1_VERSION) {
            "Unsupported perspective-event batch version: $version"
        }
        require(schemaIdentity == PERSPECTIVE_EVENT_BATCH_V1_SCHEMA_IDENTITY) {
            "Unsupported perspective-event batch schema: $schemaIdentity"
        }
        require(perspectivePlayerId.value.isNotBlank()) {
            "Perspective-event batch perspective identity is required"
        }
        require(entries.map(PerspectiveEventV1::perspectiveEventOrdinal) ==
            (0 until entries.size).toList()) {
            "Perspective event ordinals must be contiguous and producer ordered"
        }
    }

    /** Canonical semantic JSON. Producer order is preserved for the entry array. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(
        A3SemanticJson.strictJson.encodeToJsonElement(serializer(), this),
    )

    /** SHA-256 over the canonical semantic batch bytes. */
    fun semanticDigest(): String = A3SemanticJson.sha256(
        canonicalJson().toByteArray(StandardCharsets.UTF_8),
    )
}

/** Disposition assigned to every raw event seen by the projector. This is operational metadata. */
internal enum class PerspectiveEventDisposition {
    EMITTED,
    INTENTIONALLY_HIDDEN,
    UNSUPPORTED_FOR_PERSPECTIVE_HISTORY,
}

/** Why an event could not cross the A projection boundary. This is operational metadata. */
internal enum class PerspectiveEventUnsupportedReason {
    REQUIRES_KNOWLEDGE_LEDGER_B,
    REQUIRES_SEMANTIC_REFERENCE_C,
    REQUIRES_BOTH_B_AND_C,
    EVENT_TIME_STATE_REQUIRED,
    UNCHARACTERIZED,
}

/** Operational evidence for one unsupported raw event. Never part of model-facing serialization. */
internal data class PerspectiveEventDiagnostic(
    val rawEventType: String,
    val reason: PerspectiveEventUnsupportedReason,
)

/** Complete classification aligned one-for-one with the input raw event list. */
internal data class PerspectiveEventClassification(
    val rawEventType: String,
    val disposition: PerspectiveEventDisposition,
    val reason: PerspectiveEventUnsupportedReason? = null,
    val visibilityRationale: String? = null,
) {
    init {
        when (disposition) {
            PerspectiveEventDisposition.EMITTED -> {
                require(reason == null) { "Emitted events cannot carry an unsupported reason" }
                require(visibilityRationale == null) {
                    "Emitted events cannot carry a hidden-event rationale"
                }
            }

            PerspectiveEventDisposition.INTENTIONALLY_HIDDEN -> {
                require(reason == null) { "Hidden events cannot carry an unsupported reason" }
                require(!visibilityRationale.isNullOrBlank()) {
                    "Intentionally hidden events require a visibility rationale"
                }
            }

            PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY -> {
                require(reason != null) { "Unsupported events require a diagnostic reason" }
                require(visibilityRationale == null) {
                    "Unsupported events cannot carry a hidden-event rationale"
                }
            }
        }
    }
}

/**
 * Result of projecting one raw committed event batch for one perspective.
 *
 * This operational result is not serializable and is not itself a model input. Only [batch] may
 * cross the model boundary, and only when [isComplete] is true.
 */
internal data class PerspectiveEventProjectionResult(
    val batch: PerspectiveEventBatchV1,
    val classifications: List<PerspectiveEventClassification>,
) {
    init {
        require(classifications.size >= batch.entries.size) {
            "Projection classifications cannot cover fewer events than emitted entries"
        }
    }

    val diagnostics: List<PerspectiveEventDiagnostic>
        get() = classifications.mapNotNull { classification ->
            classification.reason?.let { reason ->
                PerspectiveEventDiagnostic(classification.rawEventType, reason)
            }
        }

    val isComplete: Boolean
        get() = diagnostics.isEmpty()

    internal fun requireComplete(): PerspectiveEventBatchV1 {
        require(isComplete) {
            "Perspective event projection is incomplete: ${diagnostics.joinToString()}"
        }
        return batch
    }
}

private val forbiddenPerspectiveEventKeys = setOf(
    "entityId",
    "entityIds",
    "sourceId",
    "sourceIds",
    "targetId",
    "targetIds",
    "cardId",
    "cardIds",
    "ownerId",
    "controllerId",
    "playerId",
    "rawEventOrdinal",
    "rawEventIndex",
    "sourceEventOrdinal",
    "sourceActionIndex",
    "committedActionIndex",
    "withinTransitionOrdinal",
    "replayActionIndex",
    "decisionId",
    "actionId",
    "objectIdentityStamp",
    "objectIdentityStamps",
)

private fun requireNoRuntimeIdentityKeys(element: JsonElement, label: String) {
    when (element) {
        is JsonObject -> {
            require(element.keys.none { key ->
                key in forbiddenPerspectiveEventKeys ||
                    key.endsWith("Id", ignoreCase = true) ||
                    key.endsWith("Ids", ignoreCase = true) ||
                    key.contains("ordinal", ignoreCase = true) ||
                    key.contains("coordinate", ignoreCase = true)
            }) {
                "$label contains a runtime identity or source coordinate"
            }
            element.values.forEach { child -> requireNoRuntimeIdentityKeys(child, label) }
        }

        is JsonArray -> element.forEach { child -> requireNoRuntimeIdentityKeys(child, label) }
        else -> Unit
    }
}

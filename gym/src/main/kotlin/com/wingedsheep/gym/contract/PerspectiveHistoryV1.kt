package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.model.EntityId
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

const val PERSPECTIVE_HISTORY_V1_VERSION: Int = 1

const val PERSPECTIVE_HISTORY_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-perspective-history@v1"

@Serializable
enum class PerspectiveHistoryReferenceRoleV1 {
    EVENT_SUBJECT,
    MOVED_OBJECT,
    SOURCE,
    TARGET,
}

@Serializable
enum class PerspectiveHistoryIdentityDisclosureV1 {
    OPAQUE,
    DEFINITION_KNOWN,
}

@Serializable
data class PerspectiveHistoryReferenceV1(
    val semanticRole: PerspectiveHistoryReferenceRoleV1,
    val semanticAlias: String,
    val identityDisclosure: PerspectiveHistoryIdentityDisclosureV1,
    val cardDefinitionId: String? = null,
) {
    init {
        require(Regex("o[0-9]+").matches(semanticAlias)) {
            "Perspective history references require canonical semantic aliases"
        }
        when (identityDisclosure) {
            PerspectiveHistoryIdentityDisclosureV1.OPAQUE ->
                require(cardDefinitionId == null) {
                    "Opaque perspective history references cannot carry printed identity"
                }

            PerspectiveHistoryIdentityDisclosureV1.DEFINITION_KNOWN ->
                require(!cardDefinitionId.isNullOrBlank()) {
                    "Known perspective history references require printed identity"
                }
        }
    }
}

@Serializable
data class PerspectiveHistoryEntryV1(
    val perspectiveHistoryOrdinal: Long,
    val eventFamily: PerspectiveEventFamily,
    val semanticPayload: JsonObject,
    val references: List<PerspectiveHistoryReferenceV1> = emptyList(),
) {
    init {
        require(perspectiveHistoryOrdinal >= 0L) {
            "Perspective history ordinals must not be negative"
        }
        A3SemanticJson.requireSemanticObject(semanticPayload, "Perspective history payload")
        A3SemanticJson.requireNoOpaqueTriggerHandles(
            semanticPayload,
            "Perspective history payload",
        )
        require(semanticPayload["type"]?.toString()?.trim('"') == eventFamily.name.lowercase()) {
            "Perspective history payload type must match its event family"
        }
    }
}

/**
 * V1 intentionally omits knowledgeEpoch: the Rules ledger's acquiredAtEpoch is active-fact
 * provenance, not an event-time History-D epoch, and no exact event-time History-D authority exists.
 */
@Serializable
data class PerspectiveHistoryV1(
    val version: Int = PERSPECTIVE_HISTORY_V1_VERSION,
    val schemaIdentity: String = PERSPECTIVE_HISTORY_V1_SCHEMA_IDENTITY,
    val semanticEpisodeId: String,
    val perspectivePlayerId: EntityId,
    val entries: List<PerspectiveHistoryEntryV1> = emptyList(),
) {
    init {
        require(version == PERSPECTIVE_HISTORY_V1_VERSION) {
            "Unsupported perspective history version: $version"
        }
        require(schemaIdentity == PERSPECTIVE_HISTORY_V1_SCHEMA_IDENTITY) {
            "Unsupported perspective history schema: $schemaIdentity"
        }
        require(semanticEpisodeId.isNotBlank()) {
            "Perspective history requires a semantic episode identity"
        }
        require(perspectivePlayerId.value.isNotBlank()) {
            "Perspective history requires a perspective identity"
        }
        require(entries.map { it.perspectiveHistoryOrdinal } ==
            entries.indices.map { it.toLong() }
        ) {
            "Perspective history ordinals must be contiguous and append-only"
        }
    }

    fun canonicalJson(): String = A3SemanticJson.canonicalJson(
        A3SemanticJson.strictJson.encodeToJsonElement(serializer(), this),
    )

    fun semanticDigest(): String = A3SemanticJson.sha256(
        canonicalJson().toByteArray(StandardCharsets.UTF_8),
    )
}

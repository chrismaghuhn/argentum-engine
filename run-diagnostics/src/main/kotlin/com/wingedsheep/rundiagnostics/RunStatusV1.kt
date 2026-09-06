package com.wingedsheep.rundiagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

@Serializable
public data class StageRefV1(
    public val schemaVersion: Int = DiagnosticsSchema.STAGE_REF_SCHEMA_VERSION,
    public val schemaIdentity: String = DiagnosticsSchema.STAGE_REF_SCHEMA_IDENTITY,
    public val stageFamilySchemaIdentity: String,
    public val stageName: String,
) {
    init {
        requireSchemaVersion(schemaVersion, DiagnosticsSchema.STAGE_REF_SCHEMA_VERSION, "schemaVersion")
        requireSchemaIdentity(schemaIdentity, DiagnosticsSchema.STAGE_REF_SCHEMA_IDENTITY, "schemaIdentity")
        requireVersionedStageFamily(stageFamilySchemaIdentity)
        requireSafeToken(stageName, "stageName")
    }
}

@Serializable
public data class ArtifactCounterV1(
    public val schemaVersion: Int = DiagnosticsSchema.ARTIFACT_COUNTER_SCHEMA_VERSION,
    public val schemaIdentity: String = DiagnosticsSchema.ARTIFACT_COUNTER_SCHEMA_IDENTITY,
    public val artifactKind: String,
    public val logicalName: String? = null,
    public val bytesWritten: Long? = null,
    public val itemsFinalized: Long? = null,
    public val finalized: Boolean? = null,
) {
    init {
        requireSchemaVersion(schemaVersion, DiagnosticsSchema.ARTIFACT_COUNTER_SCHEMA_VERSION, "schemaVersion")
        requireSchemaIdentity(schemaIdentity, DiagnosticsSchema.ARTIFACT_COUNTER_SCHEMA_IDENTITY, "schemaIdentity")
        requireSafeToken(artifactKind, "artifactKind")
        logicalName?.let { requireSafeToken(it, "logicalName") }
        requireNonNegative(bytesWritten, "bytesWritten")
        requireNonNegative(itemsFinalized, "itemsFinalized")
    }

    internal fun stableKey(): String = "$artifactKind/${logicalName.orEmpty()}"
}

/**
 * The bounded scalar-counter portion of the logical progress vector. Heartbeat and current-stage
 * fields stay at the surrounding RunStatusV1 level so the emitted JSON follows the accepted sidecar
 * contract without duplicating mutable values in two locations.
 */
@Serializable
public data class ProgressVectorV1(
    public val schemaVersion: Int = DiagnosticsSchema.PROGRESS_VECTOR_SCHEMA_VERSION,
    public val schemaIdentity: String = DiagnosticsSchema.PROGRESS_VECTOR_SCHEMA_IDENTITY,
    public val usefulProgressSequence: Long = 0,
    public val episodeOrdinal: Long? = null,
    public val engineProgressCount: Long? = null,
    public val authoritativeTransitionCount: Long? = null,
    public val semanticDecisionCount: Long? = null,
    public val trajectoryDecisionCount: Long? = null,
    public val replayFramesVerified: Long? = null,
    public val episodesAdmitted: Long? = null,
    public val bytesSerialized: Long? = null,
    public val shardsFinalized: Long? = null,
    public val lastUsefulProgressElapsedNanos: Long? = null,
) {
    init {
        requireSchemaVersion(schemaVersion, DiagnosticsSchema.PROGRESS_VECTOR_SCHEMA_VERSION, "schemaVersion")
        requireSchemaIdentity(schemaIdentity, DiagnosticsSchema.PROGRESS_VECTOR_SCHEMA_IDENTITY, "schemaIdentity")
        require(usefulProgressSequence >= 0) { "usefulProgressSequence must be non-negative" }
        requireNonNegative(episodeOrdinal, "episodeOrdinal")
        requireNonNegative(engineProgressCount, "engineProgressCount")
        requireNonNegative(authoritativeTransitionCount, "authoritativeTransitionCount")
        requireNonNegative(semanticDecisionCount, "semanticDecisionCount")
        requireNonNegative(trajectoryDecisionCount, "trajectoryDecisionCount")
        requireNonNegative(replayFramesVerified, "replayFramesVerified")
        requireNonNegative(episodesAdmitted, "episodesAdmitted")
        requireNonNegative(bytesSerialized, "bytesSerialized")
        requireNonNegative(shardsFinalized, "shardsFinalized")
        requireNonNegative(lastUsefulProgressElapsedNanos, "lastUsefulProgressElapsedNanos")
    }
}

@Serializable
public data class MonotonicAgeDataV1(
    public val clockIdentity: String = DiagnosticsSchema.MONOTONIC_CLOCK_IDENTITY,
    public val heartbeatElapsedNanos: Long? = null,
    public val stageStartedElapsedNanos: Long? = null,
    public val lastUsefulProgressElapsedNanos: Long? = null,
) {
    init {
        requireSchemaIdentity(clockIdentity, DiagnosticsSchema.MONOTONIC_CLOCK_IDENTITY, "clockIdentity")
        requireNonNegative(heartbeatElapsedNanos, "heartbeatElapsedNanos")
        requireNonNegative(stageStartedElapsedNanos, "stageStartedElapsedNanos")
        requireNonNegative(lastUsefulProgressElapsedNanos, "lastUsefulProgressElapsedNanos")
    }
}

@Serializable
public data class StatusPublicationV1(
    public val successfulPublicationSequence: Long = 0,
    public val lastFailureCode: StatusPublicationFailureCode? = null,
) {
    init {
        require(successfulPublicationSequence >= 0) {
            "successfulPublicationSequence must be non-negative"
        }
    }
}

@Serializable
public data class RunStatusV1(
    public val schemaVersion: Int = DiagnosticsSchema.RUN_STATUS_SCHEMA_VERSION,
    public val schemaIdentity: String = DiagnosticsSchema.RUN_STATUS_SCHEMA_IDENTITY,
    public val diagnosticRunId: String,
    public val semanticJobId: String? = null,
    public val sourceCommit: String,
    public val workloadType: String,
    public val processId: Long? = null,
    public val processStartWallClock: String,
    public val heartbeatSequence: Long,
    public val heartbeatWallClock: String? = null,
    public val monotonicAgeData: MonotonicAgeDataV1,
    public val currentStage: StageRefV1,
    public val stageSequence: Long,
    public val stageStartedWallClock: String,
    public val progress: ProgressVectorV1,
    public val latestArtifactCounters: List<ArtifactCounterV1> = emptyList(),
    public val diagnosticMode: DiagnosticsMode = DiagnosticsMode.SIDECAR_NORMAL,
    public val statusPublication: StatusPublicationV1 = StatusPublicationV1(),
) {
    init {
        requireSchemaVersion(schemaVersion, DiagnosticsSchema.RUN_STATUS_SCHEMA_VERSION, "schemaVersion")
        requireSchemaIdentity(schemaIdentity, DiagnosticsSchema.RUN_STATUS_SCHEMA_IDENTITY, "schemaIdentity")
        requireSafeToken(diagnosticRunId, "diagnosticRunId")
        semanticJobId?.let { requireSafeToken(it, "semanticJobId") }
        requireSafeLabel(sourceCommit, "sourceCommit")
        requireSafeToken(workloadType, "workloadType")
        processId?.let { require(it > 0) { "processId must be positive when present" } }
        requireInstant(processStartWallClock, "processStartWallClock")
        require(heartbeatSequence >= 0) { "heartbeatSequence must be non-negative" }
        heartbeatWallClock?.let { requireInstant(it, "heartbeatWallClock") }
        require(stageSequence >= 0) { "stageSequence must be non-negative" }
        requireInstant(stageStartedWallClock, "stageStartedWallClock")
        require(latestArtifactCounters.size <= DiagnosticsSchema.MAX_ARTIFACT_COUNTERS) {
            "latestArtifactCounters exceeds the bounded maximum"
        }
        require(latestArtifactCounters.map(ArtifactCounterV1::stableKey).distinct().size == latestArtifactCounters.size) {
            "latestArtifactCounters must not contain duplicate keys"
        }
    }
}

private fun requireInstant(value: String, field: String) {
    require(value.isNotBlank()) { "$field must not be blank" }
    try {
        Instant.parse(value)
    } catch (exception: Exception) {
        throw IllegalArgumentException("$field must be an ISO-8601 instant", exception)
    }
}

/**
 * Strict, compact JSON for the operational sidecar. Unknown fields are rejected on read and the
 * fixed field model prevents raw game state, observations, actions, rewards, and hidden objects from
 * entering a normal status value accidentally.
 */
public object RunStatusCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    private val forbiddenFieldNames = setOf(
        "gamestate",
        "rawgamestate",
        "rawaction",
        "playerobservation",
        "completelegaldomain",
        "legaldomain",
        "chosenaction",
        "reward",
        "policyinput",
        "modelinput",
        "hiddenhand",
        "librarycontents",
        "facedown",
        "exilecontents",
    )

    public fun encode(
        status: RunStatusV1,
        maxBytes: Int = DiagnosticsSchema.DEFAULT_MAX_SERIALIZED_STATUS_BYTES,
    ): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val canonicalStatus = status.copy(
            latestArtifactCounters = status.latestArtifactCounters.sortedWith(
                compareBy(ArtifactCounterV1::artifactKind, { it.logicalName.orEmpty() }),
            ),
        )
        val element = try {
            json.encodeToJsonElement(RunStatusV1.serializer(), canonicalStatus)
        } catch (exception: Exception) {
            throw StatusSerializationException(StatusPublicationFailureCode.STATUS_SCHEMA_REJECTED, exception)
        }
        rejectForbiddenFields(element)
        val bytes = json.encodeToString(JsonElement.serializer(), element).toByteArray(UTF_8)
        if (bytes.size > maxBytes) {
            throw StatusSerializationException(StatusPublicationFailureCode.STATUS_SERIALIZATION_TOO_LARGE)
        }
        return bytes
    }

    public fun decode(
        bytes: ByteArray,
        maxBytes: Int = DiagnosticsSchema.DEFAULT_MAX_SERIALIZED_STATUS_BYTES,
    ): RunStatusV1 {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (bytes.size > maxBytes) {
            throw StatusSerializationException(StatusPublicationFailureCode.STATUS_SERIALIZATION_TOO_LARGE)
        }
        return try {
            val element = json.parseToJsonElement(bytes.toString(UTF_8))
            requireRequiredSchemaFields(element, "run-status")
            val root = element.jsonObject
            requireRequiredSchemaFields(root["progress"], "run-status.progress")
            requireRequiredSchemaFields(root["currentStage"], "run-status.currentStage")
            requireRequiredField(root["monotonicAgeData"], "run-status.monotonicAgeData", "clockIdentity")
            root["latestArtifactCounters"]?.let { counters ->
                val counterArray = counters as? JsonArray
                    ?: throw SerializationException("run-status.latestArtifactCounters must be an array")
                counterArray.forEachIndexed { index, counter ->
                    requireRequiredSchemaFields(counter, "run-status.latestArtifactCounters[$index]")
                }
            }
            json.decodeFromJsonElement(RunStatusV1.serializer(), element)
        } catch (exception: SerializationException) {
            throw exception
        }
    }

    private fun requireRequiredSchemaFields(element: JsonElement?, path: String) {
        val objectValue = element as? JsonObject
            ?: throw SerializationException("$path must be an object")
        requireRequiredField(objectValue, path, "schemaVersion")
        requireRequiredField(objectValue, path, "schemaIdentity")
    }

    private fun requireRequiredField(element: JsonElement?, path: String, field: String) {
        val objectValue = element as? JsonObject
            ?: throw SerializationException("$path must be an object")
        requireRequiredField(objectValue, path, field)
    }

    private fun requireRequiredField(objectValue: JsonObject, path: String, field: String) {
        if (field !in objectValue) {
            throw SerializationException("$path.$field is required")
        }
    }

    private fun rejectForbiddenFields(element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (name, child) ->
                val normalized = name.lowercase().replace("_", "").replace("-", "").replace(" ", "")
                if (normalized in forbiddenFieldNames) {
                    throw StatusSerializationException(StatusPublicationFailureCode.STATUS_SCHEMA_REJECTED)
                }
                rejectForbiddenFields(child)
            }

            is JsonArray -> element.forEach(::rejectForbiddenFields)
            else -> Unit
        }
    }
}

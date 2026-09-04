package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.A3SemanticJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Shared physical V1 frame contract. A6 writes these DTOs and later A7 reader stages decode the
 * same serializers; this remains three independent serializers rather than a polymorphic wire type.
 */
@Serializable
internal data class EpisodeStartFrameV1(
    val recordType: String = "episode-start",
    val storageSchemaVersion: Int = TRAJECTORY_V1_STORAGE_SCHEMA_VERSION,
    val storageSchemaIdentity: String = TRAJECTORY_V1_STORAGE_SCHEMA_IDENTITY,
    val trajectorySchemaVersion: Int = TRAJECTORY_V1_VERSION,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val episodeOrdinal: Int,
    val episodeMetadata: EpisodeMetadataV1,
)

@Serializable
internal data class DecisionFrameV1(
    val recordType: String = "decision",
    val storageSchemaVersion: Int = TRAJECTORY_V1_STORAGE_SCHEMA_VERSION,
    val storageSchemaIdentity: String = TRAJECTORY_V1_STORAGE_SCHEMA_IDENTITY,
    val trajectorySchemaVersion: Int = TRAJECTORY_V1_VERSION,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val decision: DecisionRecordV1,
)

@Serializable
internal data class EpisodeEndFrameV1(
    val recordType: String = "episode-end",
    val storageSchemaVersion: Int = TRAJECTORY_V1_STORAGE_SCHEMA_VERSION,
    val storageSchemaIdentity: String = TRAJECTORY_V1_STORAGE_SCHEMA_IDENTITY,
    val trajectorySchemaVersion: Int = TRAJECTORY_V1_VERSION,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val trajectoryId: String,
    val decisionCount: Int,
    val episodeContentDigest: String,
    val closure: EpisodeClosureV1,
)

internal class TrajectoryPrivacyViolation : IllegalArgumentException()

/** Owns the one accepted A6/A7 V1 frame encoding and episode-content digest formula. */
internal object TrajectoryV1StorageCodec {
    private val forbiddenOperationalKeys = setOf(
        "actionid",
        "decisionid",
        "pendingdecisionid",
        "nonce",
        "continuationnonce",
        "projectiongeneration",
        "recordingrevision",
        "sessionid",
        "gamesessionid",
        "envid",
        "abilityid",
        "runtimeabilityid",
        "autopay",
        "autopaysuggestion",
        "workerid",
        "pid",
        "walltime",
        "timestamp",
        "gamestate",
        "rawaction",
        "pendingdecisioninternal",
    )

    fun encodeLine(trajectory: TrajectoryV1, episodeOrdinal: Int = 0): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(
            encodeFrame(
                EpisodeStartFrameV1(
                    semanticEpisodeId = trajectory.semanticEpisodeId,
                    collectionJobId = trajectory.collectionJobId,
                    episodeOrdinal = episodeOrdinal,
                    episodeMetadata = trajectory.episodeMetadata,
                ),
            ),
        )
        trajectory.decisions.forEach { decision ->
            output.write(
                encodeFrame(
                    DecisionFrameV1(
                        semanticEpisodeId = trajectory.semanticEpisodeId,
                        collectionJobId = trajectory.collectionJobId,
                        decision = decision,
                    ),
                ),
            )
        }
        output.write(
            encodeFrame(
                EpisodeEndFrameV1(
                    semanticEpisodeId = trajectory.semanticEpisodeId,
                    collectionJobId = trajectory.collectionJobId,
                    trajectoryId = trajectory.trajectoryId,
                    decisionCount = trajectory.decisions.size,
                    episodeContentDigest = episodeContentDigest(trajectory),
                    closure = trajectory.closure,
                ),
            ),
        )
        return output.toByteArray()
    }

    fun episodeContentDigest(trajectory: TrajectoryV1): String {
        val trajectoryElement = A3SemanticJson.strictJson.encodeToJsonElement(
            TrajectoryV1.serializer(),
            trajectory,
        )
        rejectOperationalFields(trajectoryElement)
        return A3SemanticJson.sha256(
            A3SemanticJson.canonicalJson(trajectoryElement).toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun encodeFrame(frame: EpisodeStartFrameV1): ByteArray = encodeFrame(frame, EpisodeStartFrameV1.serializer())

    fun encodeFrame(frame: DecisionFrameV1): ByteArray = encodeFrame(frame, DecisionFrameV1.serializer())

    fun encodeFrame(frame: EpisodeEndFrameV1): ByteArray = encodeFrame(frame, EpisodeEndFrameV1.serializer())

    private fun <T> encodeFrame(value: T, serializer: KSerializer<T>): ByteArray {
        val element = A3SemanticJson.strictJson.encodeToJsonElement(serializer, value)
        rejectOperationalFields(element)
        return (A3SemanticJson.canonicalJson(element) + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun rejectOperationalFields(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                require(element.keys.none { it.lowercase() in forbiddenOperationalKeys }) {
                    throw TrajectoryPrivacyViolation()
                }
                element.values.forEach(::rejectOperationalFields)
            }

            is JsonArray -> element.forEach(::rejectOperationalFields)
            else -> Unit
        }
    }
}

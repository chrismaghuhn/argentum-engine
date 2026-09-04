package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.A3SemanticJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Version of the non-trusted A6 quarantine metadata record. */
const val TRAJECTORY_QUARANTINE_V1_VERSION: Int = 1

/** Stable identity of one privacy-safe A6 quarantine metadata record. */
const val TRAJECTORY_QUARANTINE_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-quarantine@v1"

/** Closed machine-readable reasons for A6 admission or publication rejection. */
@Serializable
enum class TrajectoryQuarantineReason {
    A5_CONTRACT_INVALID,
    FAILED_EPISODE,
    REPLAY_CONTENT_IDENTITY_MISMATCH,
    REPLAY_VERSION_MISMATCH,
    REPLAY_NOT_EXACT,
    REPLAY_RANGE_INCOMPLETE,
    REPLAY_ACTION_COUNT_MISMATCH,
    REPLAY_CLOSURE_MISMATCH,
    FRAME_COORDINATE_MISMATCH,
    PERSPECTIVE_MISMATCH,
    OBSERVATION_MISMATCH,
    LEGAL_DOMAIN_MISMATCH,
    CANDIDATE_DOMAIN_DIGEST_MISMATCH,
    CHOSEN_INPUT_MISMATCH,
    PRIVACY_REJECTION,
    SERIALIZATION_FAILURE,
    EPISODE_TOO_LARGE,
    SHARD_INTEGRITY_FAILURE,
    STORAGE_PUBLICATION_FAILURE,
    DUPLICATE_JOB_OR_EPISODE_CONFLICT,
    EPISODE_ORDER_MISMATCH,
    FINALIZATION_STATE_VIOLATION,
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * Bounded, public-safe evidence for a rejected episode. This type deliberately has no trajectory,
 * observation, domain, chosen-input, exception, or operational-runtime field.
 */
@Serializable
data class QuarantineMetadataV1(
    val version: Int = TRAJECTORY_QUARANTINE_V1_VERSION,
    val schemaIdentity: String = TRAJECTORY_QUARANTINE_V1_SCHEMA_IDENTITY,
    val reason: TrajectoryQuarantineReason,
    val a5Reason: TrajectoryValidationReason? = null,
    val episodeOrdinal: Int? = null,
    val failureReplayActionIndex: Int? = null,
    val semanticEpisodeId: String? = null,
    val collectionJobId: String? = null,
    val trajectoryId: String? = null,
    val replayContentIdentity: String? = null,
) {
    init {
        require(version == TRAJECTORY_QUARANTINE_V1_VERSION) {
            "Unsupported trajectory-quarantine version: $version"
        }
        require(schemaIdentity == TRAJECTORY_QUARANTINE_V1_SCHEMA_IDENTITY) {
            "Unsupported trajectory-quarantine identity: $schemaIdentity"
        }
        require(episodeOrdinal == null || episodeOrdinal >= 0) {
            "Quarantine episode ordinal must not be negative"
        }
        require(failureReplayActionIndex == null || failureReplayActionIndex >= 0) {
            "Quarantine replay-action index must not be negative"
        }
        listOf(
            semanticEpisodeId,
            collectionJobId,
            trajectoryId,
            replayContentIdentity,
        ).filterNotNull().forEach { value ->
            require(value.matches(SHA256_HEX)) {
                "Quarantine identity fields must be lowercase SHA-256 hex"
            }
        }
    }

    internal fun canonicalBytes(): ByteArray {
        val element = A3SemanticJson.strictJson.encodeToJsonElement(serializer(), this)
        return (A3SemanticJson.canonicalJson(element) + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        internal fun from(
            trajectory: TrajectoryV1,
            reason: TrajectoryQuarantineReason,
            episodeOrdinal: Int?,
            failureReplayActionIndex: Int? = null,
            a5Reason: TrajectoryValidationReason? = null,
        ): QuarantineMetadataV1 = QuarantineMetadataV1(
            reason = reason,
            a5Reason = a5Reason,
            episodeOrdinal = episodeOrdinal?.takeIf { it >= 0 },
            failureReplayActionIndex = failureReplayActionIndex?.takeIf { it >= 0 },
            semanticEpisodeId = trajectory.semanticEpisodeId.takeIf { it.matches(SHA256_HEX) },
            collectionJobId = trajectory.collectionJobId.takeIf { it.matches(SHA256_HEX) },
            trajectoryId = trajectory.trajectoryId.takeIf { it.matches(SHA256_HEX) },
            replayContentIdentity = trajectory.compactReplayLink.replayContentIdentity
                .takeIf { it.matches(SHA256_HEX) },
        )
    }
}

/** Storage failure used internally to poison an A6 publisher. It carries no persisted diagnostics. */
class TrajectoryV1StorageException(
    val reason: TrajectoryQuarantineReason,
    cause: Throwable? = null,
) : IllegalStateException("A6 storage operation failed: ${reason.name}", cause)

internal fun moveAtomically(source: Path, target: Path) {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
}

internal fun writeBytesAndForce(path: Path, bytes: ByteArray) {
    try {
        FileChannel.open(
            path,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    } catch (failure: IOException) {
        throw TrajectoryV1StorageException(TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE, failure)
    }
}

/** Writes only quarantine metadata, using content-addressed CREATE_NEW artifacts. */
class TrajectoryV1Quarantine(
    private val directory: Path,
    private val atomicMove: (Path, Path) -> Unit = ::moveAtomically,
) {
    fun persist(metadata: QuarantineMetadataV1): Path {
        Files.createDirectories(directory)
        val bytes = metadata.canonicalBytes()
        val digest = A3SemanticJson.sha256(bytes)
        val target = directory.resolve("quarantine-$digest.json")
        if (Files.exists(target)) {
            if (Files.readAllBytes(target).contentEquals(bytes)) return target
            throw TrajectoryV1StorageException(TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE)
        }

        val staged = Files.createTempFile(directory, "quarantine-", ".tmp")
        try {
            writeBytesAndForce(staged, bytes)
            try {
                atomicMove(staged, target)
            } catch (failure: Exception) {
                throw TrajectoryV1StorageException(
                    TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE,
                    failure,
                )
            }
        } finally {
            Files.deleteIfExists(staged)
        }
        return target
    }
}

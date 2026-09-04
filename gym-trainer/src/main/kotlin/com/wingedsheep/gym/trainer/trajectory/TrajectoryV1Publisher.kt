package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.A3SemanticJson
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Filesystem owner for admitted A6 episodes. It keeps only the current bounded shard's bytes and
 * small manifest indexes in memory; semantic ordering comes from explicit producer ordinals.
 */
class TrajectoryV1Publisher(
    private val outputDirectory: Path,
    private val metadata: DatasetMetadataV1,
    private val atomicMove: (Path, Path) -> Unit = ::moveAtomically,
    quarantine: TrajectoryV1Quarantine? = null,
) : AutoCloseable {
    private enum class State { WRITING, VALIDATING, VALIDATED, PUBLISHED, ABORTED, CLOSED }

    private val maxShardBytes = requireNotNull(metadata.maxShardBytes) {
        "A6 requires a finite positive maxShardBytes"
    }
    private val maxEpisodesPerShard = requireNotNull(metadata.maxEpisodesPerShard) {
        "A6 requires a finite positive maxEpisodesPerShard"
    }
    private val quarantineStore = quarantine ?: TrajectoryV1Quarantine(
        outputDirectory.resolve("quarantine"),
        atomicMove,
    )
    private val stagingDirectory: Path
    private val shardsDirectory: Path
    private var state = State.WRITING
    private var nextEpisodeOrdinal = 0
    private var currentShardBytes = 0L
    private val currentShardEpisodes = mutableListOf<PendingEpisode>()
    private val seenCollectionJobs = HashSet<String>()
    private val shardMetadata = mutableListOf<DatasetShardMetadataV1>()
    private val episodeIndex = mutableListOf<DatasetEpisodeIndexV1>()

    init {
        require(maxShardBytes > 0) { "A6 requires a positive maxShardBytes" }
        require(maxEpisodesPerShard > 0) { "A6 requires a positive maxEpisodesPerShard" }
        require(metadata.deterministicEnumeration == "episode-ordinal-ascending") {
            "A6 requires episode-ordinal-ascending dataset enumeration"
        }
        try {
            Files.createDirectories(outputDirectory)
            val stagingRoot = outputDirectory.resolve(".staging")
            Files.createDirectories(stagingRoot)
            stagingDirectory = Files.createTempDirectory(stagingRoot, "dataset-")
            shardsDirectory = stagingDirectory.resolve("shards")
            Files.createDirectories(shardsDirectory)
        } catch (failure: Exception) {
            throw TrajectoryV1StorageException(
                TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE,
                failure,
            )
        }
    }

    /** Append an already replay-admitted episode in explicit producer ordinal order. */
    fun appendFinalizedEpisode(
        episodeOrdinal: Int,
        episode: ReplayAdmittedEpisodeV1,
    ): TrajectoryAdmissionResult {
        ensureWritable()
        try {
            requireNextOrdinal(episodeOrdinal)
            if (!seenCollectionJobs.add(episode.trajectory.collectionJobId)) {
                return fail(
                    TrajectoryQuarantineReason.DUPLICATE_JOB_OR_EPISODE_CONFLICT,
                    "A collection job was submitted more than once",
                )
            }

            val lineBytes = episode.storageLineBytes()
            if (lineBytes.size.toLong() > maxShardBytes) {
                val quarantineMetadata = QuarantineMetadataV1.from(
                    trajectory = episode.trajectory,
                    reason = TrajectoryQuarantineReason.EPISODE_TOO_LARGE,
                    episodeOrdinal = episodeOrdinal,
                )
                quarantineStore.persist(quarantineMetadata)
                nextEpisodeOrdinal++
                return TrajectoryAdmissionResult.Quarantined(
                    metadata = quarantineMetadata,
                )
            }

            if (
                currentShardEpisodes.isNotEmpty() &&
                (currentShardEpisodes.size >= maxEpisodesPerShard ||
                    currentShardBytes + lineBytes.size.toLong() > maxShardBytes)
            ) {
                finalizeCurrentShard()
            }

            val pending = PendingEpisode(
                episodeOrdinal = episodeOrdinal,
                lineBytes = lineBytes,
                semanticEpisodeId = episode.trajectory.semanticEpisodeId,
                collectionJobId = episode.trajectory.collectionJobId,
                trajectoryId = episode.trajectory.trajectoryId,
                decisionCount = episode.trajectory.decisions.size,
                closureKind = episode.trajectory.closure.kind,
                shardOrdinal = shardMetadata.size,
            )
            currentShardEpisodes += pending
            currentShardBytes += lineBytes.size.toLong()
            episodeIndex += pending.toIndex()
            nextEpisodeOrdinal++
            return TrajectoryAdmissionResult.Admitted(episode)
        } catch (failure: TrajectoryV1StorageException) {
            state = State.ABORTED
            throw failure
        } catch (failure: Exception) {
            state = State.ABORTED
            throw TrajectoryV1StorageException(
                TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE,
                failure,
            )
        }
    }

    /** Record an A6 rejection while consuming its producer ordinal, without trusted membership. */
    internal fun recordQuarantined(
        episodeOrdinal: Int,
        metadata: QuarantineMetadataV1,
    ): TrajectoryAdmissionResult.Quarantined {
        ensureWritable()
        try {
            requireNextOrdinal(episodeOrdinal)
            metadata.collectionJobId?.let { collectionJobId ->
                if (!seenCollectionJobs.add(collectionJobId)) {
                    return fail(
                        TrajectoryQuarantineReason.DUPLICATE_JOB_OR_EPISODE_CONFLICT,
                        "A collection job was submitted more than once",
                    )
                }
            }
            quarantineStore.persist(metadata)
            nextEpisodeOrdinal++
            return TrajectoryAdmissionResult.Quarantined(metadata)
        } catch (failure: TrajectoryV1StorageException) {
            state = State.ABORTED
            throw failure
        } catch (failure: Exception) {
            state = State.ABORTED
            throw TrajectoryV1StorageException(
                TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE,
                failure,
            )
        }
    }

    /** Finalize all shards and publish one complete immutable V1 dataset directory. */
    fun finalizeDataset(): DatasetManifestV1 {
        ensureWritable()
        try {
            state = State.VALIDATING
            finalizeCurrentShard()
            verifyFinalizedShards()
            val counts = DatasetCountsV1(
                episodeCount = episodeIndex.size,
                decisionCount = episodeIndex.sumOf(DatasetEpisodeIndexV1::decisionCount),
                gameTerminalCount = episodeIndex.count {
                    it.closureKind == com.wingedsheep.gym.EpisodeClosureV1.Kind.GAME_TERMINAL
                },
                interruptedCount = episodeIndex.count {
                    it.closureKind == com.wingedsheep.gym.EpisodeClosureV1.Kind.INTERRUPTED
                },
                // FAILED episodes are never admitted and quarantine attempts are not trusted data.
                failedCount = 0,
            )
            val manifest = TrajectoryV1Manifest.build(
                metadata = metadata,
                shards = shardMetadata.toList(),
                episodes = episodeIndex.toList(),
                counts = counts,
            )
            publishManifestLast(manifest)
            state = State.VALIDATED
            val finalDirectory = outputDirectory.resolve("dataset-${manifest.datasetId}")
            if (Files.exists(finalDirectory)) {
                return fail(
                    TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE,
                    "The final dataset destination already exists",
                )
            }
            try {
                atomicMove(stagingDirectory, finalDirectory)
            } catch (failure: Exception) {
                return fail(
                    TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE,
                    "Atomic dataset-directory publication failed",
                    failure,
                )
            }
            state = State.PUBLISHED
            return manifest
        } catch (failure: TrajectoryV1StorageException) {
            state = State.ABORTED
            throw failure
        } catch (failure: Exception) {
            state = State.ABORTED
            throw TrajectoryV1StorageException(
                TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                failure,
            )
        }
    }

    override fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
    }

    private fun ensureWritable() {
        if (state != State.WRITING) {
            throw TrajectoryV1StorageException(TrajectoryQuarantineReason.FINALIZATION_STATE_VIOLATION)
        }
    }

    private fun requireNextOrdinal(episodeOrdinal: Int) {
        if (episodeOrdinal != nextEpisodeOrdinal) {
            return fail(
                TrajectoryQuarantineReason.EPISODE_ORDER_MISMATCH,
                "Episode ordinals must be contiguous and producer ordered",
            )
        }
    }

    private fun finalizeCurrentShard() {
        if (currentShardEpisodes.isEmpty()) return
        val shardOrdinal = shardMetadata.size
        val temporary = Files.createTempFile(shardsDirectory, "shard-$shardOrdinal-", ".tmp")
        val expectedByteCount = currentShardBytes
        val expectedEpisodeCount = currentShardEpisodes.size
        try {
            val writtenDigest = writeShard(temporary)
            val contentReference = "shards/shard-${String.format(Locale.ROOT, "%06d", shardOrdinal)}-" +
                "$writtenDigest$TRAJECTORY_V1_SHARD_EXTENSION"
            val finalPath = stagingDirectory.resolve(contentReference)
            if (Files.exists(finalPath)) {
                throw TrajectoryV1StorageException(
                    TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                )
            }
            Files.createDirectories(finalPath.parent)
            try {
                atomicMove(temporary, finalPath)
            } catch (failure: Exception) {
                throw TrajectoryV1StorageException(
                    TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE,
                    failure,
                )
            }

            val integrity = verifyShardFile(finalPath)
            if (
                integrity.byteCount != expectedByteCount ||
                integrity.digest != writtenDigest ||
                integrity.episodeCount != expectedEpisodeCount.toLong()
            ) {
                throw TrajectoryV1StorageException(TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE)
            }
            val metadata = DatasetShardMetadataV1(
                shardOrdinal = shardOrdinal,
                contentReference = contentReference,
                contentDigest = integrity.digest,
                byteCount = integrity.byteCount,
                episodeCount = expectedEpisodeCount,
            )
            shardMetadata += metadata
            currentShardEpisodes.clear()
            currentShardBytes = 0L
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeShard(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                currentShardEpisodes.forEach { episode ->
                    val bytes = episode.lineBytes
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        channel.write(buffer)
                    }
                    digest.update(bytes)
                }
                channel.force(true)
            }
        } catch (failure: Exception) {
            throw TrajectoryV1StorageException(
                TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                failure,
            )
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun publishManifestLast(manifest: DatasetManifestV1) {
        val manifestPath = stagingDirectory.resolve("manifest.json")
        val temporary = Files.createTempFile(stagingDirectory, "manifest-", ".tmp")
        val manifestBytes = TrajectoryV1Manifest.encode(manifest)
        try {
            writeBytesAndForce(temporary, manifestBytes)
            try {
                atomicMove(temporary, manifestPath)
            } catch (failure: Exception) {
                throw TrajectoryV1StorageException(
                    TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE,
                    failure,
                )
            }
            if (!Files.readAllBytes(manifestPath).contentEquals(manifestBytes)) {
                throw TrajectoryV1StorageException(
                    TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE,
                )
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun verifyFinalizedShards() {
        shardMetadata.forEach { metadata ->
            val path = stagingDirectory.resolve(metadata.contentReference)
            val integrity = verifyShardFile(path)
            if (
                integrity.digest != metadata.contentDigest ||
                integrity.byteCount != metadata.byteCount ||
                integrity.episodeCount != metadata.episodeCount.toLong()
            ) {
                throw TrajectoryV1StorageException(TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE)
            }
        }
    }

    private fun verifyShardFile(path: Path): FileIntegrity {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        var lastByte = -1
        try {
            BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ)).use { input ->
                val buffer = ByteArray(DEFAULT_IO_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    byteCount += read
                    for (index in 0 until read) {
                        val byte = buffer[index].toInt() and 0xff
                        if (byte == '\r'.code) {
                            throw TrajectoryV1StorageException(
                                TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                            )
                        }
                        lastByte = byte
                    }
                }
            }
        } catch (failure: TrajectoryV1StorageException) {
            throw failure
        } catch (failure: Exception) {
            throw TrajectoryV1StorageException(
                TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                failure,
            )
        }
        if (byteCount == 0L || lastByte != '\n'.code) {
            throw TrajectoryV1StorageException(TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE)
        }
        val episodeCount = countEpisodeFrames(path)
        return FileIntegrity(
            digest = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            byteCount = byteCount,
            episodeCount = episodeCount,
        )
    }

    private fun countEpisodeFrames(path: Path): Long {
        var episodeCount = 0L
        var insideEpisode = false
        try {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isEmpty()) {
                        throw TrajectoryV1StorageException(TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE)
                    }
                    val frame = try {
                        A3SemanticJson.strictJson.parseToJsonElement(line).jsonObject
                    } catch (failure: Exception) {
                        throw TrajectoryV1StorageException(
                            TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                            failure,
                        )
                    }
                    requireFrameVersion(frame)
                    when (frame["recordType"]?.let(::stringValue)) {
                        "episode-start" -> {
                            if (insideEpisode) {
                                throw TrajectoryV1StorageException(
                                    TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                                )
                            }
                            insideEpisode = true
                        }

                        "decision" -> if (!insideEpisode) {
                            throw TrajectoryV1StorageException(
                                TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                            )
                        }

                        "episode-end" -> {
                            if (!insideEpisode) {
                                throw TrajectoryV1StorageException(
                                    TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                                )
                            }
                            insideEpisode = false
                            episodeCount++
                        }

                        else -> throw TrajectoryV1StorageException(
                            TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                        )
                    }
                }
            }
        } catch (failure: TrajectoryV1StorageException) {
            throw failure
        } catch (failure: Exception) {
            throw TrajectoryV1StorageException(
                TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE,
                failure,
            )
        }
        if (insideEpisode) {
            throw TrajectoryV1StorageException(TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE)
        }
        return episodeCount
    }

    private fun requireFrameVersion(frame: JsonObject) {
        require(frame["trajectorySchemaVersion"]?.let { value ->
            value.jsonPrimitive.intOrNull == TRAJECTORY_V1_VERSION
        } == true) {
            throw TrajectoryV1StorageException(TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE)
        }
    }

    private fun stringValue(value: kotlinx.serialization.json.JsonElement): String? {
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.content.takeIf { primitive.isString }
    }

    private fun fail(
        reason: TrajectoryQuarantineReason,
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        state = State.ABORTED
        throw TrajectoryV1StorageException(reason, cause ?: IllegalStateException(message))
    }

    private data class PendingEpisode(
        val episodeOrdinal: Int,
        val lineBytes: ByteArray,
        val semanticEpisodeId: String,
        val collectionJobId: String,
        val trajectoryId: String,
        val decisionCount: Int,
        val closureKind: com.wingedsheep.gym.EpisodeClosureV1.Kind,
        val shardOrdinal: Int,
    ) {
        fun toIndex(): DatasetEpisodeIndexV1 = DatasetEpisodeIndexV1(
            episodeOrdinal = episodeOrdinal,
            semanticEpisodeId = semanticEpisodeId,
            collectionJobId = collectionJobId,
            trajectoryId = trajectoryId,
            shardOrdinal = shardOrdinal,
            decisionCount = decisionCount,
            closureKind = closureKind,
        )
    }

    private data class FileIntegrity(
        val digest: String,
        val byteCount: Long,
        val episodeCount: Long,
    )

    companion object {
        private const val DEFAULT_IO_BUFFER_SIZE = 16 * 1024
    }
}

/** A6 admission plus publisher composition root for future generation/integration harnesses. */
class TrajectoryV1Writer(
    outputDirectory: Path,
    metadata: DatasetMetadataV1,
    atomicMove: (Path, Path) -> Unit = ::moveAtomically,
    quarantine: TrajectoryV1Quarantine? = null,
) : AutoCloseable {
    private val publisher = TrajectoryV1Publisher(
        outputDirectory = outputDirectory,
        metadata = metadata,
        atomicMove = atomicMove,
        quarantine = quarantine,
    )

    fun appendEpisode(
        episodeOrdinal: Int,
        trajectory: TrajectoryV1,
        replayTrajectoryBinding: com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1,
    ): TrajectoryAdmissionResult {
        val admission = TrajectoryV1Admission.admit(
            trajectory = trajectory,
            binding = replayTrajectoryBinding,
            episodeOrdinal = episodeOrdinal,
        )
        return when (admission) {
            is TrajectoryAdmissionResult.Admitted ->
                publisher.appendFinalizedEpisode(episodeOrdinal, admission.episode)

            is TrajectoryAdmissionResult.Quarantined ->
                publisher.recordQuarantined(episodeOrdinal, admission.metadata)
        }
    }

    fun finalizeDataset(): DatasetManifestV1 = publisher.finalizeDataset()

    override fun close() = publisher.close()
}

package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.A3SemanticJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** One manifest-owned shard plus its expected physical episode sequence. */
internal data class ManifestBoundShardV1(
    val metadata: DatasetShardMetadataV1,
    val path: Path,
    val expectedEpisodes: List<DatasetEpisodeIndexV1>,
    val maxShardBytes: Long,
    val maxEpisodesPerShard: Int,
)

/** A bounded per-shard result; A7.3 will deliberately discard each preflight result before the next. */
internal data class ValidatedTrajectoryShardV1(
    val metadata: DatasetShardMetadataV1,
    val episodes: List<ValidatedEpisodeV1>,
)

/** Builds an O(manifest episodes) physical-validation plan without filesystem enumeration. */
internal object TrajectoryV1ShardValidationPlan {
    fun from(dataset: PublishedTrajectoryDatasetManifestV1): List<ManifestBoundShardV1> {
        val maxShardBytes = requireNotNull(dataset.manifest.metadata.maxShardBytes)
        val maxEpisodesPerShard = requireNotNull(dataset.manifest.metadata.maxEpisodesPerShard)
        val hasInterleavedShardMembership = dataset.manifest.episodes
            .zipWithNext()
            .any { (previous, next) -> next.shardOrdinal < previous.shardOrdinal }
        if (hasInterleavedShardMembership) {
            throw TrajectoryV1ReadException(TrajectoryV1ReadFailure.MANIFEST_EPISODE_INDEX_MISMATCH)
        }
        val expectedByShard = dataset.manifest.episodes.groupBy(DatasetEpisodeIndexV1::shardOrdinal)
        check(dataset.manifest.shards.size == dataset.shardPaths.size)
        return dataset.manifest.shards.mapIndexed { index, shard ->
            ManifestBoundShardV1(
                metadata = shard,
                path = dataset.shardPaths[index],
                expectedEpisodes = expectedByShard.getValue(shard.shardOrdinal).toList(),
                maxShardBytes = maxShardBytes,
                maxEpisodesPerShard = maxEpisodesPerShard,
            )
        }
    }
}

/**
 * Validates one manifest-bound physical shard and reconstructs complete A5-valid trajectories.
 * It does not enumerate any directory, validate another shard, or expose a dataset stream.
 */
internal object TrajectoryV1ShardValidator {
    fun validate(shard: ManifestBoundShardV1): ValidatedTrajectoryShardV1 {
        validatePhysicalBytes(shard)
        return validateFrames(shard)
    }

    private fun validatePhysicalBytes(shard: ManifestBoundShardV1) {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        var lastByte = -1
        var hasCarriageReturn = false
        try {
            BufferedInputStream(Files.newInputStream(shard.path)).use { input ->
                val buffer = ByteArray(DEFAULT_IO_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    byteCount += read
                    for (index in 0 until read) {
                        val byte = buffer[index].toInt() and 0xff
                        if (byte == '\r'.code) hasCarriageReturn = true
                        lastByte = byte
                    }
                }
            }
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.SHARD_MISSING)
        }

        if (byteCount > shard.maxShardBytes) fail(TrajectoryV1ReadFailure.SHARD_BOUND_VIOLATION)
        if (byteCount != shard.metadata.byteCount) {
            fail(TrajectoryV1ReadFailure.SHARD_BYTE_COUNT_MISMATCH)
        }
        val actualDigest = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (actualDigest != shard.metadata.contentDigest) {
            fail(TrajectoryV1ReadFailure.SHARD_DIGEST_MISMATCH)
        }
        if (hasCarriageReturn || lastByte != '\n'.code) {
            fail(TrajectoryV1ReadFailure.NONCANONICAL_FRAME)
        }
    }

    private fun validateFrames(shard: ManifestBoundShardV1): ValidatedTrajectoryShardV1 {
        val episodes = mutableListOf<ValidatedEpisodeV1>()
        var active: OpenEpisodeV1? = null
        var expectedEpisodeIndex = 0
        var completedEpisodeCount = 0

        try {
            strictUtf8Reader(shard.path).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) fail(TrajectoryV1ReadFailure.NONCANONICAL_FRAME)
                    when (recordType(line)) {
                        "episode-start" -> {
                            if (active != null) fail(TrajectoryV1ReadFailure.FRAME_ORDER_INVALID)
                            val start = decodeStart(line)
                            if (
                                start.semanticEpisodeId != start.episodeMetadata.semanticEpisodeId ||
                                start.collectionJobId != start.episodeMetadata.collectionJobId
                            ) {
                                fail(TrajectoryV1ReadFailure.EPISODE_IDENTITY_MISMATCH)
                            }
                            active = OpenEpisodeV1(start)
                        }

                        "decision" -> {
                            val open = active ?: fail(TrajectoryV1ReadFailure.FRAME_ORDER_INVALID)
                            val decision = decodeDecision(line)
                            if (
                                decision.semanticEpisodeId != open.start.semanticEpisodeId ||
                                decision.collectionJobId != open.start.collectionJobId
                            ) {
                                fail(TrajectoryV1ReadFailure.EPISODE_IDENTITY_MISMATCH)
                            }
                            open.decisions += decision.decision
                        }

                        "episode-end" -> {
                            val open = active ?: fail(TrajectoryV1ReadFailure.FRAME_ORDER_INVALID)
                            val end = decodeEnd(line)
                            val validated = finalizeEpisode(open, end)
                            completedEpisodeCount++
                            if (completedEpisodeCount > shard.maxEpisodesPerShard) {
                                fail(TrajectoryV1ReadFailure.SHARD_BOUND_VIOLATION)
                            }
                            val expected = shard.expectedEpisodes.getOrNull(expectedEpisodeIndex)
                                ?: fail(TrajectoryV1ReadFailure.MANIFEST_EPISODE_INDEX_MISMATCH)
                            validateManifestBinding(expected, shard.metadata, open.start, validated.trajectory)
                            episodes += validated
                            expectedEpisodeIndex++
                            active = null
                        }

                        else -> fail(TrajectoryV1ReadFailure.UNKNOWN_RECORD_TYPE)
                    }
                }
            }
        } catch (failure: TrajectoryV1ReadException) {
            throw failure
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.NONCANONICAL_FRAME)
        }

        if (active != null) fail(TrajectoryV1ReadFailure.TRUNCATED_EPISODE)
        if (
            completedEpisodeCount != shard.metadata.episodeCount ||
            expectedEpisodeIndex != shard.expectedEpisodes.size
        ) {
            fail(TrajectoryV1ReadFailure.MANIFEST_EPISODE_INDEX_MISMATCH)
        }
        return ValidatedTrajectoryShardV1(shard.metadata, episodes.toList())
    }

    private fun recordType(line: String): String = try {
        val root = A3SemanticJson.strictJson.parseToJsonElement(line).jsonObject
        root["recordType"]?.let(A3SemanticJson::stringOrNull)
            ?: fail(TrajectoryV1ReadFailure.UNKNOWN_RECORD_TYPE)
    } catch (failure: TrajectoryV1ReadException) {
        throw failure
    } catch (_: Exception) {
        fail(TrajectoryV1ReadFailure.NONCANONICAL_FRAME)
    }

    private fun decodeStart(line: String): EpisodeStartFrameV1 = try {
        A3SemanticJson.strictJson.decodeFromString(EpisodeStartFrameV1.serializer(), line)
            .also { frame ->
                validateHeader(
                    expectedRecordType = "episode-start",
                    recordType = frame.recordType,
                    storageSchemaVersion = frame.storageSchemaVersion,
                    storageSchemaIdentity = frame.storageSchemaIdentity,
                    trajectorySchemaVersion = frame.trajectorySchemaVersion,
                )
                requireCanonical(line, TrajectoryV1StorageCodec.encodeFrame(frame))
            }
    } catch (failure: TrajectoryV1ReadException) {
        throw failure
    } catch (_: Exception) {
        fail(TrajectoryV1ReadFailure.NONCANONICAL_FRAME)
    }

    private fun decodeDecision(line: String): DecisionFrameV1 = try {
        A3SemanticJson.strictJson.decodeFromString(DecisionFrameV1.serializer(), line)
            .also { frame ->
                validateHeader(
                    expectedRecordType = "decision",
                    recordType = frame.recordType,
                    storageSchemaVersion = frame.storageSchemaVersion,
                    storageSchemaIdentity = frame.storageSchemaIdentity,
                    trajectorySchemaVersion = frame.trajectorySchemaVersion,
                )
                requireCanonical(line, TrajectoryV1StorageCodec.encodeFrame(frame))
            }
    } catch (failure: TrajectoryV1ReadException) {
        throw failure
    } catch (_: Exception) {
        fail(TrajectoryV1ReadFailure.NONCANONICAL_FRAME)
    }

    private fun decodeEnd(line: String): EpisodeEndFrameV1 = try {
        A3SemanticJson.strictJson.decodeFromString(EpisodeEndFrameV1.serializer(), line)
            .also { frame ->
                validateHeader(
                    expectedRecordType = "episode-end",
                    recordType = frame.recordType,
                    storageSchemaVersion = frame.storageSchemaVersion,
                    storageSchemaIdentity = frame.storageSchemaIdentity,
                    trajectorySchemaVersion = frame.trajectorySchemaVersion,
                )
                requireCanonical(line, TrajectoryV1StorageCodec.encodeFrame(frame))
            }
    } catch (failure: TrajectoryV1ReadException) {
        throw failure
    } catch (_: Exception) {
        fail(TrajectoryV1ReadFailure.NONCANONICAL_FRAME)
    }

    private fun validateHeader(
        expectedRecordType: String,
        recordType: String,
        storageSchemaVersion: Int,
        storageSchemaIdentity: String,
        trajectorySchemaVersion: Int,
    ) {
        if (recordType != expectedRecordType) fail(TrajectoryV1ReadFailure.UNKNOWN_RECORD_TYPE)
        if (
            storageSchemaVersion != TRAJECTORY_V1_STORAGE_SCHEMA_VERSION ||
            storageSchemaIdentity != TRAJECTORY_V1_STORAGE_SCHEMA_IDENTITY
        ) {
            fail(TrajectoryV1ReadFailure.STORAGE_SCHEMA_MISMATCH)
        }
        if (trajectorySchemaVersion != TRAJECTORY_V1_VERSION) {
            fail(TrajectoryV1ReadFailure.TRAJECTORY_SCHEMA_MISMATCH)
        }
    }

    private fun requireCanonical(line: String, canonicalBytes: ByteArray) {
        val physicalBytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        if (!canonicalBytes.contentEquals(physicalBytes)) {
            fail(TrajectoryV1ReadFailure.NONCANONICAL_FRAME)
        }
    }

    private fun finalizeEpisode(
        open: OpenEpisodeV1,
        end: EpisodeEndFrameV1,
    ): ValidatedEpisodeV1 {
        if (
            end.semanticEpisodeId != open.start.semanticEpisodeId ||
            end.collectionJobId != open.start.collectionJobId
        ) {
            fail(TrajectoryV1ReadFailure.EPISODE_IDENTITY_MISMATCH)
        }
        if (end.decisionCount != open.decisions.size) {
            fail(TrajectoryV1ReadFailure.EPISODE_DECISION_COUNT_MISMATCH)
        }
        if (end.closure != open.start.episodeMetadata.closure) {
            fail(TrajectoryV1ReadFailure.EPISODE_CLOSURE_MISMATCH)
        }
        val trajectory = try {
            TrajectoryV1(
                trajectoryId = end.trajectoryId,
                episodeMetadata = open.start.episodeMetadata,
                decisions = open.decisions.toList(),
            )
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.TRAJECTORY_ID_MISMATCH)
        }
        if (trajectory.recomputeTrajectoryId() != end.trajectoryId) {
            fail(TrajectoryV1ReadFailure.TRAJECTORY_ID_MISMATCH)
        }
        val computedContentDigest = try {
            TrajectoryV1StorageCodec.episodeContentDigest(trajectory)
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.A5_CONTRACT_INVALID)
        }
        if (computedContentDigest != end.episodeContentDigest) {
            fail(TrajectoryV1ReadFailure.EPISODE_CONTENT_DIGEST_MISMATCH)
        }
        return when (val validation = TrajectoryV1Validator.validate(trajectory)) {
            is TrajectoryValidationResult.Valid -> validation.episode
            is TrajectoryValidationResult.Rejected,
            is TrajectoryValidationResult.QuarantineEligible,
            -> fail(TrajectoryV1ReadFailure.A5_CONTRACT_INVALID)
        }
    }

    private fun validateManifestBinding(
        expected: DatasetEpisodeIndexV1,
        shard: DatasetShardMetadataV1,
        start: EpisodeStartFrameV1,
        trajectory: TrajectoryV1,
    ) {
        if (
            expected.episodeOrdinal != start.episodeOrdinal ||
            expected.semanticEpisodeId != trajectory.semanticEpisodeId ||
            expected.collectionJobId != trajectory.collectionJobId ||
            expected.trajectoryId != trajectory.trajectoryId ||
            expected.shardOrdinal != shard.shardOrdinal ||
            expected.decisionCount != trajectory.decisions.size ||
            expected.closureKind != trajectory.closure.kind
        ) {
            fail(TrajectoryV1ReadFailure.MANIFEST_EPISODE_INDEX_MISMATCH)
        }
    }

    private fun strictUtf8Reader(path: Path): BufferedReader = BufferedReader(
        InputStreamReader(
            Files.newInputStream(path),
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT),
        ),
    )

    private data class OpenEpisodeV1(
        val start: EpisodeStartFrameV1,
        val decisions: MutableList<DecisionRecordV1> = mutableListOf(),
    )

    private fun fail(failure: TrajectoryV1ReadFailure): Nothing = throw TrajectoryV1ReadException(failure)

    private const val DEFAULT_IO_BUFFER_SIZE: Int = 16 * 1024
}

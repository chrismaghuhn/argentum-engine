package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.A3SemanticJson
import kotlinx.serialization.decodeFromString
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Closed fail-closed vocabulary for the staged A7 dataset reader. */
enum class TrajectoryV1ReadFailure {
    DATASET_NOT_PUBLISHED,
    MANIFEST_MISSING,
    MANIFEST_INVALID,
    MANIFEST_NONCANONICAL,
    DATASET_ID_MISMATCH,
    MANIFEST_CONTENT_DIGEST_MISMATCH,
    SHARD_MISSING,
    SHARD_PATH_INVALID,
    SHARD_BYTE_COUNT_MISMATCH,
    SHARD_DIGEST_MISMATCH,
    SHARD_BOUND_VIOLATION,
    STORAGE_SCHEMA_MISMATCH,
    TRAJECTORY_SCHEMA_MISMATCH,
    UNKNOWN_RECORD_TYPE,
    NONCANONICAL_FRAME,
    FRAME_ORDER_INVALID,
    TRUNCATED_EPISODE,
    EPISODE_IDENTITY_MISMATCH,
    EPISODE_DECISION_COUNT_MISMATCH,
    EPISODE_CONTENT_DIGEST_MISMATCH,
    TRAJECTORY_ID_MISMATCH,
    EPISODE_CLOSURE_MISMATCH,
    MANIFEST_EPISODE_INDEX_MISMATCH,
    DUPLICATE_COLLECTION_JOB,
    A5_CONTRACT_INVALID,
}

/** A bounded diagnostic error: it deliberately carries no raw manifest or trajectory payload. */
class TrajectoryV1ReadException(
    val failure: TrajectoryV1ReadFailure,
) : IllegalStateException("A7 trajectory read failed: ${failure.name}")

/**
 * Immutable output of the A7.1 physical-manifest preflight. It proves the accepted final directory,
 * canonical manifest bytes, accepted manifest semantics, and one safe physical path per manifest
 * shard; it deliberately does not inspect shard contents yet.
 */
internal class PublishedTrajectoryDatasetManifestV1 internal constructor(
    val datasetRoot: Path,
    manifest: DatasetManifestV1,
    shardPaths: List<Path>,
) {
    val manifest: DatasetManifestV1 = manifest.snapshotForReadHandle()
    val shardPaths: List<Path> = shardPaths.toList()
}

internal fun DatasetManifestV1.snapshotForReadHandle(): DatasetManifestV1 = copy(
    metadata = metadata.copy(),
    shards = shards.map(DatasetShardMetadataV1::copy),
    episodes = episodes.map(DatasetEpisodeIndexV1::copy),
    counts = counts.copy(),
)

/**
 * A7.1's physical trust boundary. Membership is taken only from [DatasetManifestV1.shards]; this
 * code never enumerates a directory to discover a shard or a manifest.
 */
internal object TrajectoryV1ManifestPreflight {
    fun open(datasetDirectory: Path): PublishedTrajectoryDatasetManifestV1 {
        val requestedRoot = datasetDirectory.toAbsolutePath().normalize()
        if (
            Files.isSymbolicLink(requestedRoot) ||
            !Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            fail(TrajectoryV1ReadFailure.DATASET_NOT_PUBLISHED)
        }
        val datasetRoot = try {
            requestedRoot.toRealPath()
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.DATASET_NOT_PUBLISHED)
        }
        val manifestPath = datasetRoot.resolve("manifest.json")
        if (
            Files.isSymbolicLink(manifestPath) ||
            !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            fail(TrajectoryV1ReadFailure.MANIFEST_MISSING)
        }

        val actualBytes = try {
            Files.readAllBytes(manifestPath)
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.MANIFEST_INVALID)
        }
        val manifestText = decodeCanonicalUtf8Manifest(actualBytes)
        val manifest = try {
            A3SemanticJson.strictJson.decodeFromString(DatasetManifestV1.serializer(), manifestText)
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.MANIFEST_INVALID)
        }

        try {
            if (manifest.datasetId != manifest.recomputeDatasetId()) {
                fail(TrajectoryV1ReadFailure.DATASET_ID_MISMATCH)
            }
            if (manifest.manifestContentDigest != manifest.recomputeManifestContentDigest()) {
                fail(TrajectoryV1ReadFailure.MANIFEST_CONTENT_DIGEST_MISMATCH)
            }
            TrajectoryV1Manifest.validate(manifest)
        } catch (failure: TrajectoryV1ReadException) {
            throw failure
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.MANIFEST_INVALID)
        }

        val canonicalBytes = try {
            TrajectoryV1Manifest.encode(manifest)
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.MANIFEST_INVALID)
        }
        if (!canonicalBytes.contentEquals(actualBytes)) {
            fail(TrajectoryV1ReadFailure.MANIFEST_NONCANONICAL)
        }
        if (datasetRoot.fileName.toString() != "dataset-${manifest.datasetId}") {
            fail(TrajectoryV1ReadFailure.DATASET_NOT_PUBLISHED)
        }

        val shardPaths = manifest.shards.map { shard ->
            resolveManifestShard(datasetRoot, shard.contentReference)
        }
        return PublishedTrajectoryDatasetManifestV1(datasetRoot, manifest, shardPaths)
    }

    private fun decodeCanonicalUtf8Manifest(bytes: ByteArray): String {
        if (
            bytes.isEmpty() ||
            bytes.last() != '\n'.code.toByte() ||
            bytes.any { it == '\r'.code.toByte() }
        ) {
            fail(TrajectoryV1ReadFailure.MANIFEST_NONCANONICAL)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            fail(TrajectoryV1ReadFailure.MANIFEST_INVALID)
        }
    }

    private fun resolveManifestShard(datasetRoot: Path, contentReference: String): Path {
        val reference = try {
            Path.of(contentReference)
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        }
        if (reference.isAbsolute) fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        val resolved = datasetRoot.resolve(reference).normalize()
        if (!resolved.startsWith(datasetRoot)) fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)

        var componentPath = datasetRoot
        reference.forEach { component ->
            componentPath = componentPath.resolve(component.toString())
            if (Files.isSymbolicLink(componentPath)) {
                fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
            }
        }
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            fail(TrajectoryV1ReadFailure.SHARD_MISSING)
        }
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        }
        return resolved
    }

    private fun fail(failure: TrajectoryV1ReadFailure): Nothing = throw TrajectoryV1ReadException(failure)
}

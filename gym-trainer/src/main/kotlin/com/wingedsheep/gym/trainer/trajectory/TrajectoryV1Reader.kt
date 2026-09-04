package com.wingedsheep.gym.trainer.trajectory

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Opens only a finalized A6 dataset and completes A7.1/A7.2 validation for every manifest shard
 * before a caller can obtain any trajectory. This is storage/content validation only, not a claim
 * that the dataset is accepted for training.
 */
object TrajectoryV1Reader {
    fun openPublishedDataset(datasetDirectory: Path): ValidatedTrajectoryDatasetV1 {
        val preflight = TrajectoryV1ManifestPreflight.open(datasetDirectory)
        val shardPlan = TrajectoryV1ShardValidationPlan.from(preflight)
        val collectionJobs = HashSet<String>()
        shardPlan.forEach { shard ->
            TrajectoryV1ShardValidator.validate(shard).episodes.forEach { episode ->
                if (!collectionJobs.add(episode.trajectory.collectionJobId)) {
                    fail(TrajectoryV1ReadFailure.DUPLICATE_COLLECTION_JOB)
                }
            }
        }
        return ValidatedTrajectoryDatasetV1.fromPreflight(preflight.manifest, shardPlan)
    }

    private fun fail(failure: TrajectoryV1ReadFailure): Nothing = throw TrajectoryV1ReadException(failure)
}

/**
 * A fully preflighted A7 storage/content handle. Each call to [streamEpisodes] revalidates one
 * bounded shard before yielding its trajectories; no dataset-sized trajectory cache or open file
 * handle is retained. A6's immutable-finalized-shard contract is relied on between preflight and
 * streaming, while each stream pass still performs cheap path/size checks and full shard validation.
 */
class ValidatedTrajectoryDatasetV1 private constructor(
    manifest: DatasetManifestV1,
    shards: List<ManifestBoundShardV1>,
) {
    val manifest: DatasetManifestV1 = manifest.snapshotForReadHandle()
    private val shards: List<ManifestBoundShardV1> = shards.map { shard ->
        shard.copy(
            metadata = shard.metadata.copy(),
            expectedEpisodes = shard.expectedEpisodes.map(DatasetEpisodeIndexV1::copy),
        )
    }

    fun streamEpisodes(): Sequence<TrajectoryV1> = sequence {
        shards.forEach { shard ->
            requireStillSafe(shard)
            TrajectoryV1ShardValidator.validate(shard).episodes.forEach { episode ->
                yield(episode.trajectory)
            }
        }
    }

    private fun requireStillSafe(shard: ManifestBoundShardV1) {
        val datasetRoot = shard.datasetRoot
        if (
            Files.isSymbolicLink(datasetRoot) ||
            !Files.isDirectory(datasetRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        }
        val canonicalRoot = try {
            datasetRoot.toRealPath()
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        }
        if (canonicalRoot != datasetRoot) fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        val reference = try {
            Path.of(shard.contentReference)
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        }
        if (reference.isAbsolute) fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        val resolved = datasetRoot.resolve(reference).normalize()
        if (!resolved.startsWith(datasetRoot) || resolved != shard.path) {
            fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        }
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
        if (
            Files.isSymbolicLink(resolved) ||
            !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
        ) {
            fail(TrajectoryV1ReadFailure.SHARD_PATH_INVALID)
        }
        val byteCount = try {
            Files.size(resolved)
        } catch (_: Exception) {
            fail(TrajectoryV1ReadFailure.SHARD_MISSING)
        }
        if (byteCount != shard.metadata.byteCount) {
            fail(TrajectoryV1ReadFailure.SHARD_BYTE_COUNT_MISMATCH)
        }
    }

    private fun fail(failure: TrajectoryV1ReadFailure): Nothing = throw TrajectoryV1ReadException(failure)

    companion object {
        @JvmSynthetic
        internal fun fromPreflight(
            manifest: DatasetManifestV1,
            shards: List<ManifestBoundShardV1>,
        ): ValidatedTrajectoryDatasetV1 = ValidatedTrajectoryDatasetV1(manifest, shards)
    }
}

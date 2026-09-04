package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.A3SemanticJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class TrajectoryV1ReaderTest : FunSpec({

    test("the JVM-visible validated-dataset handle has no forgeable constructor") {
        ValidatedTrajectoryDatasetV1::class.java.isInterface shouldBe false
        ValidatedTrajectoryDatasetV1::class.java.declaredConstructors
            .all { constructor -> Modifier.isPrivate(constructor.modifiers) || constructor.isSynthetic } shouldBe true
    }

    test("same-module arbitrary inputs cannot mint a validated dataset handle") {
        val fixture = publishDataset(validFixture())

        shouldThrow<IllegalArgumentException> {
            ValidatedTrajectoryDatasetV1.fromPreflight(
                manifest = fixture.manifest,
                shards = emptyList(),
                gate = Any(),
            )
        }
    }

    test("openPublishedDataset preflights every shard before returning a handle") {
        val first = validFixture()
        val second = first.withPolicySeed(4259906L)
        val fixture = publishDataset(first, second, maxEpisodesPerShard = 1)
        val corruptPath = fixture.datasetRoot.resolve(fixture.manifest.shards[1].contentReference)
        val corruptBytes = Files.readAllBytes(corruptPath)
        corruptBytes[10] = (corruptBytes[10].toInt() xor 1).toByte()
        Files.write(corruptPath, corruptBytes)

        shouldThrow<TrajectoryV1ReadException> {
            TrajectoryV1Reader.openPublishedDataset(fixture.datasetRoot)
        }.failure shouldBe TrajectoryV1ReadFailure.SHARD_DIGEST_MISMATCH
    }

    test("a validated dataset streams every A6 trajectory in deterministic manifest order") {
        val first = validFixture()
        val second = first.withPolicySeed(4259906L)
        val fixture = publishDataset(first, second, maxEpisodesPerShard = 1)

        val dataset = TrajectoryV1Reader.openPublishedDataset(fixture.datasetRoot)
        val firstRead = dataset.streamEpisodes().toList()
        val secondRead = dataset.streamEpisodes().toList()

        dataset.manifest shouldBe fixture.manifest
        firstRead shouldBe listOf(first.trajectory, second.trajectory)
        secondRead shouldBe firstRead
    }

    test("a valid A6 empty dataset opens and streams no synthetic episode") {
        val output = Files.createTempDirectory("a7-reader-empty-")
        val writer = TrajectoryV1Writer(
            output,
            DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2),
        )
        val manifest = writer.finalizeDataset()
        val dataset = TrajectoryV1Reader.openPublishedDataset(
            output.resolve("dataset-${manifest.datasetId}"),
        )

        dataset.manifest shouldBe manifest
        dataset.streamEpisodes().toList() shouldBe emptyList()
    }

    test("a shard changed after preflight is revalidated before it can yield an episode") {
        val fixture = publishDataset(validFixture())
        val dataset = TrajectoryV1Reader.openPublishedDataset(fixture.datasetRoot)
        val changed = Files.readAllBytes(fixture.singleShardPath)
        changed[10] = (changed[10].toInt() xor 1).toByte()
        Files.write(fixture.singleShardPath, changed)
        var yielded = 0

        shouldThrow<TrajectoryV1ReadException> {
            dataset.streamEpisodes().forEach { yielded++ }
        }.failure shouldBe TrajectoryV1ReadFailure.SHARD_DIGEST_MISMATCH

        yielded shouldBe 0
    }

    test("a symlinked shard ancestor after preflight is rejected before yield when links are supported") {
        val fixture = publishDataset(validFixture())
        val dataset = TrajectoryV1Reader.openPublishedDataset(fixture.datasetRoot)
        val shardsDirectory = fixture.datasetRoot.resolve("shards")
        val externalRoot = Files.createTempDirectory("a7-reader-symlink-")
        val externalShards = externalRoot.resolve("shards")
        Files.move(shardsDirectory, externalShards)
        assumeTrue(runCatching {
            Files.createSymbolicLink(shardsDirectory, externalShards)
        }.isSuccess)
        var yielded = 0

        shouldThrow<TrajectoryV1ReadException> {
            dataset.streamEpisodes().forEach { yielded++ }
        }.failure shouldBe TrajectoryV1ReadFailure.SHARD_PATH_INVALID

        yielded shouldBe 0
    }

    test("early stream consumption leaves no shard file handle open") {
        val first = validFixture()
        val second = first.withPolicySeed(4259906L)
        val fixture = publishDataset(first, second, maxEpisodesPerShard = 1)
        val dataset = TrajectoryV1Reader.openPublishedDataset(fixture.datasetRoot)

        dataset.streamEpisodes().take(1).toList() shouldBe listOf(first.trajectory)
        Files.delete(fixture.datasetRoot.resolve(fixture.manifest.shards.first().contentReference))
    }

    test("dataset-wide duplicate collection jobs fail complete preflight") {
        val first = validFixture()
        val second = first.withClosure(
            EpisodeClosureV1.Interrupted(
                stepCount = 1,
                reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
            ),
        )
        val fixture = forgeDuplicateCollectionJobDataset(first, second)

        shouldThrow<TrajectoryV1ReadException> {
            TrajectoryV1Reader.openPublishedDataset(fixture.datasetRoot)
        }.failure shouldBe TrajectoryV1ReadFailure.DUPLICATE_COLLECTION_JOB
    }
})

private data class ReaderDatasetFixture(
    val datasetRoot: Path,
    val manifest: DatasetManifestV1,
) {
    val singleShardPath: Path
        get() = datasetRoot.resolve(manifest.shards.single().contentReference)
}

private fun publishDataset(
    vararg sources: AdmissionFixture,
    maxEpisodesPerShard: Int = 2,
): ReaderDatasetFixture {
    val output = Files.createTempDirectory("a7-reader-")
    val writer = TrajectoryV1Writer(
        output,
        DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = maxEpisodesPerShard),
    )
    sources.forEachIndexed { ordinal, source ->
        writer.appendEpisode(ordinal, source.trajectory, source.binding)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
    }
    val manifest = writer.finalizeDataset()
    return ReaderDatasetFixture(output.resolve("dataset-${manifest.datasetId}"), manifest)
}

private fun forgeDuplicateCollectionJobDataset(
    first: AdmissionFixture,
    second: AdmissionFixture,
): ReaderDatasetFixture {
    val firstDataset = publishDataset(first)
    val secondDataset = publishDataset(second)
    val firstShard = firstDataset.manifest.shards.single()
    val secondShard = secondDataset.manifest.shards.single()
    val secondBytes = TrajectoryV1StorageCodec.encodeLine(second.trajectory, episodeOrdinal = 1)
    val secondDigest = A3SemanticJson.sha256(secondBytes)
    val secondReference = "shards/shard-${String.format(Locale.ROOT, "%06d", 1)}-" +
        "$secondDigest$TRAJECTORY_V1_SHARD_EXTENSION"
    Files.write(firstDataset.datasetRoot.resolve(secondReference), secondBytes)

    val secondEntry = secondDataset.manifest.episodes.single().copy(
        episodeOrdinal = 1,
        shardOrdinal = 1,
    )
    val entries = listOf(firstDataset.manifest.episodes.single(), secondEntry)
    val proposed = firstDataset.manifest.copy(
        shards = listOf(
            firstShard,
            secondShard.copy(
                shardOrdinal = 1,
                contentReference = secondReference,
                contentDigest = secondDigest,
                byteCount = secondBytes.size.toLong(),
            ),
        ),
        episodes = entries,
        counts = DatasetCountsV1(
            episodeCount = entries.size,
            decisionCount = entries.sumOf(DatasetEpisodeIndexV1::decisionCount),
            gameTerminalCount = entries.count {
                it.closureKind == EpisodeClosureV1.Kind.GAME_TERMINAL
            },
            interruptedCount = entries.count {
                it.closureKind == EpisodeClosureV1.Kind.INTERRUPTED
            },
            failedCount = 0,
        ),
    )
    val manifest = completeManifest(proposed)
    TrajectoryV1Manifest.validate(manifest)
    Files.write(firstDataset.datasetRoot.resolve("manifest.json"), TrajectoryV1Manifest.encode(manifest))
    val publishedRoot = firstDataset.datasetRoot.parent.resolve("dataset-${manifest.datasetId}")
    Files.move(firstDataset.datasetRoot, publishedRoot)
    return ReaderDatasetFixture(publishedRoot, manifest)
}

private fun completeManifest(proposed: DatasetManifestV1): DatasetManifestV1 {
    val unidentified = proposed.copy(
        datasetId = "0".repeat(64),
        manifestContentDigest = "0".repeat(64),
    )
    val withDatasetId = unidentified.copy(datasetId = unidentified.recomputeDatasetId())
    return withDatasetId.copy(manifestContentDigest = withDatasetId.recomputeManifestContentDigest())
}

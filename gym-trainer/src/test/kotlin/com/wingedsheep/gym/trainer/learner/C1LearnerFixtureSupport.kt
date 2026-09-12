package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.EpisodeInterruptionReason
import com.wingedsheep.gym.trainer.trajectory.AdmissionFixture
import com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gym.trainer.trajectory.QuarantineMetadataV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryAdmissionResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryQuarantineReason
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Admission
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Publisher
import com.wingedsheep.gym.trainer.trajectory.validFixture
import com.wingedsheep.gym.trainer.trajectory.withPolicySeed
import java.nio.file.Files
import java.nio.file.Path

internal data class PublishedC1Fixture(
    val root: Path,
    val source: AdmissionFixture,
    val manifest: DatasetManifestV1,
)

internal fun publishC1Fixture(
    parent: Path,
    source: AdmissionFixture = validFixture(),
): PublishedC1Fixture = publishC1Fixtures(parent, listOf(source))

internal fun publishC1Fixtures(
    parent: Path,
    sources: List<AdmissionFixture>,
): PublishedC1Fixture {
    require(sources.isNotEmpty()) { "At least one fixture is required" }
    val publisherRoot = parent.resolve("a7-source")
    Files.createDirectories(publisherRoot)
    val manifest = TrajectoryV1Publisher(
        outputDirectory = publisherRoot,
        metadata = DatasetMetadataV1(
            maxShardBytes = 1_000_000L,
            maxEpisodesPerShard = 1,
        ),
    ).use { publisher ->
        sources.forEachIndexed { episodeOrdinal, source ->
            val admitted = when (
                val result = TrajectoryV1Admission.admit(
                    source.trajectory,
                    source.binding,
                    episodeOrdinal = episodeOrdinal,
                )
            ) {
                is TrajectoryAdmissionResult.Admitted -> result.episode
                is TrajectoryAdmissionResult.Quarantined -> error(
                    "Fixture unexpectedly quarantined: ${result.metadata.reason}",
                )
            }
            publisher.appendFinalizedEpisode(admitted)
        }
        publisher.finalizeDataset()
    }
    return PublishedC1Fixture(
        root = publisherRoot.resolve("dataset-${manifest.datasetId}"),
        source = sources.first(),
        manifest = manifest,
    )
}

internal fun publishC1FixtureWithQuarantine(
    parent: Path,
    source: AdmissionFixture = validFixture(),
): PublishedC1Fixture {
    val publisherRoot = parent.resolve("a7-source")
    Files.createDirectories(publisherRoot)
    val admitted = when (
        val result = TrajectoryV1Admission.admit(source.trajectory, source.binding, 0)
    ) {
        is TrajectoryAdmissionResult.Admitted -> result.episode
        is TrajectoryAdmissionResult.Quarantined -> error("Fixture unexpectedly quarantined")
    }
    val manifest = TrajectoryV1Publisher(
        outputDirectory = publisherRoot,
        metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 1),
    ).use { publisher ->
        publisher.appendFinalizedEpisode(admitted)
        publisher.recordQuarantined(
            episodeOrdinal = 1,
            metadata = QuarantineMetadataV1.from(
                trajectory = source.withPolicySeed(987654L).trajectory,
                reason = TrajectoryQuarantineReason.FAILED_EPISODE,
                episodeOrdinal = 1,
            ),
        )
        publisher.finalizeDataset()
    }
    return PublishedC1Fixture(
        root = publisherRoot.resolve("dataset-${manifest.datasetId}"),
        source = source,
        manifest = manifest,
    )
}

internal fun AdmissionFixture.zeroDecisionEpisode(): AdmissionFixture {
    val closure = EpisodeClosureV1.Interrupted(
        stepCount = 0,
        reason = EpisodeInterruptionReason.HORIZON_REACHED,
    )
    val metadataBase = trajectory.episodeMetadata.copy(
        closure = closure,
        compactReplayLink = trajectory.compactReplayLink.copy(
            replayActionCount = 0,
            replayActionEndExclusive = 0,
        ),
    )
    val metadata = metadataBase.copy(collectionJobId = metadataBase.recomputeCollectionJobId())
    val trajectoryBase = trajectory.copy(
        episodeMetadata = metadata,
        decisions = emptyList(),
        trajectoryId = "f".repeat(64),
    )
    val zeroTrajectory = trajectoryBase.copy(trajectoryId = trajectoryBase.recomputeTrajectoryId())
    val verification = binding.verificationBinding.verification.copy(
        replayActionCount = 0,
        verifiedActionCount = 0,
        frames = listOf(binding.verificationBinding.verification.frames.first().copy(replayActionIndex = 0)),
        closure = closure,
    )
    return copy(
        trajectory = zeroTrajectory,
        binding = binding.copy(
            verificationBinding = binding.verificationBinding.copy(verification = verification),
            chosenInputBinding = binding.chosenInputBinding.copy(
                replayActionCount = 0,
                chosenInputs = emptyList(),
            ),
        ),
    )
}

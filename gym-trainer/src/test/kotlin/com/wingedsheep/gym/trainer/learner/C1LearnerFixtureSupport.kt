package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.trainer.trajectory.AdmissionFixture
import com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryAdmissionResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Admission
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Publisher
import com.wingedsheep.gym.trainer.trajectory.validFixture
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
): PublishedC1Fixture {
    val publisherRoot = parent.resolve("a7-source")
    Files.createDirectories(publisherRoot)
    val admitted = when (
        val result = TrajectoryV1Admission.admit(
            source.trajectory,
            source.binding,
            episodeOrdinal = 0,
        )
    ) {
        is TrajectoryAdmissionResult.Admitted -> result.episode
        is TrajectoryAdmissionResult.Quarantined -> error(
            "Fixture unexpectedly quarantined: ${result.metadata.reason}",
        )
    }
    val manifest = TrajectoryV1Publisher(
        outputDirectory = publisherRoot,
        metadata = DatasetMetadataV1(
            maxShardBytes = 1_000_000L,
            maxEpisodesPerShard = 1,
        ),
    ).use { publisher ->
        publisher.appendFinalizedEpisode(admitted)
        publisher.finalizeDataset()
    }
    return PublishedC1Fixture(
        root = publisherRoot.resolve("dataset-${manifest.datasetId}"),
        source = source,
        manifest = manifest,
    )
}

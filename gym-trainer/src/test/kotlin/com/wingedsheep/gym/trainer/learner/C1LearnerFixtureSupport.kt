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
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayInputV1
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayPrefixAccumulatorV1
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

internal fun AdmissionFixture.twoDecisionEpisode(): AdmissionFixture {
    val first = trajectory.decisions.single()
    val closure = when (val original = trajectory.closure) {
        is EpisodeClosureV1.GameTerminal -> original.copy(stepCount = 2)
        is EpisodeClosureV1.Interrupted -> original.copy(stepCount = 2)
        is EpisodeClosureV1.Failed -> error("Cannot extend a failed fixture")
    }
    val prefix = SemanticReplayPrefixAccumulatorV1()
    val firstIdentity = prefix.semanticDecisionIdentity(
        semanticEpisodeId = trajectory.semanticEpisodeId,
        replayActionIndex = 0,
        observation = first.observationBefore,
        domain = first.completeLegalDomain,
        perspectivePlayerId = first.perspectivePlayerId.value,
        decisionKind = first.decisionKind,
    )
    val firstInput = first.chosenSemanticAction?.let(SemanticReplayInputV1::action)
        ?: first.chosenSemanticResponse?.let(SemanticReplayInputV1::response)
        ?: error("Fixture has no chosen semantic input")
    prefix.append(firstInput)
    val secondIdentity = prefix.semanticDecisionIdentity(
        semanticEpisodeId = trajectory.semanticEpisodeId,
        replayActionIndex = 1,
        observation = first.observationBefore,
        domain = first.completeLegalDomain,
        perspectivePlayerId = first.perspectivePlayerId.value,
        decisionKind = first.decisionKind,
    )
    val second = first.copy(
        decisionIndex = 1,
        replayActionIndex = 1,
        replayFrameIndex = 1,
        semanticDecisionId = secondIdentity.semanticDecisionId(),
    )
    val firstRecord = first.copy(
        decisionIndex = 0,
        replayActionIndex = 0,
        replayFrameIndex = 0,
        semanticDecisionId = firstIdentity.semanticDecisionId(),
    )
    val metadata = trajectory.episodeMetadata.copy(
        closure = closure,
        compactReplayLink = trajectory.compactReplayLink.copy(
            replayActionCount = 2,
            replayActionEndExclusive = 2,
        ),
    )
    val trajectoryBase = trajectory.copy(
        trajectoryId = "f".repeat(64),
        episodeMetadata = metadata,
        decisions = listOf(firstRecord, second),
    )
    val twoDecisionTrajectory = trajectoryBase.copy(
        trajectoryId = trajectoryBase.recomputeTrajectoryId(),
    )
    val verificationBase = binding.verificationBinding.verification
    val initialFrame = verificationBase.frames.first()
    val verification = verificationBase.copy(
        replayActionCount = 2,
        verifiedActionCount = 2,
        frames = listOf(
            initialFrame.copy(replayActionIndex = 0),
            initialFrame.copy(replayActionIndex = 1),
            initialFrame.copy(replayActionIndex = 2),
        ),
        closure = closure,
    )
    val chosen = binding.chosenInputBinding.chosenInputs.single()
    return copy(
        trajectory = twoDecisionTrajectory,
        binding = binding.copy(
            verificationBinding = binding.verificationBinding.copy(verification = verification),
            chosenInputBinding = binding.chosenInputBinding.copy(
                replayActionCount = 2,
                chosenInputs = listOf(
                    chosen.copy(replayActionIndex = 0),
                    chosen.copy(replayActionIndex = 1),
                ),
            ),
        ),
    )
}

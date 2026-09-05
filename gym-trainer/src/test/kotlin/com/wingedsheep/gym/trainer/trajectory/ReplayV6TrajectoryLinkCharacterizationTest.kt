package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.ReplayVerificationBindingV1
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Characterizes the v6 replay to Trajectory V1 linkage seam.
 *
 * This desired-state test is expected to be RED against the characterization commit. The current
 * v5-only link contract rejects before the complete v6 evidence can reach A5/A6 admission.
 */
class ReplayV6TrajectoryLinkCharacterizationTest : FunSpec({

    test("v6 replay evidence is admitted through a v6 trajectory link") {
        val fixture = validFixture()
        val v6Identity = fixture.binding.verificationBinding.replayContentIdentity.copy(
            replayVersion = COMPACT_REPLAY_V6_VERSION,
        )
        val v6Verification = fixture.binding.verificationBinding.verification.copy(
            replayVersion = COMPACT_REPLAY_V6_VERSION,
        )
        val v6Binding = ReplayTrajectoryBindingV1(
            verificationBinding = ReplayVerificationBindingV1(
                replayContentIdentity = v6Identity,
                verification = v6Verification,
            ),
            chosenInputBinding = fixture.binding.chosenInputBinding.copy(
                replayContentIdentity = v6Identity,
            ),
        )

        v6Binding.verificationBinding.replayContentIdentity.replayVersion shouldBe
            COMPACT_REPLAY_V6_VERSION
        v6Binding.verificationBinding.verification.replayVersion shouldBe COMPACT_REPLAY_V6_VERSION
        v6Binding.verificationBinding.verification.completeRangeVerified shouldBe true

        val v6Link = CompactReplayLinkV1(
            replayVersion = COMPACT_REPLAY_V6_VERSION,
            replaySchemaIdentity = COMPACT_REPLAY_V6_SCHEMA_IDENTITY,
            replayContentIdentity = v6Identity.value,
            replayActionCount = fixture.trajectory.decisions.size,
        )
        v6Link.replayVersion shouldBe COMPACT_REPLAY_V6_VERSION
        v6Link.replaySchemaIdentity shouldBe COMPACT_REPLAY_V6_SCHEMA_IDENTITY

        val v6Environment = fixture.trajectory.episodeMetadata.environmentIdentity.copy(
            replaySchemaIdentity = COMPACT_REPLAY_V6_SCHEMA_IDENTITY,
        )
        val metadataBase = fixture.trajectory.episodeMetadata.copy(
            environmentIdentity = v6Environment,
            compactReplayLink = v6Link,
        )
        val metadata = metadataBase.copy(
            semanticEpisodeId = metadataBase.recomputeSemanticEpisodeId(),
        ).let { withSemanticId ->
            withSemanticId.copy(collectionJobId = withSemanticId.recomputeCollectionJobId())
        }
        val originalRecord = fixture.trajectory.decisions.single()
        val identity = SemanticReplayPrefixAccumulatorV1().semanticDecisionIdentity(
            semanticEpisodeId = metadata.semanticEpisodeId,
            replayActionIndex = originalRecord.replayActionIndex,
            observation = originalRecord.observationBefore,
            domain = originalRecord.completeLegalDomain,
            perspectivePlayerId = originalRecord.perspectivePlayerId.value,
            decisionKind = originalRecord.decisionKind,
        )
        val v6TrajectoryBase = fixture.trajectory.copy(
            trajectoryId = "f".repeat(64),
            episodeMetadata = metadata,
            decisions = listOf(
                originalRecord.copy(semanticDecisionId = identity.semanticDecisionId()),
            ),
        )
        val v6Trajectory = v6TrajectoryBase.copy(
            trajectoryId = v6TrajectoryBase.recomputeTrajectoryId(),
        )

        val admitted = TrajectoryV1Admission.admit(
            trajectory = v6Trajectory,
            binding = v6Binding,
            episodeOrdinal = 0,
        ).shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()

        admitted.episode.replayContentIdentity shouldBe v6Identity.value
    }
})

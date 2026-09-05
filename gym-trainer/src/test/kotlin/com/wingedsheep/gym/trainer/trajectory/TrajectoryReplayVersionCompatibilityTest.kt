package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.ReplayVerificationBindingV1
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

class TrajectoryReplayVersionCompatibilityTest : FunSpec({

    test("only explicit v5 and v6 replay schema pairs are accepted") {
        val contentIdentity = "a".repeat(64)

        listOf(
            Triple(COMPACT_REPLAY_V5_VERSION, COMPACT_REPLAY_V5_SCHEMA_IDENTITY, true),
            Triple(COMPACT_REPLAY_V6_VERSION, COMPACT_REPLAY_V6_SCHEMA_IDENTITY, true),
            Triple(COMPACT_REPLAY_V5_VERSION, COMPACT_REPLAY_V6_SCHEMA_IDENTITY, false),
            Triple(COMPACT_REPLAY_V6_VERSION, COMPACT_REPLAY_V5_SCHEMA_IDENTITY, false),
            Triple(4, COMPACT_REPLAY_V5_SCHEMA_IDENTITY, false),
            Triple(7, COMPACT_REPLAY_V6_SCHEMA_IDENTITY, false),
            Triple(7, "argentum-compact-replay@v7", false),
            Triple(COMPACT_REPLAY_V6_VERSION, "argentum-compact-replay@v7", false),
        ).forEach { (replayVersion, replaySchemaIdentity, accepted) ->
            val attempt = runCatching {
                CompactReplayLinkV1(
                    replayVersion = replayVersion,
                    replaySchemaIdentity = replaySchemaIdentity,
                    replayContentIdentity = contentIdentity,
                    replayActionCount = 0,
                )
            }
            if (accepted) {
                val link = attempt.getOrThrow()
                link.replayVersion shouldBe replayVersion
                link.replaySchemaIdentity shouldBe replaySchemaIdentity
            } else {
                attempt.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
            }
        }

        SUPPORTED_TRAJECTORY_REPLAY_VERSIONS shouldBe
            setOf(COMPACT_REPLAY_V5_VERSION, COMPACT_REPLAY_V6_VERSION)
    }

    test("new replay link and environment defaults are the current v6 pair") {
        val link = CompactReplayLinkV1(
            replayContentIdentity = "b".repeat(64),
            replayActionCount = 0,
        )
        val environment = defaultEnvironmentIdentity()

        link.replayVersion shouldBe CURRENT_TRAJECTORY_REPLAY_VERSION
        link.replayVersion shouldBe COMPACT_REPLAY_V6_VERSION
        link.replaySchemaIdentity shouldBe COMPACT_REPLAY_V6_SCHEMA_IDENTITY
        environment.replaySchemaIdentity shouldBe COMPACT_REPLAY_V6_SCHEMA_IDENTITY
    }

    test("environment and replay link schema identities must agree at A5") {
        val v5WithV6Environment = withEnvironmentSchema(
            fixture = versionedFixture(COMPACT_REPLAY_V5_VERSION, COMPACT_REPLAY_V5_SCHEMA_IDENTITY),
            schemaIdentity = COMPACT_REPLAY_V6_SCHEMA_IDENTITY,
        )
        val v6WithV5Environment = withEnvironmentSchema(
            fixture = versionedFixture(COMPACT_REPLAY_V6_VERSION, COMPACT_REPLAY_V6_SCHEMA_IDENTITY),
            schemaIdentity = COMPACT_REPLAY_V5_SCHEMA_IDENTITY,
        )

        listOf(v5WithV6Environment, v6WithV5Environment).forEach { fixture ->
            val result = TrajectoryV1Validator.validate(fixture.trajectory)
            result.shouldBeInstanceOf<TrajectoryValidationResult.Rejected>().reason shouldBe
                TrajectoryValidationReason.REPLAY_LINK_INVALID
        }
    }

    test("A6 keeps strict version equality for both supported replay pairs") {
        val v5 = versionedFixture(COMPACT_REPLAY_V5_VERSION, COMPACT_REPLAY_V5_SCHEMA_IDENTITY)
        val v6 = versionedFixture(COMPACT_REPLAY_V6_VERSION, COMPACT_REPLAY_V6_SCHEMA_IDENTITY)

        TrajectoryV1Admission.admit(v5.trajectory, v6.binding, 0)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Quarantined>()
            .metadata.reason shouldBe TrajectoryQuarantineReason.REPLAY_VERSION_MISMATCH
        TrajectoryV1Admission.admit(v6.trajectory, v5.binding, 0)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Quarantined>()
            .metadata.reason shouldBe TrajectoryQuarantineReason.REPLAY_VERSION_MISMATCH
    }

    test("v5 and v6 trajectories pass A5, A6, writer publication, and A7 reader") {
        val v5 = versionedFixture(COMPACT_REPLAY_V5_VERSION, COMPACT_REPLAY_V5_SCHEMA_IDENTITY)
        val v6 = versionedFixture(COMPACT_REPLAY_V6_VERSION, COMPACT_REPLAY_V6_SCHEMA_IDENTITY)

        listOf(v5, v6).forEach { fixture ->
            TrajectoryV1Validator.validate(fixture.trajectory)
                .shouldBeInstanceOf<TrajectoryValidationResult.Valid>()

            TrajectoryV1Admission.admit(fixture.trajectory, fixture.binding, 0)
                .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()

            val read = publishAndRead(fixture)
            read shouldBe fixture.trajectory
            read.compactReplayLink.replayVersion shouldBe
                fixture.trajectory.compactReplayLink.replayVersion
            read.episodeMetadata.environmentIdentity.replaySchemaIdentity shouldBe
                fixture.trajectory.episodeMetadata.environmentIdentity.replaySchemaIdentity
        }
    }

    test("v5 and v6 storage JSON round-trips preserve their explicit replay pairs") {
        listOf(
            versionedFixture(COMPACT_REPLAY_V5_VERSION, COMPACT_REPLAY_V5_SCHEMA_IDENTITY),
            versionedFixture(COMPACT_REPLAY_V6_VERSION, COMPACT_REPLAY_V6_SCHEMA_IDENTITY),
        ).forEach { fixture ->
            val decoded = TrajectoryV1Json.decode(TrajectoryV1Json.encode(fixture.trajectory))

            decoded shouldBe fixture.trajectory
            decoded.compactReplayLink.replayVersion shouldBe fixture.trajectory.compactReplayLink.replayVersion
            decoded.compactReplayLink.replaySchemaIdentity shouldBe
                fixture.trajectory.compactReplayLink.replaySchemaIdentity
        }
    }

    test("v5 and v6 environment and semantic episode identities remain distinct") {
        val v5 = versionedFixture(COMPACT_REPLAY_V5_VERSION, COMPACT_REPLAY_V5_SCHEMA_IDENTITY)
        val v6 = versionedFixture(COMPACT_REPLAY_V6_VERSION, COMPACT_REPLAY_V6_SCHEMA_IDENTITY)

        v5.trajectory.episodeMetadata.environmentIdentity.identityDigest() shouldNotBe
            v6.trajectory.episodeMetadata.environmentIdentity.identityDigest()
        v5.trajectory.semanticEpisodeId shouldNotBe v6.trajectory.semanticEpisodeId
    }
})

private fun defaultEnvironmentIdentity(): EnvironmentIdentityV1 = EnvironmentIdentityV1(
    engineCommit = "d".repeat(40),
    cardDefinitionIdentity = "trajectory-version-test-cards",
    akiriDeckIdentity = "trajectory-version-test-akiri",
    chevillDeckIdentity = "trajectory-version-test-chevill",
    format = "COMMANDER",
    attackMode = "MULTIPLE",
    startingHandSize = 7,
    skipMulligans = true,
    useHandSmoother = false,
    roster = listOf(
        RosterSeatV1(
            seatIndex = 0,
            playerId = EntityId("e0"),
            role = "AKIRI",
            deckIdentity = "trajectory-version-test-akiri",
        ),
        RosterSeatV1(
            seatIndex = 1,
            playerId = EntityId("e1"),
            role = "CHEVILL",
            deckIdentity = "trajectory-version-test-chevill",
        ),
    ),
    startingPlayer = EntityId("e0"),
    actualEngineSeed = 1L,
)

private fun versionedFixture(
    replayVersion: Int,
    replaySchemaIdentity: String,
): AdmissionFixture {
    val source = validFixture()
    val replayIdentity = source.binding.verificationBinding.replayContentIdentity.copy(
        replayVersion = replayVersion,
    )
    val verification = source.binding.verificationBinding.verification.copy(
        replayVersion = replayVersion,
    )
    val link = CompactReplayLinkV1(
        replayVersion = replayVersion,
        replaySchemaIdentity = replaySchemaIdentity,
        replayContentIdentity = replayIdentity.value,
        replayActionCount = source.trajectory.decisions.size,
    )
    val metadataBase = source.trajectory.episodeMetadata.copy(
        environmentIdentity = source.trajectory.episodeMetadata.environmentIdentity.copy(
            replaySchemaIdentity = replaySchemaIdentity,
        ),
        compactReplayLink = link,
    )
    val metadata = metadataBase.copy(
        semanticEpisodeId = metadataBase.recomputeSemanticEpisodeId(),
    ).let { withSemanticId ->
        withSemanticId.copy(collectionJobId = withSemanticId.recomputeCollectionJobId())
    }
    val originalRecord = source.trajectory.decisions.single()
    val identity = SemanticReplayPrefixAccumulatorV1().semanticDecisionIdentity(
        semanticEpisodeId = metadata.semanticEpisodeId,
        replayActionIndex = originalRecord.replayActionIndex,
        observation = originalRecord.observationBefore,
        domain = originalRecord.completeLegalDomain,
        perspectivePlayerId = originalRecord.perspectivePlayerId.value,
        decisionKind = originalRecord.decisionKind,
    )
    val trajectoryBase = source.trajectory.copy(
        trajectoryId = "f".repeat(64),
        episodeMetadata = metadata,
        decisions = listOf(originalRecord.copy(semanticDecisionId = identity.semanticDecisionId())),
    )
    val trajectory = trajectoryBase.copy(trajectoryId = trajectoryBase.recomputeTrajectoryId())
    val binding = ReplayTrajectoryBindingV1(
        verificationBinding = ReplayVerificationBindingV1(
            replayContentIdentity = replayIdentity,
            verification = verification,
        ),
        chosenInputBinding = source.binding.chosenInputBinding.copy(
            replayContentIdentity = replayIdentity,
        ),
    )
    return AdmissionFixture(trajectory = trajectory, binding = binding)
}

private fun withEnvironmentSchema(
    fixture: AdmissionFixture,
    schemaIdentity: String,
): AdmissionFixture {
    val metadataBase = fixture.trajectory.episodeMetadata.copy(
        environmentIdentity = fixture.trajectory.episodeMetadata.environmentIdentity.copy(
            replaySchemaIdentity = schemaIdentity,
        ),
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
    val trajectoryBase = fixture.trajectory.copy(
        trajectoryId = "f".repeat(64),
        episodeMetadata = metadata,
        decisions = listOf(originalRecord.copy(semanticDecisionId = identity.semanticDecisionId())),
    )
    return fixture.copy(
        trajectory = trajectoryBase.copy(trajectoryId = trajectoryBase.recomputeTrajectoryId()),
    )
}

private fun publishAndRead(fixture: AdmissionFixture): TrajectoryV1 {
    val output = Files.createTempDirectory("trajectory-replay-version-")
    TrajectoryV1Writer(
        outputDirectory = output,
        metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 1),
    ).use { writer ->
        writer.appendEpisode(0, fixture.trajectory, fixture.binding)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
        val manifest = writer.finalizeDataset()
        val dataset = TrajectoryV1Reader.openPublishedDataset(
            output.resolve("dataset-${manifest.datasetId}"),
        )
        return dataset.streamEpisodes().single()
    }
}

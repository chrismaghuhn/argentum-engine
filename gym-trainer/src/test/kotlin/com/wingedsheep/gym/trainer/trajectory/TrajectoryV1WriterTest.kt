package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameEndReason
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.ChosenSemanticResponseV1
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ReplayChosenInputBindingV1
import com.wingedsheep.gym.contract.ReplayChosenInputV1
import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.ReplayVerificationBindingV1
import com.wingedsheep.gym.contract.VerifiedReplayFrame
import com.wingedsheep.gym.contract.VerifiedReplayVerification
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

class TrajectoryV1WriterTest : FunSpec({

    test("matching exact replay evidence is admitted") {
        val (trajectory, binding) = validFixture()

        val result = TrajectoryV1Admission.admit(trajectory, binding, episodeOrdinal = 0)

        result.shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
    }

    test("an A5-invalid trajectory is quarantined before replay evidence is trusted") {
        val fixture = validFixture()

        val result = TrajectoryV1Admission.admit(
            fixture.trajectory.copy(trajectoryId = "0".repeat(64)),
            fixture.binding,
            episodeOrdinal = 3,
        )

        result.quarantineReason() shouldBe TrajectoryQuarantineReason.A5_CONTRACT_INVALID
    }

    test("replay content identity must match the trajectory link") {
        val fixture = validFixture()
        val changedIdentity = fixture.binding.verificationBinding.replayContentIdentity.copy(
            value = "d".repeat(64),
        )
        val changedBinding = fixture.withBindingIdentity(changedIdentity)

        val result = TrajectoryV1Admission.admit(fixture.trajectory, changedBinding, episodeOrdinal = 0)

        result.quarantineReason() shouldBe TrajectoryQuarantineReason.REPLAY_CONTENT_IDENTITY_MISMATCH
    }

    test("replay version mismatch fails closed") {
        val fixture = validFixture()
        val changedIdentity = fixture.binding.verificationBinding.replayContentIdentity.copy(replayVersion = 4)
        val changedVerification = fixture.binding.verificationBinding.verification.copy(replayVersion = 4)
        val changedBinding = fixture.withBindingIdentity(
            changedIdentity,
            changedVerification,
        )

        val result = TrajectoryV1Admission.admit(fixture.trajectory, changedBinding, episodeOrdinal = 0)

        result.quarantineReason() shouldBe TrajectoryQuarantineReason.REPLAY_VERSION_MISMATCH
    }

    test("non-exact replay evidence is never admitted") {
        val fixture = validFixture()
        listOf(ReplayFidelity.UNVERIFIED, ReplayFidelity.DIVERGED).forEach { fidelity ->
            val nonExact = fixture.binding.verificationBinding.verification.copy(
                verifiedActionCount = 0,
                fidelity = fidelity,
                frames = emptyList(),
                initialCheckpointVerified = false,
                intermediateCheckpointsVerified = false,
                tailCheckpointVerified = false,
                closure = null,
                failureAtReplayActionIndex = if (fidelity == ReplayFidelity.DIVERGED) 0 else null,
                failureReason = if (fidelity == ReplayFidelity.DIVERGED) "private detail" else null,
            )
            val changedBinding = fixture.withVerification(nonExact).binding

            TrajectoryV1Admission.admit(fixture.trajectory, changedBinding, episodeOrdinal = 0)
                .quarantineReason() shouldBe TrajectoryQuarantineReason.REPLAY_NOT_EXACT
        }
    }

    test("replay closure must equal the trajectory closure") {
        val fixture = validFixture()
        val changedVerification = fixture.binding.verificationBinding.verification.copy(
            closure = EpisodeClosureV1.Interrupted(
                stepCount = 1,
                reason = com.wingedsheep.gym.EpisodeInterruptionReason.CALLER_CANCELLED,
            ),
        )
        val changedBinding = fixture.withVerification(changedVerification).binding

        val result = TrajectoryV1Admission.admit(fixture.trajectory, changedBinding, episodeOrdinal = 0)

        result.quarantineReason() shouldBe TrajectoryQuarantineReason.REPLAY_CLOSURE_MISMATCH
    }

    test("every replay and trajectory action count must agree") {
        val fixture = validFixture()
        val verification = fixture.binding.verificationBinding.verification
        val extraInput = fixture.binding.chosenInputBinding.chosenInputs.single()
            .copy(replayActionIndex = 1)
        val changedBinding = ReplayTrajectoryBindingV1(
            verificationBinding = ReplayVerificationBindingV1(
                replayContentIdentity = fixture.binding.verificationBinding.replayContentIdentity,
                verification = verification.copy(
                    replayActionCount = 2,
                    verifiedActionCount = 2,
                    frames = listOf(
                        verification.frames[0],
                        verification.frames[1],
                        verification.frames[1].copy(replayActionIndex = 2),
                    ),
                ),
            ),
            chosenInputBinding = fixture.binding.chosenInputBinding.copy(
                replayActionCount = 2,
                chosenInputs = listOf(
                    fixture.binding.chosenInputBinding.chosenInputs.single(),
                    extraInput,
                ),
            ),
        )

        TrajectoryV1Admission.admit(fixture.trajectory, changedBinding, 0)
            .quarantineReason() shouldBe TrajectoryQuarantineReason.REPLAY_ACTION_COUNT_MISMATCH
    }

    test("perspective, observation, and chosen input mismatches fail closed") {
        val fixture = validFixture()
        val original = fixture.binding
        val perspectiveChanged = original.chosenInputBinding.chosenInputs.map {
            it.copy(perspectivePlayerId = EntityId("e1"))
        }
        val perspectiveBinding = original.copy(
            chosenInputBinding = original.chosenInputBinding.copy(chosenInputs = perspectiveChanged),
        )
        TrajectoryV1Admission.admit(fixture.trajectory, perspectiveBinding, 0)
            .quarantineReason() shouldBe TrajectoryQuarantineReason.PERSPECTIVE_MISMATCH

        val changedObservation = original.verificationBinding.verification.frames[0].observation.copy(
            turnNumber = original.verificationBinding.verification.frames[0].observation.turnNumber + 1,
        )
        val observationBinding = fixture.withVerification(
            original.verificationBinding.verification.copy(
                frames = listOf(
                    original.verificationBinding.verification.frames[0].copy(observation = changedObservation),
                    original.verificationBinding.verification.frames[1],
                ),
            ),
        )
        TrajectoryV1Admission.admit(fixture.trajectory, observationBinding.binding, 0)
            .quarantineReason() shouldBe TrajectoryQuarantineReason.OBSERVATION_MISMATCH

    }

    test("one admitted episode publishes one immutable digest-addressed shard") {
        val fixture = validFixture()
        val output = Files.createTempDirectory("a6-writer-test-")
        val metadata = DatasetMetadataV1(
            maxShardBytes = 1_000_000L,
            maxEpisodesPerShard = 2,
        )

        val writer = TrajectoryV1Writer(output, metadata)
        writer.appendEpisode(0, fixture.trajectory, fixture.binding)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
        val manifest = writer.finalizeDataset()

        manifest.counts.episodeCount shouldBe 1
        manifest.counts.decisionCount shouldBe 1
        manifest.shards.size shouldBe 1
        val datasetRoot = output.resolve("dataset-${manifest.datasetId}")
        Files.exists(datasetRoot.resolve("manifest.json")) shouldBe true
        val shardPath = datasetRoot.resolve(manifest.shards.single().contentReference)
        val shardBytes = Files.readAllBytes(shardPath)
        shardBytes.last() shouldBe '\n'.code.toByte()
        shardBytes.contains('\r'.code.toByte()) shouldBe false
        val shardText = shardBytes.toString(Charsets.UTF_8)
        listOf("actionId", "decisionId", "nonce", "workerId", "pid", "wallTime", "GameState")
            .forEach { field -> shardText shouldNotContain "\"$field\"" }
        manifest.shards.single().contentDigest shouldBe A3SemanticJson.sha256(shardBytes)
        manifest.shards.single().byteCount shouldBe shardBytes.size.toLong()
        manifest.datasetId shouldBe manifest.recomputeDatasetId()
        manifest.manifestContentDigest shouldBe manifest.recomputeManifestContentDigest()
    }

    test("the complete replay frame domain is compared, not just its digest") {
        val fixture = validFixture()
        val originalFrame = fixture.binding.verificationBinding.verification.frames[0]
        val originalCandidate = originalFrame.domain.candidates.single()
        val changedCandidate = buildJsonObject {
            originalCandidate.forEach { (key, value) ->
                put(key, if (key == "kind") JsonPrimitive("different-kind") else value)
            }
        }
        val changedDomain = originalFrame.domain.copy(candidates = listOf(changedCandidate))
        val changedFrame = originalFrame.copy(
            domain = changedDomain,
            candidateDomainDigest = CandidateDomainDigestV1.from(changedDomain),
        )
        val changedBinding = fixture.withVerification(
            fixture.binding.verificationBinding.verification.copy(
                frames = listOf(
                    changedFrame,
                    fixture.binding.verificationBinding.verification.frames[1],
                ),
            ),
        ).binding

        TrajectoryV1Admission.admit(fixture.trajectory, changedBinding, 0)
            .quarantineReason() shouldBe TrajectoryQuarantineReason.LEGAL_DOMAIN_MISMATCH
    }

    test("the actual chosen response or action must match the trajectory record exactly") {
        val fixture = validFixture()
        val original = fixture.binding
        val originalCandidate = fixture.trajectory.decisions.single().completeLegalDomain.candidates.single()
        val responseCandidates = listOf(true, false).map { choice ->
            buildJsonObject {
                originalCandidate.forEach { (key, value) ->
                    when (key) {
                        "actionSemantics" -> put("actionSemantics", buildJsonObject {
                            put("type", "YesNoResponse")
                            put("choice", choice)
                        })

                        "isDecisionOption" -> put(key, true)
                        else -> put(key, value)
                    }
                }
            }
        }
        val responseDomain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            decisionKind = PendingDecisionKind.YES_NO,
            shape = com.wingedsheep.gym.contract.DecisionShape(),
            candidates = responseCandidates,
        )
        val response = ChosenSemanticResponseV1.from(
            responseDomain,
            buildJsonObject {
                put("type", "YesNoResponse")
                put("choice", false)
            },
        )
        val chosenBinding = original.copy(
            chosenInputBinding = original.chosenInputBinding.copy(
                chosenInputs = listOf(
                    original.chosenInputBinding.chosenInputs.single().copy(
                        chosenSemanticAction = null,
                        chosenSemanticResponse = response,
                    ),
                ),
            ),
        )

        TrajectoryV1Admission.admit(fixture.trajectory, chosenBinding, 0)
            .quarantineReason() shouldBe TrajectoryQuarantineReason.CHOSEN_INPUT_MISMATCH
    }

    test("trusted publication requires finite positive shard bounds") {
        val output = Files.createTempDirectory("a6-bounds-test-")

        shouldThrow<IllegalArgumentException> {
            TrajectoryV1Writer(
                output,
                DatasetMetadataV1(maxShardBytes = null, maxEpisodesPerShard = 1),
            )
        }
        shouldThrow<IllegalArgumentException> {
            TrajectoryV1Writer(
                output,
                DatasetMetadataV1(maxShardBytes = 1, maxEpisodesPerShard = null),
            )
        }
    }

    test("episode-count and byte bounds rotate deterministically without oversized shards") {
        val first = validFixture()
        val second = first.withPolicySeed(4259906L)
        val episodeLineSize = TrajectoryV1Admission.admit(first.trajectory, first.binding, 0)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
            .episode.storageLineBytes().size

        val byCountOutput = Files.createTempDirectory("a6-count-rollover-")
        val byCount = TrajectoryV1Writer(
            byCountOutput,
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 1),
        )
        byCount.appendEpisode(0, first.trajectory, first.binding)
        byCount.appendEpisode(1, second.trajectory, second.binding)
        val countManifest = byCount.finalizeDataset()
        countManifest.shards.size shouldBe 2
        countManifest.episodes.map { it.episodeOrdinal } shouldBe listOf(0, 1)

        val byBytesOutput = Files.createTempDirectory("a6-byte-rollover-")
        val byBytes = TrajectoryV1Writer(
            byBytesOutput,
            DatasetMetadataV1(maxShardBytes = episodeLineSize.toLong(), maxEpisodesPerShard = 10),
        )
        byBytes.appendEpisode(0, first.trajectory, first.binding)
        byBytes.appendEpisode(1, second.trajectory, second.binding)
        val byteManifest = byBytes.finalizeDataset()
        byteManifest.shards.size shouldBe 2
        byteManifest.shards.all { it.byteCount <= episodeLineSize } shouldBe true

        val oversizedOutput = Files.createTempDirectory("a6-oversized-")
        val oversized = TrajectoryV1Writer(
            oversizedOutput,
            DatasetMetadataV1(maxShardBytes = (episodeLineSize - 1).toLong(), maxEpisodesPerShard = 1),
        )
        oversized.appendEpisode(0, first.trajectory, first.binding)
            .quarantineReason() shouldBe TrajectoryQuarantineReason.EPISODE_TOO_LARGE
        val oversizedManifest = oversized.finalizeDataset()
        oversizedManifest.shards shouldBe emptyList()
        oversizedManifest.counts.episodeCount shouldBe 0
        Files.list(oversizedOutput.resolve("quarantine")).use { it.count() shouldBe 1L }
    }

    test("quarantined failed episodes never enter trusted membership or leak payloads") {
        val fixture = validFixture().withClosure(
            EpisodeClosureV1.Failed(
                stepCount = 1,
                reason = com.wingedsheep.gym.EpisodeFailureReason.ENGINE_EXCEPTION,
            ),
        )
        val output = Files.createTempDirectory("a6-quarantine-test-")
        val writer = TrajectoryV1Writer(
            output,
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 2),
        )

        writer.appendEpisode(0, fixture.trajectory, fixture.binding)
            .quarantineReason() shouldBe TrajectoryQuarantineReason.FAILED_EPISODE
        val manifest = writer.finalizeDataset()

        manifest.episodes shouldBe emptyList()
        manifest.counts.episodeCount shouldBe 0
        Files.list(output.resolve("quarantine")).use { paths ->
            val quarantinePath = paths.findFirst().orElseThrow()
            val raw = Files.readString(quarantinePath)
            raw shouldNotContain "observationBefore"
            raw shouldNotContain "completeLegalDomain"
            raw shouldNotContain "chosenSemanticAction"
            raw shouldNotContain "GameState"
            raw shouldNotContain "exception.toString"
            A3SemanticJson.strictJson.decodeFromString(
                QuarantineMetadataV1.serializer(),
                raw,
            ).reason shouldBe TrajectoryQuarantineReason.FAILED_EPISODE
        }
    }

    test("an interrupted exact replay remains trusted without fabricated terminal facts") {
        val fixture = validFixture().withClosure(
            EpisodeClosureV1.Interrupted(
                stepCount = 1,
                reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
            ),
        )
        val writer = TrajectoryV1Writer(
            Files.createTempDirectory("a6-interrupted-test-"),
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 2),
        )

        writer.appendEpisode(0, fixture.trajectory, fixture.binding)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
        val manifest = writer.finalizeDataset()

        manifest.counts.interruptedCount shouldBe 1
        manifest.counts.gameTerminalCount shouldBe 0
        manifest.episodes.single().closureKind shouldBe EpisodeClosureV1.Kind.INTERRUPTED
    }

    test("canonical storage bytes ignore object insertion order but retain the manifest order") {
        val fixture = validFixture()
        val record = fixture.trajectory.decisions.single()
        val reorderedCandidate = reorderObject(record.completeLegalDomain.candidates.single())
        val reorderedDomain = record.completeLegalDomain.copy(candidates = listOf(reorderedCandidate))
        val reorderedRecord = record.copy(
            completeLegalDomain = reorderedDomain,
            candidateDomainDigest = CandidateDomainDigestV1.from(reorderedDomain),
            chosenSemanticAction = ChosenSemanticActionV1.from(reorderedDomain, reorderedCandidate),
        )
        val trajectoryBase = fixture.trajectory.copy(
            trajectoryId = "f".repeat(64),
            decisions = listOf(reorderedRecord),
        )
        val reorderedTrajectory = trajectoryBase.copy(
            trajectoryId = trajectoryBase.recomputeTrajectoryId(),
        )

        val first = TrajectoryV1Admission.admit(fixture.trajectory, fixture.binding, 0)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
        val second = TrajectoryV1Admission.admit(reorderedTrajectory, fixture.binding, 0)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()

        first.episode.storageLineBytes().toList() shouldBe second.episode.storageLineBytes().toList()
    }

    test("writer owns order and finalization state, never the filesystem enumeration") {
        val fixture = validFixture()
        val output = Files.createTempDirectory("a6-state-test-")
        val writer = TrajectoryV1Writer(
            output,
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 2),
        )
        shouldThrow<TrajectoryV1StorageException> {
            writer.appendEpisode(1, fixture.trajectory, fixture.binding)
        }.reason shouldBe TrajectoryQuarantineReason.EPISODE_ORDER_MISMATCH

        val deterministicOutput = Files.createTempDirectory("a6-enumeration-test-")
        Files.writeString(deterministicOutput.resolve("unrelated.txt"), "not a shard")
        val firstWriter = TrajectoryV1Writer(
            deterministicOutput,
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 2),
        )
        firstWriter.appendEpisode(0, fixture.trajectory, fixture.binding)
        Files.exists(deterministicOutput.resolve("manifest.json")) shouldBe false
        val firstManifest = firstWriter.finalizeDataset()
        shouldThrow<TrajectoryV1StorageException> { firstWriter.finalizeDataset() }
        shouldThrow<TrajectoryV1StorageException> {
            firstWriter.appendEpisode(1, fixture.trajectory, fixture.binding)
        }

        val secondOutput = Files.createTempDirectory("a6-determinism-test-")
        val secondWriter = TrajectoryV1Writer(
            secondOutput,
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 2),
        )
        secondWriter.appendEpisode(0, fixture.trajectory, fixture.binding)
        val secondManifest = secondWriter.finalizeDataset()
        firstManifest shouldBe secondManifest
    }

    test("the same collection job cannot replace an already accepted trajectory") {
        val first = validFixture()
        val conflicting = first.withClosure(
            EpisodeClosureV1.GameTerminal(
                stepCount = 1,
                winnerId = EntityId("e0"),
                reason = null,
            ),
        )
        conflicting.trajectory.collectionJobId shouldBe first.trajectory.collectionJobId
        conflicting.trajectory.trajectoryId shouldNotBe first.trajectory.trajectoryId

        val writer = TrajectoryV1Writer(
            Files.createTempDirectory("a6-duplicate-test-"),
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 2),
        )
        writer.appendEpisode(0, first.trajectory, first.binding)
        shouldThrow<TrajectoryV1StorageException> {
            writer.appendEpisode(1, conflicting.trajectory, conflicting.binding)
        }.reason shouldBe TrajectoryQuarantineReason.DUPLICATE_JOB_OR_EPISODE_CONFLICT

        val exactDuplicateWriter = TrajectoryV1Writer(
            Files.createTempDirectory("a6-exact-duplicate-test-"),
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 2),
        )
        exactDuplicateWriter.appendEpisode(0, first.trajectory, first.binding)
        shouldThrow<TrajectoryV1StorageException> {
            exactDuplicateWriter.appendEpisode(1, first.trajectory, first.binding)
        }.reason shouldBe TrajectoryQuarantineReason.DUPLICATE_JOB_OR_EPISODE_CONFLICT
    }

    test("corruption of a finalized staging shard blocks manifest publication") {
        val first = validFixture()
        val second = first.withPolicySeed(4259906L)
        val output = Files.createTempDirectory("a6-corruption-test-")
        val writer = TrajectoryV1Writer(
            output,
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 1),
        )
        writer.appendEpisode(0, first.trajectory, first.binding)
        writer.appendEpisode(1, second.trajectory, second.binding)

        val shardPath = Files.walk(output.resolve(".staging")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(TRAJECTORY_V1_SHARD_EXTENSION) }
                .findFirst()
                .orElseThrow()
        }
        val corrupted = Files.readAllBytes(shardPath)
        corrupted[0] = (corrupted[0].toInt() xor 1).toByte()
        Files.write(shardPath, corrupted)

        shouldThrow<TrajectoryV1StorageException> { writer.finalizeDataset() }
            .reason shouldBe TrajectoryQuarantineReason.SHARD_INTEGRITY_FAILURE
        Files.list(output).use { paths ->
            paths.anyMatch { path -> path.fileName.toString().startsWith("dataset-") } shouldBe false
        }
    }

    test("atomic publication failure leaves no final dataset and never overwrites an existing one") {
        val fixture = validFixture()
        val metadata = DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 2)
        val output = Files.createTempDirectory("a6-atomic-test-")
        val failingMove: (java.nio.file.Path, java.nio.file.Path) -> Unit = { _, _ ->
            throw UnsupportedOperationException("atomic move unavailable")
        }
        val failingWriter = TrajectoryV1Writer(output, metadata, atomicMove = failingMove)
        failingWriter.appendEpisode(0, fixture.trajectory, fixture.binding)
        shouldThrow<TrajectoryV1StorageException> { failingWriter.finalizeDataset() }
            .reason shouldBe TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE
        Files.list(output).use { paths ->
            paths.anyMatch { path -> path.fileName.toString().startsWith("dataset-") } shouldBe false
        }

        val publishedWriter = TrajectoryV1Writer(output, metadata)
        publishedWriter.appendEpisode(0, fixture.trajectory, fixture.binding)
        val manifest = publishedWriter.finalizeDataset()
        val manifestPath = output.resolve("dataset-${manifest.datasetId}").resolve("manifest.json")
        val originalManifestBytes = Files.readAllBytes(manifestPath)

        val overwriteWriter = TrajectoryV1Writer(output, metadata)
        overwriteWriter.appendEpisode(0, fixture.trajectory, fixture.binding)
        shouldThrow<TrajectoryV1StorageException> { overwriteWriter.finalizeDataset() }
            .reason shouldBe TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE
        Files.readAllBytes(manifestPath).toList() shouldBe originalManifestBytes.toList()
    }

    test("failure while publishing the validated staging directory leaves no final dataset") {
        val fixture = validFixture()
        val output = Files.createTempDirectory("a6-directory-publication-test-")
        var moveCount = 0
        val failDirectoryMove: (java.nio.file.Path, java.nio.file.Path) -> Unit = { source, target ->
            moveCount++
            if (moveCount == 3) {
                throw UnsupportedOperationException("atomic directory move unavailable")
            }
            moveAtomically(source, target)
        }
        val writer = TrajectoryV1Writer(
            output,
            DatasetMetadataV1(maxShardBytes = 1_000_000, maxEpisodesPerShard = 2),
            atomicMove = failDirectoryMove,
        )
        writer.appendEpisode(0, fixture.trajectory, fixture.binding)

        shouldThrow<TrajectoryV1StorageException> { writer.finalizeDataset() }
            .reason shouldBe TrajectoryQuarantineReason.STORAGE_PUBLICATION_FAILURE
        moveCount shouldBe 3
        Files.list(output).use { paths ->
            paths.anyMatch { path -> path.fileName.toString().startsWith("dataset-") } shouldBe false
        }
        Files.walk(output.resolve(".staging")).use { paths ->
            paths.anyMatch { path -> path.fileName.toString() == "manifest.json" } shouldBe true
        }
    }
})

private fun TrajectoryAdmissionResult.quarantineReason(): TrajectoryQuarantineReason =
    shouldBeInstanceOf<TrajectoryAdmissionResult.Quarantined>().metadata.reason

private fun AdmissionFixture.withVerification(
    verification: VerifiedReplayVerification,
): AdmissionFixture = copy(
    binding = binding.copy(
        verificationBinding = binding.verificationBinding.copy(verification = verification),
    ),
)

private fun AdmissionFixture.withBindingIdentity(
    identity: ReplayContentIdentityV1,
    verification: VerifiedReplayVerification = binding.verificationBinding.verification,
): ReplayTrajectoryBindingV1 = ReplayTrajectoryBindingV1(
    verificationBinding = ReplayVerificationBindingV1(
        replayContentIdentity = identity,
        verification = verification,
    ),
    chosenInputBinding = binding.chosenInputBinding.copy(
        replayContentIdentity = identity,
    ),
)

private fun AdmissionFixture.withPolicySeed(policySeed: Long): AdmissionFixture {
    val metadataBase = trajectory.episodeMetadata.copy(
        policyProvenance = trajectory.episodeMetadata.policyProvenance.copy(policySeed = policySeed),
    )
    val metadata = metadataBase.copy(collectionJobId = metadataBase.recomputeCollectionJobId())
    val trajectoryBase = trajectory.copy(
        trajectoryId = "f".repeat(64),
        episodeMetadata = metadata,
    )
    return copy(trajectory = trajectoryBase.copy(trajectoryId = trajectoryBase.recomputeTrajectoryId()))
}

private fun AdmissionFixture.withClosure(closure: EpisodeClosureV1): AdmissionFixture {
    val metadata = trajectory.episodeMetadata.copy(closure = closure)
    val trajectoryBase = trajectory.copy(
        trajectoryId = "f".repeat(64),
        episodeMetadata = metadata,
    )
    val changedTrajectory = trajectoryBase.copy(trajectoryId = trajectoryBase.recomputeTrajectoryId())
    val changedVerification = binding.verificationBinding.verification.copy(closure = closure)
    return AdmissionFixture(
        trajectory = changedTrajectory,
        binding = withVerification(changedVerification).binding,
    )
}

private fun reorderObject(value: JsonObject): JsonObject = JsonObject(
    value.entries.sortedByDescending { it.key }.associate { (key, child) -> key to child },
)

private data class AdmissionFixture(
    val trajectory: TrajectoryV1,
    val binding: ReplayTrajectoryBindingV1,
)

private fun validFixture(): AdmissionFixture {
    val registry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }
    val environment = GameEnvironment.create(registry)
    environment.reset(
        GameConfig(
            players = listOf(
                PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                PlayerConfig("Bob", Deck.of("Mountain" to 20)),
            ),
            startingHandSize = 2,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 70L,
        ),
    )
    val source = ObservationBuilder(cardRegistry = registry).build(
        state = environment.state,
        perspectivePlayerId = environment.playerIds.first(),
        legalActions = environment.legalActions(),
    ).observation as TrainingObservation
    val observation = PlayerObservationV1.from(source)
    val domain = CompleteLegalDomainV1.from(source)
    val candidate = domain.candidates.first { it["affordable"] == JsonPrimitive(true) }
    val chosen = ChosenSemanticActionV1.from(domain, candidate)
    val closure = EpisodeClosureV1.GameTerminal(
        stepCount = 1,
        winnerId = EntityId("e0"),
        reason = GameEndReason.LIFE_ZERO,
    )
    val replayIdentity = ReplayContentIdentityV1(
        replayVersion = 5,
        value = "c".repeat(64),
    )
    val metadataBase = EpisodeMetadataV1(
        semanticEpisodeId = "a".repeat(64),
        collectionJobId = "b".repeat(64),
        environmentIdentity = EnvironmentIdentityV1(
            engineCommit = "d".repeat(40),
            cardDefinitionIdentity = "portal-card-definitions-v1",
            akiriDeckIdentity = "akiri-deck-test",
            chevillDeckIdentity = "chevill-deck-test",
            format = "COMMANDER",
            attackMode = "MULTIPLE",
            startingHandSize = 2,
            skipMulligans = true,
            useHandSmoother = false,
            roster = listOf(
                RosterSeatV1(
                    seatIndex = 0,
                    playerId = EntityId("e0"),
                    role = "AKIRI",
                    deckIdentity = "akiri-deck-test",
                    commanderDefinitionIdentity = "Akiri",
                ),
                RosterSeatV1(
                    seatIndex = 1,
                    playerId = EntityId("e1"),
                    role = "CHEVILL",
                    deckIdentity = "chevill-deck-test",
                    commanderDefinitionIdentity = "Chevill",
                ),
            ),
            startingPlayer = EntityId("e0"),
            actualEngineSeed = 70L,
        ),
        policyProvenance = PolicyProvenanceV1(
            behaviorPolicyIdentity = "behavior@v1",
            opponentPolicyIdentity = "opponent@v1",
            behaviorPolicyRole = "EXTERNAL_CONTROLLER",
            opponentPolicyRole = "EXTERNAL_CONTROLLER",
            policyRngIdentity = "seeded-policy-v1",
            policySeed = 4259905L,
            policySourceIdentity = "e".repeat(64),
        ),
        compactReplayLink = CompactReplayLinkV1(
            replayContentIdentity = replayIdentity.value,
            replayActionCount = 1,
        ),
        closure = closure,
    )
    val metadata = metadataBase.copy(
        semanticEpisodeId = metadataBase.recomputeSemanticEpisodeId(),
    ).let { withSemanticId ->
        withSemanticId.copy(collectionJobId = withSemanticId.recomputeCollectionJobId())
    }
    val prefix = SemanticReplayPrefixAccumulatorV1()
    val identity = prefix.semanticDecisionIdentity(
        semanticEpisodeId = metadata.semanticEpisodeId,
        replayActionIndex = 0,
        observation = observation,
        domain = domain,
    )
    val record = DecisionRecordV1(
        decisionIndex = 0,
        replayActionIndex = 0,
        perspectivePlayerId = observation.perspectivePlayerId,
        decisionKind = identity.decisionKind,
        semanticDecisionId = identity.semanticDecisionId(),
        observationBefore = observation,
        completeLegalDomain = domain,
        candidateDomainDigest = CandidateDomainDigestV1.from(domain),
        chosenSemanticAction = chosen,
    )
    val trajectoryBase = TrajectoryV1(
        trajectoryId = "f".repeat(64),
        episodeMetadata = metadata,
        decisions = listOf(record),
    )
    val trajectory = trajectoryBase.copy(trajectoryId = trajectoryBase.recomputeTrajectoryId())
    val frame = VerifiedReplayFrame(
        replayActionIndex = 0,
        perspectivePlayerId = observation.perspectivePlayerId,
        observation = observation,
        domain = domain,
        candidateDomainDigest = CandidateDomainDigestV1.from(domain),
    )
    val tailFrame = frame.copy(replayActionIndex = 1)
    val verification = VerifiedReplayVerification(
        replayVersion = 5,
        replayActionCount = 1,
        verifiedActionCount = 1,
        fidelity = ReplayFidelity.EXACT,
        frames = listOf(frame, tailFrame),
        initialCheckpointVerified = true,
        intermediateCheckpointsVerified = true,
        tailCheckpointVerified = true,
        closure = closure,
    )
    val chosenBinding = ReplayChosenInputBindingV1(
        replayContentIdentity = replayIdentity,
        replayActionCount = 1,
        chosenInputs = listOf(
            ReplayChosenInputV1(
                replayActionIndex = 0,
                perspectivePlayerId = observation.perspectivePlayerId,
                chosenSemanticAction = chosen,
            ),
        ),
    )
    return AdmissionFixture(
        trajectory = trajectory,
        binding = ReplayTrajectoryBindingV1(
            verificationBinding = ReplayVerificationBindingV1(
                replayContentIdentity = replayIdentity,
                verification = verification,
            ),
            chosenInputBinding = chosenBinding,
        ),
    )
}

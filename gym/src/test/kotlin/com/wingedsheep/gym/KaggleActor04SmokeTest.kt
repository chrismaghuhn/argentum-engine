package com.wingedsheep.gym

import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.ResolvedAction
import com.wingedsheep.gym.contract.SchemaHash
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gameserver.curriculum.CurriculumAiTournamentPreset
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceLoader
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.GymReplayFrameSource
import com.wingedsheep.gameserver.replay.ReplayCardPin
import com.wingedsheep.gameserver.replay.ReplayCheckpoint
import com.wingedsheep.gameserver.replay.ReplayCodec
import com.wingedsheep.gameserver.replay.ReplayContentCanonicalizerV1
import com.wingedsheep.gameserver.replay.ReplayFingerprint
import com.wingedsheep.gameserver.replay.ReplayPlayerInfo
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplayRecordingPolicy
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.trainer.actor.ActorDiagnosticV1
import com.wingedsheep.gym.trainer.actor.ActorDiagnosticCodeV1
import com.wingedsheep.gym.trainer.actor.ActorDiagnosticSeverityV1
import com.wingedsheep.gym.trainer.actor.ActorEpisodeExecutor
import com.wingedsheep.gym.trainer.actor.ActorEpisodeOutcome
import com.wingedsheep.gym.trainer.actor.ActorRuntimeProbeV1
import com.wingedsheep.gym.trainer.actor.ActorRuntimeSnapshotV1
import com.wingedsheep.gym.trainer.actor.ActorStateV1
import com.wingedsheep.gym.trainer.actor.AcceptedSemanticJobRegistry
import com.wingedsheep.gym.trainer.actor.ExecutionAttemptIdentityV1
import com.wingedsheep.gym.trainer.actor.FileStoreStorageCapacityProbeV1
import com.wingedsheep.gym.trainer.actor.LocalActorExecutionRequestV1
import com.wingedsheep.gym.trainer.actor.LocalActorExecutionV1
import com.wingedsheep.gym.trainer.actor.LocalPublicationEnvelopeV1
import com.wingedsheep.gym.trainer.actor.GitSourceBootstrapProbeV1
import com.wingedsheep.gym.trainer.actor.LocalSourceBootstrapV1
import com.wingedsheep.gym.trainer.actor.MeasuredStoragePreflightV1
import com.wingedsheep.gym.trainer.actor.OfflineAdmissionRequestV1
import com.wingedsheep.gym.trainer.actor.OfflineAdmissionV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayVerificationResultV1
import com.wingedsheep.gym.trainer.actor.StoragePreflightConfigurationV1
import com.wingedsheep.gym.trainer.actor.SourceBootstrapFailureCodeV1
import com.wingedsheep.gym.trainer.actor.SourceBootstrapGitCommandV1
import com.wingedsheep.gym.trainer.actor.SourceBootstrapStatusV1
import com.wingedsheep.gym.trainer.actor.TrajectoryV1B2Sink
import com.wingedsheep.gym.trainer.actor.WorkAssignmentV1
import com.wingedsheep.gym.trainer.actor.WorkItemV1
import com.wingedsheep.gym.trainer.actor.WorkloadJobV1
import com.wingedsheep.gym.trainer.actor.WorkloadPlanV1
import com.wingedsheep.gym.trainer.trajectory.CompactReplayLinkV1
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1
import com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1
import com.wingedsheep.gym.trainer.trajectory.EpisodeMetadataV1
import com.wingedsheep.gym.trainer.trajectory.PolicyProvenanceV1
import com.wingedsheep.gym.trainer.trajectory.RosterSeatV1
import com.wingedsheep.gym.trainer.trajectory.SemanticDecisionIdentityV1
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayInputV1
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayPrefixV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1ReadException
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val KA04_CARD_DEFINITION_IDENTITY =
    "3C3C2DF4993D875D1239F49D4D3DACF059D8842BC2A6E0D03DDF31CDB7901E23"
private const val KA04_MAX_STEPS = 1
private const val KA04_JOB_COUNT = 4
private const val KA04_POLICY_IDENTITY = "ka04-deterministic-reference-policy@v1"
private const val KA04_POLICY_RNG_IDENTITY = "explicit-seed/kotlin-policy-state-v1"

/** The provider round-trip harness is opt-in through the dedicated Gradle test task. */
class KaggleActor04SmokeTest : FunSpec({
    test("locked curriculum crosses the trusted actor and local publication envelope") {
        val run = KaggleActor04SmokeHarness.run(
            profiles = listOf(1),
            outputRoot = Files.createTempDirectory("ka04-local-smoke-").resolve("scratch"),
            publicationRoot = Files.createTempDirectory("ka04-local-publication-"),
        )

        run.plan.jobs.size shouldBe KA04_JOB_COUNT
        run.plan.jobs.map { it.environmentIdentity.engineCommit }.distinct() shouldContainExactly
            listOf(run.sourceCommit)
        run.plan.jobs.forEach { job ->
            job.environmentIdentity.akiriDeckIdentity shouldBe
                "E774200BF9444DBF420B27573C63BAC4659F59568BBB53340D3A0FD7BDBE5E04"
            job.environmentIdentity.chevillDeckIdentity shouldBe
                "0257823208E24D8EAC90773081B98ECF875FB77639BAFD820BC24CA41FC06474"
        }
        run.profiles.single().completeEpisodes shouldBe KA04_JOB_COUNT
        run.profiles.single().failedEpisodes shouldBe 0
        run.profiles.single().partialLostEpisodes shouldBe 0
        run.profiles.single().reimported.size shouldBe 1
        run.profiles.single().reimported.single().streamEpisodes().count() shouldBe KA04_JOB_COUNT
        run.noProofAdmission.offlineReplayReverification.name shouldBe "NO_INDEPENDENT_PROOF"
        run.noProofAdmission.datasetEligible.shouldBeFalse()
        run.noProofAdmission.acceptedSources shouldBe emptyList()
        run.noProofAdmission.ledger.entries.map { it.membershipState.name }
            .distinct() shouldContainExactly listOf("MISSING")
    }

    test("exact retry is an identical duplicate and a changed result is a conflict") {
        val run = KaggleActor04SmokeHarness.run(
            profiles = listOf(1),
            outputRoot = Files.createTempDirectory("ka04-duplicate-smoke-").resolve("scratch"),
            publicationRoot = Files.createTempDirectory("ka04-duplicate-publication-"),
        )
        val capture = run.profiles.single().generated.first()
        val item = run.plan.resolve(capture.item.jobOrdinal)
        val registry = AcceptedSemanticJobRegistry()

        registry.accept(item, capture.trajectory)
            .shouldBeInstanceOf<com.wingedsheep.gym.trainer.actor.SemanticJobClaimResult.Accepted>()
        registry.accept(item, capture.trajectory)
            .shouldBeInstanceOf<com.wingedsheep.gym.trainer.actor.SemanticJobClaimResult.IdenticalDuplicate>()

        val changedMetadata = capture.trajectory.episodeMetadata.copy(
            closure = EpisodeClosureV1.Interrupted(
                stepCount = capture.trajectory.closure.stepCount,
                reason = EpisodeInterruptionReason.CALLER_CANCELLED,
            ),
        )
        val conflicting = changedMetadata.let { metadata ->
            val provisional = capture.trajectory.copy(
                episodeMetadata = metadata,
                trajectoryId = "0".repeat(64),
            )
            provisional.copy(trajectoryId = provisional.recomputeTrajectoryId())
        }
        registry.accept(item, conflicting)
            .shouldBeInstanceOf<com.wingedsheep.gym.trainer.actor.SemanticJobClaimResult.Conflict>()
    }

    test("partial provider loss never creates a canonical dataset") {
        val root = Files.createTempDirectory("ka04-partial-loss-")
        val run = KaggleActor04SmokeHarness.planOnly()
        val assignment = WorkAssignmentV1.from(run.plan, listOf(0))
        val result = LocalActorExecutionV1.run(
            LocalActorExecutionRequestV1(
                assignment = assignment,
                executionAttemptIdentity = ExecutionAttemptIdentityV1("ka04-partial-loss"),
                sourceRepositoryRoot = run.repositoryRoot,
                preflight = {
                    KaggleActor04SmokeHarness.preflight(
                        root.resolve("working"),
                        root.resolve("scratch"),
                    )
                },
                episodeExecutor = ActorEpisodeExecutor {
                    ActorEpisodeOutcome.PartialLost(
                        diagnostics = listOf(
                            ActorDiagnosticV1(
                                code = ActorDiagnosticCodeV1.PROVIDER_PROCESS_LOST,
                                severity = ActorDiagnosticSeverityV1.ERROR,
                            ),
                        ),
                    )
                },
                sinkFactory = {
                    TrajectoryV1B2Sink(
                        com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Writer(
                            root.resolve("sink"),
                            run.metadata,
                        ),
                    )
                },
            ),
        )

        result.status.state shouldBe ActorStateV1.PARTIAL_LOST
        result.report.partialLostJobOrdinals shouldBe listOf(0)
        result.manifest shouldBe null
    }

    test("a wrong source revision fails before the actor executor starts") {
        val root = Files.createTempDirectory("ka04-source-mismatch-")
        val context = KaggleActor04SmokeHarness.planOnly()
        val wrongPlan = context.plan.copy(
            jobs = context.plan.jobs.map { job ->
                job.copy(environmentIdentity = job.environmentIdentity.copy(
                    engineCommit = "f".repeat(40),
                ))
            },
        )
        val assignment = WorkAssignmentV1.from(wrongPlan, listOf(0))
        val executions = AtomicInteger(0)
        val result = LocalActorExecutionV1.run(
            LocalActorExecutionRequestV1(
                assignment = assignment,
                executionAttemptIdentity = ExecutionAttemptIdentityV1("ka04-source-mismatch"),
                sourceRepositoryRoot = context.repositoryRoot,
                preflight = {
                    KaggleActor04SmokeHarness.preflight(
                        root.resolve("working"),
                        root.resolve("scratch"),
                    )
                },
                episodeExecutor = ActorEpisodeExecutor {
                    executions.incrementAndGet()
                    error("source verification must stop execution")
                },
                sinkFactory = { error("source verification must stop sink creation") },
            ),
        )

        result.status.state shouldBe ActorStateV1.FAILED
        result.report.sourceRevisionVerified.shouldBeFalse()
        result.report.actualRuntimeSourceCommit shouldBe null
        executions.get() shouldBe 0
        result.manifest shouldBe null
    }

    test("dirty tracked source is rejected by the source bootstrap probe") {
        val root = Files.createTempDirectory("ka04-dirty-source-")
        listOf(
            "gradlew",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradle/libs.versions.toml",
        ).forEach { relative ->
            val path = root.resolve(relative)
            Files.createDirectories(path.parent)
            Files.writeString(path, "pinned")
        }
        val expected = "a".repeat(40)
        val fakeGit = SourceBootstrapGitCommandV1 { arguments ->
            when {
                arguments == listOf("rev-parse", "HEAD") -> expected
                arguments == listOf("status", "--porcelain=v1", "--untracked-files=no") ->
                    " M tracked-source.kt"

                arguments.firstOrNull() == "ls-files" -> arguments.last()
                else -> null
            }
        }
        val probe = GitSourceBootstrapProbeV1(root, git = fakeGit)
        val result = LocalSourceBootstrapV1(probe).verify(expected)

        result.status shouldBe SourceBootstrapStatusV1.REJECTED
        result.failureCode shouldBe SourceBootstrapFailureCodeV1.TRACKED_SOURCE_DIRTY
        result.verified.shouldBeFalse()
    }

    test("a missing independent replay verifier remains fail closed") {
        val run = KaggleActor04SmokeHarness.run(
            profiles = listOf(1),
            outputRoot = Files.createTempDirectory("ka04-proof-smoke-").resolve("scratch"),
            publicationRoot = Files.createTempDirectory("ka04-proof-publication-"),
        )
        run.noProofAdmission.datasetEligible.shouldBeFalse()
        run.noProofAdmission.acceptedSources shouldBe emptyList()
        run.noProofAdmission.ledger.entries.all { it.claims.isEmpty() }.shouldBeTrue()
    }

    test("semantic join uses B2 identities, not physical episode ordinals") {
        val run = KaggleActor04SmokeHarness.run(
            profiles = listOf(1, 2),
            outputRoot = Files.createTempDirectory("ka04-join-smoke-").resolve("scratch"),
            publicationRoot = Files.createTempDirectory("ka04-join-publication-"),
        )
        val bindingByTrajectoryId = run.profiles
            .flatMap { it.generated }
            .associate { it.trajectory.trajectoryId to it.binding }
        val admission = OfflineAdmissionV1.admit(
            OfflineAdmissionRequestV1(
                assignment = run.fullAssignment,
                sources = run.profiles.flatMap(ProfileCapture::reimported),
                replayVerifier = { trajectory ->
                    bindingByTrajectoryId[trajectory.trajectoryId]?.let {
                        OfflineReplayVerificationResultV1.Verified(it)
                    } ?: OfflineReplayVerificationResultV1.Unavailable(
                        com.wingedsheep.gym.trainer.actor.OfflineReplayFailureCodeV1.NO_PROOF,
                    )
                },
            ),
        )

        admission.datasetEligible.shouldBeTrue()
        admission.ledger.entries.map { it.jobOrdinal } shouldBe (0 until KA04_JOB_COUNT).toList()
        admission.ledger.entries.all { it.membershipState.name == "ACCEPTED" }.shouldBeTrue()
        admission.acceptedSources.map { it.jobOrdinal } shouldBe (0 until KA04_JOB_COUNT).toList()
        run.profiles.flatMap { profile ->
            profile.reimported.flatMap { source ->
                source.manifest.episodes.map { episode -> episode.episodeOrdinal }
            }
        }.any { it > 0 }.shouldBeTrue()

        val repackedA = OfflineAdmissionV1.repackAcceptedSources(
            admission = admission,
            outputDirectory = Files.createTempDirectory("ka04-repack-a-"),
            metadata = run.metadata,
        )
        val repackedB = OfflineAdmissionV1.repackAcceptedSources(
            admission = admission,
            outputDirectory = Files.createTempDirectory("ka04-repack-b-"),
            metadata = run.metadata,
        )
        repackedA shouldBe repackedB
    }

    test("re-import rejects mutation of a provider-visible copy") {
        val run = KaggleActor04SmokeHarness.run(
            profiles = listOf(1),
            outputRoot = Files.createTempDirectory("ka04-mutation-smoke-").resolve("scratch"),
            publicationRoot = Files.createTempDirectory("ka04-mutation-publication-"),
        )
        val envelope = run.profiles.single().published.single().envelopeDirectory
        val shard = Files.walk(envelope).use { stream ->
            stream.filter { path ->
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    path.fileName.toString().endsWith(".ndjson")
            }.findFirst().orElseThrow()
        }
        Files.write(
            shard,
            byteArrayOf('\n'.code.toByte()),
            StandardOpenOption.APPEND,
        )
        shouldThrow<TrajectoryV1ReadException> { LocalPublicationEnvelopeV1.reimport(envelope) }
    }
})

private val ka04ProviderSmokeEnabled = System.getProperty("ka04.enabled") == "true"

/** Opt-in Gradle entrypoint used from a provider notebook; ordinary gym tests never run it. */
class KaggleActor04ProviderSmokeTest : FunSpec({
    test("run the configured provider smoke ladder")
        .config(enabled = ka04ProviderSmokeEnabled) {
            val outputRoot = Path.of(
                System.getProperty("ka04.outputRoot")
                    ?: Files.createTempDirectory("ka04-provider-scratch-").toString(),
            )
            val publicationRoot = Path.of(
                System.getProperty("ka04.publicationRoot")
                    ?: Files.createTempDirectory("ka04-provider-working-").toString(),
            )
            val profiles = (System.getProperty("ka04.profiles") ?: "1")
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(String::toInt)
            val repositoryRoot = Path.of(
                System.getProperty("ka04.repositoryRoot") ?: System.getProperty("user.dir"),
            )
            val expectedSourceCommit = requireNotNull(System.getProperty("ka04.engineSourceCommit")) {
                "ka04.engineSourceCommit must pin the exact checkout for a provider smoke"
            }
            val run = KaggleActor04SmokeHarness.run(
                profiles = profiles,
                outputRoot = outputRoot,
                publicationRoot = publicationRoot,
                repositoryRoot = repositoryRoot,
                expectedSourceCommit = expectedSourceCommit,
            )
            println(
                buildString {
                    appendLine("KA04_PROVIDER_SMOKE=PASS")
                    appendLine("SOURCE_COMMIT=${run.sourceCommit}")
                    appendLine("WORKLOAD_PLAN_ID=${run.plan.workloadPlanIdentity}")
                    appendLine("WORK_ASSIGNMENT_ID=${run.fullAssignment.assignmentIdentity}")
                    appendLine("JOB_COUNT=${run.plan.jobs.size}")
                    run.profiles.forEach { profile ->
                        appendLine("CONCURRENCY_${profile.concurrency}=PASS")
                        appendLine("CONCURRENCY_${profile.concurrency}_COMPLETE=${profile.completeEpisodes}")
                        appendLine("CONCURRENCY_${profile.concurrency}_FAILED=${profile.failedEpisodes}")
                        appendLine("CONCURRENCY_${profile.concurrency}_PARTIAL_LOST=${profile.partialLostEpisodes}")
                        appendLine(
                            "CONCURRENCY_${profile.concurrency}_DATASETS=" +
                                profile.published.joinToString(",") { it.manifest.datasetId },
                        )
                    }
                    appendLine("OFFLINE_REPLAY_REVERIFICATION=${run.noProofAdmission.offlineReplayReverification}")
                    appendLine("DATASET_ELIGIBLE=${run.noProofAdmission.datasetEligible}")
                    appendLine("RUNTIME_PROBE=${run.runtime.renderSafeText()}")
                },
            )
        }
})

private object KaggleActor04SmokeHarness {
    fun planOnly(
        repositoryRootOverride: Path? = null,
        expectedSourceCommitOverride: String? = null,
    ): PlanContext {
        val repositoryRoot = (
            repositoryRootOverride
                ?: System.getProperty("ka04.repositoryRoot")?.let(Path::of)
                ?: System.getProperty("ka04.gradleProjectRoot")?.let(Path::of)
                ?: findRepositoryRoot()
            )
            .toAbsolutePath()
            .normalize()
        require(Files.isDirectory(repositoryRoot.resolve("docs/ml/curriculum"))) {
            "KA04 repository root does not contain the locked curriculum"
        }
        val sourceCommit = expectedSourceCommitOverride
            ?: System.getProperty("ka04.engineSourceCommit")
            ?: gitHead(repositoryRoot)
        val registry = exactRegistry()
        val loader = CurriculumDeckSourceLoader(repositoryRoot)
        val akiri = loader.load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[0])
        val chevill = loader.load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[1])
        val policySourceIdentity = policySourceIdentity(repositoryRoot)
        val plan = WorkloadPlanV1(
            workloadNamespace = "argentum-ka04-real-provider-smoke",
            rolloutGeneration = "2026-09-15-smoke-v1",
            behaviorPolicyCampaignIdentity = KA04_POLICY_IDENTITY,
            opponentPolicyCampaignIdentity = KA04_POLICY_IDENTITY,
            jobs = (0 until KA04_JOB_COUNT).map { ordinal ->
                WorkloadJobV1(
                    jobOrdinal = ordinal,
                    environmentIdentity = environmentIdentity(
                        ordinal = ordinal,
                        engineCommit = sourceCommit,
                        akiriDigest = akiri.sourceDigest,
                        chevillDigest = chevill.sourceDigest,
                    ),
                    policyProvenance = policyProvenance(
                        ordinal = ordinal,
                        policySourceIdentity = policySourceIdentity,
                    ),
                )
            },
        )
        return PlanContext(
            repositoryRoot = repositoryRoot,
            registry = registry,
            resolver = DeckResolver(registry),
            akiri = akiri,
            chevill = chevill,
            plan = plan,
            metadata = DatasetMetadataV1(
                maxShardBytes = 16L * 1024L * 1024L,
                maxEpisodesPerShard = 1,
            ),
            policySourceIdentity = policySourceIdentity,
            sourceCommit = sourceCommit,
        )
    }

    fun run(
        profiles: List<Int>,
        outputRoot: Path,
        publicationRoot: Path,
        repositoryRoot: Path? = null,
        expectedSourceCommit: String? = null,
    ): SmokeRun {
        require(profiles.isNotEmpty() && profiles.all { it in setOf(1, 2, 4) })
        require(profiles.distinct().size == profiles.size)
        Files.createDirectories(outputRoot)
        Files.createDirectories(publicationRoot)
        val context = planOnly(repositoryRoot, expectedSourceCommit)
        val runtime = ActorRuntimeProbeV1(
            sourceRepositoryRoot = context.repositoryRoot,
            workingRoot = publicationRoot,
            scratchRoot = outputRoot,
        ).probe()
        val fullAssignment = WorkAssignmentV1.from(context.plan, (0 until KA04_JOB_COUNT).toList())
        val captures = profiles.map { concurrency ->
            runProfile(
                context = context,
                fullAssignment = fullAssignment,
                concurrency = concurrency,
                outputRoot = outputRoot,
                publicationRoot = publicationRoot,
            )
        }
        val allReimported = captures.flatMap(ProfileCapture::reimported)
        val noProofAdmission = OfflineAdmissionV1.admit(
            OfflineAdmissionRequestV1(
                assignment = fullAssignment,
                sources = allReimported,
                replayVerifier = null,
            ),
        )
        writeOperationalReport(
            outputRoot = publicationRoot,
            runtime = runtime,
            context = context,
            fullAssignment = fullAssignment,
            profiles = captures,
            noProofAdmission = noProofAdmission,
        )
        return SmokeRun(
            repositoryRoot = context.repositoryRoot,
            plan = context.plan,
            fullAssignment = fullAssignment,
            profiles = captures,
            noProofAdmission = noProofAdmission,
            runtime = runtime,
            metadata = context.metadata,
            sourceCommit = context.sourceCommit,
        )
    }

    private fun runProfile(
        context: PlanContext,
        fullAssignment: WorkAssignmentV1,
        concurrency: Int,
        outputRoot: Path,
        publicationRoot: Path,
    ): ProfileCapture {
        val chunkSize = (fullAssignment.items.size + concurrency - 1) / concurrency
        val assignments = fullAssignment.items.chunked(chunkSize).map { items ->
            WorkAssignmentV1(workloadPlan = fullAssignment.workloadPlan, items = items)
        }
        val executor = Executors.newFixedThreadPool(concurrency)
        val futures = assignments.mapIndexed { actorIndex, assignment ->
            executor.submit(Callable {
                runActor(
                    context = context,
                    assignment = assignment,
                    concurrency = concurrency,
                    actorIndex = actorIndex,
                    outputRoot = outputRoot,
                    publicationRoot = publicationRoot,
                )
            })
        }
        return try {
            futures.map { it.get() }.let { actorRuns ->
                ProfileCapture(
                    concurrency = concurrency,
                    assignmentIdentities = assignments.map(WorkAssignmentV1::assignmentIdentity),
                    actors = actorRuns,
                    generated = actorRuns.flatMap { it.generated },
                    published = actorRuns.flatMap { it.published },
                    reimported = actorRuns.flatMap { it.reimported },
                    completeEpisodes = actorRuns.sumOf { it.result.report.completedJobOrdinals.size },
                    failedEpisodes = actorRuns.sumOf { it.result.report.failedJobOrdinals.size },
                    partialLostEpisodes = actorRuns.sumOf { it.result.report.partialLostJobOrdinals.size },
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runActor(
        context: PlanContext,
        assignment: WorkAssignmentV1,
        concurrency: Int,
        actorIndex: Int,
        outputRoot: Path,
        publicationRoot: Path,
    ): ActorCapture {
        val actorRoot = outputRoot.resolve("concurrency-$concurrency").resolve("actor-$actorIndex")
        val actorWorkingRoot = publicationRoot.resolve("concurrency-$concurrency").resolve("actor-$actorIndex")
        Files.createDirectories(actorRoot)
        Files.createDirectories(actorWorkingRoot)
        val generated = mutableListOf<KA04GeneratedEpisode>()
        val result = LocalActorExecutionV1.run(
            LocalActorExecutionRequestV1(
                assignment = assignment,
                executionAttemptIdentity = ExecutionAttemptIdentityV1(
                    "ka04-c$concurrency-a$actorIndex",
                ),
                sourceRepositoryRoot = context.repositoryRoot,
                preflight = {
                    preflight(
                        workingRoot = actorWorkingRoot,
                        scratchRoot = actorRoot,
                    )
                },
                episodeExecutor = ActorEpisodeExecutor { item ->
                    val episode = generateEpisode(item, context)
                    generated += episode
                    ActorEpisodeOutcome.Completed(
                        trajectory = episode.trajectory,
                        replayTrajectoryBinding = episode.binding,
                    )
                },
                sinkFactory = {
                    TrajectoryV1B2Sink(
                        com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Writer(
                            actorRoot.resolve("b2"),
                            context.metadata,
                        ),
                    )
                },
                statusSink = com.wingedsheep.gym.trainer.actor.AtomicActorStatusFileSink(
                    actorRoot.resolve("status-final.json"),
                ),
                requestedConcurrency = concurrency,
                actualConcurrency = concurrency,
            ),
        )
        val manifest = result.manifest ?: run {
            return ActorCapture(result, generated, emptyList(), emptyList())
        }
        val datasetRoot = actorRoot.resolve("b2").resolve("dataset-${manifest.datasetId}")
        val published = LocalPublicationEnvelopeV1.publish(
            sourceDatasetDirectory = datasetRoot,
            destinationDirectory = actorWorkingRoot,
            assignment = assignment,
            status = result.status,
            report = result.report,
        )
        val reimported = LocalPublicationEnvelopeV1.reimport(published.envelopeDirectory)
        return ActorCapture(result, generated, listOf(published), listOf(reimported))
    }

    private fun generateEpisode(item: WorkItemV1, context: PlanContext): KA04GeneratedEpisode {
        val config = gameConfig(item, context)
        val environment = GameEnvironment.create(
            cardRegistry = context.registry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        environment.reset(config, KA04_MAX_STEPS)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(
                cardRegistry = context.registry,
            ),
        )
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(item.policyProvenance.policySeed)
        val actions = mutableListOf<GameAction>()
        val checkpoints = mutableListOf<ReplayCheckpoint>()
        var result: ObservationResult = gym.observe()
        var observation = requireTrainingObservation(result)
        var transitions = 0
        while (!observation.terminated && !observation.truncated) {
            require(transitions < KA04_MAX_STEPS) { "KA04 smoke exceeded its bounded horizon" }
            val choice = policy.choose(observation, policyState)
            policyState = policyState.afterChoice()
            when (choice) {
                is SemanticChoice.Gap -> error("KA04 policy gap: $choice")
                is SemanticChoice.Action -> {
                    val view = observation.legalActions.singleOrNull { it.actionId == choice.actionId }
                        ?: error("KA04 policy selected an action outside the public domain")
                    when (val resolved = result.registry.resolve(view.actionId)) {
                        is ResolvedAction.Legal -> {
                            actions += if (choice.payload == null) {
                                resolved.action
                            } else {
                                materializeAction(resolved.action, choice.payload)
                            }
                        }

                        is ResolvedAction.Decision -> {
                            require(choice.payload == null)
                            val actor = checkNotNull(observation.agentToAct)
                            actions += SubmitDecision(actor, resolved.response)
                        }

                        ResolvedAction.Unknown -> error("KA04 action did not resolve")
                    }
                    result = if (choice.payload == null) {
                        gym.step(choice.actionId)
                    } else {
                        gym.step(choice.actionId, choice.payload)
                    }
                }

                is SemanticChoice.Structured -> {
                    val pending = checkNotNull(observation.pendingDecision)
                    val decisionId = checkNotNull(pending.decisionId)
                    val response = toDecisionResponse(decisionId, choice.selection)
                    actions += SubmitDecision(pending.playerId, response)
                    result = gym.submitDecision(response, actorId = observation.agentToAct)
                }
            }
            transitions += 1
            if (transitions % ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS == 0) {
                checkpoints += ReplayCheckpoint(
                    afterActionCount = transitions,
                    fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
                )
            }
            observation = requireTrainingObservation(result)
        }

        val closure = checkNotNull(environment.episodeClosure)
        require(closure.stepCount == actions.size)
        if (checkpoints.lastOrNull()?.afterActionCount != actions.size) {
            checkpoints += ReplayCheckpoint(
                afterActionCount = actions.size,
                fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
            )
        }
        val playerIds = config.players.map { checkNotNull(it.playerId) }
        val setup = ReplaySetup(
            seed = checkNotNull(config.seed),
            format = config.format,
            attackMode = config.attackMode,
            startingHandSize = config.startingHandSize,
            skipMulligans = config.skipMulligans,
            useHandSmoother = config.useHandSmoother,
            handSmootherCandidates = config.handSmootherCandidates,
            startingPlayerIndex = config.startingPlayerIndex,
            teams = config.teams,
            players = config.players.mapIndexed { index, player ->
                ReplayPlayerSetup(
                    playerId = playerIds[index].value,
                    name = player.name,
                    deck = player.deck,
                    startingLife = player.startingLife,
                    commanderCardName = player.commanderCardName,
                )
            },
            seatRoster = config.players.mapIndexed { index, player ->
                com.wingedsheep.gameserver.protocol.ServerMessage.PlayerSeatInfo(
                    playerId = playerIds[index].value,
                    name = player.name,
                    seatIndex = index,
                )
            },
        )
        val replay = CompactReplay(
            version = CompactReplay.CURRENT_VERSION,
            gameId = "ka04-job-${item.jobOrdinal}",
            players = config.players.mapIndexed { index, player ->
                ReplayPlayerInfo(playerIds[index].value, player.name)
            },
            startedAt = "2026-09-15T00:00:00Z",
            endedAt = "2026-09-15T00:00:01Z",
            winnerName = (closure as? EpisodeClosureV1.GameTerminal)?.winnerId?.let { winner ->
                config.players.firstOrNull { it.playerId == winner }?.name
            },
            setup = setup,
            actions = actions,
            pinnedCards = ReplayCardPin.capture(context.registry, setup),
            checkpoints = checkpoints,
        )
        val decodedReplay = ReplayCodec.decode(ReplayCodec.encode(replay))
        require(decodedReplay == replay)
        val replayIdentity = ReplayContentCanonicalizerV1.identity(decodedReplay)
        val binding = GymReplayFrameSource(
            replay = decodedReplay,
            cardRegistry = context.registry,
            fallbackPerspectivePlayerIndex = 0,
            tailClosure = closure,
        ).verifyTrajectoryBinding()
        require(binding.verificationBinding.verification.fidelity == ReplayFidelity.EXACT) {
            "KA04 replay was not exact: ${binding.verificationBinding.verification.failureReason}"
        }
        require(binding.chosenInputBinding.chosenInputs.size == actions.size)
        val metadataBase = EpisodeMetadataV1(
            semanticEpisodeId = "0".repeat(64),
            collectionJobId = "0".repeat(64),
            environmentIdentity = item.environmentIdentity,
            policyProvenance = item.policyProvenance,
            compactReplayLink = CompactReplayLinkV1(
                replayVersion = decodedReplay.version,
                replaySchemaIdentity = "argentum-compact-replay@v6",
                replayContentIdentity = replayIdentity.value,
                replayActionCount = decodedReplay.actions.size,
            ),
            closure = closure,
        )
        val metadataWithSemanticId = metadataBase.copy(
            semanticEpisodeId = metadataBase.recomputeSemanticEpisodeId(),
        )
        val metadata = metadataWithSemanticId.copy(
            collectionJobId = metadataWithSemanticId.recomputeCollectionJobId(),
        )
        var prefix = SemanticReplayPrefixV1()
        val records = binding.chosenInputBinding.chosenInputs.mapIndexed { index, chosen ->
            val frame = binding.verificationBinding.verification.frames[index]
            val identity = SemanticDecisionIdentityV1.from(
                semanticEpisodeId = metadata.semanticEpisodeId,
                prefix = prefix,
                replayActionIndex = index,
                observation = frame.observation,
                domain = frame.domain,
                perspectivePlayerId = frame.perspectivePlayerId.value,
            )
            val record = DecisionRecordV1(
                decisionIndex = index,
                replayActionIndex = index,
                replayFrameIndex = index,
                perspectivePlayerId = frame.perspectivePlayerId,
                decisionKind = identity.decisionKind,
                semanticDecisionId = identity.semanticDecisionId(),
                observationBefore = frame.observation,
                completeLegalDomain = frame.domain,
                candidateDomainDigest = frame.candidateDomainDigest,
                chosenSemanticAction = chosen.chosenSemanticAction,
                chosenSemanticResponse = chosen.chosenSemanticResponse,
            )
            val input = chosen.chosenSemanticAction?.let(SemanticReplayInputV1::action)
                ?: SemanticReplayInputV1.response(checkNotNull(chosen.chosenSemanticResponse))
            prefix = prefix.copy(inputs = prefix.inputs + input)
            record
        }
        val provisional = TrajectoryV1(
            trajectoryId = "0".repeat(64),
            episodeMetadata = metadata,
            decisions = records,
        )
        val trajectory = provisional.copy(trajectoryId = provisional.recomputeTrajectoryId())
        require(trajectory.episodeMetadata.environmentIdentity == item.environmentIdentity)
        require(trajectory.episodeMetadata.policyProvenance == item.policyProvenance)
        return KA04GeneratedEpisode(item, trajectory, binding)
    }

    private fun gameConfig(item: WorkItemV1, context: PlanContext): GameConfig {
        val akiriDeck = context.resolver.resolve(
            DeckSpec.Explicit(context.akiri.libraryDeckList()),
        )
        val chevillDeck = context.resolver.resolve(
            DeckSpec.Explicit(context.chevill.libraryDeckList()),
        )
        val players = listOf(
            PlayerConfig(
                name = "Akiri",
                deck = akiriDeck,
                startingLife = 40,
                playerId = EntityId("ka04-seat-0"),
                commanderCardName = context.akiri.commander,
            ),
            PlayerConfig(
                name = "Chevill",
                deck = chevillDeck,
                startingLife = 40,
                playerId = EntityId("ka04-seat-1"),
                commanderCardName = context.chevill.commander,
            ),
        )
        return GameConfig(
            players = players,
            startingHandSize = item.environmentIdentity.startingHandSize,
            skipMulligans = item.environmentIdentity.skipMulligans,
            useHandSmoother = item.environmentIdentity.useHandSmoother,
            startingPlayerIndex = players.indexOfFirst {
                it.playerId == item.environmentIdentity.startingPlayer
            },
            format = Format.Commander(),
            attackMode = AttackMode.MULTIPLE,
            seed = item.environmentIdentity.actualEngineSeed,
        )
    }

    private fun environmentIdentity(
        ordinal: Int,
        engineCommit: String,
        akiriDigest: String,
        chevillDigest: String,
    ): EnvironmentIdentityV1 {
        val players = listOf(EntityId("ka04-seat-0"), EntityId("ka04-seat-1"))
        return EnvironmentIdentityV1(
            engineCommit = engineCommit,
            cardDefinitionIdentity = KA04_CARD_DEFINITION_IDENTITY,
            akiriDeckIdentity = akiriDigest,
            chevillDeckIdentity = chevillDigest,
            format = "COMMANDER",
            attackMode = AttackMode.MULTIPLE.name,
            startingHandSize = 7,
            skipMulligans = true,
            useHandSmoother = false,
            roster = listOf(
                RosterSeatV1(
                    seatIndex = 0,
                    playerId = players[0],
                    role = "AKIRI",
                    deckIdentity = akiriDigest,
                    commanderDefinitionIdentity = "Akiri, Fearless Voyager",
                ),
                RosterSeatV1(
                    seatIndex = 1,
                    playerId = players[1],
                    role = "CHEVILL",
                    deckIdentity = chevillDigest,
                    commanderDefinitionIdentity = "Chevill, Bane of Monsters",
                ),
            ),
            startingPlayer = players[0],
            actualEngineSeed = ordinal.toLong(),
        )
    }

    private fun policyProvenance(ordinal: Int, policySourceIdentity: String): PolicyProvenanceV1 =
        PolicyProvenanceV1(
            behaviorPolicyIdentity = KA04_POLICY_IDENTITY,
            opponentPolicyIdentity = KA04_POLICY_IDENTITY,
            behaviorPolicyRole = "EXTERNAL_CONTROLLER",
            opponentPolicyRole = "EXTERNAL_CONTROLLER",
            policyRngIdentity = KA04_POLICY_RNG_IDENTITY,
            policySeed = 42_599_05L + ordinal,
            policySourceIdentity = policySourceIdentity,
        )

    fun preflight(workingRoot: Path, scratchRoot: Path) = MeasuredStoragePreflightV1(
        probe = FileStoreStorageCapacityProbeV1(),
        workingRoot = workingRoot.also(Files::createDirectories),
        scratchRoot = scratchRoot.also(Files::createDirectories),
        configuration = StoragePreflightConfigurationV1(
            estimatedFinalizedOutputBytes = 4L * 1024L * 1024L,
            requiredAdditionalScratchBytes = 4L * 1024L * 1024L,
            publicationOverheadBytes = 1L * 1024L * 1024L,
            workingSafetyReserveBytes = 1L * 1024L * 1024L,
            providerSafetyReserveBytes = 1L * 1024L * 1024L,
            configuredProviderOutputCapBytes = 1L * 1024L * 1024L * 1024L,
        ),
    ).evaluate()

    private fun writeOperationalReport(
        outputRoot: Path,
        runtime: ActorRuntimeSnapshotV1,
        context: PlanContext,
        fullAssignment: WorkAssignmentV1,
        profiles: List<ProfileCapture>,
        noProofAdmission: com.wingedsheep.gym.trainer.actor.OfflineAdmissionResultV1,
    ) {
        Files.createDirectories(outputRoot)
        val report = buildJsonObject {
            put("schemaIdentity", "argentum-ka04-smoke-report@v1")
            put("sourceCommit", context.sourceCommit)
            put("workloadPlanIdentity", context.plan.workloadPlanIdentity)
            put("fullAssignmentIdentity", fullAssignment.assignmentIdentity)
            put("jobCount", KA04_JOB_COUNT)
            put("runtime", A3SemanticJson.strictJson.parseToJsonElement(runtime.renderSafeText()))
            put("offlineReplayReverification", noProofAdmission.offlineReplayReverification.name)
            put("datasetEligible", noProofAdmission.datasetEligible)
            put(
                "profiles",
                JsonArray(profiles.map { profile ->
                    buildJsonObject {
                        put("concurrency", profile.concurrency)
                        put("completeEpisodes", profile.completeEpisodes)
                        put("failedEpisodes", profile.failedEpisodes)
                        put("partialLostEpisodes", profile.partialLostEpisodes)
                        put("assignmentIdentities", JsonArray(profile.assignmentIdentities.map(::JsonPrimitive)))
                        put("publishedEnvelopeCount", profile.published.size)
                        put("reimportedEnvelopeCount", profile.reimported.size)
                    }
                }),
            )
        }
        Files.writeString(
            outputRoot.resolve("ka04-smoke-report.json"),
            A3SemanticJson.canonicalJson(report) + "\n",
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun exactRegistry(): CardRegistry = CardRegistry().apply {
        MtgSetCatalog.all.forEach { set ->
            register(set.cards)
            register(set.basicLands)
        }
    }

    private fun policySourceIdentity(repositoryRoot: Path): String {
        val source = repositoryRoot.resolve(
            "gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt",
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(Files.readString(source).replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02X".format(byte) }
        return "EnvironmentV1ExternalPolicy.kt@sha256:$digest"
    }

    private fun findRepositoryRoot(): Path = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }

    private fun gitHead(repositoryRoot: Path): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repositoryRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
        require(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            "KA04 source HEAD probe timed out"
        }
        require(process.exitValue() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
            "KA04 source HEAD probe failed"
        }
        return output
    }

    private fun requireTrainingObservation(result: ObservationResult): TrainingObservation {
        require(result.diagnostics.isEmpty()) { "KA04 public observation carried diagnostics" }
        val observation = result.observation.shouldBeInstanceOf<TrainingObservation>()
        require(observation.schemaHash == SchemaHash.CURRENT)
        return observation
    }

    private fun materializeAction(template: GameAction, payload: JsonObject): GameAction {
        val json = A3SemanticJson.strictJson
            .encodeToJsonElement(GameAction.serializer(), template)
            .jsonObject
        val merged = buildJsonObject {
            json.forEach { (key, value) -> put(key, value) }
            payload.forEach { (key, value) -> if (key != "abilityKey") put(key, value) }
        }
        return A3SemanticJson.strictJson.decodeFromJsonElement(GameAction.serializer(), merged)
    }

    private fun toDecisionResponse(decisionId: String, selection: SemanticDecision): DecisionResponse =
        when (selection) {
            is SemanticDecision.Targets -> com.wingedsheep.engine.core.TargetsResponse(decisionId, selection.selected)
            is SemanticDecision.Cards -> com.wingedsheep.engine.core.CardsSelectedResponse(decisionId, selection.selected)
            is SemanticDecision.Modes -> com.wingedsheep.engine.core.ModesChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Color -> com.wingedsheep.engine.core.ColorChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Number -> com.wingedsheep.engine.core.NumberChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Distribution -> com.wingedsheep.engine.core.DistributionResponse(decisionId, selection.selected)
            is SemanticDecision.Ordered -> com.wingedsheep.engine.core.OrderedResponse(decisionId, selection.selected)
            is SemanticDecision.Piles -> com.wingedsheep.engine.core.PilesSplitResponse(decisionId, selection.selected)
            is SemanticDecision.Option -> com.wingedsheep.engine.core.OptionChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Replacement -> com.wingedsheep.engine.core.ReplacementChosenResponse(
                decisionId,
                selection.from,
                selection.to,
            )
            is SemanticDecision.Budget -> com.wingedsheep.engine.core.BudgetModalResponse(decisionId, selection.selected)
            is SemanticDecision.Damage -> com.wingedsheep.engine.core.CombatResolutionResponse(
                decisionId = decisionId,
                edges = selection.selected.map {
                    com.wingedsheep.engine.core.DamageEdgeAmount(it.edgeId, it.amount)
                },
            )
            is SemanticDecision.Payment -> selection.toDecisionResponse(decisionId)
        }
}

private data class PlanContext(
    val repositoryRoot: Path,
    val registry: CardRegistry,
    val resolver: DeckResolver,
    val akiri: com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceV1,
    val chevill: com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceV1,
    val plan: WorkloadPlanV1,
    val metadata: DatasetMetadataV1,
    val policySourceIdentity: String,
    val sourceCommit: String,
)

private data class KA04GeneratedEpisode(
    val item: WorkItemV1,
    val trajectory: TrajectoryV1,
    val binding: com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1,
)

private data class ActorCapture(
    val result: com.wingedsheep.gym.trainer.actor.ActorRunResult,
    val generated: List<KA04GeneratedEpisode>,
    val published: List<com.wingedsheep.gym.trainer.actor.LocalPublishedEnvelopeV1>,
    val reimported: List<com.wingedsheep.gym.trainer.actor.ReimportedLocalPublicationEnvelopeV1>,
)

private data class ProfileCapture(
    val concurrency: Int,
    val assignmentIdentities: List<String>,
    val actors: List<ActorCapture>,
    val generated: List<KA04GeneratedEpisode>,
    val published: List<com.wingedsheep.gym.trainer.actor.LocalPublishedEnvelopeV1>,
    val reimported: List<com.wingedsheep.gym.trainer.actor.ReimportedLocalPublicationEnvelopeV1>,
    val completeEpisodes: Int,
    val failedEpisodes: Int,
    val partialLostEpisodes: Int,
)

private data class SmokeRun(
    val repositoryRoot: Path,
    val plan: WorkloadPlanV1,
    val fullAssignment: WorkAssignmentV1,
    val profiles: List<ProfileCapture>,
    val noProofAdmission: com.wingedsheep.gym.trainer.actor.OfflineAdmissionResultV1,
    val runtime: ActorRuntimeSnapshotV1,
    val metadata: DatasetMetadataV1,
    val sourceCommit: String,
)

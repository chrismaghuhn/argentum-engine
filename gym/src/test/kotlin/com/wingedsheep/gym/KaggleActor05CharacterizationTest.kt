package com.wingedsheep.gym

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.trainer.actor.ActorDiagnosticCodeV1
import com.wingedsheep.gym.trainer.actor.ActorEpisodeExecutor
import com.wingedsheep.gym.trainer.actor.ActorEpisodeOutcome
import com.wingedsheep.gym.trainer.actor.ActorMeasurementSummaryV1
import com.wingedsheep.gym.trainer.actor.ActorRuntimeProbeV1
import com.wingedsheep.gym.trainer.actor.ActorRuntimeSnapshotV1
import com.wingedsheep.gym.trainer.actor.ActorStateV1
import com.wingedsheep.gym.trainer.actor.AtomicActorStatusFileSink
import com.wingedsheep.gym.trainer.actor.ExecutionAttemptIdentityV1
import com.wingedsheep.gym.trainer.actor.FileStoreStorageCapacityProbeV1
import com.wingedsheep.gym.trainer.actor.LocalActorExecutionRequestV1
import com.wingedsheep.gym.trainer.actor.LocalActorExecutionV1
import com.wingedsheep.gym.trainer.actor.LocalPublicationEnvelopeV1
import com.wingedsheep.gym.trainer.actor.MeasuredStoragePreflightV1
import com.wingedsheep.gym.trainer.actor.MembershipStateV1
import com.wingedsheep.gym.trainer.actor.OfflineAdmissionRequestV1
import com.wingedsheep.gym.trainer.actor.OfflineAdmissionV1
import com.wingedsheep.gym.trainer.actor.StoragePreflightConfigurationV1
import com.wingedsheep.gym.trainer.actor.TrajectoryV1B2Sink
import com.wingedsheep.gym.trainer.actor.WorkAssignmentV1
import com.wingedsheep.gym.trainer.actor.WorkItemV1
import com.wingedsheep.gym.trainer.actor.WorkloadPlanV1
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.time.Duration.Companion.hours

private const val KA05_MAX_STEPS = 2_000
private const val KA05_PLAN_EPISODES = 64
private const val KA05_POLICY_IDENTITY = "b2-a9-deterministic-external-policy@v1"
private const val KA05_WORKLOAD_NAMESPACE = "argentum-ka05-bounded-characterization"
private const val KA05_ROLLOUT_GENERATION = "2026-09-15-a9-primary-v1"
private const val KA05_MAX_SHARD_BYTES = 256L * 1024L * 1024L
private const val KA05_MAX_EPISODES_PER_SHARD = 1
private const val KA05_INITIAL_BYTES_PER_EPISODE = 64L * 1024L * 1024L
private const val KA05_INITIAL_SCRATCH_ESTIMATE = 512L * 1024L * 1024L
private const val KA05_PROVIDER_OUTPUT_CAP = 16L * 1024L * 1024L * 1024L
private const val KA05_PUBLICATION_OVERHEAD = 8L * 1024L * 1024L
private const val KA05_WORKING_SAFETY_RESERVE = 512L * 1024L * 1024L
private const val KA05_PROVIDER_SAFETY_RESERVE = 512L * 1024L * 1024L
private const val KA05_REPORT_SCHEMA = "argentum-ml-ka05-characterization@v1"

class KaggleActor05CharacterizationTest : FunSpec({
    test("the KA05 characterization Gradle entrypoint is registered separately from KA04") {
        Files.readString(ka05RepositoryRoot().resolve("gym/build.gradle.kts"))
            .contains("kaggleActor05CharacterizationTest")
            .shouldBeTrue()
    }

    test("one stable plan preserves global semantic identities across assignment sizes") {
        val context = Ka05CharacterizationHarness.planForTests()
        context.plan.jobs.size shouldBe KA05_PLAN_EPISODES
        context.plan.jobs.map { it.jobOrdinal } shouldBe (0 until KA05_PLAN_EPISODES).toList()
        val planIdentity = context.plan.workloadPlanIdentity
        val semanticIds = context.plan.jobs.map {
            context.plan.resolve(it.jobOrdinal).semanticJobIdentity.value
        }
        semanticIds.distinct().size shouldBe KA05_PLAN_EPISODES
        listOf(16, 32, 64).forEach { size ->
            val assignment = WorkAssignmentV1.from(context.plan, (0 until size).toList())
            assignment.workloadPlanIdentity shouldBe planIdentity
            assignment.items.map(WorkItemV1::jobOrdinal) shouldBe (0 until size).toList()
            assignment.items.map { it.semanticJobIdentity.value } shouldBe semanticIds.take(size)
        }
    }

    test("worker partitioning preserves the exact sorted semantic work set") {
        val context = Ka05CharacterizationHarness.planForTests()
        val assignment = WorkAssignmentV1.from(context.plan, (0 until 32).toList())
        listOf(1, 2, 4).forEach { concurrency ->
            val partitions = Ka05CharacterizationHarness.partitionForTests(
                assignment.items,
                concurrency,
            )
            partitions.flatMap { it }.map(WorkItemV1::jobOrdinal) shouldBe
                assignment.items.map(WorkItemV1::jobOrdinal)
            partitions.flatten().map { it.semanticJobIdentity.value } shouldContainExactly
                assignment.items.map { it.semanticJobIdentity.value }
        }
    }

    test("operational sizing and worker values do not change the assignment identity") {
        val context = Ka05CharacterizationHarness.planForTests()
        val assignmentA = WorkAssignmentV1.from(context.plan, (0 until 16).toList())
        val assignmentB = WorkAssignmentV1.from(context.plan, (0 until 16).toList())
        assignmentA.assignmentIdentity shouldBe assignmentB.assignmentIdentity
        assignmentA.items.map { it.expectedSemanticEpisodeId } shouldBe
            assignmentB.items.map { it.expectedSemanticEpisodeId }
        assignmentA.items.map { it.expectedCollectionJobId } shouldBe
            assignmentB.items.map { it.expectedCollectionJobId }
    }

    test("missing jobs are derived from the offline membership ledger") {
        val context = Ka05CharacterizationHarness.planForTests()
        val assignment = WorkAssignmentV1.from(context.plan, (0 until 16).toList())
        val admission = OfflineAdmissionV1.admit(
            OfflineAdmissionRequestV1(assignment, emptyList(), replayVerifier = null),
        )
        admission.offlineReplayReverification.name shouldBe "NO_INDEPENDENT_PROOF"
        admission.datasetEligible.shouldBeFalse()
        admission.acceptedSources shouldBe emptyList()
        admission.ledger.entries.map { it.jobOrdinal } shouldBe (0 until KA05_PLAN_EPISODES).toList()
        admission.ledger.entries.map { it.membershipState } shouldContainExactly
            List(KA05_PLAN_EPISODES) { MembershipStateV1.MISSING }
    }

    test("exact retries keep semantic identities while attempts remain operationally distinct") {
        val context = Ka05CharacterizationHarness.planForTests()
        val assignment = WorkAssignmentV1.from(context.plan, listOf(17))
        val firstAttempt = ExecutionAttemptIdentityV1("ka05-attempt-1")
        val retryAttempt = ExecutionAttemptIdentityV1("ka05-attempt-2")
        val item = assignment.items.single()
        firstAttempt.value shouldNotBe retryAttempt.value
        item.semanticJobIdentity.value shouldBe context.plan.resolve(17).semanticJobIdentity.value
        item.expectedSemanticEpisodeId shouldBe context.plan.resolve(17).expectedSemanticEpisodeId
        item.expectedCollectionJobId shouldBe context.plan.resolve(17).expectedCollectionJobId
    }
})

private val ka05ProviderCharacterizationEnabled = System.getProperty("ka05.enabled") == "true"

class KaggleActor05ProviderCharacterizationTest : FunSpec({
    test("runs the bounded actor characterization ladder")
        .config(enabled = ka05ProviderCharacterizationEnabled, timeout = 8.hours) {
            val repositoryRoot = Path.of(
                System.getProperty("ka05.repositoryRoot") ?: ka05RepositoryRoot().toString(),
            ).toAbsolutePath().normalize()
            val sourceCommit = System.getProperty("ka05.engineSourceCommit") ?: gitHead(repositoryRoot)
            val outputRoot = Path.of(
                System.getProperty("ka05.outputRoot")
                    ?: Files.createTempDirectory("ka05-characterization-scratch-").toString(),
            )
            val publicationRoot = Path.of(
                System.getProperty("ka05.publicationRoot")
                    ?: Files.createTempDirectory("ka05-characterization-working-").toString(),
            )
            val sizes = parseBoundedList("ka05.sizes", listOf(16, 32, 64), setOf(16, 32, 64))
            val concurrencies = parseBoundedList(
                "ka05.concurrencies",
                listOf(1, 2, 4),
                setOf(1, 2, 4),
            )
            val result = Ka05CharacterizationHarness.run(
                repositoryRoot = repositoryRoot,
                expectedSourceCommit = sourceCommit,
                sizes = sizes,
                concurrencies = concurrencies,
                outputRoot = outputRoot,
                publicationRoot = publicationRoot,
                initialBytesPerEpisodeEstimate = positiveLongProperty(
                    "ka05.initialBytesPerEpisodeEstimate",
                    KA05_INITIAL_BYTES_PER_EPISODE,
                ),
                initialScratchEstimateBytes = positiveLongProperty(
                    "ka05.initialScratchEstimateBytes",
                    KA05_INITIAL_SCRATCH_ESTIMATE,
                ),
                providerOutputCapBytes = positiveLongProperty(
                    "ka05.providerOutputCapBytes",
                    KA05_PROVIDER_OUTPUT_CAP,
                ),
            )
            println(result.renderSummary())
        }
})

private object Ka05CharacterizationHarness {
    fun planForTests(): Ka05PlanContext {
        val root = ka05RepositoryRoot()
        return planContext(root, gitHead(root))
    }

    fun partitionForTests(items: List<WorkItemV1>, concurrency: Int): List<List<WorkItemV1>> =
        partitionItems(items, concurrency)

    fun run(
        repositoryRoot: Path,
        expectedSourceCommit: String,
        sizes: List<Int>,
        concurrencies: List<Int>,
        outputRoot: Path,
        publicationRoot: Path,
        initialBytesPerEpisodeEstimate: Long,
        initialScratchEstimateBytes: Long,
        providerOutputCapBytes: Long,
    ): Ka05RunResult {
        require(sizes.isNotEmpty() && sizes == sizes.distinct().sorted())
        require(sizes.all { it in setOf(16, 32, 64) })
        require(concurrencies.isNotEmpty() && concurrencies == concurrencies.distinct().sorted())
        require(concurrencies.all { it in setOf(1, 2, 4) })
        require(initialBytesPerEpisodeEstimate > 0)
        require(initialScratchEstimateBytes > 0)
        require(providerOutputCapBytes > 0)
        Files.createDirectories(outputRoot)
        Files.createDirectories(publicationRoot)
        require(isEmptyDirectory(outputRoot)) { "KA05 scratch output root must start empty" }
        require(isEmptyDirectory(publicationRoot)) { "KA05 publication output root must start empty" }

        val context = planContext(repositoryRoot, expectedSourceCommit)
        val runtime = ActorRuntimeProbeV1(
            sourceRepositoryRoot = repositoryRoot,
            workingRoot = publicationRoot,
            scratchRoot = outputRoot,
        ).probe()
        val assignmentIds = sizes.associateWith { size ->
            WorkAssignmentV1.from(context.plan, (0 until size).toList()).assignmentIdentity
        }
        val conditionReports = mutableListOf<Ka05ConditionReportV1>()
        val offlineReports = mutableListOf<Ka05OfflineAdmissionReportV1>()
        val blockedSizes = mutableListOf<Int>()
        var estimate = initialBytesPerEpisodeEstimate
        var stopAfterCapacityBlock = false

        sizes.forEach { size ->
            if (stopAfterCapacityBlock) {
                blockedSizes += size
                offlineReports += blockedOfflineReport(context.plan, size, assignmentIds.getValue(size))
                return@forEach
            }
            val captures = mutableListOf<Ka05ConditionCapture>()
            concurrencies.forEach { concurrency ->
                if (stopAfterCapacityBlock) return@forEach
                val capture = runCondition(
                    context = context,
                    size = size,
                    concurrency = concurrency,
                    outputRoot = outputRoot,
                    publicationRoot = publicationRoot,
                    bytesPerEpisodeEstimate = estimate,
                    initialScratchEstimateBytes = initialScratchEstimateBytes,
                    providerOutputCapBytes = providerOutputCapBytes,
                )
                captures += capture
                conditionReports += capture.report
                estimate = nextEstimate(estimate, capture.report.canonicalBytesPerEpisode?.p95)
                if (capture.report.runStatus == "BLOCKED_BY_MEASURED_PREFLIGHT") {
                    blockedSizes += size
                    stopAfterCapacityBlock = true
                }
            }
            offlineReports += if (captures.isEmpty()) {
                blockedOfflineReport(context.plan, size, assignmentIds.getValue(size))
            } else {
                noProofAdmissionReport(
                    context.plan,
                    size,
                    assignmentIds.getValue(size),
                    captures,
                )
            }
        }

        val successfulSizes = conditionReports.filter {
            it.runStatus == "PASS" &&
                it.episodesCompleted == it.episodesAssigned &&
                it.failedEpisodes == 0 &&
                it.partialLostEpisodes == 0 &&
                it.publicationFailures == 0
        }.map { it.assignmentSize }.distinct()
        val largestSuccessful = successfulSizes.maxOrNull()
        val result = Ka05RunResult(
            context = context,
            runtime = runtime,
            assignmentIds = assignmentIds,
            conditions = conditionReports,
            offlineAdmission = offlineReports,
            blockedAssignmentSizes = blockedSizes.distinct().sorted(),
            recommendations = Ka05RecommendationV1(
                measured = if (conditionReports.isEmpty()) {
                    "No assignment condition completed"
                } else {
                    "Measured: " + conditionReports.joinToString(", ") {
                        it.assignmentSize.toString() + "x" + it.concurrency + "=" + it.runStatus
                    }
                },
                derived = if (largestSuccessful == null) {
                    "Derived: no complete successful assignment condition"
                } else {
                    "Derived: largest complete successful condition=" +
                        largestSuccessful + " episodes"
                },
                hypothesis = "Hypothesis: retain one episode per shard until independent tail evidence supports grouping.",
            ),
            reportPath = publicationRoot.resolve("ka05-characterization-report.json"),
        )
        writeReport(result)
        return result
    }

    private fun planContext(repositoryRoot: Path, expectedSourceCommit: String): Ka05PlanContext {
        val schedule = A9TrustedGenerationHarness.actorSchedule(KA05_PLAN_EPISODES)
        val registry = A9TrustedGenerationHarness.actorRegistry()
        val resolver = DeckResolver(registry)
        val policySourceIdentity = A9TrustedGenerationHarness.actorPolicySourceIdentity(repositoryRoot)
        val plan = WorkloadPlanV1(
            workloadNamespace = KA05_WORKLOAD_NAMESPACE,
            rolloutGeneration = KA05_ROLLOUT_GENERATION,
            behaviorPolicyCampaignIdentity = KA05_POLICY_IDENTITY,
            opponentPolicyCampaignIdentity = KA05_POLICY_IDENTITY,
            jobs = schedule.map {
                A9TrustedGenerationHarness.actorWorkloadJob(
                    schedule = it,
                    resolver = resolver,
                    engineCommit = expectedSourceCommit,
                    policySourceIdentity = policySourceIdentity,
                )
            },
        )
        require(plan.jobs.size == KA05_PLAN_EPISODES)
        return Ka05PlanContext(
            repositoryRoot = repositoryRoot,
            registry = registry,
            resolver = resolver,
            plan = plan,
            scheduleByOrdinal = schedule.associateBy { it.jobOrdinal },
            policySourceIdentity = policySourceIdentity,
            sourceCommit = expectedSourceCommit,
            metadata = DatasetMetadataV1(
                maxShardBytes = KA05_MAX_SHARD_BYTES,
                maxEpisodesPerShard = KA05_MAX_EPISODES_PER_SHARD,
            ),
        )
    }

    private fun runCondition(
        context: Ka05PlanContext,
        size: Int,
        concurrency: Int,
        outputRoot: Path,
        publicationRoot: Path,
        bytesPerEpisodeEstimate: Long,
        initialScratchEstimateBytes: Long,
        providerOutputCapBytes: Long,
    ): Ka05ConditionCapture {
        val assignment = WorkAssignmentV1.from(context.plan, (0 until size).toList())
        val partitions = partitionItems(assignment.items, concurrency)
        val conditionOutputRoot = outputRoot.resolve("assignment-" + size)
            .resolve("concurrency-" + concurrency)
        val conditionPublicationRoot = publicationRoot.resolve("assignment-" + size)
            .resolve("concurrency-" + concurrency)
        Files.createDirectories(conditionOutputRoot)
        Files.createDirectories(conditionPublicationRoot)
        val existingWorkingBytes = treeFileBytes(publicationRoot)
        val beforeJvm = Ka05JvmSnapshot.capture()
        val sampler = Ka05ResourceSampler(conditionPublicationRoot, conditionOutputRoot)
        sampler.start()
        val executor = Executors.newFixedThreadPool(concurrency)
        val futures = partitions.mapIndexed { actorIndex, actorItems ->
            val actorAssignment = WorkAssignmentV1(
                workloadPlan = assignment.workloadPlan,
                items = actorItems,
            )
            executor.submit(Callable {
                runActor(
                    context = context,
                    assignment = actorAssignment,
                    assignmentSize = size,
                    concurrency = concurrency,
                    actorIndex = actorIndex,
                    actorCount = partitions.size,
                    existingWorkingBytes = existingWorkingBytes,
                    bytesPerEpisodeEstimate = bytesPerEpisodeEstimate,
                    initialScratchEstimateBytes = initialScratchEstimateBytes,
                    providerOutputCapBytes = providerOutputCapBytes,
                    conditionOutputRoot = conditionOutputRoot,
                    conditionPublicationRoot = conditionPublicationRoot,
                )
            })
        }
        val actors = try {
            futures.map { it.get() }
        } finally {
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.MINUTES)) {
                executor.shutdownNow()
                require(executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    "KA05 actor executor did not terminate after bounded shutdown"
                }
            }
            sampler.close()
        }
        val afterJvm = Ka05JvmSnapshot.capture()
        return Ka05ConditionCapture(
            assignment = assignment,
            actors = actors,
            report = conditionReport(
                context = context,
                assignment = assignment,
                size = size,
                concurrency = concurrency,
                actors = actors,
                beforeJvm = beforeJvm,
                afterJvm = afterJvm,
                sampler = sampler,
            ),
        )
    }

    private fun runActor(
        context: Ka05PlanContext,
        assignment: WorkAssignmentV1,
        assignmentSize: Int,
        concurrency: Int,
        actorIndex: Int,
        actorCount: Int,
        existingWorkingBytes: Long,
        bytesPerEpisodeEstimate: Long,
        initialScratchEstimateBytes: Long,
        providerOutputCapBytes: Long,
        conditionOutputRoot: Path,
        conditionPublicationRoot: Path,
    ): Ka05ActorCapture {
        val actorRoot = conditionOutputRoot.resolve("actor-" + actorIndex)
        val actorWorkingRoot = conditionPublicationRoot.resolve("actor-" + actorIndex)
        Files.createDirectories(actorRoot)
        Files.createDirectories(actorWorkingRoot)
        val timings = ConcurrentLinkedQueue<Ka05EpisodeTiming>()
        val assignedEstimate = Math.multiplyExact(bytesPerEpisodeEstimate, assignment.items.size.toLong())
        val previousJobCount = (0 until actorIndex).sumOf { index ->
            partitionItems(assignment.items, actorCount)[index].size.toLong()
        }
        val existingOrPlanned = Math.addExact(
            existingWorkingBytes,
            Math.multiplyExact(bytesPerEpisodeEstimate, previousJobCount),
        )
        val requiredScratch = max(
            initialScratchEstimateBytes,
            Math.multiplyExact(bytesPerEpisodeEstimate, assignment.items.size.toLong()),
        )
        val result = LocalActorExecutionV1.run(
            LocalActorExecutionRequestV1(
                assignment = assignment,
                executionAttemptIdentity = ExecutionAttemptIdentityV1(
                    "ka05-size" + assignmentSize + "-c" + concurrency + "-a" + actorIndex,
                ),
                sourceRepositoryRoot = context.repositoryRoot,
                preflight = {
                    MeasuredStoragePreflightV1(
                        probe = FileStoreStorageCapacityProbeV1(),
                        workingRoot = actorWorkingRoot,
                        scratchRoot = actorRoot,
                        configuration = StoragePreflightConfigurationV1(
                            estimatedFinalizedOutputBytes = assignedEstimate,
                            requiredAdditionalScratchBytes = requiredScratch,
                            publicationOverheadBytes = KA05_PUBLICATION_OVERHEAD,
                            workingSafetyReserveBytes = KA05_WORKING_SAFETY_RESERVE,
                            providerSafetyReserveBytes = KA05_PROVIDER_SAFETY_RESERVE,
                            configuredProviderOutputCapBytes = providerOutputCapBytes,
                            providerExistingOrPlannedOutputBytes = existingOrPlanned,
                        ),
                    ).evaluate()
                },
                episodeExecutor = ActorEpisodeExecutor { item ->
                    val started = System.nanoTime()
                    val generated = A9TrustedGenerationHarness.actorGenerateEpisode(
                        schedule = context.scheduleByOrdinal.getValue(item.jobOrdinal),
                        registry = context.registry,
                        resolver = context.resolver,
                        repositoryRoot = context.repositoryRoot,
                        policySourceIdentity = context.policySourceIdentity,
                        engineCommit = context.sourceCommit,
                    )
                    timings += Ka05EpisodeTiming(
                        jobOrdinal = item.jobOrdinal,
                        elapsedNanos = (System.nanoTime() - started).coerceAtLeast(0L),
                    )
                    ActorEpisodeOutcome.Completed(
                        trajectory = generated.trajectory,
                        replayTrajectoryBinding = generated.replayTrajectoryBinding,
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
                statusSink = AtomicActorStatusFileSink(actorRoot.resolve("status-final.json")),
                requestedConcurrency = concurrency,
                actualConcurrency = concurrency,
            ),
        )
        var published: com.wingedsheep.gym.trainer.actor.LocalPublishedEnvelopeV1? = null
        var reimported: com.wingedsheep.gym.trainer.actor.ReimportedLocalPublicationEnvelopeV1? = null
        var publicationFailure = false
        val manifest = result.manifest
        if (manifest != null) {
            val datasetRoot = actorRoot.resolve("b2")
                .resolve("dataset-" + manifest.datasetId)
            try {
                published = LocalPublicationEnvelopeV1.publish(
                    sourceDatasetDirectory = datasetRoot,
                    destinationDirectory = actorWorkingRoot,
                    assignment = assignment,
                    status = result.status,
                    report = result.report,
                )
                reimported = LocalPublicationEnvelopeV1.reimport(published.envelopeDirectory)
            } catch (_: Exception) {
                publicationFailure = true
            }
        }
        return Ka05ActorCapture(
            result = result,
            timings = timings.toList().sortedBy(Ka05EpisodeTiming::jobOrdinal),
            published = published,
            reimported = reimported,
            publicationFailure = publicationFailure,
        )
    }

    private fun conditionReport(
        context: Ka05PlanContext,
        assignment: WorkAssignmentV1,
        size: Int,
        concurrency: Int,
        actors: List<Ka05ActorCapture>,
        beforeJvm: Ka05JvmSnapshot,
        afterJvm: Ka05JvmSnapshot,
        sampler: Ka05ResourceSampler,
    ): Ka05ConditionReportV1 {
        val wallTimeNanos = (sampler.finishedAtNanos - sampler.startedAtNanos).coerceAtLeast(1L)
        val itemByJoinKey = assignment.items.associateBy {
            Ka05JoinKey(it.expectedSemanticEpisodeId, it.expectedCollectionJobId)
        }
        val episodes = actors.flatMap { actor ->
            val imported = actor.reimported ?: return@flatMap emptyList<Ka05EpisodeMeasurement>()
            val shardBytesByOrdinal = imported.manifest.shards.mapIndexed { index, shard ->
                index to Files.size(imported.datasetDirectory.resolve(shard.contentReference))
            }.toMap()
            val timingsByJob = actor.timings.associateBy(Ka05EpisodeTiming::jobOrdinal)
            imported.streamEpisodes().map { trajectory ->
                val item = itemByJoinKey.getValue(
                    Ka05JoinKey(trajectory.semanticEpisodeId, trajectory.collectionJobId),
                )
                val manifestEpisode = imported.manifest.episodes.single {
                    it.trajectoryId == trajectory.trajectoryId
                }
                Ka05EpisodeMeasurement(
                    jobOrdinal = item.jobOrdinal,
                    decisionCount = trajectory.decisions.size,
                    closureKind = trajectory.closure.kind.name,
                    canonicalBytes = shardBytesByOrdinal.getValue(manifestEpisode.shardOrdinal),
                    elapsedNanos = timingsByJob.getValue(item.jobOrdinal).elapsedNanos,
                )
            }.toList()
        }
        val shardBytes = actors.flatMap { actor ->
            val imported = actor.reimported ?: return@flatMap emptyList<Long>()
            imported.manifest.shards.map { shard ->
                Files.size(imported.datasetDirectory.resolve(shard.contentReference))
            }
        }
        val episodesPerShard = actors.flatMap { actor ->
            actor.reimported?.manifest?.episodes
                ?.groupingBy { it.shardOrdinal }
                ?.eachCount()
                ?.values
                ?.map(Int::toLong)
                .orEmpty()
        }
        val canonicalBytes = shardBytes.sumChecked()
        val publicationEnvelopeBytes = actors.sumOf { actor ->
            actor.published?.let { treeFileBytes(it.envelopeDirectory) } ?: 0L
        }
        val completed = actors.sumOf { it.result.report.completedJobOrdinals.size }
        val failed = actors.sumOf { it.result.report.failedJobOrdinals.size }
        val partialLost = actors.sumOf { it.result.report.partialLostJobOrdinals.size }
        val publicationFailures = actors.count { it.publicationFailure }
        val runStatus = when {
            actors.any { it.result.status.state == ActorStateV1.CAPACITY_EXHAUSTED } ->
                "BLOCKED_BY_MEASURED_PREFLIGHT"
            completed == size && failed == 0 && partialLost == 0 && publicationFailures == 0 ->
                "PASS"
            else -> "FAIL"
        }
        val decisions = episodes.map { it.decisionCount.toLong() }
        val canonicalPerEpisode = episodes.map(Ka05EpisodeMeasurement::canonicalBytes)
        val bytesPerDecision = episodes.mapNotNull {
            it.decisionCount.takeIf { count -> count > 0 }?.let { count ->
                it.canonicalBytes / count.toLong()
            }
        }
        val totalDecisions = decisions.sumChecked()
        val processCpuNanos = positiveDelta(afterJvm.processCpuNanos, beforeJvm.processCpuNanos)
        val allocatedBytes = positiveDelta(afterJvm.allocatedBytes, beforeJvm.allocatedBytes)
        return Ka05ConditionReportV1(
            assignmentSize = size,
            concurrency = concurrency,
            runStatus = runStatus,
            workloadPlanIdentity = assignment.workloadPlanIdentity,
            assignmentIdentity = assignment.assignmentIdentity,
            executionAttemptIdentities = actors.map {
                it.result.report.executionAttemptIdentity.value
            }.sorted(),
            episodesAssigned = size,
            episodesCompleted = completed,
            failedEpisodes = failed,
            partialLostEpisodes = partialLost,
            publicationFailures = publicationFailures,
            gameTerminalEpisodes = episodes.count { it.closureKind == "GAME_TERMINAL" },
            interruptedEpisodes = episodes.count { it.closureKind == "INTERRUPTED" },
            decisionsTotal = totalDecisions,
            decisionsPerEpisode = decisions.summaryOrNull(),
            canonicalBytesPerEpisode = canonicalPerEpisode.summaryOrNull(),
            bytesPerDecision = bytesPerDecision.summaryOrNull(),
            shardCount = shardBytes.size,
            episodesPerShard = episodesPerShard.summaryOrNull(),
            shardBytes = shardBytes.summaryOrNull(),
            finalizedCanonicalBytes = canonicalBytes,
            publicationEnvelopeBytes = publicationEnvelopeBytes,
            peakScratchFootprintBytesAboveBaseline = sampler.peakScratchFootprintBytes,
            peakWorkingFootprintBytesAboveBaseline = sampler.peakWorkingFootprintBytes,
            scratchFootprintAmplification = ratio(sampler.peakScratchFootprintBytes, canonicalBytes),
            publicationFootprintAmplification = ratio(publicationEnvelopeBytes, canonicalBytes),
            physicalWriteAmplification = "NOT_MEASURED",
            compressionFootprint = "NONE_CURRENT_CONTRACT",
            wallTimeNanos = wallTimeNanos,
            episodesPerSecond = completed.toDouble() / wallTimeNanos.toDouble() * 1_000_000_000.0,
            decisionsPerSecond = totalDecisions.toDouble() / wallTimeNanos.toDouble() * 1_000_000_000.0,
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            processCpuNanos = processCpuNanos,
            processCpuUtilizationPercent = processCpuNanos?.let {
                it.toDouble() / wallTimeNanos.toDouble() * 100.0
            },
            heapUsedBeforeBytes = beforeJvm.heapUsedBytes,
            heapPeakBytes = sampler.peakHeapUsedBytes,
            heapUsedAfterBytes = afterJvm.heapUsedBytes,
            allocatedBytes = allocatedBytes,
            gcCollections = positiveDelta(afterJvm.gcCollections, beforeJvm.gcCollections),
            gcTimeMillis = positiveDelta(afterJvm.gcTimeMillis, beforeJvm.gcTimeMillis),
            rssBeforeBytes = beforeJvm.rssBytes,
            rssAfterBytes = afterJvm.rssBytes,
            sourceRevisionVerified = actors.all { it.result.report.sourceRevisionVerified },
            zeroUnsupported = actors.all { actor ->
                actor.result.report.diagnostics.none {
                    it.code == ActorDiagnosticCodeV1.UNSUPPORTED_PATH
                }
            },
            actorOutcomes = actors.map(::actorOutcome).sortedBy { it.actorIndex },
            semanticJobIdentities = assignment.items.map { it.semanticJobIdentity.value },
        )
    }

    private fun actorOutcome(actor: Ka05ActorCapture): Ka05ActorOutcomeV1 =
        Ka05ActorOutcomeV1(
            actorIndex = actor.result.report.executionAttemptIdentity.value
                .substringAfterLast("-a")
                .toIntOrNull() ?: -1,
            assignmentIdentity = actor.result.report.assignmentIdentity,
            executionAttemptIdentity = actor.result.report.executionAttemptIdentity.value,
            state = actor.result.status.state.name,
            expectedSourceCommit = actor.result.report.expectedSourceCommit,
            actualRuntimeSourceCommit = actor.result.report.actualRuntimeSourceCommit,
            sourceRevisionVerified = actor.result.report.sourceRevisionVerified,
            preflightStatus = actor.result.report.storagePreflight?.status?.name,
            preflightFailureCode = actor.result.report.storagePreflight?.failureCode?.name,
            completedEpisodes = actor.result.report.completedJobOrdinals.size,
            failedEpisodes = actor.result.report.failedJobOrdinals.size,
            partialLostEpisodes = actor.result.report.partialLostJobOrdinals.size,
            localShardDigests = actor.result.report.localShardDigests,
            published = actor.published != null,
            reimported = actor.reimported != null,
            publicationFailure = actor.publicationFailure,
            datasetIds = listOfNotNull(actor.reimported?.manifest?.datasetId),
            diagnosticCodes = actor.result.report.diagnostics.map { it.code.name }.distinct().sorted(),
        )

    private fun noProofAdmissionReport(
        plan: WorkloadPlanV1,
        size: Int,
        assignmentIdentity: String,
        captures: List<Ka05ConditionCapture>,
    ): Ka05OfflineAdmissionReportV1 {
        val assignment = WorkAssignmentV1.from(plan, (0 until size).toList())
        val sources = captures.flatMap { capture ->
            capture.actors.mapNotNull(Ka05ActorCapture::reimported)
        }
        val admission = OfflineAdmissionV1.admit(
            OfflineAdmissionRequestV1(assignment, sources, replayVerifier = null),
        )
        return Ka05OfflineAdmissionReportV1(
            assignmentSize = size,
            assignmentIdentity = assignmentIdentity,
            offlineReplayReverification = admission.offlineReplayReverification.name,
            datasetEligible = admission.datasetEligible,
            acceptedSources = admission.acceptedSources.size,
            membershipStates = admission.ledger.entries.groupingBy {
                it.membershipState.name
            }.eachCount().toSortedMap(),
            missingJobOrdinals = admission.ledger.entries
                .filter { it.membershipState == MembershipStateV1.MISSING }
                .map { it.jobOrdinal },
        )
    }

    private fun blockedOfflineReport(
        plan: WorkloadPlanV1,
        size: Int,
        assignmentIdentity: String,
    ): Ka05OfflineAdmissionReportV1 = Ka05OfflineAdmissionReportV1(
        assignmentSize = size,
        assignmentIdentity = assignmentIdentity,
        offlineReplayReverification = "NO_INDEPENDENT_PROOF",
        datasetEligible = false,
        acceptedSources = 0,
        membershipStates = mapOf("MISSING" to size),
        missingJobOrdinals = (0 until size).toList(),
    )

    private fun writeReport(result: Ka05RunResult) {
        val report = Ka05CharacterizationReportV1(
            version = 1,
            schemaIdentity = KA05_REPORT_SCHEMA,
            sourceCommit = result.context.sourceCommit,
            workloadPlanIdentity = result.context.plan.workloadPlanIdentity,
            policyCampaignIdentity = KA05_POLICY_IDENTITY,
            policySourceIdentity = result.context.policySourceIdentity,
            deckIdentities = mapOf(
                "Akiri" to result.context.plan.jobs.first().environmentIdentity.akiriDeckIdentity,
                "Chevill" to result.context.plan.jobs.first().environmentIdentity.chevillDeckIdentity,
            ),
            maxSteps = KA05_MAX_STEPS,
            maxShardBytes = KA05_MAX_SHARD_BYTES,
            maxEpisodesPerShard = KA05_MAX_EPISODES_PER_SHARD,
            compressionFootprint = "NONE_CURRENT_CONTRACT",
            physicalWriteAmplification = "NOT_MEASURED",
            runtime = result.runtime,
            assignmentIdentities = result.assignmentIds,
            conditions = result.conditions,
            offlineAdmission = result.offlineAdmission,
            blockedAssignmentSizes = result.blockedAssignmentSizes,
            recommendations = result.recommendations,
        )
        Files.writeString(
            result.reportPath,
            com.wingedsheep.gym.contract.A3SemanticJson.canonicalJson(
                com.wingedsheep.gym.contract.A3SemanticJson.strictJson.encodeToJsonElement(
                    Ka05CharacterizationReportV1.serializer(),
                    report,
                ),
            ) + "\n",
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun nextEstimate(current: Long, observed: Long?): Long =
        if (observed == null || observed <= 0L) current else max(current, Math.multiplyExact(observed, 2L))
}

private fun partitionItems(items: List<WorkItemV1>, concurrency: Int): List<List<WorkItemV1>> {
    require(concurrency > 0)
    val chunkSize = (items.size + concurrency - 1) / concurrency
    return items.chunked(chunkSize)
}

private fun parseBoundedList(property: String, default: List<Int>, allowed: Set<Int>): List<Int> {
    val values = (System.getProperty(property) ?: default.joinToString(","))
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::toInt)
    require(values.isNotEmpty() && values == values.distinct().sorted())
    require(values.all { it in allowed })
    return values
}

private fun positiveLongProperty(property: String, default: Long): Long =
    (System.getProperty(property)?.toLongOrNull() ?: default).also {
        require(it > 0L) { property + " must be positive" }
    }

private fun ka05RepositoryRoot(): Path =
    generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }

private fun gitHead(repositoryRoot: Path): String {
    val process = ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(repositoryRoot.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
    require(process.waitFor(30, TimeUnit.SECONDS)) { "KA05 source HEAD probe timed out" }
    require(process.exitValue() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
        "KA05 source HEAD probe failed"
    }
    return output
}

private fun isEmptyDirectory(path: Path): Boolean =
    Files.list(path).use { stream -> !stream.findAny().isPresent }

private fun treeFileBytes(root: Path): Long =
    Files.walk(root).use { stream ->
        stream
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .mapToLong { Files.size(it) }
            .reduce(0L, Math::addExact)
    }

private fun List<Long>.sumChecked(): Long = fold(0L, Math::addExact)

private fun List<Long>.summaryOrNull(): ActorMeasurementSummaryV1? =
    takeIf(List<Long>::isNotEmpty)?.let(ActorMeasurementSummaryV1::from)

private fun positiveDelta(after: Long?, before: Long?): Long? =
    if (after != null && before != null && after >= before) after - before else null

private fun ratio(numerator: Long, denominator: Long): Double? =
    denominator.takeIf { it > 0L }?.let { numerator.toDouble() / it.toDouble() }

private data class Ka05JoinKey(
    val semanticEpisodeId: String,
    val collectionJobId: String,
)

private data class Ka05PlanContext(
    val repositoryRoot: Path,
    val registry: CardRegistry,
    val resolver: DeckResolver,
    val plan: WorkloadPlanV1,
    val scheduleByOrdinal: Map<Int, A9ActorScheduleV1>,
    val policySourceIdentity: String,
    val sourceCommit: String,
    val metadata: DatasetMetadataV1,
)

private data class Ka05RunResult(
    val context: Ka05PlanContext,
    val runtime: ActorRuntimeSnapshotV1,
    val assignmentIds: Map<Int, String>,
    val conditions: List<Ka05ConditionReportV1>,
    val offlineAdmission: List<Ka05OfflineAdmissionReportV1>,
    val blockedAssignmentSizes: List<Int>,
    val recommendations: Ka05RecommendationV1,
    val reportPath: Path,
) {
    fun renderSummary(): String = buildString {
        appendLine("KA05_CHARACTERIZATION=PASS")
        appendLine("SOURCE_COMMIT=" + context.sourceCommit)
        appendLine("WORKLOAD_PLAN_ID=" + context.plan.workloadPlanIdentity)
        assignmentIds.toSortedMap().forEach { (size, identity) ->
            val status = when {
                size in blockedAssignmentSizes -> "BLOCKED_BY_MEASURED_PREFLIGHT"
                conditions.any { it.assignmentSize == size && it.runStatus == "FAIL" } -> "FAIL"
                conditions.any { it.assignmentSize == size && it.runStatus == "PASS" } -> "PASS"
                else -> "NOT_RUN"
            }
            appendLine("ASSIGNMENT_" + size + "_ID=" + identity)
            appendLine("ASSIGNMENT_" + size + "=" + status)
        }
        conditions.forEach { condition ->
            appendLine(
                "CONDITION_" + condition.assignmentSize + "_C" +
                    condition.concurrency + "=" + condition.runStatus,
            )
            appendLine(
                "CONDITION_" + condition.assignmentSize + "_C" +
                    condition.concurrency + "_EPISODES=" +
                    condition.episodesCompleted + "/" + condition.episodesAssigned,
            )
        }
        appendLine(
            "OFFLINE_REPLAY_REVERIFICATION=" +
                offlineAdmission.map { it.offlineReplayReverification }.distinct().joinToString(","),
        )
        appendLine("DATASET_ELIGIBLE=false")
        appendLine("REPORT_PATH=" + reportPath)
    }
}

@Serializable
private data class Ka05CharacterizationReportV1(
    val version: Int,
    val schemaIdentity: String,
    val sourceCommit: String,
    val workloadPlanIdentity: String,
    val policyCampaignIdentity: String,
    val policySourceIdentity: String,
    val deckIdentities: Map<String, String>,
    val maxSteps: Int,
    val maxShardBytes: Long,
    val maxEpisodesPerShard: Int,
    val compressionFootprint: String,
    val physicalWriteAmplification: String,
    val runtime: ActorRuntimeSnapshotV1,
    val assignmentIdentities: Map<Int, String>,
    val conditions: List<Ka05ConditionReportV1>,
    val offlineAdmission: List<Ka05OfflineAdmissionReportV1>,
    val blockedAssignmentSizes: List<Int>,
    val recommendations: Ka05RecommendationV1,
)

@Serializable
private data class Ka05ConditionReportV1(
    val assignmentSize: Int,
    val concurrency: Int,
    val runStatus: String,
    val workloadPlanIdentity: String,
    val assignmentIdentity: String,
    val executionAttemptIdentities: List<String>,
    val episodesAssigned: Int,
    val episodesCompleted: Int,
    val failedEpisodes: Int,
    val partialLostEpisodes: Int,
    val publicationFailures: Int,
    val gameTerminalEpisodes: Int,
    val interruptedEpisodes: Int,
    val decisionsTotal: Long,
    val decisionsPerEpisode: ActorMeasurementSummaryV1?,
    val canonicalBytesPerEpisode: ActorMeasurementSummaryV1?,
    val bytesPerDecision: ActorMeasurementSummaryV1?,
    val shardCount: Int,
    val episodesPerShard: ActorMeasurementSummaryV1?,
    val shardBytes: ActorMeasurementSummaryV1?,
    val finalizedCanonicalBytes: Long,
    val publicationEnvelopeBytes: Long,
    val peakScratchFootprintBytesAboveBaseline: Long,
    val peakWorkingFootprintBytesAboveBaseline: Long,
    val scratchFootprintAmplification: Double?,
    val publicationFootprintAmplification: Double?,
    val physicalWriteAmplification: String,
    val compressionFootprint: String,
    val wallTimeNanos: Long,
    val episodesPerSecond: Double,
    val decisionsPerSecond: Double,
    val availableProcessors: Int,
    val processCpuNanos: Long?,
    val processCpuUtilizationPercent: Double?,
    val heapUsedBeforeBytes: Long,
    val heapPeakBytes: Long,
    val heapUsedAfterBytes: Long,
    val allocatedBytes: Long?,
    val gcCollections: Long?,
    val gcTimeMillis: Long?,
    val rssBeforeBytes: Long?,
    val rssAfterBytes: Long?,
    val sourceRevisionVerified: Boolean,
    val zeroUnsupported: Boolean,
    val actorOutcomes: List<Ka05ActorOutcomeV1>,
    val semanticJobIdentities: List<String>,
)

@Serializable
private data class Ka05ActorOutcomeV1(
    val actorIndex: Int,
    val assignmentIdentity: String,
    val executionAttemptIdentity: String,
    val state: String,
    val expectedSourceCommit: String,
    val actualRuntimeSourceCommit: String?,
    val sourceRevisionVerified: Boolean,
    val preflightStatus: String?,
    val preflightFailureCode: String?,
    val completedEpisodes: Int,
    val failedEpisodes: Int,
    val partialLostEpisodes: Int,
    val localShardDigests: List<String>,
    val published: Boolean,
    val reimported: Boolean,
    val publicationFailure: Boolean,
    val datasetIds: List<String>,
    val diagnosticCodes: List<String>,
)

@Serializable
private data class Ka05OfflineAdmissionReportV1(
    val assignmentSize: Int,
    val assignmentIdentity: String,
    val offlineReplayReverification: String,
    val datasetEligible: Boolean,
    val acceptedSources: Int,
    val membershipStates: Map<String, Int>,
    val missingJobOrdinals: List<Int>,
)

@Serializable
private data class Ka05RecommendationV1(
    val measured: String,
    val derived: String,
    val hypothesis: String,
)

private data class Ka05ConditionCapture(
    val assignment: WorkAssignmentV1,
    val actors: List<Ka05ActorCapture>,
    val report: Ka05ConditionReportV1,
)

private data class Ka05ActorCapture(
    val result: com.wingedsheep.gym.trainer.actor.ActorRunResult,
    val timings: List<Ka05EpisodeTiming>,
    val published: com.wingedsheep.gym.trainer.actor.LocalPublishedEnvelopeV1?,
    val reimported: com.wingedsheep.gym.trainer.actor.ReimportedLocalPublicationEnvelopeV1?,
    val publicationFailure: Boolean,
)

private data class Ka05EpisodeTiming(
    val jobOrdinal: Int,
    val elapsedNanos: Long,
)

private data class Ka05EpisodeMeasurement(
    val jobOrdinal: Int,
    val decisionCount: Int,
    val closureKind: String,
    val canonicalBytes: Long,
    val elapsedNanos: Long,
)

private data class Ka05JvmSnapshot(
    val processCpuNanos: Long?,
    val allocatedBytes: Long?,
    val heapUsedBytes: Long,
    val gcCollections: Long?,
    val gcTimeMillis: Long?,
    val rssBytes: Long?,
) {
    companion object {
        fun capture(): Ka05JvmSnapshot {
            val operatingSystem = ManagementFactory.getOperatingSystemMXBean() as?
                com.sun.management.OperatingSystemMXBean
            val thread = ManagementFactory.getThreadMXBean() as?
                com.sun.management.ThreadMXBean
            val allocated = thread?.let {
                if (!it.isThreadAllocatedMemorySupported) {
                    null
                } else {
                    runCatching {
                        if (!it.isThreadAllocatedMemoryEnabled) {
                            it.isThreadAllocatedMemoryEnabled = true
                        }
                        it.getThreadAllocatedBytes(it.allThreadIds)
                            .filter { bytes -> bytes >= 0L }
                            .fold(0L, Math::addExact)
                    }.getOrNull()
                }
            }
            val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
            val gcCollections = gcBeans.map { it.collectionCount }
                .takeIf { values -> values.all { it >= 0L } }
                ?.fold(0L, Math::addExact)
            val gcTimeMillis = gcBeans.map { it.collectionTime }
                .takeIf { values -> values.all { it >= 0L } }
                ?.fold(0L, Math::addExact)
            return Ka05JvmSnapshot(
                processCpuNanos = operatingSystem?.processCpuTime?.takeIf { it >= 0L },
                allocatedBytes = allocated,
                heapUsedBytes = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                    .coerceAtLeast(0L),
                gcCollections = gcCollections,
                gcTimeMillis = gcTimeMillis,
                rssBytes = currentRssBytes(),
            )
        }
    }
}

private class Ka05ResourceSampler(
    private val workingRoot: Path,
    private val scratchRoot: Path,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val minWorkingFreeBytes = AtomicLong(Long.MAX_VALUE)
    private val minScratchFreeBytes = AtomicLong(Long.MAX_VALUE)
    private val peakHeap = AtomicLong(0L)
    val baselineWorkingFreeBytes: Long = freeBytes(workingRoot)
    val baselineScratchFreeBytes: Long = freeBytes(scratchRoot)
    var startedAtNanos: Long = 0L
        private set
    var finishedAtNanos: Long = 0L
        private set
    val peakScratchFootprintBytes: Long
        get() = (baselineScratchFreeBytes - minScratchFreeBytes.get().coerceAtMost(
            baselineScratchFreeBytes,
        )).coerceAtLeast(0L)
    val peakWorkingFootprintBytes: Long
        get() = (baselineWorkingFreeBytes - minWorkingFreeBytes.get().coerceAtMost(
            baselineWorkingFreeBytes,
        )).coerceAtLeast(0L)
    val peakHeapUsedBytes: Long
        get() = peakHeap.get().coerceAtLeast(0L)

    private var samplerThread: Thread? = null

    fun start() {
        startedAtNanos = System.nanoTime()
        running.set(true)
        sample()
        samplerThread = Thread({
            while (running.get()) {
                sample()
                try {
                    Thread.sleep(100L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "ka05-resource-sampler").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun close() {
        running.set(false)
        samplerThread?.interrupt()
        samplerThread?.join(2_000L)
        sample()
        finishedAtNanos = System.nanoTime()
    }

    private fun sample() {
        runCatching {
            minWorkingFreeBytes.updateAndGet { minOf(it, freeBytes(workingRoot)) }
            minScratchFreeBytes.updateAndGet { minOf(it, freeBytes(scratchRoot)) }
            peakHeap.updateAndGet {
                maxOf(it, ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
            }
        }
    }
}

private fun freeBytes(root: Path): Long = Files.getFileStore(root).usableSpace

private fun currentRssBytes(): Long? {
    val status = Path.of("/proc/self/status")
    if (!Files.isRegularFile(status)) return null
    return runCatching {
        Files.readAllLines(status).firstNotNullOfOrNull { line ->
            Regex("^VmRSS:\\s+(\\d+)\\s+kB$").matchEntire(line.trim())
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?.let { Math.multiplyExact(it, 1024L) }
        }
    }.getOrNull()
}

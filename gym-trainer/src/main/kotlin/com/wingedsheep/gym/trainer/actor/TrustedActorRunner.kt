package com.wingedsheep.gym.trainer.actor

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryAdmissionResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1StorageCodec
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Writer
import kotlinx.serialization.Serializable

@Serializable
data class AcceptedSemanticJobClaimV1(
    val semanticJobIdentity: SemanticJobIdentityV1,
    val expectedSemanticEpisodeId: String,
    val expectedCollectionJobId: String,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val trajectoryId: String,
    val episodeContentDigest: String,
    val closureKind: EpisodeClosureV1.Kind,
    val trajectorySchemaIdentity: String,
    val observationSchemaIdentity: String,
    val actionDomainSchemaIdentity: String,
    val replaySchemaIdentity: String,
) {
    init {
        require(expectedSemanticEpisodeId == semanticEpisodeId) {
            "Accepted claim semantic episode identity does not match expectation"
        }
        require(expectedCollectionJobId == collectionJobId) {
            "Accepted claim collection-job identity does not match expectation"
        }
    }
}

sealed interface SemanticJobClaimResult {
    data class Accepted(val claim: AcceptedSemanticJobClaimV1) : SemanticJobClaimResult
    data class IdenticalDuplicate(
        val existing: AcceptedSemanticJobClaimV1,
        val duplicate: AcceptedSemanticJobClaimV1,
    ) : SemanticJobClaimResult

    data class Conflict(
        val existing: AcceptedSemanticJobClaimV1,
        val conflicting: AcceptedSemanticJobClaimV1,
    ) : SemanticJobClaimResult
}

/**
 * In-memory duplicate/conflict gate for already accepted B2 trajectory results. A later immutable
 * membership ledger may persist the same claim model; this registry never selects between conflicts.
 */
class AcceptedSemanticJobRegistry {
    private val claimsBySemanticJob =
        linkedMapOf<String, MutableList<AcceptedSemanticJobClaimV1>>()

    fun accept(item: WorkItemV1, trajectory: TrajectoryV1): SemanticJobClaimResult {
        require(trajectory.semanticEpisodeId == item.expectedSemanticEpisodeId) {
            "Trajectory semantic episode identity does not match the WorkItem"
        }
        require(trajectory.collectionJobId == item.expectedCollectionJobId) {
            "Trajectory collection-job identity does not match the WorkItem"
        }
        require(trajectory.episodeMetadata.environmentIdentity == item.environmentIdentity) {
            "Trajectory environment identity does not match the WorkItem"
        }
        require(trajectory.episodeMetadata.policyProvenance == item.policyProvenance) {
            "Trajectory policy provenance does not match the WorkItem"
        }

        val claim = AcceptedSemanticJobClaimV1(
            semanticJobIdentity = item.semanticJobIdentity,
            expectedSemanticEpisodeId = item.expectedSemanticEpisodeId,
            expectedCollectionJobId = item.expectedCollectionJobId,
            semanticEpisodeId = trajectory.semanticEpisodeId,
            collectionJobId = trajectory.collectionJobId,
            trajectoryId = trajectory.trajectoryId,
            episodeContentDigest = TrajectoryV1StorageCodec.episodeContentDigest(trajectory),
            closureKind = trajectory.closure.kind,
            trajectorySchemaIdentity = trajectory.schemaIdentity,
            observationSchemaIdentity = trajectory.episodeMetadata.environmentIdentity.observationSchemaIdentity,
            actionDomainSchemaIdentity = trajectory.episodeMetadata.environmentIdentity.actionDomainSchemaIdentity,
            replaySchemaIdentity = trajectory.episodeMetadata.environmentIdentity.replaySchemaIdentity,
        )
        val claims = claimsBySemanticJob.getOrPut(item.semanticJobIdentity.value) {
            mutableListOf()
        }
        if (claims.isEmpty()) {
            claims += claim
            return SemanticJobClaimResult.Accepted(claim)
        }
        return if (claims.all { it == claim }) {
            claims += claim
            SemanticJobClaimResult.IdenticalDuplicate(existing = claims.first(), duplicate = claim)
        } else {
            claims += claim
            SemanticJobClaimResult.Conflict(existing = claims.first(), conflicting = claim)
        }
    }

    fun claimsFor(semanticJobIdentity: SemanticJobIdentityV1): List<AcceptedSemanticJobClaimV1> =
        claimsBySemanticJob[semanticJobIdentity.value]?.toList().orEmpty()
}

sealed interface ActorEpisodeOutcome {
    data class Completed(
        val trajectory: TrajectoryV1,
        val replayTrajectoryBinding: ReplayTrajectoryBindingV1,
    ) : ActorEpisodeOutcome

    data class Failed(
        val diagnostics: List<ActorDiagnosticV1>,
    ) : ActorEpisodeOutcome

    data class PartialLost(
        val diagnostics: List<ActorDiagnosticV1>,
    ) : ActorEpisodeOutcome
}

fun interface ActorEpisodeExecutor {
    fun execute(item: WorkItemV1): ActorEpisodeOutcome
}

interface B2TrajectorySink : AutoCloseable {
    fun appendEpisode(
        localEpisodeOrdinal: Int,
        trajectory: TrajectoryV1,
        replayTrajectoryBinding: ReplayTrajectoryBindingV1,
    ): TrajectoryAdmissionResult

    fun finalizeDataset(): DatasetManifestV1
}

class TrajectoryV1B2Sink(
    private val writer: TrajectoryV1Writer,
) : B2TrajectorySink {
    override fun appendEpisode(
        localEpisodeOrdinal: Int,
        trajectory: TrajectoryV1,
        replayTrajectoryBinding: ReplayTrajectoryBindingV1,
    ): TrajectoryAdmissionResult =
        writer.appendEpisode(localEpisodeOrdinal, trajectory, replayTrajectoryBinding)

    override fun finalizeDataset(): DatasetManifestV1 = writer.finalizeDataset()

    override fun close() = writer.close()
}

data class ActorRunResult(
    val status: ActorStatusV1,
    val report: RunReportV1,
    val manifest: DatasetManifestV1?,
)

/**
 * Provider-neutral whole-episode runner. The executor owns the existing trusted Gym/replay
 * composition; this class only owns assignment order, local B2 admission, status, and failure
 * boundaries.
 */
class TrustedActorRunner(
    private val assignment: WorkAssignmentV1,
    private val executionAttemptIdentity: ExecutionAttemptIdentityV1,
    private val preflight: () -> StoragePreflightResultV1,
    private val episodeExecutor: ActorEpisodeExecutor,
    private val sinkFactory: () -> B2TrajectorySink,
    private val statusSink: ActorStatusSink? = null,
    private val clock: ActorClock = SystemActorClock,
    private val requestedConcurrency: Int = 1,
    private val actualConcurrency: Int = 1,
) {
    init {
        require(requestedConcurrency > 0) { "Requested concurrency must be positive" }
        require(actualConcurrency > 0) { "Actual concurrency must be positive" }
    }

    fun run(): ActorRunResult {
        val tracker = ActorStatusTracker(
            assignmentIdentity = assignment.assignmentIdentity,
            executionAttemptIdentity = executionAttemptIdentity,
            clock = clock,
            sink = statusSink,
        )
        tracker.assign(assignment.items.size)
        val planned = assignment.items.map(WorkItemV1::jobOrdinal)
        val started = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val failed = mutableListOf<Int>()
        val partialLost = mutableListOf<Int>()
        val diagnostics = mutableListOf<ActorDiagnosticV1>()
        var preflightResult: StoragePreflightResultV1? = null
        var sink: B2TrajectorySink? = null

        fun report(): RunReportV1 = RunReportV1(
            assignmentIdentity = assignment.assignmentIdentity,
            executionAttemptIdentity = executionAttemptIdentity,
            finalState = tracker.snapshot().state,
            sourceCommit = assignment.items.first().environmentIdentity.engineCommit,
            actorStartTime = tracker.snapshot().actorStartTime,
            actorEndTime = clock.utcNow(),
            plannedJobOrdinals = planned,
            startedJobOrdinals = started.sorted(),
            completedJobOrdinals = completed.sorted(),
            failedJobOrdinals = failed.sorted(),
            partialLostJobOrdinals = partialLost.sorted(),
            localShardDigests = emptyList(),
            diagnostics = diagnostics.toList(),
            storagePreflight = preflightResult,
            requestedConcurrency = requestedConcurrency,
            actualConcurrency = actualConcurrency,
        )

        try {
            val evaluatedPreflight = preflight()
            preflightResult = evaluatedPreflight
            if (evaluatedPreflight.status != StoragePreflightStatus.PASS) {
                val code = when (evaluatedPreflight.failureCode) {
                    StoragePreflightFailureCode.INSUFFICIENT_SCRATCH_BUDGET ->
                        ActorDiagnosticCodeV1.INSUFFICIENT_SCRATCH_BUDGET

                    StoragePreflightFailureCode.INSUFFICIENT_PUBLISH_BUDGET ->
                        ActorDiagnosticCodeV1.INSUFFICIENT_WORKING_BUDGET

                    StoragePreflightFailureCode.PROVIDER_BUDGET_UNAVAILABLE,
                    null,
                    -> ActorDiagnosticCodeV1.PROVIDER_BUDGET_UNAVAILABLE
                }
                val diagnostic = ActorDiagnosticV1(
                    code = code,
                    severity = ActorDiagnosticSeverityV1.FATAL,
                )
                diagnostics += diagnostic
                tracker.fail(ActorStateV1.CAPACITY_EXHAUSTED, diagnostic)
                return ActorRunResult(tracker.snapshot(), report(), null)
            }

            tracker.milestone(ActorStateV1.PREFLIGHT_VERIFIED)
            sink = sinkFactory()
            for ((localOrdinal, item) in assignment.items.withIndex()) {
                started += item.jobOrdinal
                tracker.jobStarted(item)
                val outcome = try {
                    episodeExecutor.execute(item)
                } catch (_: Exception) {
                    ActorEpisodeOutcome.Failed(
                        diagnostics = listOf(
                            ActorDiagnosticV1(
                                code = ActorDiagnosticCodeV1.ACTOR_EXECUTOR_FAILURE,
                                severity = ActorDiagnosticSeverityV1.ERROR,
                                jobOrdinal = item.jobOrdinal,
                                semanticJobIdentity = item.semanticJobIdentity.value,
                            ),
                        ),
                    )
                }
                when (outcome) {
                    is ActorEpisodeOutcome.Completed -> {
                        val trajectory = outcome.trajectory
                        val identityFailure = identityFailure(item, trajectory)
                        if (identityFailure != null) {
                            diagnostics += identityFailure
                            failed += item.jobOrdinal
                            tracker.fail(ActorStateV1.FAILED, identityFailure)
                            return ActorRunResult(tracker.snapshot(), report(), null)
                        }
                        tracker.episodeClosed(trajectory.decisions.size)
                        val verification = outcome.replayTrajectoryBinding
                            .verificationBinding
                            .verification
                        val replayFailure = when {
                            verification.fidelity == ReplayFidelity.DIVERGED ->
                                ActorDiagnosticCodeV1.REPLAY_DIVERGED

                            !verification.completeRangeVerified ->
                                ActorDiagnosticCodeV1.REPLAY_INCOMPLETE

                            else -> null
                        }
                        if (replayFailure != null) {
                            val diagnostic = ActorDiagnosticV1(
                                code = replayFailure,
                                severity = ActorDiagnosticSeverityV1.ERROR,
                                jobOrdinal = item.jobOrdinal,
                                semanticJobIdentity = item.semanticJobIdentity.value,
                            )
                            diagnostics += diagnostic
                            failed += item.jobOrdinal
                            tracker.fail(ActorStateV1.FAILED, diagnostic)
                            return ActorRunResult(tracker.snapshot(), report(), null)
                        }
                        val admission: TrajectoryAdmissionResult = try {
                            checkNotNull(sink).appendEpisode(
                                localEpisodeOrdinal = localOrdinal,
                                trajectory = trajectory,
                                replayTrajectoryBinding = outcome.replayTrajectoryBinding,
                            )
                        } catch (_: Exception) {
                            val diagnostic = ActorDiagnosticV1(
                                code = ActorDiagnosticCodeV1.B2_PUBLISHER_FAILURE,
                                severity = ActorDiagnosticSeverityV1.ERROR,
                                jobOrdinal = item.jobOrdinal,
                                semanticJobIdentity = item.semanticJobIdentity.value,
                            )
                            diagnostics += diagnostic
                            failed += item.jobOrdinal
                            tracker.fail(ActorStateV1.FAILED, diagnostic)
                            return ActorRunResult(tracker.snapshot(), report(), null)
                        }
                        when (admission) {
                            is TrajectoryAdmissionResult.Admitted -> {
                                completed += item.jobOrdinal
                                tracker.replayVerified()
                                tracker.episodeAdmittedLocal()
                            }

                            is TrajectoryAdmissionResult.Quarantined -> {
                                val diagnostic = ActorDiagnosticV1(
                                    code = ActorDiagnosticCodeV1.LOCAL_ADMISSION_FAILURE,
                                    severity = ActorDiagnosticSeverityV1.ERROR,
                                    jobOrdinal = item.jobOrdinal,
                                    semanticJobIdentity = item.semanticJobIdentity.value,
                                )
                                diagnostics += diagnostic
                                failed += item.jobOrdinal
                                tracker.fail(ActorStateV1.FAILED, diagnostic)
                                return ActorRunResult(tracker.snapshot(), report(), null)
                            }
                        }
                    }

                    is ActorEpisodeOutcome.Failed -> {
                        val episodeDiagnostics = outcome.diagnostics.ifEmpty {
                            listOf(
                                ActorDiagnosticV1(
                                    code = ActorDiagnosticCodeV1.ACTOR_EXECUTOR_FAILURE,
                                    severity = ActorDiagnosticSeverityV1.ERROR,
                                ),
                            )
                        }.map { diagnostic ->
                            diagnostic.copy(
                                jobOrdinal = diagnostic.jobOrdinal ?: item.jobOrdinal,
                                semanticJobIdentity =
                                    diagnostic.semanticJobIdentity ?: item.semanticJobIdentity.value,
                            )
                        }
                        diagnostics += episodeDiagnostics
                        failed += item.jobOrdinal
                        tracker.fail(ActorStateV1.FAILED, episodeDiagnostics.first())
                        return ActorRunResult(tracker.snapshot(), report(), null)
                    }

                    is ActorEpisodeOutcome.PartialLost -> {
                        val episodeDiagnostics = outcome.diagnostics.ifEmpty {
                            listOf(
                                ActorDiagnosticV1(
                                    code = ActorDiagnosticCodeV1.PROVIDER_PROCESS_LOST,
                                    severity = ActorDiagnosticSeverityV1.ERROR,
                                ),
                            )
                        }.map { diagnostic ->
                            diagnostic.copy(
                                jobOrdinal = diagnostic.jobOrdinal ?: item.jobOrdinal,
                                semanticJobIdentity =
                                    diagnostic.semanticJobIdentity ?: item.semanticJobIdentity.value,
                            )
                        }
                        diagnostics += episodeDiagnostics
                        partialLost += item.jobOrdinal
                        tracker.fail(ActorStateV1.PARTIAL_LOST, episodeDiagnostics.first())
                        return ActorRunResult(tracker.snapshot(), report(), null)
                    }
                }
            }

            val manifest = checkNotNull(sink).finalizeDataset()
            tracker.shardStaged()
            tracker.shardValidated()
            tracker.finalizedLocal(manifest)
            return ActorRunResult(tracker.snapshot(), report().copy(
                localShardDigests = manifest.shards.map { it.contentDigest },
            ), manifest)
        } catch (_: Exception) {
            val diagnostic = ActorDiagnosticV1(
                code = ActorDiagnosticCodeV1.B2_PUBLISHER_FAILURE,
                severity = ActorDiagnosticSeverityV1.FATAL,
            )
            diagnostics += diagnostic
            tracker.fail(ActorStateV1.FAILED, diagnostic)
            return ActorRunResult(tracker.snapshot(), report(), null)
        } finally {
            try {
                sink?.close()
            } catch (_: Exception) {
                // The B2 result is already classified; close failures cannot create trust.
            }
        }
    }

    private fun identityFailure(
        item: WorkItemV1,
        trajectory: TrajectoryV1,
    ): ActorDiagnosticV1? = when {
        trajectory.episodeMetadata.environmentIdentity != item.environmentIdentity ->
            ActorDiagnosticV1(
                code = ActorDiagnosticCodeV1.WORKLOAD_PLAN_RESOLUTION_MISMATCH,
                severity = ActorDiagnosticSeverityV1.ERROR,
                jobOrdinal = item.jobOrdinal,
                semanticJobIdentity = item.semanticJobIdentity.value,
            )

        trajectory.episodeMetadata.policyProvenance != item.policyProvenance ->
            ActorDiagnosticV1(
                code = ActorDiagnosticCodeV1.POLICY_CAMPAIGN_MISMATCH,
                severity = ActorDiagnosticSeverityV1.ERROR,
                jobOrdinal = item.jobOrdinal,
                semanticJobIdentity = item.semanticJobIdentity.value,
            )

        trajectory.semanticEpisodeId != item.expectedSemanticEpisodeId ->
            ActorDiagnosticV1(
                code = ActorDiagnosticCodeV1.EXPECTED_EPISODE_ID_MISMATCH,
                severity = ActorDiagnosticSeverityV1.ERROR,
                jobOrdinal = item.jobOrdinal,
                semanticJobIdentity = item.semanticJobIdentity.value,
            )

        trajectory.collectionJobId != item.expectedCollectionJobId ->
            ActorDiagnosticV1(
                code = ActorDiagnosticCodeV1.EXPECTED_COLLECTION_ID_MISMATCH,
                severity = ActorDiagnosticSeverityV1.ERROR,
                jobOrdinal = item.jobOrdinal,
                semanticJobIdentity = item.semanticJobIdentity.value,
            )

        trajectory.semanticEpisodeId !=
            trajectory.episodeMetadata.recomputeSemanticEpisodeId() ->
            ActorDiagnosticV1(
                code = ActorDiagnosticCodeV1.EXPECTED_EPISODE_ID_MISMATCH,
                severity = ActorDiagnosticSeverityV1.ERROR,
                jobOrdinal = item.jobOrdinal,
                semanticJobIdentity = item.semanticJobIdentity.value,
            )

        trajectory.collectionJobId !=
            trajectory.episodeMetadata.recomputeCollectionJobId() ->
            ActorDiagnosticV1(
                code = ActorDiagnosticCodeV1.EXPECTED_COLLECTION_ID_MISMATCH,
                severity = ActorDiagnosticSeverityV1.ERROR,
                jobOrdinal = item.jobOrdinal,
                semanticJobIdentity = item.semanticJobIdentity.value,
            )

        else -> null
    }
}

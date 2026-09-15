package com.wingedsheep.gym.trainer.actor

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.EpisodeInterruptionReason
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.ReplayChosenInputBindingV1
import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.ReplayVerificationBindingV1
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.VerifiedReplayFrame
import com.wingedsheep.gym.contract.VerifiedReplayVerification
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.trainer.trajectory.CompactReplayLinkV1
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gym.trainer.trajectory.EpisodeMetadataV1
import com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1
import com.wingedsheep.gym.trainer.trajectory.PolicyProvenanceV1
import com.wingedsheep.gym.trainer.trajectory.RosterSeatV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryAdmissionResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Identity
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files

class ActorControlPlaneTest : FunSpec({
    test("workload plan resolves authoritative work items and content identity") {
        val plan = testPlan()
        val item = plan.resolve(0)

        item.jobOrdinal shouldBe 0
        item.environmentIdentity shouldBe testEnvironment(11)
        item.policyProvenance shouldBe testPolicy(21)
        item.expectedSemanticEpisodeId shouldBe
            TrajectoryV1Identity.semanticEpisodeId(item.environmentIdentity)
        item.expectedCollectionJobId shouldBe
            TrajectoryV1Identity.collectionJobId(
                item.expectedSemanticEpisodeId,
                item.policyProvenance,
            )
        item.semanticJobIdentity.value shouldBe
            SemanticJobIdentityV1.from(plan, item.jobOrdinal).value
        plan.workloadPlanIdentity shouldBe plan.recomputeWorkloadPlanIdentity()
    }

    test("changing the policy campaign changes the workload and semantic job identities") {
        val original = testPlan()
        val changedPolicy = testPolicy(22, "policy-b")
        val changed = original.copy(
            behaviorPolicyCampaignIdentity = changedPolicy.behaviorPolicyIdentity,
            opponentPolicyCampaignIdentity = changedPolicy.opponentPolicyIdentity,
            jobs = original.jobs.map { it.copy(policyProvenance = changedPolicy) },
        )

        changed.workloadPlanIdentity shouldNotBe original.workloadPlanIdentity
        changed.resolve(0).semanticJobIdentity shouldNotBe original.resolve(0).semanticJobIdentity
        changed.resolve(0).expectedCollectionJobId shouldNotBe
            original.resolve(0).expectedCollectionJobId
    }

    test("exact assignment retry keeps assignment identity while a subset changes it") {
        val plan = testPlan()
        val original = WorkAssignmentV1.from(plan, listOf(0, 1))
        val exactRetry = WorkAssignmentV1.from(plan, listOf(0, 1))
        val subsetRetry = WorkAssignmentV1.from(plan, listOf(1))

        exactRetry.assignmentIdentity shouldBe original.assignmentIdentity
        subsetRetry.assignmentIdentity shouldNotBe original.assignmentIdentity
        exactRetry.items.map { it.jobOrdinal } shouldContainExactly listOf(0, 1)
        subsetRetry.items.single().jobOrdinal shouldBe 1
        ExecutionAttemptIdentityV1("attempt-a") shouldNotBe
            ExecutionAttemptIdentityV1("attempt-b")
    }

    test("assignment rejects unordered, duplicated, or plan-mismatched items") {
        val plan = testPlan()
        shouldThrow<IllegalArgumentException> {
            WorkAssignmentV1.from(plan, listOf(1, 0))
        }
        shouldThrow<IllegalArgumentException> {
            WorkAssignmentV1.from(plan, listOf(0, 0))
        }

        val expected = plan.resolve(0)
        shouldThrow<IllegalArgumentException> {
            WorkAssignmentV1(
                workloadPlan = plan,
                items = listOf(
                    expected.copy(
                        environmentIdentity = expected.environmentIdentity.copy(actualEngineSeed = 999),
                    ),
                ),
            )
        }
    }

    test("strict assignment JSON rejects provider and attempt fields") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val root = A3SemanticJson.strictJson.parseToJsonElement(
            ActorWorkloadV1Json.encode(assignment),
        ).jsonObject
        val withProvider = JsonObject(
            root.entries.associate { it.key to it.value } +
                ("provider" to JsonPrimitive("KAGGLE")),
        )
        val withAttempt = JsonObject(
            root.entries.associate { it.key to it.value } +
                ("executionAttemptIdentity" to JsonPrimitive("attempt")),
        )

        ActorWorkloadV1Json.decodeAndValidateAssignment(withProvider.toString())
            .shouldBeInstanceOf<ActorContractValidationResult.Rejected>()
        ActorWorkloadV1Json.decodeAndValidateAssignment(withAttempt.toString())
            .shouldBeInstanceOf<ActorContractValidationResult.Rejected>()
        ActorWorkloadV1Json.decodeAndValidateAssignment(
            ActorWorkloadV1Json.encode(assignment).replaceFirst("{", "{\n"),
        ).shouldBeInstanceOf<ActorContractValidationResult.Rejected>()

        val planRoot = A3SemanticJson.strictJson.parseToJsonElement(
            ActorWorkloadV1Json.encode(testPlan()),
        ).jsonObject
        val futurePlan = JsonObject(
            planRoot.entries.associate { it.key to it.value } +
                ("version" to JsonPrimitive(2)),
        )
        shouldThrow<IllegalArgumentException> {
            ActorWorkloadV1Json.decodePlan(futurePlan.toString())
        }
    }

    test("accepted semantic job claims deduplicate only exact accepted content") {
        val plan = testPlan()
        val item = plan.resolve(0)
        val first = testTrajectory(item, closureReason = EpisodeInterruptionReason.HORIZON_REACHED)
        val identical = first.copy()
        val registry = AcceptedSemanticJobRegistry()

        registry.accept(item, first).shouldBeInstanceOf<SemanticJobClaimResult.Accepted>()
        registry.accept(item, identical)
            .shouldBeInstanceOf<SemanticJobClaimResult.IdenticalDuplicate>()
        registry.claimsFor(item.semanticJobIdentity).size shouldBe 2
        registry.accept(
            item,
            testTrajectory(item, closureReason = EpisodeInterruptionReason.CALLER_CANCELLED),
        ).shouldBeInstanceOf<SemanticJobClaimResult.Conflict>()
        registry.claimsFor(item.semanticJobIdentity).size shouldBe 3
        registry.accept(item, first).shouldBeInstanceOf<SemanticJobClaimResult.Conflict>()
        registry.claimsFor(item.semanticJobIdentity).size shouldBe 4
    }

    test("storage preflight accounts for additional scratch without double counting bootstrap") {
        val request = StoragePreflightRequestV1(
            measuredWorkingFreeBytes = 10_000,
            measuredScratchFreeBytes = 8_000,
            estimatedFinalizedOutputBytes = 5_000,
            requiredAdditionalScratchBytes = 2_000,
            publicationOverheadBytes = 500,
            workingSafetyReserveBytes = 1_000,
            providerSafetyReserveBytes = 500,
            providerOutputBudgetRemainingBytes = 7_000,
        )

        val result = StoragePreflight.evaluate(request)

        result.status shouldBe StoragePreflightStatus.PASS
        result.publishBudgetBytes shouldBe 6_500
        result.requiredAdditionalScratchBytes shouldBe 2_000
    }

    test("storage preflight fails closed when provider budget is unavailable or space is insufficient") {
        val base = StoragePreflightRequestV1(
            measuredWorkingFreeBytes = 10_000,
            measuredScratchFreeBytes = 8_000,
            estimatedFinalizedOutputBytes = 5_000,
            requiredAdditionalScratchBytes = 2_000,
            publicationOverheadBytes = 500,
            workingSafetyReserveBytes = 1_000,
            providerSafetyReserveBytes = 500,
        )

        StoragePreflight.evaluate(base).failureCode shouldBe
            StoragePreflightFailureCode.PROVIDER_BUDGET_UNAVAILABLE
        StoragePreflight.evaluate(
            base.copy(providerOutputBudgetRemainingBytes = 7_000, measuredScratchFreeBytes = 1_999),
        ).failureCode shouldBe StoragePreflightFailureCode.INSUFFICIENT_SCRATCH_BUDGET
        StoragePreflight.evaluate(
            base.copy(providerOutputBudgetRemainingBytes = 5_400),
        ).failureCode shouldBe StoragePreflightFailureCode.INSUFFICIENT_PUBLISH_BUDGET
    }

    test("storage preflight saturates Long arithmetic and rejects an exceeded provider cap") {
        val overflow = StoragePreflight.evaluate(
            StoragePreflightRequestV1(
                measuredWorkingFreeBytes = 0,
                measuredScratchFreeBytes = 1,
                estimatedFinalizedOutputBytes = 1,
                requiredAdditionalScratchBytes = 0,
                publicationOverheadBytes = Long.MAX_VALUE,
                workingSafetyReserveBytes = Long.MAX_VALUE,
                providerSafetyReserveBytes = 0,
                providerOutputBudgetRemainingBytes = Long.MAX_VALUE,
            ),
        )

        overflow.status shouldBe StoragePreflightStatus.REJECTED
        overflow.failureCode shouldBe StoragePreflightFailureCode.INSUFFICIENT_PUBLISH_BUDGET
        overflow.workingFilesystemBudgetBytes shouldBe 0
        overflow.providerBudgetBytes shouldBe Long.MAX_VALUE
        overflow.publishBudgetBytes shouldBe 0

        val exceededCap = StoragePreflight.evaluate(
            StoragePreflightRequestV1(
                measuredWorkingFreeBytes = Long.MAX_VALUE,
                measuredScratchFreeBytes = 1,
                estimatedFinalizedOutputBytes = 1,
                requiredAdditionalScratchBytes = 0,
                publicationOverheadBytes = 0,
                workingSafetyReserveBytes = 0,
                providerSafetyReserveBytes = 0,
                configuredProviderOutputCapBytes = 10,
                providerExistingOrPlannedOutputBytes = 11,
            ),
        )

        exceededCap.status shouldBe StoragePreflightStatus.REJECTED
        exceededCap.failureCode shouldBe StoragePreflightFailureCode.INSUFFICIENT_PUBLISH_BUDGET
        exceededCap.providerBudgetBytes shouldBe 0
        exceededCap.publishBudgetBytes shouldBe 0
    }

    test("status progress is monotonic and physical persistence is milestone based") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val clock = FakeActorClock()
        val sink = RecordingStatusSink()
        val tracker = ActorStatusTracker(
            assignmentIdentity = assignment.assignmentIdentity,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-a"),
            clock = clock,
            sink = sink,
            heartbeatIntervalNanos = 10,
        )
        tracker.assign(1)

        tracker.markProgress(assignment.items.single())
        tracker.snapshot().lastProgressTime shouldBe "2026-09-15T12:00:00Z"
        sink.statuses shouldBe emptyList()

        tracker.jobStarted(assignment.items.single())
        clock.advanceNanos(2_000_000_000)
        tracker.snapshot().currentEpisodeElapsedSeconds shouldBe 2

        clock.advanceNanos(10)
        tracker.heartbeat()
        sink.statuses.size shouldBe 1
        sink.statuses.single().heartbeatSequence shouldBe 1

        tracker.milestone(ActorStateV1.EPISODE_CLOSED)
        sink.statuses.size shouldBe 2
        sink.statuses.last().state shouldBe ActorStateV1.EPISODE_CLOSED
        sink.statuses.last().heartbeatSequence shouldBe 2
    }

    test("actor runner never sends a partial episode to the B2 sink") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val sink = RecordingB2Sink()
        val result = TrustedActorRunner(
            assignment = assignment,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-a"),
            actualRuntimeSourceCommit = assignment.items.first().environmentIdentity.engineCommit,
            preflight = {
                StoragePreflightResultV1.pass(
                    publishBudgetBytes = 10_000,
                    requiredAdditionalScratchBytes = 1_000,
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
            sinkFactory = { sink },
            statusSink = RecordingStatusSink(),
            clock = FakeActorClock(),
        ).run()

        result.status.state shouldBe ActorStateV1.PARTIAL_LOST
        sink.appendCalls shouldBe 0
        result.report.partialLostJobOrdinals shouldContainExactly listOf(0)
    }

    test("executor exception is a failed actor job, not a fabricated provider interruption") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val result = TrustedActorRunner(
            assignment = assignment,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-exception"),
            actualRuntimeSourceCommit = assignment.items.first().environmentIdentity.engineCommit,
            preflight = {
                StoragePreflightResultV1.pass(
                    publishBudgetBytes = 10_000,
                    requiredAdditionalScratchBytes = 1_000,
                )
            },
            episodeExecutor = ActorEpisodeExecutor {
                error("executor failure")
            },
            sinkFactory = { RecordingB2Sink() },
            statusSink = RecordingStatusSink(),
            clock = FakeActorClock(),
        ).run()

        result.status.state shouldBe ActorStateV1.FAILED
        result.report.failedJobOrdinals shouldContainExactly listOf(0)
        result.report.partialLostJobOrdinals shouldBe emptyList()
        result.report.diagnostics.single().code shouldBe
            ActorDiagnosticCodeV1.ACTOR_EXECUTOR_FAILURE
    }

    test("B2 sink failure is distinct from semantic local admission quarantine") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val generated = testB2Episode(assignment.items.single())
        val result = TrustedActorRunner(
            assignment = assignment,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-sink-failure"),
            actualRuntimeSourceCommit = assignment.items.first().environmentIdentity.engineCommit,
            preflight = {
                StoragePreflightResultV1.pass(
                    publishBudgetBytes = 10_000,
                    requiredAdditionalScratchBytes = 1_000,
                )
            },
            episodeExecutor = ActorEpisodeExecutor {
                ActorEpisodeOutcome.Completed(generated.trajectory, generated.binding)
            },
            sinkFactory = { ThrowingB2Sink() },
            statusSink = RecordingStatusSink(),
            clock = FakeActorClock(),
        ).run()

        result.status.state shouldBe ActorStateV1.FAILED
        result.report.diagnostics.single().code shouldBe ActorDiagnosticCodeV1.B2_PUBLISHER_FAILURE
    }

    test("preflight rejection stops before an episode and is not counted as a job failure") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val preflight = StoragePreflight.evaluate(
            StoragePreflightRequestV1(
                measuredWorkingFreeBytes = 10_000,
                measuredScratchFreeBytes = 10_000,
                estimatedFinalizedOutputBytes = 1_000,
                requiredAdditionalScratchBytes = 1_000,
                publicationOverheadBytes = 100,
                workingSafetyReserveBytes = 100,
                providerSafetyReserveBytes = 100,
            ),
        )
        var sinkCreated = false
        val result = TrustedActorRunner(
            assignment = assignment,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-preflight"),
            actualRuntimeSourceCommit = assignment.items.first().environmentIdentity.engineCommit,
            preflight = { preflight },
            episodeExecutor = ActorEpisodeExecutor {
                error("Episode executor must not run after failed preflight")
            },
            sinkFactory = {
                sinkCreated = true
                RecordingB2Sink()
            },
            statusSink = RecordingStatusSink(),
            clock = FakeActorClock(),
        ).run()

        result.status.state shouldBe ActorStateV1.CAPACITY_EXHAUSTED
        result.status.jobsFailed shouldBe 0
        result.report.failedJobOrdinals shouldBe emptyList()
        sinkCreated shouldBe false
    }

    test("actor runner integrates an admitted episode with the existing B2 writer and publisher") {
        val item = testPlan().resolve(0)
        val generated = testB2Episode(item)
        val outputRoot = Files.createTempDirectory("actor-control-plane-run-")
        val result = TrustedActorRunner(
            assignment = WorkAssignmentV1.from(testPlan(), listOf(0)),
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-complete"),
            actualRuntimeSourceCommit = item.environmentIdentity.engineCommit,
            preflight = {
                StoragePreflightResultV1.pass(
                    publishBudgetBytes = 100_000_000,
                    requiredAdditionalScratchBytes = 1_000,
                )
            },
            episodeExecutor = ActorEpisodeExecutor {
                ActorEpisodeOutcome.Completed(
                    trajectory = generated.trajectory,
                    replayTrajectoryBinding = generated.binding,
                )
            },
            sinkFactory = {
                TrajectoryV1B2Sink(
                    com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Writer(
                        outputDirectory = outputRoot,
                        metadata = DatasetMetadataV1(
                            maxShardBytes = 10_000_000,
                            maxEpisodesPerShard = 1,
                        ),
                    ),
                )
            },
            statusSink = RecordingStatusSink(),
            clock = FakeActorClock(),
            requestedConcurrency = 3,
            actualConcurrency = 2,
        ).run()

        result.status.state shouldBe ActorStateV1.FINALIZED_LOCAL
        result.report.completedJobOrdinals shouldContainExactly listOf(0)
        result.report.expectedSourceCommit shouldBe "engine-commit"
        result.report.actualRuntimeSourceCommit shouldBe "engine-commit"
        result.report.sourceRevisionVerified shouldBe true
        result.report.requestedConcurrency shouldBe 3
        result.report.actualConcurrency shouldBe 2
        ActorOperationalV1Json.decodeAndValidateRunReport(
            ActorOperationalV1Json.encode(result.report),
        ).shouldBeInstanceOf<ActorOperationalValidationResult.RunReportValid>()
        result.manifest!!.counts.episodeCount shouldBe 1
        result.manifest.counts.failedCount shouldBe 0
    }

    test("unverified runtime source revision fails before executing or publishing an episode") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val sink = RecordingB2Sink()
        var executorCalled = false
        val result = TrustedActorRunner(
            assignment = assignment,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-source-mismatch"),
            actualRuntimeSourceCommit = "different-runtime-commit",
            preflight = {
                error("Preflight must not run before source revision verification")
            },
            episodeExecutor = ActorEpisodeExecutor {
                executorCalled = true
                error("Episode executor must not run after source revision mismatch")
            },
            sinkFactory = { sink },
            statusSink = RecordingStatusSink(),
            clock = FakeActorClock(),
        ).run()

        result.status.state shouldBe ActorStateV1.FAILED
        result.report.expectedSourceCommit shouldBe "engine-commit"
        result.report.actualRuntimeSourceCommit shouldBe "different-runtime-commit"
        result.report.sourceRevisionVerified shouldBe false
        result.report.diagnostics.single().code shouldBe
            ActorDiagnosticCodeV1.SOURCE_REVISION_UNVERIFIED
        executorCalled shouldBe false
        sink.appendCalls shouldBe 0
        ActorOperationalV1Json.decodeAndValidateRunReport(
            ActorOperationalV1Json.encode(result.report),
        ).shouldBeInstanceOf<ActorOperationalValidationResult.RunReportValid>()
    }

    test("non-exact replay binding is rejected before the B2 sink and is not reported as verified") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val generated = testB2Episode(assignment.items.single())
        val nonExactBinding = generated.binding.copy(
            verificationBinding = generated.binding.verificationBinding.copy(
                verification = generated.binding.verificationBinding.verification.copy(
                    fidelity = ReplayFidelity.UNVERIFIED,
                ),
            ),
        )
        val sink = RecordingB2Sink()
        val statuses = RecordingStatusSink()
        val result = TrustedActorRunner(
            assignment = assignment,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-replay-gap"),
            actualRuntimeSourceCommit = assignment.items.first().environmentIdentity.engineCommit,
            preflight = {
                StoragePreflightResultV1.pass(
                    publishBudgetBytes = 10_000,
                    requiredAdditionalScratchBytes = 1_000,
                )
            },
            episodeExecutor = ActorEpisodeExecutor {
                ActorEpisodeOutcome.Completed(generated.trajectory, nonExactBinding)
            },
            sinkFactory = { sink },
            statusSink = statuses,
            clock = FakeActorClock(),
        ).run()

        result.status.state shouldBe ActorStateV1.FAILED
        result.report.diagnostics.single().code shouldBe ActorDiagnosticCodeV1.REPLAY_INCOMPLETE
        sink.appendCalls shouldBe 0
        statuses.statuses.map { it.state }.contains(ActorStateV1.REPLAY_VERIFIED) shouldBe false
    }

    test("trajectory identity mismatch is rejected before the B2 sink is called") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val item = assignment.items.single()
        val generated = testB2Episode(item)
        val wrongTrajectory = generated.trajectory.copy(
            episodeMetadata = generated.trajectory.episodeMetadata.copy(
                environmentIdentity = testEnvironment(999),
            ),
        )
        val sink = RecordingB2Sink()
        val result = TrustedActorRunner(
            assignment = assignment,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-mismatch"),
            actualRuntimeSourceCommit = assignment.items.first().environmentIdentity.engineCommit,
            preflight = {
                StoragePreflightResultV1.pass(
                    publishBudgetBytes = 10_000,
                    requiredAdditionalScratchBytes = 1_000,
                )
            },
            episodeExecutor = ActorEpisodeExecutor {
                ActorEpisodeOutcome.Completed(wrongTrajectory, generated.binding)
            },
            sinkFactory = { sink },
            statusSink = RecordingStatusSink(),
            clock = FakeActorClock(),
        ).run()

        result.status.state shouldBe ActorStateV1.FAILED
        result.report.failedJobOrdinals shouldContainExactly listOf(0)
        result.report.diagnostics.single().code shouldBe
            ActorDiagnosticCodeV1.WORKLOAD_PLAN_RESOLUTION_MISMATCH
        sink.appendCalls shouldBe 0
    }

    test("operational status and report codecs reject unknown fields and status files are replace-safe") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val tracker = ActorStatusTracker(
            assignmentIdentity = assignment.assignmentIdentity,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-codec"),
            clock = FakeActorClock(),
        )
        tracker.assign(1)
        val status = tracker.snapshot()
        val encoded = ActorOperationalV1Json.encode(status)
        ActorOperationalV1Json.decodeAndValidateStatus(encoded)
            .shouldBeInstanceOf<ActorOperationalValidationResult.StatusValid>()

        val root = A3SemanticJson.strictJson.parseToJsonElement(encoded).jsonObject
        val malformed = JsonObject(root.entries.associate { it.key to it.value } +
            ("pid" to JsonPrimitive("123")))
        ActorOperationalV1Json.decodeAndValidateStatus(malformed.toString())
            .shouldBeInstanceOf<ActorOperationalValidationResult.Rejected>()

        val path = Files.createTempDirectory("actor-status-").resolve("status.json")
        AtomicActorStatusFileSink(path).persist(status)
        ActorOperationalV1Json.decodeStatus(Files.readString(path)) shouldBe status
    }

    test("filesystem storage probe reports actual capacity") {
        val root = Files.createTempDirectory("actor-storage-probe-")
        val snapshot = FileStoreStorageCapacityProbeV1().probe(StorageAreaV1.SCRATCH, root)

        snapshot.area shouldBe StorageAreaV1.SCRATCH
        snapshot.freeBytes shouldBe snapshot.freeBytes.coerceIn(0, snapshot.totalBytes)
        snapshot.rootLabel shouldBe "scratch"
    }

    test("diagnostics reject arbitrary detail fields instead of transporting free strings") {
        val assignment = WorkAssignmentV1.from(testPlan(), listOf(0))
        val tracker = ActorStatusTracker(
            assignmentIdentity = assignment.assignmentIdentity,
            executionAttemptIdentity = ExecutionAttemptIdentityV1("attempt-diagnostics"),
            clock = FakeActorClock(),
        )
        tracker.assign(1)
        val status = tracker.snapshot().copy(
            diagnostics = listOf(
                ActorDiagnosticV1(
                    code = ActorDiagnosticCodeV1.ACTOR_EXECUTOR_FAILURE,
                    severity = ActorDiagnosticSeverityV1.ERROR,
                ),
            ),
        )
        val root = A3SemanticJson.strictJson.parseToJsonElement(
            ActorOperationalV1Json.encode(status),
        ).jsonObject
        val diagnostic = root.getValue("diagnostics").jsonArray.single().jsonObject

        listOf(
            "/tmp/diagnostic.log",
            "/kaggle/working/diagnostic.log",
            "/var/log/diagnostic.log",
            "\\\\server\\share\\diagnostic.log",
            "Authorization: Bearer secret-value",
        ).forEach { detail ->
            val diagnosticWithFreeDetail = JsonObject(
                diagnostic.toMap() + ("detail" to JsonPrimitive(detail)),
            )
            val malformed = JsonObject(
                root.toMap() + (
                    "diagnostics" to JsonArray(listOf(diagnosticWithFreeDetail))
                ),
            )
            ActorOperationalV1Json.decodeAndValidateStatus(malformed.toString())
                .shouldBeInstanceOf<ActorOperationalValidationResult.Rejected>()
        }
    }
})

private fun testPlan(): WorkloadPlanV1 {
    val first = WorkloadJobV1(
        jobOrdinal = 0,
        environmentIdentity = testEnvironment(11),
        policyProvenance = testPolicy(21),
    )
    val second = WorkloadJobV1(
        jobOrdinal = 1,
        environmentIdentity = testEnvironment(12),
        policyProvenance = testPolicy(22),
    )
    return WorkloadPlanV1(
        workloadNamespace = "actor-test",
        rolloutGeneration = "campaign-a",
        behaviorPolicyCampaignIdentity = "behavior-campaign-a",
        opponentPolicyCampaignIdentity = "opponent-campaign-a",
        jobs = listOf(first, second),
    )
}

private fun testEnvironment(seed: Long): EnvironmentIdentityV1 = EnvironmentIdentityV1(
    engineCommit = "engine-commit",
    cardDefinitionIdentity = "card-definition",
    akiriDeckIdentity = "a".repeat(64),
    chevillDeckIdentity = "b".repeat(64),
    format = "COMMANDER",
    attackMode = "MULTIPLE",
    startingHandSize = 7,
    skipMulligans = true,
    useHandSmoother = false,
    roster = listOf(
        RosterSeatV1(
            seatIndex = 0,
            playerId = EntityId("player-0"),
            role = "AKIRI",
            deckIdentity = "a".repeat(64),
        ),
        RosterSeatV1(
            seatIndex = 1,
            playerId = EntityId("player-1"),
            role = "CHEVILL",
            deckIdentity = "b".repeat(64),
        ),
    ),
    startingPlayer = EntityId("player-0"),
    actualEngineSeed = seed,
)

private fun testPolicy(seed: Long, identity: String = "policy-a"): PolicyProvenanceV1 = PolicyProvenanceV1(
    behaviorPolicyIdentity = identity,
    opponentPolicyIdentity = identity,
    behaviorPolicyRole = "EXTERNAL_CONTROLLER",
    opponentPolicyRole = "EXTERNAL_CONTROLLER",
    policyRngIdentity = "policy-rng-v1",
    policySeed = seed,
    policySourceIdentity = "policy-source-$seed",
)

private fun testTrajectory(
    item: WorkItemV1,
    closureReason: EpisodeInterruptionReason,
): TrajectoryV1 {
    val closure = com.wingedsheep.gym.EpisodeClosureV1.Interrupted(
        stepCount = 0,
        reason = closureReason,
    )
    val link = com.wingedsheep.gym.trainer.trajectory.CompactReplayLinkV1(
        replayContentIdentity = "c".repeat(64),
        replayActionCount = 0,
    )
    val metadataBase = com.wingedsheep.gym.trainer.trajectory.EpisodeMetadataV1(
        semanticEpisodeId = "0".repeat(64),
        collectionJobId = "0".repeat(64),
        environmentIdentity = item.environmentIdentity,
        policyProvenance = item.policyProvenance,
        compactReplayLink = link,
        closure = closure,
    )
    val metadataWithEpisode = metadataBase.copy(
        semanticEpisodeId = metadataBase.recomputeSemanticEpisodeId(),
    )
    val metadata = metadataWithEpisode.copy(
        collectionJobId = metadataWithEpisode.recomputeCollectionJobId(),
    )
    val base = TrajectoryV1(
        trajectoryId = "0".repeat(64),
        episodeMetadata = metadata,
        decisions = emptyList(),
    )
    return base.copy(trajectoryId = base.recomputeTrajectoryId())
}

private data class TestB2Episode(
    val trajectory: TrajectoryV1,
    val binding: ReplayTrajectoryBindingV1,
)

private fun testB2Episode(item: WorkItemV1): TestB2Episode {
    val registry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }
    val environment = GameEnvironment.create(registry)
    environment.reset(
        GameConfig(
            players = listOf(
                PlayerConfig(
                    name = "Alice",
                    deck = Deck.of("Mountain" to 20),
                    playerId = EntityId("player-0"),
                ),
                PlayerConfig(
                    name = "Bob",
                    deck = Deck.of("Mountain" to 20),
                    playerId = EntityId("player-1"),
                ),
            ),
            startingHandSize = 0,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = item.environmentIdentity.actualEngineSeed,
        ),
        maxSteps = 10,
    )
    val sourceObservation = ObservationBuilder(cardRegistry = registry)
        .build(
            state = environment.state,
            perspectivePlayerId = EntityId("player-0"),
            legalActions = environment.legalActions(),
        ).observation as TrainingObservation
    val observation = PlayerObservationV1.from(sourceObservation)
    val domain = CompleteLegalDomainV1.from(sourceObservation)
    val closure = com.wingedsheep.gym.EpisodeClosureV1.Interrupted(
        stepCount = 0,
        reason = EpisodeInterruptionReason.HORIZON_REACHED,
    )
    val replayContent = ReplayContentIdentityV1(
        replayVersion = 6,
        value = "c".repeat(64),
    )
    val verification = VerifiedReplayVerification(
        replayVersion = 6,
        replayActionCount = 0,
        verifiedActionCount = 0,
        fidelity = ReplayFidelity.EXACT,
        frames = listOf(
            VerifiedReplayFrame(
                replayActionIndex = 0,
                perspectivePlayerId = EntityId("player-0"),
                observation = observation,
                domain = domain,
                candidateDomainDigest = CandidateDomainDigestV1.from(domain),
            ),
        ),
        initialCheckpointVerified = true,
        intermediateCheckpointsVerified = true,
        tailCheckpointVerified = true,
        closure = closure,
    )
    val binding = ReplayTrajectoryBindingV1(
        verificationBinding = ReplayVerificationBindingV1(
            replayContentIdentity = replayContent,
            verification = verification,
        ),
        chosenInputBinding = ReplayChosenInputBindingV1(
            replayContentIdentity = replayContent,
            replayActionCount = 0,
            chosenInputs = emptyList(),
        ),
    )
    val metadataBase = EpisodeMetadataV1(
        semanticEpisodeId = "0".repeat(64),
        collectionJobId = "0".repeat(64),
        environmentIdentity = item.environmentIdentity,
        policyProvenance = item.policyProvenance,
        compactReplayLink = CompactReplayLinkV1(
            replayContentIdentity = replayContent.value,
            replayActionCount = 0,
        ),
        closure = closure,
    )
    val metadataWithEpisode = metadataBase.copy(
        semanticEpisodeId = metadataBase.recomputeSemanticEpisodeId(),
    )
    val metadata = metadataWithEpisode.copy(
        collectionJobId = metadataWithEpisode.recomputeCollectionJobId(),
    )
    val base = TrajectoryV1(
        trajectoryId = "0".repeat(64),
        episodeMetadata = metadata,
        decisions = emptyList(),
    )
    return TestB2Episode(
        trajectory = base.copy(trajectoryId = base.recomputeTrajectoryId()),
        binding = binding,
    )
}

private class FakeActorClock : ActorClock {
    private var nowNanos: Long = 0
    override fun utcNow(): String = "2026-09-15T12:00:00Z"
    override fun monotonicNanos(): Long = nowNanos
    fun advanceNanos(delta: Long) {
        nowNanos += delta
    }
}

private class RecordingStatusSink : ActorStatusSink {
    val statuses = mutableListOf<ActorStatusV1>()
    override fun persist(status: ActorStatusV1) {
        statuses += status
    }
}

private class RecordingB2Sink : B2TrajectorySink {
    var appendCalls: Int = 0

    override fun appendEpisode(
        localEpisodeOrdinal: Int,
        trajectory: TrajectoryV1,
        replayTrajectoryBinding: ReplayTrajectoryBindingV1,
    ): TrajectoryAdmissionResult {
        appendCalls += 1
        error("Partial episode must never reach the B2 sink")
    }

    override fun finalizeDataset(): com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1 =
        error("Partial episode must not finalize a dataset")

    override fun close() = Unit
}

private class ThrowingB2Sink : B2TrajectorySink {
    override fun appendEpisode(
        localEpisodeOrdinal: Int,
        trajectory: TrajectoryV1,
        replayTrajectoryBinding: ReplayTrajectoryBindingV1,
    ): TrajectoryAdmissionResult {
        error("synthetic B2 sink failure")
    }

    override fun finalizeDataset(): com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1 =
        error("synthetic B2 sink failure")

    override fun close() = Unit
}

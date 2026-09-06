package com.wingedsheep.rundiagnostics.supervisor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StallClassifierTest : FunSpec({
    test("requires a fresh heartbeat and useful cursor for HEALTHY") {
        val classifier = StallClassifier(config())
        val first = classifier.classify(input(now = 0, heartbeat = 1, useful = 1), SupervisorState())

        val result = classifier.classify(
            input(now = 50, heartbeat = 2, useful = 2),
            first.nextState,
        )

        result.trigger shouldBe StallTriggerKind.NONE
        result.classification shouldBe DiagnosticClassification.HEALTHY
        result.action shouldBe SupervisorAction.CONTINUE_OBSERVING
    }

    test("fresh heartbeat with stale useful progress is a suspected stall") {
        val classifier = StallClassifier(config())
        val first = classifier.classify(input(now = 0, heartbeat = 1, useful = 1), SupervisorState())

        val result = classifier.classify(
            input(now = 200_000_000, heartbeat = 2, useful = 1),
            first.nextState,
        )

        result.trigger shouldBe StallTriggerKind.USEFUL_PROGRESS_STALE
        result.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
        result.action shouldBe SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE
    }

    test("fresh useful progress with a stale heartbeat is not HEALTHY") {
        val classifier = StallClassifier(config())
        val first = classifier.classify(input(now = 0, heartbeat = 1, useful = 1), SupervisorState())

        val result = classifier.classify(
            input(now = 200_000_000, heartbeat = 1, useful = 2),
            first.nextState,
        )

        result.trigger shouldBe StallTriggerKind.HEARTBEAT_STALE
        result.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
    }

    test("does not treat heartbeat sequence zero as a completed heartbeat") {
        val classifier = StallClassifier(config())
        val first = classifier.classify(input(now = 0, heartbeat = 0, useful = 1), SupervisorState())

        val result = classifier.classify(
            input(now = 50_000_000, heartbeat = 0, useful = 2),
            first.nextState,
        )

        result.trigger shouldBe StallTriggerKind.HEARTBEAT_STALE
        result.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
    }

    test("does not treat regressed status cursors as fresh progress") {
        val classifier = StallClassifier(config())
        val first = classifier.classify(input(now = 0, heartbeat = 2, useful = 2), SupervisorState())

        val result = classifier.classify(
            input(now = 50_000_000, heartbeat = 1, useful = 1),
            first.nextState,
        )

        result.trigger shouldBe StallTriggerKind.STATUS_UNAVAILABLE
        result.classification shouldBe DiagnosticClassification.UNKNOWN
        result.action shouldBe SupervisorAction.CONTINUE_OBSERVING
    }

    test("uses stable hot-stack evidence for CPU_SPIN_SUSPECT") {
        val classifier = StallClassifier(config())
        val first = classifier.classify(
            input(now = 0, heartbeat = 1, useful = 1, cpuNanos = 0),
            SupervisorState(),
        )

        val result = classifier.classify(
            input(
                now = 200_000_000,
                heartbeat = 2,
                useful = 1,
                cpuNanos = 200_000_000,
                jvmEvidence = JvmEvidenceV1(
                    availability = EvidenceAvailability.AVAILABLE,
                    stableHotStack = true,
                ),
            ),
            first.nextState,
        )

        result.classification shouldBe DiagnosticClassification.CPU_SPIN_SUSPECT
    }

    test("uses low-CPU waiting evidence for BLOCKED_WAIT_SUSPECT") {
        val classifier = StallClassifier(config())
        val first = classifier.classify(
            input(now = 0, heartbeat = 1, useful = 1, cpuNanos = 0),
            SupervisorState(),
        )

        val result = classifier.classify(
            input(
                now = 200_000_000,
                heartbeat = 2,
                useful = 1,
                cpuNanos = 0,
                jvmEvidence = JvmEvidenceV1(
                    availability = EvidenceAvailability.AVAILABLE,
                    stableWaitStack = true,
                ),
            ),
            first.nextState,
        )

        result.classification shouldBe DiagnosticClassification.BLOCKED_WAIT_SUSPECT
    }

    test("emits DEADLOCK_DETECTED only for explicit JVM deadlock evidence") {
        val classifier = StallClassifier(config())
        val first = classifier.classify(input(now = 0, heartbeat = 1, useful = 1), SupervisorState())

        val result = classifier.classify(
            input(
                now = 200_000_000,
                heartbeat = 2,
                useful = 1,
                jvmEvidence = JvmEvidenceV1(
                    availability = EvidenceAvailability.AVAILABLE,
                    deadlockDetected = true,
                ),
            ),
            first.nextState,
        )

        result.classification shouldBe DiagnosticClassification.DEADLOCK_DETECTED
    }

    test("reports process exit and continues observing without termination") {
        val classifier = StallClassifier(config())
        val result = classifier.classify(
            input(now = 10, heartbeat = null, useful = null, process = ProcessLiveness.PROCESS_EXITED),
            SupervisorState(),
        )

        result.trigger shouldBe StallTriggerKind.PROCESS_EXITED
        result.classification shouldBe DiagnosticClassification.PROCESS_EXITED
        result.action shouldBe SupervisorAction.CONTINUE_OBSERVING
    }

    test("preserves UNKNOWN when the sidecar is unavailable") {
        val classifier = StallClassifier(config())
        val result = classifier.classify(
            input(now = 10, heartbeat = null, useful = null, status = StatusReadResult.Unavailable(
                EvidenceAvailability.MISSING,
                SupervisorFailureCode.STATUS_MISSING,
            )),
            SupervisorState(),
        )

        result.trigger shouldBe StallTriggerKind.STATUS_UNAVAILABLE
        result.classification shouldBe DiagnosticClassification.UNKNOWN
    }
})

private fun config() = SupervisorConfigV1(
    targetPid = FIXTURE_PID,
    statusPath = "status.json",
    diagnosticsDirectory = "diagnostics",
    heartbeatTimeoutMillis = 100,
    usefulProgressTimeoutMillis = 100,
    sampleIntervalMillis = 10,
    diagnosticCaptureCooldownMillis = 100,
)

private fun input(
    now: Long,
    heartbeat: Long?,
    useful: Long?,
    cpuNanos: Long? = null,
    process: ProcessLiveness = ProcessLiveness.ALIVE,
    status: StatusReadResult = StatusReadResult.Available(
        fixtureStatus(
            heartbeatSequence = heartbeat ?: 0,
            usefulProgressSequence = useful ?: 0,
        ),
    ),
    jvmEvidence: JvmEvidenceV1? = null,
) = ClassifierInput(
    nowElapsedNanos = now,
    process = ProcessIdentityResult(
        liveness = process,
        observation = if (process == ProcessLiveness.PROCESS_EXITED) null else ProcessHandleObservation(
            pid = FIXTURE_PID,
            alive = true,
            startInstant = FIXTURE_START,
        ),
    ),
    status = status,
    metrics = ProcessMetricsV1(
        availability = EvidenceAvailability.AVAILABLE,
        cpuTimeNanos = cpuNanos,
        sampledAtElapsedNanos = now,
    ),
    jvmEvidence = jvmEvidence,
)

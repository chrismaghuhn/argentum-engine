package com.wingedsheep.rundiagnostics.supervisor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class ExternalSupervisorTest : FunSpec({
    test("separates healthy progress from stale useful progress") {
        val clock = MutableSupervisorClock()
        val statuses = ArrayDeque<StatusReadResult>().apply {
            add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = 1, usefulProgressSequence = 1)))
            add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = 2, usefulProgressSequence = 1)))
            add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = 3, usefulProgressSequence = 2)))
        }
        val supervisor = newSupervisor(clock, statuses)

        try {
            supervisor.pollOnce().decision.classification shouldBe DiagnosticClassification.UNKNOWN
            clock.elapsedNanos = 200_000_000
            supervisor.pollOnce().decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            clock.elapsedNanos = 300_000_000
            supervisor.pollOnce().decision.classification shouldBe DiagnosticClassification.HEALTHY
        } finally {
            supervisor.close()
        }
    }

    test("captures bounded evidence on a trigger but never terminates the target") {
        val clock = MutableSupervisorClock()
        val statuses = ArrayDeque<StatusReadResult>().apply {
            add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = 1, usefulProgressSequence = 1)))
            add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = 2, usefulProgressSequence = 1)))
        }
        val jvmCalls = AtomicInteger(0)
        val bundles = AtomicInteger(0)
        val supervisor = newSupervisor(
            clock = clock,
            statuses = statuses,
            jvmRunner = RecordingJvmRunner(jvmCalls),
            bundleSink = DiagnosticBundleSink { bundles.incrementAndGet(); null },
        )

        try {
            supervisor.pollOnce()
            clock.elapsedNanos = 200_000_000
            val result = supervisor.pollOnce()

            result.decision.action shouldBe SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE
            jvmCalls.get() shouldBe 5
            bundles.get() shouldBe 1
        } finally {
            supervisor.close()
        }
    }

    test("does not capture again during the configured cooldown") {
        val clock = MutableSupervisorClock()
        val statuses = ArrayDeque<StatusReadResult>().apply {
            repeat(3) { add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = it + 1L, usefulProgressSequence = 1))) }
        }
        val jvmCalls = AtomicInteger(0)
        val supervisor = newSupervisor(clock, statuses, RecordingJvmRunner(jvmCalls))

        try {
            supervisor.pollOnce()
            clock.elapsedNanos = 200_000_000
            supervisor.pollOnce()
            clock.elapsedNanos = 250_000_000
            val result = supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            jvmCalls.get() shouldBe 5
        } finally {
            supervisor.close()
        }
    }

    test("process exit is reported without JVM capture or kill") {
        val clock = MutableSupervisorClock()
        val jvmCalls = AtomicInteger(0)
        val supervisor = newSupervisor(
            clock = clock,
            statuses = ArrayDeque(),
            processSource = object : ProcessHandleSource {
                override fun observe(pid: Long): ProcessHandleObservation? = null
            },
            jvmRunner = RecordingJvmRunner(jvmCalls),
        )

        try {
            val result = supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.PROCESS_EXITED
            result.decision.action shouldBe SupervisorAction.CONTINUE_OBSERVING
            jvmCalls.get() shouldBe 0
        } finally {
            supervisor.close()
        }
    }

    test("does not attach to a different live PID when the sidecar identity disagrees") {
        val clock = MutableSupervisorClock()
        val statuses = ArrayDeque<StatusReadResult>().apply {
            add(StatusReadResult.Available(fixtureStatus(processId = FIXTURE_PID + 1)))
        }
        val sourceCalls = AtomicInteger(0)
        val supervisor = newSupervisor(
            clock = clock,
            statuses = statuses,
            processSource = object : ProcessHandleSource {
                override fun observe(pid: Long): ProcessHandleObservation? {
                    sourceCalls.incrementAndGet()
                    return ProcessHandleObservation(pid, true, FIXTURE_START)
                }
            },
        )

        try {
            val result = supervisor.pollOnce()

            result.process.liveness shouldBe ProcessLiveness.IDENTITY_MISMATCH
            result.decision.classification shouldBe DiagnosticClassification.UNKNOWN
            sourceCalls.get() shouldBe 0
        } finally {
            supervisor.close()
        }
    }

    test("fuses high CPU evidence against the pre-sample state") {
        val clock = MutableSupervisorClock()
        val statuses = ArrayDeque<StatusReadResult>().apply {
            add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = 1, usefulProgressSequence = 1)))
            add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = 2, usefulProgressSequence = 1)))
        }
        val metrics = ArrayDeque(
            listOf(
                ProcessMetricsV1(availability = EvidenceAvailability.AVAILABLE, cpuTimeNanos = 0),
                ProcessMetricsV1(availability = EvidenceAvailability.AVAILABLE, cpuTimeNanos = 200_000_000),
            ),
        )
        val hotDump = """
            "hot-worker" #1 prio=5
               java.lang.Thread.State: RUNNABLE
                at worker.Hot.loop(Hot.kt:1)
        """.trimIndent()
        val supervisor = newSupervisor(
            clock = clock,
            statuses = statuses,
            jvmRunner = FixedJvmRunner(hotDump),
            metricsSampler = ProcessMetricsSampler { metrics.removeFirst() },
            bundleSink = DiagnosticBundleSink { null },
        )

        try {
            supervisor.pollOnce()
            clock.elapsedNanos = 200_000_000
            val result = supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.CPU_SPIN_SUSPECT
        } finally {
            supervisor.close()
        }
    }

    test("fuses low CPU waiting evidence against the pre-sample state") {
        val clock = MutableSupervisorClock()
        val statuses = ArrayDeque<StatusReadResult>().apply {
            add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = 1, usefulProgressSequence = 1)))
            add(StatusReadResult.Available(fixtureStatus(heartbeatSequence = 2, usefulProgressSequence = 1)))
        }
        val metrics = ArrayDeque(
            listOf(
                ProcessMetricsV1(availability = EvidenceAvailability.AVAILABLE, cpuTimeNanos = 0),
                ProcessMetricsV1(availability = EvidenceAvailability.AVAILABLE, cpuTimeNanos = 0),
            ),
        )
        val waitingDump = """
            "waiting-worker" #2 prio=5
               java.lang.Thread.State: WAITING (parking)
                at worker.Wait.await(Wait.kt:1)
        """.trimIndent()
        val supervisor = newSupervisor(
            clock = clock,
            statuses = statuses,
            jvmRunner = FixedJvmRunner(waitingDump),
            metricsSampler = ProcessMetricsSampler { metrics.removeFirst() },
            bundleSink = DiagnosticBundleSink { null },
        )

        try {
            supervisor.pollOnce()
            clock.elapsedNanos = 200_000_000
            val result = supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.BLOCKED_WAIT_SUSPECT
        } finally {
            supervisor.close()
        }
    }
})

private fun newSupervisor(
    clock: MutableSupervisorClock,
    statuses: ArrayDeque<StatusReadResult>,
    jvmRunner: JvmCommandRunner = RecordingJvmRunner(AtomicInteger()),
    metricsSampler: ProcessMetricsSampler = ProcessMetricsSampler {
        ProcessMetricsV1(
            availability = EvidenceAvailability.AVAILABLE,
            cpuTimeNanos = 0,
            sampledAtElapsedNanos = clock.elapsedNanos,
        )
    },
    bundleSink: DiagnosticBundleSink? = null,
    processSource: ProcessHandleSource = object : ProcessHandleSource {
        override fun observe(pid: Long): ProcessHandleObservation =
            ProcessHandleObservation(FIXTURE_PID, alive = true, startInstant = FIXTURE_START)
    },
): ExternalSupervisor {
    val config = SupervisorConfigV1(
        targetPid = FIXTURE_PID,
        statusPath = "status.json",
        diagnosticsDirectory = "diagnostics",
        heartbeatTimeoutMillis = 100,
        usefulProgressTimeoutMillis = 100,
        sampleIntervalMillis = 10,
        diagnosticCaptureCooldownMillis = 100,
        captureTimeoutMillis = 10,
        threadDumpIntervalMillis = 1,
        expectedProcessStartWallClock = FIXTURE_START.toString(),
    )
    val bundleRoot = Files.createTempDirectory("run-diagnostics-supervisor-test-")
    return ExternalSupervisor(
        config = config,
        processIdentityChecker = ProcessIdentityChecker(processSource, startToleranceMillis = 0),
        statusSource = StatusSource {
            if (statuses.isEmpty()) {
                StatusReadResult.Unavailable(EvidenceAvailability.MISSING, SupervisorFailureCode.STATUS_MISSING)
            } else {
                statuses.removeFirst()
            }
        },
        metricsSampler = metricsSampler,
        jvmRunner = jvmRunner,
        monotonicClock = clock,
        wallClock = FIXTURE_WALL_CLOCK,
        bundleSink = bundleSink ?: DiagnosticBundleWriter(bundleRoot),
        sleeper = SupervisorSleeper { },
    )
}

private class RecordingJvmRunner(
    private val calls: AtomicInteger,
) : JvmCommandRunner {
    override fun run(pid: Long, kind: JvmCommandKind, timeoutMillis: Long, maxBytes: Int): JvmCommandResult {
        calls.incrementAndGet()
        return JvmCommandResult(kind, EvidenceAvailability.AVAILABLE, exitCode = 0, output = "stable")
    }
}

private class FixedJvmRunner(
    private val threadDump: String,
) : JvmCommandRunner {
    override fun run(pid: Long, kind: JvmCommandKind, timeoutMillis: Long, maxBytes: Int): JvmCommandResult =
        JvmCommandResult(
            kind = kind,
            availability = EvidenceAvailability.AVAILABLE,
            exitCode = 0,
            output = if (kind == JvmCommandKind.THREAD_PRINT) threadDump else "metadata",
        )
}

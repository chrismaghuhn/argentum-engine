package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.AtomicStatusFileOps
import com.wingedsheep.rundiagnostics.RunStatusCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque

class D2RemediationTest : FunSpec({
    test("revalidates process identity before every privileged attachment and stops after exit") {
        val root = Files.createTempDirectory("run-diagnostics-d2-identity-")
        val identitySource = D2IdentitySource(
            listOf(
                d2LiveObservation(),
                null,
            ),
        )
        val runner = D2JvmRunner()
        val collector = JvmEvidenceCollector(
            config = d2Config(root),
            runner = runner,
            sleeper = SupervisorSleeper { },
            identityChecker = ProcessIdentityChecker(identitySource, startToleranceMillis = 0),
        )

        try {
            val evidence = collector.capture(FIXTURE_PID, FIXTURE_START)

            runner.kinds shouldBe listOf(JvmCommandKind.THREAD_PRINT)
            identitySource.calls shouldBe 2
            evidence.results.size shouldBe 2
            evidence.results.last().failureCode shouldBe SupervisorFailureCode.PROCESS_NOT_FOUND
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("revalidates identity before all thread, heap, and VM attachments") {
        val root = Files.createTempDirectory("run-diagnostics-d2-all-attachments-")
        val identitySource = D2IdentitySource(List(5) { d2LiveObservation() })
        val runner = D2JvmRunner()
        val collector = JvmEvidenceCollector(
            config = d2Config(root),
            runner = runner,
            sleeper = SupervisorSleeper { },
            identityChecker = ProcessIdentityChecker(identitySource, startToleranceMillis = 0),
        )
        try {
            collector.capture(FIXTURE_PID, FIXTURE_START)

            runner.kinds shouldBe listOf(
                JvmCommandKind.THREAD_PRINT,
                JvmCommandKind.THREAD_PRINT,
                JvmCommandKind.THREAD_PRINT,
                JvmCommandKind.GC_HEAP_INFO,
                JvmCommandKind.VM_FLAGS,
            )
            identitySource.calls shouldBe 5
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("stops on a start-time mismatch between privileged attachments") {
        val root = Files.createTempDirectory("run-diagnostics-d2-start-mismatch-")
        val identitySource = D2IdentitySource(
            listOf(
                d2LiveObservation(),
                ProcessHandleObservation(
                    pid = FIXTURE_PID,
                    alive = true,
                    startInstant = FIXTURE_START.plusSeconds(1),
                ),
            ),
        )
        val runner = D2JvmRunner()
        val collector = JvmEvidenceCollector(
            config = d2Config(root),
            runner = runner,
            sleeper = SupervisorSleeper { },
            identityChecker = ProcessIdentityChecker(identitySource, startToleranceMillis = 0),
        )
        try {
            val evidence = collector.capture(FIXTURE_PID, FIXTURE_START)

            runner.kinds shouldBe listOf(JvmCommandKind.THREAD_PRINT)
            identitySource.calls shouldBe 2
            evidence.results.last().failureCode shouldBe SupervisorFailureCode.PROCESS_IDENTITY_MISMATCH
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("ExternalSupervisor stops the real capture path before the next JVM command after exit") {
        val root = Files.createTempDirectory("run-diagnostics-d2-supervisor-identity-")
        val identitySource = D2IdentitySource(
            listOf(
                d2LiveObservation(),
                d2LiveObservation(),
                d2LiveObservation(),
                null,
            ),
        )
        val runner = D2JvmRunner()
        val clock = MutableSupervisorClock()
        val guardedSupervisor = ExternalSupervisor(
            config = d2Config(root),
            processIdentityChecker = ProcessIdentityChecker(identitySource, startToleranceMillis = 0),
            statusSource = D2StatusSource(
                listOf(
                    d2Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                    d2Status(heartbeatSequence = 2, usefulProgressSequence = 1),
                ),
            ),
            metricsSampler = D2MetricsSampler(listOf(d2Metrics(), d2Metrics())),
            jvmRunner = runner,
            monotonicClock = clock,
            wallClock = FIXTURE_WALL_CLOCK,
            bundleSink = DiagnosticBundleSink { null },
            sleeper = SupervisorSleeper { },
        )
        try {
            guardedSupervisor.pollOnce()
            clock.elapsedNanos = 200_000_000
            val result = guardedSupervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            runner.kinds shouldBe listOf(JvmCommandKind.THREAD_PRINT)
            result.jvmEvidence!!.results.last().failureCode shouldBe SupervisorFailureCode.PROCESS_NOT_FOUND
        } finally {
            guardedSupervisor.close()
            root.toFile().deleteRecursively()
        }
    }

    test("retention failure latches bundle publication across writer instances") {
        val root = Files.createTempDirectory("run-diagnostics-d2-retention-latch-")
        try {
            val failingRetention: (Path, Int) -> DiagnosticRetentionResult = { _, _ ->
                DiagnosticRetentionResult(
                    availability = EvidenceAvailability.FAILED,
                    deletedBundleCount = 0,
                    failureCode = SupervisorFailureCode.RETENTION_FAILED,
                )
            }
            val writer = DiagnosticBundleWriter(root, retentionEnforcer = failingRetention)
            val first = writer.write(bundleInput(stallId = "stall-000001"))

            first.failures shouldContain SupervisorFailureCode.RETENTION_FAILED
            writer.captureEnabled("supervisor-test-run") shouldBe false
            Files.exists(root.resolve("supervisor-test-run/stalls/stall-000001")) shouldBe true

            val second = writer.write(bundleInput(stallId = "stall-000002"))
            second.failures shouldContain SupervisorFailureCode.RETENTION_FAILED
            Files.exists(root.resolve("supervisor-test-run/stalls/stall-000002")) shouldBe false

            val restartedWriter = DiagnosticBundleWriter(root)
            restartedWriter.captureEnabled("supervisor-test-run") shouldBe false
            val third = restartedWriter.write(bundleInput(stallId = "stall-000003"))
            third.failures shouldContain SupervisorFailureCode.RETENTION_FAILED
            Files.exists(root.resolve("supervisor-test-run/stalls/stall-000003")) shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("ExternalSupervisor stops JVM capture after a retention failure") {
        val root = Files.createTempDirectory("run-diagnostics-d2-supervisor-retention-")
        val runner = D2JvmRunner()
        val writer = DiagnosticBundleWriter(
            root,
            retentionEnforcer = { _, _ ->
                DiagnosticRetentionResult(
                    availability = EvidenceAvailability.FAILED,
                    deletedBundleCount = 0,
                    failureCode = SupervisorFailureCode.RETENTION_FAILED,
                )
            },
        )
        val supervisor = ExternalSupervisor(
            config = d2Config(root, maxDiagnosticBundles = 3),
            processIdentityChecker = ProcessIdentityChecker(D2IdentitySource(listOf(d2LiveObservation())), 0),
            statusSource = D2StatusSource(
                listOf(
                    d2Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                    d2Status(heartbeatSequence = 2, usefulProgressSequence = 1),
                    d2Status(heartbeatSequence = 3, usefulProgressSequence = 1),
                ),
            ),
            metricsSampler = D2MetricsSampler(listOf(d2Metrics(), d2Metrics(), d2Metrics())),
            jvmRunner = runner,
            monotonicClock = MutableSupervisorClock(0),
            wallClock = FIXTURE_WALL_CLOCK,
            bundleSink = writer,
            sleeper = SupervisorSleeper { },
        )

        try {
            supervisor.pollOnce()
            val clock = MutableSupervisorClock(0)
            val gatedSupervisor = ExternalSupervisor(
                config = d2Config(root, maxDiagnosticBundles = 3),
                processIdentityChecker = ProcessIdentityChecker(D2IdentitySource(listOf(d2LiveObservation())), 0),
                statusSource = D2StatusSource(
                    listOf(
                        d2Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                        d2Status(heartbeatSequence = 2, usefulProgressSequence = 1),
                        d2Status(heartbeatSequence = 3, usefulProgressSequence = 1),
                    ),
                ),
                metricsSampler = D2MetricsSampler(listOf(d2Metrics(), d2Metrics(), d2Metrics())),
                jvmRunner = runner,
                monotonicClock = clock,
                wallClock = FIXTURE_WALL_CLOCK,
                bundleSink = writer,
                sleeper = SupervisorSleeper { },
            )
            try {
                gatedSupervisor.pollOnce()
                clock.elapsedNanos = 200_000_000
                val firstStall = gatedSupervisor.pollOnce()
                val commandCountAfterFailure = runner.kinds.size
                clock.elapsedNanos = 400_000_000
                val secondStall = gatedSupervisor.pollOnce()

                firstStall.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
                firstStall.bundle!!.failures shouldContain SupervisorFailureCode.RETENTION_FAILED
                secondStall.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
                runner.kinds.size shouldBe commandCountAfterFailure
                Files.exists(root.resolve("supervisor-test-run/stalls/stall-000002")) shouldBe false
            } finally {
                gatedSupervisor.close()
            }
        } finally {
            supervisor.close()
            root.toFile().deleteRecursively()
        }
    }

    test("ExternalSupervisor latches a retention failure reported by a capture sink") {
        val root = Files.createTempDirectory("run-diagnostics-d2-sink-retention-")
        val runner = D2JvmRunner()
        val sink = D2RetentionFailureSink()
        val clock = MutableSupervisorClock()
        val supervisor = ExternalSupervisor(
            config = d2Config(root),
            processIdentityChecker = ProcessIdentityChecker(D2IdentitySource(listOf(d2LiveObservation())), 0),
            statusSource = D2StatusSource(
                listOf(
                    d2Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                    d2Status(heartbeatSequence = 2, usefulProgressSequence = 1),
                    d2Status(heartbeatSequence = 3, usefulProgressSequence = 1),
                ),
            ),
            metricsSampler = D2MetricsSampler(listOf(d2Metrics(), d2Metrics(), d2Metrics())),
            jvmRunner = runner,
            monotonicClock = clock,
            wallClock = FIXTURE_WALL_CLOCK,
            bundleSink = sink,
            sleeper = SupervisorSleeper { },
        )
        try {
            supervisor.pollOnce()
            clock.elapsedNanos = 200_000_000
            val firstStall = supervisor.pollOnce()
            val commandCountAfterFailure = runner.kinds.size
            clock.elapsedNanos = 400_000_000
            val secondStall = supervisor.pollOnce()

            firstStall.bundle!!.failures shouldContain SupervisorFailureCode.RETENTION_FAILED
            secondStall.bundle shouldBe null
            secondStall.captureAvailability shouldBe EvidenceAvailability.FAILED
            runner.kinds.size shouldBe commandCountAfterFailure
            sink.calls shouldBe 1
        } finally {
            supervisor.close()
            root.toFile().deleteRecursively()
        }
    }

    test("writer emits every required artifact with explicit unavailable envelopes") {
        val root = Files.createTempDirectory("run-diagnostics-d2-required-artifacts-")
        try {
            val result = DiagnosticBundleWriter(root).write(
                bundleInput(
                    status = null,
                    jvmResults = emptyList(),
                    safeArtifactSizes = emptyList(),
                ),
            )
            val directory = result.bundleDirectory!!
            val required = listOf(
                "bundle.json",
                "summary.json",
                "status.json",
                "process-metrics.json",
                "artifact-sizes.json",
                "recent-stages.json",
            )

            required.forEach { Files.exists(directory.resolve(it)) shouldBe true }
            val manifest = Files.readString(directory.resolve("bundle.json"))
            required.forEach { manifest shouldContain it }
            Files.readString(directory.resolve("status.json")) shouldContain "\"availability\":\"MISSING\""
            Files.readString(directory.resolve("artifact-sizes.json")) shouldContain
                "\"availability\":\"NOT_CONFIGURED\""
            Files.readString(directory.resolve("recent-stages.json")) shouldContain
                "\"availability\":\"NOT_CONFIGURED\""
            result.summary!!.files.filter { it.name in required }
                .all { it.required && it.availability == EvidenceAvailability.AVAILABLE } shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("available status artifact preserves the accepted RunStatusV1 payload") {
        val root = Files.createTempDirectory("run-diagnostics-d2-status-payload-")
        try {
            val expected = fixtureStatus()
            val result = DiagnosticBundleWriter(root).write(bundleInput(status = expected))

            RunStatusCodec.decode(
                Files.readAllBytes(result.bundleDirectory!!.resolve("status.json")),
            ) shouldBe expected
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

private fun d2Config(root: Path, maxDiagnosticBundles: Int = 3) = SupervisorConfigV1(
    targetPid = FIXTURE_PID,
    statusPath = root.resolve("run-status.json").toString(),
    diagnosticsDirectory = root.toString(),
    heartbeatTimeoutMillis = 100,
    usefulProgressTimeoutMillis = 100,
    sampleIntervalMillis = 10,
    diagnosticCaptureCooldownMillis = 100,
    maxDiagnosticBundles = maxDiagnosticBundles,
    threadDumpCount = 3,
    threadDumpIntervalMillis = 1,
    captureTimeoutMillis = 10,
    expectedProcessStartWallClock = FIXTURE_START.toString(),
)

private fun d2Status(
    heartbeatSequence: Long,
    usefulProgressSequence: Long,
) = fixtureStatus(
    heartbeatSequence = heartbeatSequence,
    usefulProgressSequence = usefulProgressSequence,
)

private fun d2LiveObservation() = ProcessHandleObservation(
    pid = FIXTURE_PID,
    alive = true,
    startInstant = FIXTURE_START,
)

private class D2IdentitySource(
    private val observations: List<ProcessHandleObservation?>,
) : ProcessHandleSource {
    private var index = 0
    var calls: Int = 0
        private set

    override fun observe(pid: Long): ProcessHandleObservation? {
        calls++
        val current = if (index < observations.size) observations[index++] else observations.lastOrNull()
        return current
    }
}

private class D2StatusSource(
    statuses: List<com.wingedsheep.rundiagnostics.RunStatusV1>,
) : StatusSource {
    private val remaining = ArrayDeque(statuses.map(StatusReadResult::Available))
    private var last: StatusReadResult = remaining.lastOrNull()
        ?: StatusReadResult.Unavailable(EvidenceAvailability.MISSING, SupervisorFailureCode.STATUS_MISSING)

    override fun read(): StatusReadResult = if (remaining.isEmpty()) last else remaining.removeFirst().also { last = it }
}

private class D2MetricsSampler(
    metrics: List<ProcessMetricsV1>,
) : ProcessMetricsSampler {
    private val remaining = ArrayDeque(metrics)
    private var last: ProcessMetricsV1 = metrics.lastOrNull() ?: d2Metrics()
    var calls: Int = 0
        private set

    override fun sample(pid: Long): ProcessMetricsV1 {
        calls++
        return if (remaining.isEmpty()) last else remaining.removeFirst().also { last = it }
    }
}

private class D2JvmRunner : JvmCommandRunner {
    val kinds: MutableList<JvmCommandKind> = ArrayList()

    override fun run(pid: Long, kind: JvmCommandKind, timeoutMillis: Long, maxBytes: Int): JvmCommandResult {
        kinds += kind
        return JvmCommandResult(
            kind = kind,
            availability = EvidenceAvailability.AVAILABLE,
            exitCode = 0,
            output = if (kind == JvmCommandKind.THREAD_PRINT) d2HotDump() else "metadata",
        )
    }
}

private class D2RetentionFailureSink : DiagnosticBundleSink {
    var calls: Int = 0

    override fun write(input: DiagnosticBundleInput): DiagnosticBundleResult {
        calls++
        return DiagnosticBundleResult(
            availability = EvidenceAvailability.FAILED,
            failures = listOf(SupervisorFailureCode.RETENTION_FAILED),
        )
    }
}

private fun d2Metrics() = ProcessMetricsV1(
    availability = EvidenceAvailability.AVAILABLE,
    cpuTimeNanos = 0,
    rssBytes = 1_024,
    threadCount = 2,
)

private fun d2HotDump(): String = """
    "hot-worker" #1 prio=5 os_prio=0 tid=0x1 nid=0x1 runnable
       java.lang.Thread.State: RUNNABLE
        at worker.Hot.loop(Hot.kt:1)
""".trimIndent()

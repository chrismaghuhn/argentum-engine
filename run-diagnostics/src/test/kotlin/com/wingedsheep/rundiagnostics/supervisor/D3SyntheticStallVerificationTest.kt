package com.wingedsheep.rundiagnostics.supervisor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class D3SyntheticStallVerificationTest : FunSpec({
    test("D3-01 HEALTHY_PROGRESS ignores long stage age when useful work advances") {
        val sink = D3RecordingBundleSink()
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1, stageStartedElapsedNanos = 0),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 2, stageStartedElapsedNanos = 0),
            ),
            metrics = listOf(d3Metrics(), d3Metrics()),
            bundleSink = sink,
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.HEALTHY
            result.decision.action shouldBe SupervisorAction.CONTINUE_OBSERVING
            sink.inputs shouldBe emptyList()
            fixture.jvmRunner.kinds shouldBe emptyList()
        } finally {
            fixture.close()
        }
    }

    test("D3-02 HEARTBEAT_ONLY_NO_USEFUL_PROGRESS captures a bounded suspected stall") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(), d3Metrics()),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            result.decision.action shouldBe SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE
            result.bundle?.summary?.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            Files.exists(result.bundle!!.bundleDirectory!!.resolve("summary.json")) shouldBe true
            result.process.liveness shouldBe ProcessLiveness.ALIVE
        } finally {
            fixture.close()
        }
    }

    test("D3-03 CPU_SPIN uses stable correlated RUNNABLE evidence") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = 0), d3Metrics(cpuTimeNanos = 200_000_000)),
            jvmRunner = d3AvailableJvmRunner(d3HotDump()),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.CPU_SPIN_SUSPECT
            result.jvmEvidence?.stableHotStack shouldBe true
            result.jvmEvidence?.deadlockDetected shouldBe false
            result.decision.action shouldBe SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE
        } finally {
            fixture.close()
        }
    }

    test("D3-04 BLOCKED_WAIT uses stable correlated WAITING evidence without deadlock claim") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = 0), d3Metrics(cpuTimeNanos = 0)),
            jvmRunner = d3AvailableJvmRunner(d3WaitingDump()),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.BLOCKED_WAIT_SUSPECT
            result.jvmEvidence?.stableWaitStack shouldBe true
            result.jvmEvidence?.deadlockDetected shouldBe false
            result.decision.action shouldBe SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE
        } finally {
            fixture.close()
        }
    }

    test("D3-05 PROCESS_EXIT stops observation without a retry or JVM attach") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(), d3Metrics()),
            processSource = D3ProcessSource(
                listOf(
                    ProcessHandleObservation(FIXTURE_PID, alive = true, startInstant = FIXTURE_START),
                    null,
                ),
            ),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.process.liveness shouldBe ProcessLiveness.PROCESS_EXITED
            result.decision.classification shouldBe DiagnosticClassification.PROCESS_EXITED
            result.decision.action shouldBe SupervisorAction.CONTINUE_OBSERVING
            fixture.metricsSampler.calls shouldBe 1
            fixture.jvmRunner.kinds shouldBe emptyList()
            fixture.processSource.calls shouldBe 2
        } finally {
            fixture.close()
        }
    }

    test("D3-08 MISSING_JCMD remains unavailable and falls back to generic stall evidence") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = null), d3Metrics(cpuTimeNanos = null)),
            jvmRunner = d3UnavailableJvmRunner(),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()
            val summary = result.bundle!!.summary!!

            result.jvmEvidence?.availability shouldBe EvidenceAvailability.FAILED
            result.jvmEvidence?.threadDumpCount shouldBe 0
            result.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            summary.files.filter { it.name.startsWith("privileged/") }
                .all { it.availability == EvidenceAvailability.NOT_CONFIGURED } shouldBe true
            Files.exists(result.bundle.bundleDirectory!!.resolve("privileged/thread-dump-0.txt")) shouldBe false
        } finally {
            fixture.close()
        }
    }

    test("D3-09 SLOW_BUT_PROGRESSING_STAGE remains healthy") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1, stageStartedElapsedNanos = 0),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 2, stageStartedElapsedNanos = 0),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = 0), d3Metrics(cpuTimeNanos = 200_000_000)),
            bundleSink = D3RecordingBundleSink(),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 86_000_000_000
            val result = fixture.supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.HEALTHY
            result.decision.action shouldBe SupervisorAction.CONTINUE_OBSERVING
            (fixture.bundleSink as D3RecordingBundleSink).inputs shouldBe emptyList()
        } finally {
            fixture.close()
        }
    }

    test("D3-10 stale sidecar with live process is not healthy") {
        val statusDirectory = Files.createTempDirectory("run-diagnostics-d3-stale-sidecar-")
        val statusPath = statusDirectory.resolve("run-status.json")
        Files.write(statusPath, com.wingedsheep.rundiagnostics.RunStatusCodec.encode(d3Status()))
        val sink = D3RecordingBundleSink()
        val fixture = newD3Supervisor(
            statuses = null,
            statusPath = statusPath,
            metrics = listOf(d3Metrics(), d3Metrics()),
            bundleSink = sink,
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.process.liveness shouldBe ProcessLiveness.ALIVE
            result.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            result.decision.action shouldBe SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE
            sink.inputs.size shouldBe 1
        } finally {
            fixture.close()
            statusDirectory.toFile().deleteRecursively()
        }
    }

    test("D3-11 missing CPU metrics do not become zero or a specific CPU diagnosis") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = null), d3Metrics(cpuTimeNanos = null)),
            jvmRunner = d3AvailableJvmRunner(d3HotDump()),
            bundleSink = D3RecordingBundleSink(),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.metrics.cpuTimeNanos shouldBe null
            result.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            result.decision.classification shouldNotBe DiagnosticClassification.CPU_SPIN_SUSPECT
            result.decision.classification shouldNotBe DiagnosticClassification.BLOCKED_WAIT_SUSPECT
        } finally {
            fixture.close()
        }
    }

    test("D3-12 missing RSS remains null while CPU evidence stays usable") {
        val sink = D3RecordingBundleSink()
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(
                d3Metrics(cpuTimeNanos = 0, rssBytes = null),
                d3Metrics(cpuTimeNanos = 200_000_000, rssBytes = null),
            ),
            jvmRunner = d3AvailableJvmRunner(d3HotDump()),
            bundleSink = sink,
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.metrics.rssBytes shouldBe null
            result.decision.classification shouldBe DiagnosticClassification.CPU_SPIN_SUSPECT
            sink.inputs.single().metrics.rssBytes shouldBe null
        } finally {
            fixture.close()
        }
    }

    test("D3-13 process exit during capture is observed next sample without further attach") {
        val processSource = D3ProcessSource()
        val jvmRunner = d3AvailableJvmRunner(d3HotDump())
        jvmRunner.onRun = { kind ->
            if (kind == JvmCommandKind.THREAD_PRINT && jvmRunner.kinds.size == 1) processSource.markExited()
        }
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 3, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = 0), d3Metrics(cpuTimeNanos = 0)),
            processSource = processSource,
            jvmRunner = jvmRunner,
            bundleSink = D3RecordingBundleSink(),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val duringCapture = fixture.supervisor.pollOnce()
            val callsAfterCapture = jvmRunner.kinds.size

            fixture.clock.elapsedNanos = 400_000_000
            val afterExit = fixture.supervisor.pollOnce()

            duringCapture.process.liveness shouldBe ProcessLiveness.ALIVE
            afterExit.process.liveness shouldBe ProcessLiveness.PROCESS_EXITED
            afterExit.decision.action shouldBe SupervisorAction.CONTINUE_OBSERVING
            jvmRunner.kinds.size shouldBe callsAfterCapture
            fixture.metricsSampler.calls shouldBe 2
        } finally {
            fixture.close()
        }
    }

    test("D3-15 command timeout remains bounded and explicit") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = null), d3Metrics(cpuTimeNanos = null)),
            jvmRunner = d3UnavailableJvmRunner(
                availability = EvidenceAvailability.TIMED_OUT,
                failureCode = SupervisorFailureCode.COMMAND_TIMED_OUT,
            ),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.jvmEvidence?.availability shouldBe EvidenceAvailability.TIMED_OUT
            result.jvmEvidence?.results?.all { it.availability == EvidenceAvailability.TIMED_OUT } shouldBe true
            result.bundle!!.summary!!.files.filter { it.name.startsWith("privileged/") }
                .all { it.availability == EvidenceAvailability.TIMED_OUT } shouldBe true
            result.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
        } finally {
            fixture.close()
        }
    }

    test("D3-16 explicit deadlock evidence is strongest but remains continue-only") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = 0), d3Metrics(cpuTimeNanos = 0)),
            jvmRunner = d3AvailableJvmRunner(d3DeadlockDump()),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.DEADLOCK_DETECTED
            result.decision.action shouldBe SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE
            result.jvmEvidence?.deadlockDetected shouldBe true
        } finally {
            fixture.close()
        }
    }

    test("D3-17 contradictory stable hot and wait evidence stays generic") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = 200_000_000), d3Metrics(cpuTimeNanos = 400_000_000)),
            jvmRunner = d3AvailableJvmRunner(d3ContradictoryDump()),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.jvmEvidence?.ambiguousThreadStateEvidence shouldBe true
            result.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            result.decision.classification shouldNotBe DiagnosticClassification.CPU_SPIN_SUSPECT
            result.decision.classification shouldNotBe DiagnosticClassification.BLOCKED_WAIT_SUSPECT
        } finally {
            fixture.close()
        }
    }

    test("D3-18 status cursor regression is unknown and does not capture") {
        val sink = D3RecordingBundleSink()
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 2),
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(), d3Metrics()),
            bundleSink = sink,
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()

            result.decision.classification shouldBe DiagnosticClassification.UNKNOWN
            result.decision.action shouldBe SupervisorAction.CONTINUE_OBSERVING
            sink.inputs shouldBe emptyList()
            fixture.jvmRunner.kinds shouldBe emptyList()
        } finally {
            fixture.close()
        }
    }

    test("D3-19 PID and start-time mismatch prevents metrics and JVM attachment") {
        val processSource = D3ProcessSource()
        val metrics = listOf(d3Metrics())
        val jvmRunner = d3AvailableJvmRunner(d3HotDump())
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(
                    processStartWallClock = FIXTURE_START.plusSeconds(1),
                    processId = FIXTURE_PID,
                ),
            ),
            metrics = metrics,
            processSource = processSource,
            jvmRunner = jvmRunner,
            bundleSink = D3RecordingBundleSink(),
        )
        try {
            val result = fixture.supervisor.pollOnce()

            result.process.liveness shouldBe ProcessLiveness.IDENTITY_MISMATCH
            result.decision.classification shouldBe DiagnosticClassification.UNKNOWN
            result.decision.action shouldBe SupervisorAction.CONTINUE_OBSERVING
            fixture.metricsSampler.calls shouldBe 0
            jvmRunner.kinds shouldBe emptyList()
        } finally {
            fixture.close()
        }
    }

    test("persistent synthetic stall respects capture cooldown and bundle count bound") {
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 3, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 4, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 5, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(), d3Metrics(), d3Metrics(), d3Metrics(), d3Metrics()),
            jvmRunner = d3UnavailableJvmRunner(),
            maxDiagnosticBundles = 2,
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 250_000_000
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 400_000_000
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 600_000_000
            val final = fixture.supervisor.pollOnce()

            final.decision.classification shouldBe DiagnosticClassification.SUSPECTED_STALL
            fixture.jvmRunner.kinds.size shouldBe 16
            Files.list(fixture.root.resolve("supervisor-test-run/stalls")).use { stream ->
                stream.filter { Files.isDirectory(it) }.count() shouldBe 2
            }
        } finally {
            fixture.close()
        }
    }

    test("D3-20 unavailable optional evidence is recorded without fabricated files") {
        val missingArtifact = Files.createTempDirectory("run-diagnostics-d3-missing-artifact-")
            .resolve("not-created.bin")
        val fixture = newD3Supervisor(
            statuses = listOf(
                d3Status(heartbeatSequence = 1, usefulProgressSequence = 1),
                d3Status(heartbeatSequence = 2, usefulProgressSequence = 1),
            ),
            metrics = listOf(d3Metrics(cpuTimeNanos = null), d3Metrics(cpuTimeNanos = null)),
            jvmRunner = d3UnavailableJvmRunner(),
            safeArtifactPaths = listOf(missingArtifact.toString()),
        )
        try {
            fixture.supervisor.pollOnce()
            fixture.clock.elapsedNanos = 200_000_000
            val result = fixture.supervisor.pollOnce()
            val bundle = result.bundle!!
            val summary = bundle.summary!!
            val summaryText = Files.readString(bundle.bundleDirectory!!.resolve("summary.json"))

            Files.readString(bundle.bundleDirectory.resolve("artifact-sizes.json"))
                .contains("\"availability\":\"MISSING\"") shouldBe true
            summary.files.any {
                it.name == "recent-stages.json" &&
                    it.availability == EvidenceAvailability.AVAILABLE &&
                    it.datasetSafe
            } shouldBe true
            summary.files.filter { it.name.startsWith("privileged/") }
                .all { it.availability == EvidenceAvailability.NOT_CONFIGURED && !it.datasetSafe } shouldBe true
            summary.privilegedDiagnosticPolicy shouldBe "DEVELOPER_PRIVILEGED_DIAGNOSTIC_NOT_DATASET_SAFE"
            summaryText.lowercase().contains("gamestate") shouldBe false
            summaryText.lowercase().contains("playerobservation") shouldBe false
            summaryText.lowercase().contains("completelegaldomain") shouldBe false
        } finally {
            fixture.close()
            missingArtifact.parent.toFile().deleteRecursively()
        }
    }
})

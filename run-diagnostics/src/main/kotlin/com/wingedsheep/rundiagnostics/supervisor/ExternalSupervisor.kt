package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.BoundedHistoryRing
import com.wingedsheep.rundiagnostics.ElapsedMonotonicClock
import com.wingedsheep.rundiagnostics.SystemMonotonicClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

public fun interface StatusSource {
    public fun read(): StatusReadResult
}

public data class SupervisorPollResult(
    public val observedAtWallClock: String,
    public val process: ProcessIdentityResult,
    public val status: StatusReadResult,
    public val metrics: ProcessMetricsV1,
    public val decision: ClassificationDecision,
    public val jvmEvidence: JvmEvidenceV1? = null,
    public val bundle: DiagnosticBundleResult? = null,
    public val captureAvailability: EvidenceAvailability = EvidenceAvailability.NOT_CONFIGURED,
)

public data class SupervisorRunResult(
    public val lastPoll: SupervisorPollResult?,
)

/**
 * External-only supervisor orchestration. It observes a PID and scalar sidecar, captures bounded
 * evidence after a trigger, and always returns to observation; it has no kill/retry/recovery path.
 */
public class ExternalSupervisor(
    private val config: SupervisorConfigV1,
    private val processIdentityChecker: ProcessIdentityChecker = ProcessIdentityChecker(
        JdkProcessHandleSource(),
        config.processStartToleranceMillis,
    ),
    private val statusSource: StatusSource = StatusSource {
        StatusSidecarReader(Path.of(config.statusPath)).read()
    },
    private val metricsSampler: ProcessMetricsSampler = SystemProcessMetricsSampler.create(),
    private val jvmRunner: JvmCommandRunner = JdkJvmCommandRunner(
        config.jvmToolDirectory?.let(Path::of) ?: Path.of(System.getProperty("java.home"), "bin"),
    ),
    monotonicClock: com.wingedsheep.rundiagnostics.MonotonicClock = SystemMonotonicClock,
    private val wallClock: Clock = Clock.systemUTC(),
    private val bundleSink: DiagnosticBundleSink = DiagnosticBundleWriter(
        Path.of(config.diagnosticsDirectory),
        config.maxDiagnosticBundles,
        config.maxBundleBytes,
    ),
    private val sleeper: SupervisorSleeper = SupervisorSleeper { millis -> Thread.sleep(millis) },
) : AutoCloseable {
    private val elapsedClock = ElapsedMonotonicClock(monotonicClock)
    private val classifier = StallClassifier(config)
    private val jvmCollector = JvmEvidenceCollector(config, jvmRunner, sleeper, processIdentityChecker)
    private val history = BoundedHistoryRing<SupervisorHistoryEntryV1>(config.maxHistorySamples)
    private var state = SupervisorState()
    private var stopped = false
    private var nextStallOrdinal = 1L
    private var retentionFailureLatched = false

    public fun pollOnce(): SupervisorPollResult {
        val now = elapsedClock.nowElapsedNanos()
        val wallClockNow = safeWallClock()
        val status = safeStatusRead()
        val expectedStart = expectedProcessStart(status)
        val process = processForStatus(status, expectedStart)
        val metrics = safeMetrics(process, now)
        val baseInput = ClassifierInput(now, process, status, metrics)
        val samplingStateBefore = state
        var decision = classifier.classify(baseInput, samplingStateBefore)
        var evidence: JvmEvidenceV1? = null
        var bundle: DiagnosticBundleResult? = null
        var captureAvailability = EvidenceAvailability.NOT_CONFIGURED

        val diagnosticRunId = statusDiagnosticRunId(status)
        if (decision.action == SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE &&
            decision.trigger != StallTriggerKind.PROCESS_EXITED
        ) {
            when (captureGate(now, diagnosticRunId)) {
                CaptureGate.CAPTURE -> {
                    evidence = jvmCollector.capture(config.targetPid, expectedStart)
                    val captureState = samplingStateBefore.copy(
                        capturesCompleted = samplingStateBefore.capturesCompleted + 1,
                        lastCaptureElapsedNanos = now,
                    )
                    // Evidence capture must not replace the previous sampling baseline. The
                    // second classification is for the same sample, so CPU/wall deltas remain
                    // comparable to the sample that triggered capture.
                    decision = classifier.classify(
                        baseInput.copy(jvmEvidence = evidence),
                        samplingStateBefore,
                    )
                    state = decision.nextState.copy(
                        capturesCompleted = captureState.capturesCompleted,
                        lastCaptureElapsedNanos = captureState.lastCaptureElapsedNanos,
                    )
                    val input = DiagnosticBundleInput(
                        diagnosticRunId = statusDiagnosticRunId(status),
                        stallId = nextStallId(),
                        createdWallClock = wallClockNow,
                        trigger = decision.trigger,
                        classification = decision.classification,
                        action = decision.action,
                        status = (status as? StatusReadResult.Available)?.status,
                        metrics = metrics,
                        process = process,
                        recentHistory = history.snapshot(),
                        jvmResults = evidence.results,
                        safeArtifactSizes = readSafeArtifactSizes(),
                    )
                    bundle = try {
                        bundleSink.write(input)
                    } catch (_: Exception) {
                        null
                    }
                    if (bundle?.failures?.contains(SupervisorFailureCode.RETENTION_FAILED) == true) {
                        retentionFailureLatched = true
                    }
                    captureAvailability = if (bundle == null) EvidenceAvailability.FAILED
                    else bundle.availability
                }

                CaptureGate.COOLDOWN -> captureAvailability = EvidenceAvailability.NOT_CONFIGURED
                CaptureGate.MAX_BUNDLES -> captureAvailability = EvidenceAvailability.NOT_CONFIGURED
                CaptureGate.RETENTION_FAILED -> captureAvailability = EvidenceAvailability.FAILED
            }
        }

        state = decision.nextState.copy(
            capturesCompleted = state.capturesCompleted,
            lastCaptureElapsedNanos = state.lastCaptureElapsedNanos,
        )
        history.add(
            SupervisorHistoryEntryV1(
                observedAtWallClock = wallClockNow.toString(),
                heartbeatSequence = (status as? StatusReadResult.Available)?.status?.heartbeatSequence,
                usefulProgressSequence = (status as? StatusReadResult.Available)
                    ?.status?.progress?.usefulProgressSequence,
                stageSequence = (status as? StatusReadResult.Available)?.status?.stageSequence,
                classification = decision.classification,
            ),
        )
        return SupervisorPollResult(
            observedAtWallClock = wallClockNow.toString(),
            process = process,
            status = status,
            metrics = metrics,
            decision = decision,
            jvmEvidence = evidence,
            bundle = bundle,
            captureAvailability = captureAvailability,
        )
    }

    public fun run(): SupervisorRunResult {
        var lastPoll: SupervisorPollResult? = null
        while (!stopped) {
            lastPoll = pollOnce()
            if (config.once || lastPoll.process.liveness == ProcessLiveness.PROCESS_EXITED) break
            try {
                sleeper.sleepMillis(config.sampleIntervalMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return SupervisorRunResult(lastPoll)
    }

    override fun close() {
        stopped = true
    }

    private fun safeStatusRead(): StatusReadResult = try {
        statusSource.read()
    } catch (_: Exception) {
        StatusReadResult.Unavailable(EvidenceAvailability.FAILED, SupervisorFailureCode.STATUS_READ_FAILED)
    }

    private fun processForStatus(status: StatusReadResult, expectedStart: Instant?): ProcessIdentityResult {
        val statusPid = (status as? StatusReadResult.Available)?.status?.processId
        if (statusPid != null && statusPid != config.targetPid) {
            return ProcessIdentityResult(ProcessLiveness.IDENTITY_MISMATCH, null)
        }
        return processIdentityChecker.observe(config.targetPid, expectedStart)
    }

    private fun expectedProcessStart(status: StatusReadResult): Instant? {
        config.expectedProcessStartWallClock?.let { return Instant.parse(it) }
        return (status as? StatusReadResult.Available)?.status?.processStartWallClock?.let(Instant::parse)
    }

    private fun safeMetrics(process: ProcessIdentityResult, now: Long): ProcessMetricsV1 {
        if (process.liveness != ProcessLiveness.ALIVE) {
            return ProcessMetricsV1(
                availability = EvidenceAvailability.NOT_CONFIGURED,
                failureCode = SupervisorFailureCode.METRICS_UNAVAILABLE,
            )
        }
        return try {
            metricsSampler.sample(config.targetPid).let { sampled ->
                if (sampled.sampledAtElapsedNanos == null) sampled.copy(sampledAtElapsedNanos = now) else sampled
            }
        } catch (_: Exception) {
            ProcessMetricsV1(
                availability = EvidenceAvailability.FAILED,
                failureCode = SupervisorFailureCode.METRICS_UNAVAILABLE,
            )
        }
    }

    private fun captureGate(now: Long, diagnosticRunId: String): CaptureGate {
        if (retentionFailureLatched || !safeCaptureEnabled(diagnosticRunId)) return CaptureGate.RETENTION_FAILED
        if (state.capturesCompleted >= config.maxDiagnosticBundles) return CaptureGate.MAX_BUNDLES
        val lastCapture = state.lastCaptureElapsedNanos
        if (lastCapture != null && now - lastCapture < config.captureCooldownNanos()) {
            return CaptureGate.COOLDOWN
        }
        return CaptureGate.CAPTURE
    }

    private fun safeCaptureEnabled(diagnosticRunId: String): Boolean = try {
        bundleSink.captureEnabled(diagnosticRunId)
    } catch (_: Exception) {
        false
    }

    private fun nextStallId(): String {
        val id = "stall-${nextStallOrdinal.toString().padStart(6, '0')}"
        nextStallOrdinal++
        return id
    }

    private fun statusDiagnosticRunId(status: StatusReadResult): String =
        (status as? StatusReadResult.Available)?.status?.diagnosticRunId ?: "supervisor-${config.targetPid}"

    private fun readSafeArtifactSizes(): List<SafeArtifactSizeV1> = config.safeArtifactPaths.mapIndexed { index, rawPath ->
        try {
            val path = Path.of(rawPath)
            if (!Files.isRegularFile(path)) {
                SafeArtifactSizeV1(
                    logicalName = "artifact-$index",
                    availability = EvidenceAvailability.MISSING,
                    failureCode = SupervisorFailureCode.ARTIFACT_SIZE_UNAVAILABLE,
                )
            } else {
                SafeArtifactSizeV1(logicalName = "artifact-$index", bytes = Files.size(path))
            }
        } catch (_: Exception) {
            SafeArtifactSizeV1(
                logicalName = "artifact-$index",
                availability = EvidenceAvailability.FAILED,
                failureCode = SupervisorFailureCode.ARTIFACT_SIZE_UNAVAILABLE,
            )
        }
    }

    private fun safeWallClock(): Instant = try {
        wallClock.instant()
    } catch (_: Exception) {
        Instant.EPOCH
    }

    private enum class CaptureGate {
        CAPTURE,
        COOLDOWN,
        MAX_BUNDLES,
        RETENTION_FAILED,
    }
}

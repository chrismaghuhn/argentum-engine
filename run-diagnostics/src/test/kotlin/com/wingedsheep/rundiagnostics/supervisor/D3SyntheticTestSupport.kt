package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.MonotonicAgeDataV1
import com.wingedsheep.rundiagnostics.RunStatusV1
import com.wingedsheep.rundiagnostics.StatusPublicationV1
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque

internal fun d3Status(
    heartbeatSequence: Long = 1,
    usefulProgressSequence: Long = 1,
    stageSequence: Long = 0,
    processId: Long? = FIXTURE_PID,
    processStartWallClock: java.time.Instant = FIXTURE_START,
    stageStartedElapsedNanos: Long = 0,
): RunStatusV1 = fixtureStatus(
    heartbeatSequence = heartbeatSequence,
    usefulProgressSequence = usefulProgressSequence,
    stageSequence = stageSequence,
    processId = processId,
    processStartWallClock = processStartWallClock,
).copy(
    monotonicAgeData = MonotonicAgeDataV1(
        heartbeatElapsedNanos = 1,
        stageStartedElapsedNanos = stageStartedElapsedNanos,
        lastUsefulProgressElapsedNanos = 1,
    ),
    statusPublication = StatusPublicationV1(),
)

internal fun d3Metrics(
    cpuTimeNanos: Long? = 0,
    rssBytes: Long? = 1_024,
    threadCount: Long? = 2,
): ProcessMetricsV1 = ProcessMetricsV1(
    availability = EvidenceAvailability.AVAILABLE,
    cpuTimeNanos = cpuTimeNanos,
    rssBytes = rssBytes,
    threadCount = threadCount,
)

internal class D3StatusTimeline(statuses: List<StatusReadResult>) : StatusSource {
    private val remaining = ArrayDeque(statuses)
    private var last: StatusReadResult = statuses.lastOrNull()
        ?: StatusReadResult.Unavailable(EvidenceAvailability.MISSING, SupervisorFailureCode.STATUS_MISSING)

    override fun read(): StatusReadResult = if (remaining.isEmpty()) {
        last
    } else {
        remaining.removeFirst().also { last = it }
    }
}

internal class D3ProcessSource(
    observations: List<ProcessHandleObservation?> = listOf(
        ProcessHandleObservation(FIXTURE_PID, alive = true, startInstant = FIXTURE_START),
    ),
) : ProcessHandleSource {
    private val observations = observations
    private var nextObservationIndex = 0
    private var current: ProcessHandleObservation? = null
    private var initialized = false
    var calls: Int = 0
        private set

    fun markExited() {
        current = null
        initialized = true
    }

    override fun observe(pid: Long): ProcessHandleObservation? {
        calls++
        if (nextObservationIndex < observations.size) {
            current = observations[nextObservationIndex++]
            initialized = true
        }
        return if (initialized) current else null
    }
}

internal class D3MetricsSampler(metrics: List<ProcessMetricsV1>) : ProcessMetricsSampler {
    private val remaining = ArrayDeque(metrics)
    private var last: ProcessMetricsV1 = metrics.lastOrNull() ?: d3Metrics()
    var calls: Int = 0
        private set

    override fun sample(pid: Long): ProcessMetricsV1 {
        calls++
        return if (remaining.isEmpty()) last else remaining.removeFirst().also { last = it }
    }
}

internal class D3JvmRunner(
    private val resultFactory: (JvmCommandKind) -> JvmCommandResult,
) : JvmCommandRunner {
    val kinds: MutableList<JvmCommandKind> = ArrayList()
    val pids: MutableList<Long> = ArrayList()
    var onRun: ((JvmCommandKind) -> Unit)? = null

    override fun run(pid: Long, kind: JvmCommandKind, timeoutMillis: Long, maxBytes: Int): JvmCommandResult {
        pids += pid
        kinds += kind
        onRun?.invoke(kind)
        return resultFactory(kind)
    }
}

internal fun d3AvailableJvmRunner(
    threadDump: String,
    onRun: ((JvmCommandKind) -> Unit)? = null,
): D3JvmRunner = D3JvmRunner { kind ->
    JvmCommandResult(
        kind = kind,
        availability = EvidenceAvailability.AVAILABLE,
        exitCode = 0,
        output = if (kind == JvmCommandKind.THREAD_PRINT) threadDump else "metadata",
    )
}.also { it.onRun = onRun }

internal fun d3UnavailableJvmRunner(
    availability: EvidenceAvailability = EvidenceAvailability.NOT_CONFIGURED,
    failureCode: SupervisorFailureCode = SupervisorFailureCode.COMMAND_TOOL_MISSING,
): D3JvmRunner = D3JvmRunner { kind ->
    JvmCommandResult(
        kind = kind,
        availability = availability,
        failureCode = failureCode,
    )
}

internal class D3RecordingBundleSink(
    private val returnResult: DiagnosticBundleResult? = null,
) : DiagnosticBundleSink {
    val inputs: MutableList<DiagnosticBundleInput> = ArrayList()

    override fun write(input: DiagnosticBundleInput): DiagnosticBundleResult? {
        inputs += input
        return returnResult
    }
}

internal class D3SupervisorFixture(
    val root: Path,
    val clock: MutableSupervisorClock,
    val processSource: D3ProcessSource,
    val metricsSampler: D3MetricsSampler,
    val jvmRunner: D3JvmRunner,
    val bundleSink: DiagnosticBundleSink,
    val supervisor: ExternalSupervisor,
) : AutoCloseable {
    override fun close() {
        supervisor.close()
        root.toFile().deleteRecursively()
    }
}

internal fun newD3Supervisor(
    statuses: Iterable<*>?,
    metrics: List<ProcessMetricsV1>,
    clock: MutableSupervisorClock = MutableSupervisorClock(),
    processSource: D3ProcessSource = D3ProcessSource(),
    jvmRunner: D3JvmRunner = d3AvailableJvmRunner(d3HotDump()),
    bundleSink: DiagnosticBundleSink? = null,
    statusPath: Path? = null,
    maxDiagnosticBundles: Int = 3,
    maxHistorySamples: Int = 128,
    threadDumpCount: Int = 3,
    maxBundleBytes: Int = 4 * 1024 * 1024,
    safeArtifactPaths: List<String> = emptyList(),
): D3SupervisorFixture {
    val root = Files.createTempDirectory("run-diagnostics-d3-")
    val actualStatusPath = statusPath ?: root.resolve("run-status.json")
    val actualBundleSink = bundleSink ?: DiagnosticBundleWriter(
        root = root,
        maxDiagnosticBundles = maxDiagnosticBundles,
        maxBundleBytes = maxBundleBytes,
    )
    val config = SupervisorConfigV1(
        targetPid = FIXTURE_PID,
        statusPath = actualStatusPath.toString(),
        diagnosticsDirectory = root.toString(),
        heartbeatTimeoutMillis = 100,
        usefulProgressTimeoutMillis = 100,
        sampleIntervalMillis = 10,
        diagnosticCaptureCooldownMillis = 100,
        maxDiagnosticBundles = maxDiagnosticBundles,
        maxHistorySamples = maxHistorySamples,
        threadDumpCount = threadDumpCount,
        threadDumpIntervalMillis = 1,
        captureTimeoutMillis = 10,
        maxBundleBytes = maxBundleBytes,
        safeArtifactPaths = safeArtifactPaths,
    )
    val actualStatusSource = statuses?.let { values ->
        D3StatusTimeline(values.map { value ->
            when (value) {
                is StatusReadResult -> value
                is RunStatusV1 -> StatusReadResult.Available(value)
                else -> error("unsupported D3 status fixture")
            }
        })
    } ?: StatusSource {
        StatusSidecarReader(actualStatusPath).read()
    }
    val actualMetricsSampler = D3MetricsSampler(metrics)
    val supervisor = ExternalSupervisor(
        config = config,
        processIdentityChecker = ProcessIdentityChecker(processSource, startToleranceMillis = 0),
        statusSource = actualStatusSource,
        metricsSampler = actualMetricsSampler,
        jvmRunner = jvmRunner,
        monotonicClock = clock,
        wallClock = FIXTURE_WALL_CLOCK,
        bundleSink = actualBundleSink,
        sleeper = SupervisorSleeper { },
    )
    return D3SupervisorFixture(
        root = root,
        clock = clock,
        processSource = processSource,
        metricsSampler = actualMetricsSampler,
        jvmRunner = jvmRunner,
        bundleSink = actualBundleSink,
        supervisor = supervisor,
    )
}

internal fun d3HotDump(): String = """
    "hot-worker" #1 prio=5 os_prio=0 tid=0x1 nid=0x1 runnable
       java.lang.Thread.State: RUNNABLE
        at worker.Hot.loop(Hot.kt:1)
""".trimIndent()

internal fun d3WaitingDump(): String = """
    "waiting-worker" #2 prio=5 os_prio=0 tid=0x2 nid=0x2 waiting on condition
       java.lang.Thread.State: WAITING (parking)
        at worker.Wait.await(Wait.kt:1)
""".trimIndent()

internal fun d3ContradictoryDump(): String = """
    "hot-worker" #1 prio=5 os_prio=0 tid=0x1 nid=0x1 runnable
       java.lang.Thread.State: RUNNABLE
        at worker.Hot.loop(Hot.kt:1)
    "waiting-worker" #2 prio=5 os_prio=0 tid=0x2 nid=0x2 waiting on condition
       java.lang.Thread.State: WAITING (parking)
        at worker.Wait.await(Wait.kt:1)
""".trimIndent()

internal fun d3DeadlockDump(): String = """
    Found one Java-level deadlock:
    =============================
    \"thread-a\" waits to lock monitor held by thread-b
    \"thread-b\" waits to lock monitor held by thread-a
""".trimIndent()

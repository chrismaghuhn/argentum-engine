package com.wingedsheep.rundiagnostics

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Serializable
public data class ProgressHistoryEntryV1(
    public val schemaVersion: Int = DiagnosticsSchema.PROGRESS_HISTORY_SCHEMA_VERSION,
    public val schemaIdentity: String = DiagnosticsSchema.PROGRESS_HISTORY_SCHEMA_IDENTITY,
    public val sequence: Long,
    public val kind: ProgressHistoryKind,
    public val monotonicElapsedNanos: Long,
    public val heartbeatSequence: Long,
    public val usefulProgressSequence: Long,
    public val stageSequence: Long,
    public val currentStage: StageRefV1,
) {
    init {
        requireSchemaVersion(schemaVersion, DiagnosticsSchema.PROGRESS_HISTORY_SCHEMA_VERSION, "schemaVersion")
        requireSchemaIdentity(schemaIdentity, DiagnosticsSchema.PROGRESS_HISTORY_SCHEMA_IDENTITY, "schemaIdentity")
        requireNonNegative(sequence, "sequence")
        requireNonNegative(monotonicElapsedNanos, "monotonicElapsedNanos")
        requireNonNegative(heartbeatSequence, "heartbeatSequence")
        requireNonNegative(usefulProgressSequence, "usefulProgressSequence")
        requireNonNegative(stageSequence, "stageSequence")
    }
}

/**
 * Generic scalar diagnostics sink. The enabled implementation records operational progress only;
 * it has no reference to Rules, game state, actions, observations, replay, or training data.
 *
 * Callers should keep the recorder nullable at the workload boundary. That is the intended disabled
 * path: no clock read, object construction, serialization, or file operation per transition.
 */
public interface DiagnosticsRecorder : AutoCloseable {
    public val enabled: Boolean

    /** Advances independently of useful-work counters. Returns the new sequence, or zero when disabled. */
    public fun heartbeatTick(): Long

    /** Records a versioned workload stage transition; stage ownership remains with the future adapter. */
    public fun advanceStage(stage: StageRefV1)

    /**
     * Applies only explicitly supplied scalar deltas. A null field means unavailable/no update; zero is
     * an explicit known delta. No field is inferred from another counter. Useful progress advances
     * only when a supplied counter delta is positive; gauge updates alone are not progress.
     */
    public fun recordUsefulProgress(
        episodeOrdinal: Long? = null,
        engineProgressDelta: Long? = null,
        authoritativeTransitionDelta: Long? = null,
        semanticDecisionDelta: Long? = null,
        trajectoryDecisionDelta: Long? = null,
        replayFramesVerifiedDelta: Long? = null,
        episodesAdmittedDelta: Long? = null,
        bytesSerializedDelta: Long? = null,
        shardsFinalizedDelta: Long? = null,
    )

    /**
     * Replaces the bounded scalar artifact view with caller-owned, public-safe counters. This is an
     * observation only and never refreshes useful-progress time or sequence.
     */
    public fun recordArtifactCounters(counters: List<ArtifactCounterV1>)

    /** Returns a point-in-time scalar status, or null for the disabled implementation. */
    public fun snapshot(): RunStatusV1?

    /** Returns a bounded copy of recent operational events. */
    public fun recentHistory(): List<ProgressHistoryEntryV1>

    override public fun close()

    public companion object {
        public fun disabled(): DiagnosticsRecorder = NoOpDiagnosticsRecorder

        public fun enabled(
            diagnosticRunId: String,
            sourceCommit: String,
            workloadType: String,
            initialStage: StageRefV1,
            semanticJobId: String? = null,
            processId: Long? = ProcessHandle.current().pid(),
            wallClock: Clock = Clock.systemUTC(),
            monotonicClock: MonotonicClock = MonotonicClock.system(),
            historyCapacity: Int = DiagnosticsSchema.DEFAULT_HISTORY_CAPACITY,
        ): DiagnosticsRecorder = DefaultDiagnosticsRecorder(
            diagnosticRunId = diagnosticRunId,
            sourceCommit = sourceCommit,
            workloadType = workloadType,
            initialStage = initialStage,
            semanticJobId = semanticJobId,
            processId = processId,
            wallClock = wallClock,
            monotonicClock = monotonicClock,
            historyCapacity = historyCapacity,
        )
    }
}

private object NoOpDiagnosticsRecorder : DiagnosticsRecorder {
    override val enabled: Boolean = false

    override fun heartbeatTick(): Long = 0

    override fun advanceStage(stage: StageRefV1) = Unit

    override fun recordUsefulProgress(
        episodeOrdinal: Long?,
        engineProgressDelta: Long?,
        authoritativeTransitionDelta: Long?,
        semanticDecisionDelta: Long?,
        trajectoryDecisionDelta: Long?,
        replayFramesVerifiedDelta: Long?,
        episodesAdmittedDelta: Long?,
        bytesSerializedDelta: Long?,
        shardsFinalizedDelta: Long?,
    ) = Unit

    override fun recordArtifactCounters(counters: List<ArtifactCounterV1>) = Unit

    override fun snapshot(): RunStatusV1? = null

    override fun recentHistory(): List<ProgressHistoryEntryV1> = emptyList()

    override fun close() = Unit
}

private class DefaultDiagnosticsRecorder(
    private val diagnosticRunId: String,
    private val sourceCommit: String,
    private val workloadType: String,
    initialStage: StageRefV1,
    private val semanticJobId: String?,
    private val processId: Long?,
    private val wallClock: Clock,
    monotonicClock: MonotonicClock,
    historyCapacity: Int,
) : DiagnosticsRecorder {
    override val enabled: Boolean = true

    private val closed = AtomicBoolean(false)
    private val elapsedClock = ElapsedMonotonicClock(monotonicClock)
    private val processStartInstant: Instant = wallClock.instant()

    private val heartbeatSequence = AtomicLong(0)
    private val heartbeatElapsedNanos = AtomicLong(UNSET)
    private val heartbeatWallClock = AtomicReference<Instant?>(null)

    private val currentStage = AtomicReference(initialStage)
    private val stageSequence = AtomicLong(0)
    private val stageStartedElapsedNanos = AtomicLong(elapsedClock.nowElapsedNanos())
    private val stageStartedWallClock = AtomicReference(processStartInstant)

    private val lastUsefulProgressElapsedNanos = AtomicLong(stageStartedElapsedNanos.get())
    private val usefulProgressSequence = AtomicLong(0)
    private val eventSequence = AtomicLong(0)

    private val episodeOrdinal = AtomicLong(UNSET)
    private val engineProgressCount = AtomicLong(UNSET)
    private val authoritativeTransitionCount = AtomicLong(UNSET)
    private val semanticDecisionCount = AtomicLong(UNSET)
    private val trajectoryDecisionCount = AtomicLong(UNSET)
    private val replayFramesVerified = AtomicLong(UNSET)
    private val episodesAdmitted = AtomicLong(UNSET)
    private val bytesSerialized = AtomicLong(UNSET)
    private val shardsFinalized = AtomicLong(UNSET)

    private val artifactCounters = AtomicReference<List<ArtifactCounterV1>>(emptyList())
    private val history = BoundedHistoryRing<ProgressHistoryEntryV1>(historyCapacity)

    init {
        requireSafeToken(diagnosticRunId, "diagnosticRunId")
        requireSafeLabel(sourceCommit, "sourceCommit")
        requireSafeToken(workloadType, "workloadType")
        semanticJobId?.let { requireSafeToken(it, "semanticJobId") }
        processId?.let { require(it > 0) { "processId must be positive when present" } }

        appendHistory(ProgressHistoryKind.STAGE_CHANGED, stageStartedElapsedNanos.get())
    }

    override fun heartbeatTick(): Long {
        if (closed.get()) return 0
        val elapsed = elapsedClock.nowElapsedNanos()
        val sequence = heartbeatSequence.incrementAndGet()
        heartbeatElapsedNanos.set(elapsed)
        heartbeatWallClock.set(wallClock.instant())
        appendHistory(ProgressHistoryKind.HEARTBEAT, elapsed)
        return sequence
    }

    override fun advanceStage(stage: StageRefV1) {
        if (closed.get()) return
        while (!closed.get()) {
            val previousStage = currentStage.get()
            if (previousStage == stage) return
            if (currentStage.compareAndSet(previousStage, stage)) {
                val elapsed = elapsedClock.nowElapsedNanos()
                stageSequence.incrementAndGet()
                stageStartedElapsedNanos.set(elapsed)
                stageStartedWallClock.set(wallClock.instant())
                markUseful(elapsed)
                appendHistory(ProgressHistoryKind.STAGE_CHANGED, elapsed)
                return
            }
        }
    }

    override fun recordUsefulProgress(
        episodeOrdinal: Long?,
        engineProgressDelta: Long?,
        authoritativeTransitionDelta: Long?,
        semanticDecisionDelta: Long?,
        trajectoryDecisionDelta: Long?,
        replayFramesVerifiedDelta: Long?,
        episodesAdmittedDelta: Long?,
        bytesSerializedDelta: Long?,
        shardsFinalizedDelta: Long?,
    ) {
        if (closed.get()) return
        validateDelta(episodeOrdinal, "episodeOrdinal")
        validateDelta(engineProgressDelta, "engineProgressDelta")
        validateDelta(authoritativeTransitionDelta, "authoritativeTransitionDelta")
        validateDelta(semanticDecisionDelta, "semanticDecisionDelta")
        validateDelta(trajectoryDecisionDelta, "trajectoryDecisionDelta")
        validateDelta(replayFramesVerifiedDelta, "replayFramesVerifiedDelta")
        validateDelta(episodesAdmittedDelta, "episodesAdmittedDelta")
        validateDelta(bytesSerializedDelta, "bytesSerializedDelta")
        validateDelta(shardsFinalizedDelta, "shardsFinalizedDelta")

        val counterAdvanced =
            (engineProgressDelta != null && engineProgressDelta > 0) ||
                (authoritativeTransitionDelta != null && authoritativeTransitionDelta > 0) ||
                (semanticDecisionDelta != null && semanticDecisionDelta > 0) ||
                (trajectoryDecisionDelta != null && trajectoryDecisionDelta > 0) ||
                (replayFramesVerifiedDelta != null && replayFramesVerifiedDelta > 0) ||
                (episodesAdmittedDelta != null && episodesAdmittedDelta > 0) ||
                (bytesSerializedDelta != null && bytesSerializedDelta > 0) ||
                (shardsFinalizedDelta != null && shardsFinalizedDelta > 0)

        episodeOrdinal?.let {
            this.episodeOrdinal.set(it)
        }
        addDelta(engineProgressCount, engineProgressDelta, "engineProgressDelta")
        addDelta(authoritativeTransitionCount, authoritativeTransitionDelta, "authoritativeTransitionDelta")
        addDelta(semanticDecisionCount, semanticDecisionDelta, "semanticDecisionDelta")
        addDelta(trajectoryDecisionCount, trajectoryDecisionDelta, "trajectoryDecisionDelta")
        addDelta(replayFramesVerified, replayFramesVerifiedDelta, "replayFramesVerifiedDelta")
        addDelta(episodesAdmitted, episodesAdmittedDelta, "episodesAdmittedDelta")
        addDelta(bytesSerialized, bytesSerializedDelta, "bytesSerializedDelta")
        addDelta(shardsFinalized, shardsFinalizedDelta, "shardsFinalizedDelta")

        val elapsed = elapsedClock.nowElapsedNanos()
        if (counterAdvanced) {
            markUseful(elapsed)
            appendHistory(ProgressHistoryKind.USEFUL_PROGRESS, elapsed)
        }
    }

    override fun recordArtifactCounters(counters: List<ArtifactCounterV1>) {
        if (closed.get()) return
        require(counters.size <= DiagnosticsSchema.MAX_ARTIFACT_COUNTERS) {
            "artifact counter list exceeds the bounded maximum"
        }
        require(counters.map(ArtifactCounterV1::stableKey).distinct().size == counters.size) {
            "artifact counter list must not contain duplicate keys"
        }
        artifactCounters.set(
            counters.sortedWith(compareBy(ArtifactCounterV1::artifactKind, { it.logicalName.orEmpty() })).toList(),
        )
        val elapsed = elapsedClock.nowElapsedNanos()
        appendHistory(ProgressHistoryKind.ARTIFACT_COUNTERS, elapsed)
    }

    override fun snapshot(): RunStatusV1 {
        val stage = currentStage.get()
        return RunStatusV1(
            diagnosticRunId = diagnosticRunId,
            semanticJobId = semanticJobId,
            sourceCommit = sourceCommit,
            workloadType = workloadType,
            processId = processId,
            processStartWallClock = processStartInstant.toString(),
            heartbeatSequence = heartbeatSequence.get(),
            heartbeatWallClock = heartbeatWallClock.get()?.toString(),
            monotonicAgeData = MonotonicAgeDataV1(
                heartbeatElapsedNanos = heartbeatElapsedNanos.get().knownOrNull(),
                stageStartedElapsedNanos = stageStartedElapsedNanos.get(),
                lastUsefulProgressElapsedNanos = lastUsefulProgressElapsedNanos.get(),
            ),
            currentStage = stage,
            stageSequence = stageSequence.get(),
            stageStartedWallClock = stageStartedWallClock.get().toString(),
            progress = ProgressVectorV1(
                usefulProgressSequence = usefulProgressSequence.get(),
                episodeOrdinal = episodeOrdinal.get().knownOrNull(),
                engineProgressCount = engineProgressCount.get().knownOrNull(),
                authoritativeTransitionCount = authoritativeTransitionCount.get().knownOrNull(),
                semanticDecisionCount = semanticDecisionCount.get().knownOrNull(),
                trajectoryDecisionCount = trajectoryDecisionCount.get().knownOrNull(),
                replayFramesVerified = replayFramesVerified.get().knownOrNull(),
                episodesAdmitted = episodesAdmitted.get().knownOrNull(),
                bytesSerialized = bytesSerialized.get().knownOrNull(),
                shardsFinalized = shardsFinalized.get().knownOrNull(),
                lastUsefulProgressElapsedNanos = lastUsefulProgressElapsedNanos.get(),
            ),
            latestArtifactCounters = artifactCounters.get(),
        )
    }

    override fun recentHistory(): List<ProgressHistoryEntryV1> = history.snapshot()

    override fun close() {
        closed.set(true)
    }

    private fun markUseful(elapsed: Long) {
        usefulProgressSequence.incrementAndGet()
        lastUsefulProgressElapsedNanos.set(elapsed)
    }

    private fun appendHistory(kind: ProgressHistoryKind, elapsed: Long) {
        history.add(
            ProgressHistoryEntryV1(
                sequence = eventSequence.getAndIncrement(),
                kind = kind,
                monotonicElapsedNanos = elapsed,
                heartbeatSequence = heartbeatSequence.get(),
                usefulProgressSequence = usefulProgressSequence.get(),
                stageSequence = stageSequence.get(),
                currentStage = currentStage.get(),
            ),
        )
    }

    private fun addDelta(counter: AtomicLong, delta: Long?, field: String) {
        if (delta == null) return
        try {
            counter.updateAndGet { current ->
                if (current == UNSET) delta else Math.addExact(current, delta)
            }
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("$field exceeds the monotonic counter range", exception)
        }
    }

    private fun validateDelta(value: Long?, field: String) {
        requireNonNegative(value, field)
    }

    private companion object {
        const val UNSET: Long = -1

        fun Long.knownOrNull(): Long? = if (this == UNSET) null else this
    }
}

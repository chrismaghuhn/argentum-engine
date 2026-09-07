package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.StatusPublicationFailureCode

public data class SupervisorState(
    public val lastHeartbeatSequence: Long? = null,
    public val lastHeartbeatObservedElapsedNanos: Long? = null,
    public val lastUsefulProgressSequence: Long? = null,
    public val lastUsefulProgressObservedElapsedNanos: Long? = null,
    public val lastStageSequence: Long? = null,
    public val lastCpuTimeNanos: Long? = null,
    public val lastSampleElapsedNanos: Long? = null,
    public val capturesCompleted: Int = 0,
    public val lastCaptureElapsedNanos: Long? = null,
)

public data class ClassifierInput(
    public val nowElapsedNanos: Long,
    public val process: ProcessIdentityResult,
    public val status: StatusReadResult,
    public val metrics: ProcessMetricsV1,
    public val jvmEvidence: JvmEvidenceV1? = null,
) {
    init {
        require(nowElapsedNanos >= 0) { "nowElapsedNanos must be non-negative" }
    }
}

public data class ClassificationDecision(
    public val trigger: StallTriggerKind,
    public val classification: DiagnosticClassification,
    public val action: SupervisorAction,
    public val nextState: SupervisorState,
)

/**
 * Pure multi-signal classification. A classification is evidence, not a final root-cause claim;
 * every triggered action remains capture-and-continue.
 */
public class StallClassifier(
    private val config: SupervisorConfigV1,
) {
    public fun classify(input: ClassifierInput, previous: SupervisorState): ClassificationDecision {
        val availableStatus = input.status as? StatusReadResult.Available
        val status = availableStatus?.status
        var next = previous
        val initialSample = previous.lastSampleElapsedNanos == null
        val statusCursorRegressed = status?.let {
            (previous.lastHeartbeatSequence != null && it.heartbeatSequence < previous.lastHeartbeatSequence) ||
                (previous.lastUsefulProgressSequence != null &&
                    it.progress.usefulProgressSequence < previous.lastUsefulProgressSequence) ||
                (previous.lastStageSequence != null && it.stageSequence < previous.lastStageSequence)
        } == true

        if (!statusCursorRegressed) status?.let {
            if (it.heartbeatSequence > 0 && it.heartbeatSequence != previous.lastHeartbeatSequence) {
                next = next.copy(
                    lastHeartbeatSequence = it.heartbeatSequence,
                    lastHeartbeatObservedElapsedNanos = input.nowElapsedNanos,
                )
            }
            if (it.progress.usefulProgressSequence != previous.lastUsefulProgressSequence) {
                next = next.copy(
                    lastUsefulProgressSequence = it.progress.usefulProgressSequence,
                    lastUsefulProgressObservedElapsedNanos = input.nowElapsedNanos,
                )
            }
            if (it.stageSequence != previous.lastStageSequence) {
                next = next.copy(lastStageSequence = it.stageSequence)
            }
        }
        next = next.copy(
            lastCpuTimeNanos = input.metrics.cpuTimeNanos ?: previous.lastCpuTimeNanos,
            lastSampleElapsedNanos = input.nowElapsedNanos,
        )

        if (input.process.liveness == ProcessLiveness.PROCESS_EXITED) {
            return decision(
                trigger = StallTriggerKind.PROCESS_EXITED,
                classification = DiagnosticClassification.PROCESS_EXITED,
                action = SupervisorAction.CONTINUE_OBSERVING,
                next = next,
            )
        }
        if (input.process.liveness != ProcessLiveness.ALIVE) {
            return decision(
                trigger = StallTriggerKind.STATUS_UNAVAILABLE,
                classification = DiagnosticClassification.UNKNOWN,
                action = SupervisorAction.CONTINUE_OBSERVING,
                next = next,
            )
        }
        if (status == null) {
            return decision(
                trigger = StallTriggerKind.STATUS_UNAVAILABLE,
                classification = DiagnosticClassification.UNKNOWN,
                action = SupervisorAction.CONTINUE_OBSERVING,
                next = next,
            )
        }
        if (statusCursorRegressed) {
            return decision(
                trigger = StallTriggerKind.STATUS_UNAVAILABLE,
                classification = DiagnosticClassification.UNKNOWN,
                action = SupervisorAction.CONTINUE_OBSERVING,
                next = next,
            )
        }
        if (initialSample) {
            return decision(
                trigger = StallTriggerKind.NONE,
                classification = DiagnosticClassification.UNKNOWN,
                action = SupervisorAction.CONTINUE_OBSERVING,
                next = next,
            )
        }

        val heartbeatAge = next.lastHeartbeatObservedElapsedNanos?.let {
            elapsedAge(input.nowElapsedNanos, it)
        }
        val usefulAge = next.lastUsefulProgressObservedElapsedNanos?.let {
            elapsedAge(input.nowElapsedNanos, it)
        }
        val heartbeatFresh = heartbeatAge != null && heartbeatAge <= config.heartbeatTimeoutNanos()
        val usefulFresh = usefulAge != null && usefulAge <= config.usefulProgressTimeoutNanos()
        if (heartbeatFresh && usefulFresh) {
            return decision(
                trigger = StallTriggerKind.NONE,
                classification = DiagnosticClassification.HEALTHY,
                action = SupervisorAction.CONTINUE_OBSERVING,
                next = next,
            )
        }

        val trigger = if (!heartbeatFresh) StallTriggerKind.HEARTBEAT_STALE
        else StallTriggerKind.USEFUL_PROGRESS_STALE
        val cpuFraction = cpuFraction(input, previous)
        val evidence = input.jvmEvidence
        val classification = when {
            evidence?.deadlockDetected == true -> DiagnosticClassification.DEADLOCK_DETECTED
            usefulFresh.not() && evidence?.ambiguousThreadStateEvidence != true &&
                evidence?.stableHotStack == true &&
                cpuFraction != null && cpuFraction >= config.cpuActiveFraction ->
                DiagnosticClassification.CPU_SPIN_SUSPECT
            usefulFresh.not() && evidence?.ambiguousThreadStateEvidence != true &&
                evidence?.stableWaitStack == true &&
                cpuFraction != null && cpuFraction <= config.cpuLowFraction ->
                DiagnosticClassification.BLOCKED_WAIT_SUSPECT
            status.statusPublication.lastFailureCode in IO_FAILURE_CODES ->
                DiagnosticClassification.IO_STALL_SUSPECT
            else -> DiagnosticClassification.SUSPECTED_STALL
        }
        return decision(
            trigger = trigger,
            classification = classification,
            action = SupervisorAction.CAPTURE_DIAGNOSTICS_AND_CONTINUE,
            next = next,
        )
    }

    private fun cpuFraction(input: ClassifierInput, previous: SupervisorState): Double? {
        val cpuNow = input.metrics.cpuTimeNanos ?: return null
        val cpuBefore = previous.lastCpuTimeNanos ?: return null
        val wallBefore = previous.lastSampleElapsedNanos ?: return null
        val wallDelta = elapsedAge(input.nowElapsedNanos, wallBefore)
        if (wallDelta == 0L) return null
        val cpuDelta = (cpuNow - cpuBefore).coerceAtLeast(0)
        return cpuDelta.toDouble() / wallDelta.toDouble()
    }

    private fun decision(
        trigger: StallTriggerKind,
        classification: DiagnosticClassification,
        action: SupervisorAction,
        next: SupervisorState,
    ) = ClassificationDecision(trigger, classification, action, next)

    private companion object {
        val IO_FAILURE_CODES = setOf(
            StatusPublicationFailureCode.STATUS_DIRECTORY_UNAVAILABLE,
            StatusPublicationFailureCode.STATUS_WRITE_FAILED,
            StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_UNAVAILABLE,
            StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_FAILED,
        )

        fun elapsedAge(now: Long, then: Long): Long = (now - then).coerceAtLeast(0)
    }
}

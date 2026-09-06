package com.wingedsheep.rundiagnostics.supervisor

public enum class JvmCommandKind {
    THREAD_PRINT,
    GC_HEAP_INFO,
    VM_FLAGS,
    JSTACK,
}
public data class JvmCommandResult(
    public val kind: JvmCommandKind,
    public val availability: EvidenceAvailability,
    public val exitCode: Int? = null,
    public val timedOut: Boolean = false,
    /** This field is privileged text and must not enter normal sidecars or safe summaries. */
    public val output: String? = null,
    public val capturedBytes: Int = 0,
    public val failureCode: SupervisorFailureCode? = null,
)

public interface JvmCommandRunner {
    public fun run(pid: Long, kind: JvmCommandKind, timeoutMillis: Long, maxBytes: Int): JvmCommandResult
}

/** Text in this type is developer-privileged JVM evidence, not dataset-safe data. */
public data class JvmEvidenceV1(
    public val availability: EvidenceAvailability,
    public val stableHotStack: Boolean? = null,
    public val stableWaitStack: Boolean? = null,
    public val deadlockDetected: Boolean? = null,
    public val gcPressure: Boolean? = null,
    public val threadDumpCount: Int = 0,
    public val results: List<JvmCommandResult> = emptyList(),
)

public fun interface SupervisorSleeper {
    public fun sleepMillis(millis: Long)
}

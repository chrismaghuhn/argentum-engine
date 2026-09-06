package com.wingedsheep.rundiagnostics.supervisor

import kotlinx.serialization.Serializable

public object SupervisorSchema {
    public const val CONFIG_SCHEMA_VERSION: Int = 1
    public const val CONFIG_SCHEMA_IDENTITY: String = "argentum-supervisor-config@v1"
    public const val PROCESS_METRICS_SCHEMA_VERSION: Int = 1
    public const val PROCESS_METRICS_SCHEMA_IDENTITY: String = "argentum-process-metrics@v1"
    public const val BUNDLE_SCHEMA_VERSION: Int = 1
    public const val BUNDLE_SCHEMA_IDENTITY: String = "argentum-diagnostic-bundle@v1"
    public const val DEFAULT_HEARTBEAT_TIMEOUT_MILLIS: Long = 30_000
    public const val DEFAULT_USEFUL_PROGRESS_TIMEOUT_MILLIS: Long = 600_000
    public const val DEFAULT_SAMPLE_INTERVAL_MILLIS: Long = 5_000
    public const val DEFAULT_CAPTURE_COOLDOWN_MILLIS: Long = 60_000
    public const val DEFAULT_MAX_DIAGNOSTIC_BUNDLES: Int = 3
    public const val DEFAULT_MAX_HISTORY_SAMPLES: Int = 128
    public const val DEFAULT_THREAD_DUMP_COUNT: Int = 3
    public const val DEFAULT_THREAD_DUMP_INTERVAL_MILLIS: Long = 2_000
    public const val DEFAULT_CAPTURE_TIMEOUT_MILLIS: Long = 5_000
    public const val DEFAULT_MAX_COMMAND_OUTPUT_BYTES: Int = 64 * 1024
    public const val DEFAULT_MAX_BUNDLE_BYTES: Int = 4 * 1024 * 1024
    public const val MAX_DURATION_MILLIS: Long = 86_400_000
}

@Serializable
public enum class EvidenceAvailability {
    AVAILABLE,
    MISSING,
    FAILED,
    TIMED_OUT,
    NOT_CONFIGURED,
}

@Serializable
public enum class SupervisorFailureCode {
    STATUS_MISSING,
    STATUS_NOT_REGULAR_FILE,
    STATUS_READ_FAILED,
    STATUS_TOO_LARGE,
    STATUS_SCHEMA_INVALID,
    PROCESS_NOT_FOUND,
    PROCESS_IDENTITY_MISMATCH,
    PROCESS_START_UNAVAILABLE,
    METRICS_UNAVAILABLE,
    METRICS_PARSE_FAILED,
    ARTIFACT_SIZE_UNAVAILABLE,
    COMMAND_TOOL_MISSING,
    COMMAND_FAILED,
    COMMAND_TIMED_OUT,
    COMMAND_OUTPUT_TOO_LARGE,
    BUNDLE_DIRECTORY_FAILED,
    BUNDLE_FILE_FAILED,
    BUNDLE_TOO_LARGE,
    RETENTION_FAILED,
    CAPTURE_COOLDOWN,
    MAX_BUNDLES_REACHED,
}

@Serializable
public enum class ProcessLiveness {
    ALIVE,
    PROCESS_EXITED,
    IDENTITY_MISMATCH,
    UNKNOWN,
}

@Serializable
public enum class StallTriggerKind {
    NONE,
    HEARTBEAT_STALE,
    USEFUL_PROGRESS_STALE,
    STATUS_UNAVAILABLE,
    PROCESS_EXITED,
}

@Serializable
public enum class DiagnosticClassification {
    HEALTHY,
    SUSPECTED_STALL,
    CPU_SPIN_SUSPECT,
    BLOCKED_WAIT_SUSPECT,
    GC_PRESSURE_SUSPECT,
    IO_STALL_SUSPECT,
    DEADLOCK_DETECTED,
    PROCESS_EXITED,
    UNKNOWN,
}

@Serializable
public enum class SupervisorAction {
    CONTINUE_OBSERVING,
    CAPTURE_DIAGNOSTICS_AND_CONTINUE,
}

internal fun requirePath(value: String, field: String) {
    require(value.isNotBlank()) { "$field must not be blank" }
    require(value.length <= 4_096) { "$field exceeds the bounded length" }
    require(value.none(Char::isISOControl)) { "$field must not contain control characters" }
}

internal fun requireDuration(value: Long, field: String) {
    require(value in 1..SupervisorSchema.MAX_DURATION_MILLIS) {
        "$field must be between 1 and ${SupervisorSchema.MAX_DURATION_MILLIS} milliseconds"
    }
}

internal fun requireNonNegative(value: Long?, field: String) {
    require(value == null || value >= 0) { "$field must be non-negative when present" }
}

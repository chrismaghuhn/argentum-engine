package com.wingedsheep.rundiagnostics.supervisor

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Bounded, operator-supplied configuration. The numeric defaults are provisional operational
 * policy, not semantic or universal stall thresholds; deployments may replace them explicitly.
 */
@Serializable
public data class SupervisorConfigV1(
    public val schemaVersion: Int = SupervisorSchema.CONFIG_SCHEMA_VERSION,
    public val schemaIdentity: String = SupervisorSchema.CONFIG_SCHEMA_IDENTITY,
    public val targetPid: Long,
    public val statusPath: String,
    public val diagnosticsDirectory: String,
    public val heartbeatTimeoutMillis: Long = SupervisorSchema.DEFAULT_HEARTBEAT_TIMEOUT_MILLIS,
    public val usefulProgressTimeoutMillis: Long = SupervisorSchema.DEFAULT_USEFUL_PROGRESS_TIMEOUT_MILLIS,
    public val sampleIntervalMillis: Long = SupervisorSchema.DEFAULT_SAMPLE_INTERVAL_MILLIS,
    public val diagnosticCaptureCooldownMillis: Long = SupervisorSchema.DEFAULT_CAPTURE_COOLDOWN_MILLIS,
    public val maxDiagnosticBundles: Int = SupervisorSchema.DEFAULT_MAX_DIAGNOSTIC_BUNDLES,
    public val maxHistorySamples: Int = SupervisorSchema.DEFAULT_MAX_HISTORY_SAMPLES,
    public val threadDumpCount: Int = SupervisorSchema.DEFAULT_THREAD_DUMP_COUNT,
    public val threadDumpIntervalMillis: Long = SupervisorSchema.DEFAULT_THREAD_DUMP_INTERVAL_MILLIS,
    public val captureTimeoutMillis: Long = SupervisorSchema.DEFAULT_CAPTURE_TIMEOUT_MILLIS,
    public val maxCommandOutputBytes: Int = SupervisorSchema.DEFAULT_MAX_COMMAND_OUTPUT_BYTES,
    public val maxBundleBytes: Int = SupervisorSchema.DEFAULT_MAX_BUNDLE_BYTES,
    public val safeArtifactPaths: List<String> = emptyList(),
    public val jvmToolDirectory: String? = null,
    public val expectedProcessStartWallClock: String? = null,
    public val processStartToleranceMillis: Long = 2_000,
    public val cpuActiveFraction: Double = 0.5,
    public val cpuLowFraction: Double = 0.1,
    public val once: Boolean = false,
) {
    init {
        require(schemaVersion == SupervisorSchema.CONFIG_SCHEMA_VERSION) { "unsupported supervisor schemaVersion" }
        require(schemaIdentity == SupervisorSchema.CONFIG_SCHEMA_IDENTITY) { "unsupported supervisor schemaIdentity" }
        require(targetPid > 0) { "targetPid must be positive" }
        requirePath(statusPath, "statusPath")
        requirePath(diagnosticsDirectory, "diagnosticsDirectory")
        requireDuration(heartbeatTimeoutMillis, "heartbeatTimeoutMillis")
        requireDuration(usefulProgressTimeoutMillis, "usefulProgressTimeoutMillis")
        requireDuration(sampleIntervalMillis, "sampleIntervalMillis")
        requireDuration(diagnosticCaptureCooldownMillis, "diagnosticCaptureCooldownMillis")
        require(maxDiagnosticBundles in 1..100) { "maxDiagnosticBundles must be between 1 and 100" }
        require(maxHistorySamples in 1..4_096) { "maxHistorySamples must be between 1 and 4096" }
        require(threadDumpCount in 1..5) { "threadDumpCount must be between 1 and 5" }
        requireDuration(threadDumpIntervalMillis, "threadDumpIntervalMillis")
        requireDuration(captureTimeoutMillis, "captureTimeoutMillis")
        require(maxCommandOutputBytes in 1_024..4 * 1024 * 1024) {
            "maxCommandOutputBytes must be between 1024 and 4194304"
        }
        require(maxBundleBytes in 4_096..64 * 1024 * 1024) {
            "maxBundleBytes must be between 4096 and 67108864"
        }
        require(safeArtifactPaths.size <= 128) { "safeArtifactPaths exceeds the bounded maximum" }
        safeArtifactPaths.forEachIndexed { index, path -> requirePath(path, "safeArtifactPaths[$index]") }
        jvmToolDirectory?.let { requirePath(it, "jvmToolDirectory") }
        expectedProcessStartWallClock?.let {
            try {
                Instant.parse(it)
            } catch (exception: Exception) {
                throw IllegalArgumentException("expectedProcessStartWallClock must be an ISO-8601 instant", exception)
            }
        }
        require(processStartToleranceMillis in 0..SupervisorSchema.MAX_DURATION_MILLIS) {
            "processStartToleranceMillis is outside the bounded range"
        }
        require(cpuLowFraction in 0.0..1.0 && cpuActiveFraction in 0.0..1.0) {
            "CPU fractions must be between 0 and 1"
        }
        require(cpuLowFraction <= cpuActiveFraction) { "cpuLowFraction must not exceed cpuActiveFraction" }
    }

    public fun heartbeatTimeoutNanos(): Long = heartbeatTimeoutMillis * 1_000_000

    public fun usefulProgressTimeoutNanos(): Long = usefulProgressTimeoutMillis * 1_000_000

    public fun captureCooldownNanos(): Long = diagnosticCaptureCooldownMillis * 1_000_000
}

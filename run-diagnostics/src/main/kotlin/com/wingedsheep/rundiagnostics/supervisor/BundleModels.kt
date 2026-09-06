package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.RunStatusV1
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Instant

@Serializable
public data class SafeArtifactSizeV1(
    public val logicalName: String,
    public val availability: EvidenceAvailability = EvidenceAvailability.AVAILABLE,
    public val bytes: Long? = null,
    public val failureCode: SupervisorFailureCode? = null,
) {
    init {
        require(logicalName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            "logicalName must be a bounded safe token"
        }
        requireNonNegative(bytes, "bytes")
    }
}
@Serializable
public data class SupervisorHistoryEntryV1(
    public val observedAtWallClock: String,
    public val heartbeatSequence: Long? = null,
    public val usefulProgressSequence: Long? = null,
    public val stageSequence: Long? = null,
    public val classification: DiagnosticClassification,
) {
    init {
        requireNonNegative(heartbeatSequence, "heartbeatSequence")
        requireNonNegative(usefulProgressSequence, "usefulProgressSequence")
        requireNonNegative(stageSequence, "stageSequence")
    }
}

@Serializable
public data class BundleFileRecordV1(
    public val name: String,
    public val required: Boolean,
    public val availability: EvidenceAvailability,
    public val datasetSafe: Boolean,
    public val bytesWritten: Long? = null,
    public val failureCode: SupervisorFailureCode? = null,
) {
    init {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}"))) {
            "bundle file name must be bounded"
        }
        requireNonNegative(bytesWritten, "bytesWritten")
    }
}

@Serializable
public data class DiagnosticBundleSummaryV1(
    public val schemaVersion: Int = SupervisorSchema.BUNDLE_SCHEMA_VERSION,
    public val schemaIdentity: String = SupervisorSchema.BUNDLE_SCHEMA_IDENTITY,
    public val diagnosticRunId: String,
    public val stallId: String,
    public val createdWallClock: String,
    public val trigger: StallTriggerKind,
    public val classification: DiagnosticClassification,
    public val action: SupervisorAction,
    public val processLiveness: ProcessLiveness,
    public val files: List<BundleFileRecordV1>,
    public val privilegedDiagnosticPolicy: String =
        "DEVELOPER_PRIVILEGED_DIAGNOSTIC_NOT_DATASET_SAFE",
) {
    init {
        require(schemaVersion == SupervisorSchema.BUNDLE_SCHEMA_VERSION) { "unsupported bundle schemaVersion" }
        require(schemaIdentity == SupervisorSchema.BUNDLE_SCHEMA_IDENTITY) { "unsupported bundle schemaIdentity" }
        require(diagnosticRunId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            "diagnosticRunId must be a bounded safe token"
        }
        require(stallId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            "stallId must be a bounded safe token"
        }
    }
}

public data class DiagnosticBundleInput(
    public val diagnosticRunId: String,
    public val stallId: String,
    public val createdWallClock: Instant,
    public val trigger: StallTriggerKind,
    public val classification: DiagnosticClassification,
    public val action: SupervisorAction,
    public val status: RunStatusV1?,
    public val metrics: ProcessMetricsV1,
    public val process: ProcessIdentityResult,
    public val recentHistory: List<SupervisorHistoryEntryV1>,
    public val jvmResults: List<JvmCommandResult>,
    public val safeArtifactSizes: List<SafeArtifactSizeV1>,
)

public data class DiagnosticBundleResult(
    public val availability: EvidenceAvailability,
    public val bundleDirectory: Path? = null,
    public val summary: DiagnosticBundleSummaryV1? = null,
    public val failures: List<SupervisorFailureCode> = emptyList(),
)

public fun interface DiagnosticBundleSink {
    public fun write(input: DiagnosticBundleInput): DiagnosticBundleResult?
}

package com.wingedsheep.rundiagnostics.supervisor

public data class BoundedCommandResult(
    public val availability: EvidenceAvailability,
    public val exitCode: Int? = null,
    public val output: String? = null,
    public val capturedBytes: Int = 0,
    public val failureCode: SupervisorFailureCode? = null,
) {
    init {
        require(capturedBytes >= 0) { "capturedBytes must be non-negative" }
    }
}
public interface BoundedCommandRunner {
    public fun run(command: List<String>, timeoutMillis: Long, maxBytes: Int): BoundedCommandResult
}

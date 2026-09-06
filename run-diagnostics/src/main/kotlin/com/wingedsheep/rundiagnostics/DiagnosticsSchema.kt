package com.wingedsheep.rundiagnostics

import kotlinx.serialization.Serializable

/**
 * Version identities for the operational diagnostics sidecar.
 *
 * This module is deliberately outside the Rules/Gym/data-contract graph. Values from this package
 * are observations about a running process and must never be used to derive game semantics,
 * replay identity, trajectory bytes, dataset admission, policy input, reward, or training targets.
 */
public object DiagnosticsSchema {
    public const val RUN_STATUS_SCHEMA_VERSION: Int = 1
    public const val RUN_STATUS_SCHEMA_IDENTITY: String = "argentum-run-status@v1"

    public const val PROGRESS_VECTOR_SCHEMA_VERSION: Int = 1
    public const val PROGRESS_VECTOR_SCHEMA_IDENTITY: String = "argentum-progress-vector@v1"

    public const val STAGE_REF_SCHEMA_VERSION: Int = 1
    public const val STAGE_REF_SCHEMA_IDENTITY: String = "argentum-stage-ref@v1"

    public const val ARTIFACT_COUNTER_SCHEMA_VERSION: Int = 1
    public const val ARTIFACT_COUNTER_SCHEMA_IDENTITY: String = "argentum-artifact-counter@v1"

    public const val MONOTONIC_AGE_SCHEMA_IDENTITY: String = "argentum-monotonic-age@v1"
    public const val MONOTONIC_CLOCK_IDENTITY: String = "JVM_NANO_TIME_PROCESS_RELATIVE"

    public const val PROGRESS_HISTORY_SCHEMA_VERSION: Int = 1
    public const val PROGRESS_HISTORY_SCHEMA_IDENTITY: String = "argentum-progress-history@v1"

    public const val DEFAULT_MAX_SERIALIZED_STATUS_BYTES: Int = 64 * 1024
    public const val DEFAULT_HISTORY_CAPACITY: Int = 128
    public const val MAX_STATUS_LABEL_LENGTH: Int = 256
    public const val MAX_ARTIFACT_COUNTERS: Int = 128
}

@Serializable
public enum class DiagnosticsMode {
    SIDECAR_NORMAL,
}

/** Stable machine-readable outcomes for best-effort diagnostics operations. */
@Serializable
public enum class StatusPublicationFailureCode {
    STATUS_SERIALIZATION_TOO_LARGE,
    STATUS_SCHEMA_REJECTED,
    STATUS_SNAPSHOT_FAILED,
    STATUS_DIRECTORY_UNAVAILABLE,
    STATUS_TEMP_FILE_CREATE_FAILED,
    STATUS_WRITE_FAILED,
    STATUS_ATOMIC_REPLACE_UNAVAILABLE,
    STATUS_ATOMIC_REPLACE_FAILED,
    STATUS_TEMP_CLEANUP_FAILED,
    PUBLISHER_CLOSED,
    PUBLISHER_QUEUE_REJECTED,
    HEARTBEAT_SCHEDULER_REJECTED,
    HEARTBEAT_CALLBACK_FAILED,
}

/**
 * The only publication strategy implemented by D1 is an atomic same-directory replacement.
 * The other values are explicit future classification outcomes, not silent fallbacks.
 */
public enum class AtomicReplacementImplementation {
    ATOMIC_REPLACEMENT,
    IMMUTABLE_SEQUENCE_PLUS_POINTER,
    PROVIDER_UNSUPPORTED,
}

public sealed interface StatusPublicationResult {
    public val implementation: AtomicReplacementImplementation

    public data class Published(
        public val publicationSequence: Long? = null,
        public val bytesWritten: Int,
        override val implementation: AtomicReplacementImplementation =
            AtomicReplacementImplementation.ATOMIC_REPLACEMENT,
    ) : StatusPublicationResult {
        init {
            require(publicationSequence == null || publicationSequence >= 0) {
                "publicationSequence must be non-negative when present"
            }
            require(bytesWritten >= 0) { "bytesWritten must be non-negative" }
        }
    }

    public data class Failed(
        public val code: StatusPublicationFailureCode,
        override val implementation: AtomicReplacementImplementation =
            AtomicReplacementImplementation.ATOMIC_REPLACEMENT,
        public val tempCleanupFailed: Boolean = false,
    ) : StatusPublicationResult
}

public class StatusSerializationException(
    public val code: StatusPublicationFailureCode,
    cause: Throwable? = null,
) : IllegalArgumentException("diagnostic status serialization failed: $code", cause)

@Serializable
public enum class ProgressHistoryKind {
    HEARTBEAT,
    USEFUL_PROGRESS,
    STAGE_CHANGED,
    ARTIFACT_COUNTERS,
}

internal fun requireSchemaVersion(actual: Int, expected: Int, field: String) {
    require(actual == expected) { "$field must be $expected" }
}

internal fun requireSchemaIdentity(actual: String, expected: String, field: String) {
    require(actual == expected) { "$field must be $expected" }
}

internal fun requireSafeLabel(value: String, field: String, maxLength: Int = DiagnosticsSchema.MAX_STATUS_LABEL_LENGTH) {
    require(value.isNotBlank()) { "$field must not be blank" }
    require(value.length <= maxLength) { "$field exceeds the bounded length" }
    require(value.none(Char::isISOControl)) { "$field must not contain control characters" }
}

internal fun requireSafeToken(value: String, field: String) {
    requireSafeLabel(value, field)
    require(value.all { it.isLetterOrDigit() || it in "-_.:@" }) {
        "$field contains unsupported token characters"
    }
}

internal fun requireVersionedStageFamily(value: String) {
    requireSafeLabel(value, "stageFamilySchemaIdentity")
    require(Regex("[A-Za-z0-9][A-Za-z0-9._:@-]*@v[1-9][0-9]*").matches(value)) {
        "stageFamilySchemaIdentity must include a version suffix such as @v1"
    }
}

internal fun requireNonNegative(value: Long?, field: String) {
    require(value == null || value >= 0) { "$field must be non-negative when present" }
}

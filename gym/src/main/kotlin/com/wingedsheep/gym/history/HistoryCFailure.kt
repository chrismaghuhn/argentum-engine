package com.wingedsheep.gym.history

/** Stable fail-closed categories for the internal History-C authority seam. */
internal enum class HistoryCFailureCode {
    UNKNOWN_REFERENCE_SCHEMA_VERSION,
    UNKNOWN_REFERENCE_SCHEMA_IDENTITY,
    UNCOMMITTED_TRANSITION,
    FORK_OR_SPECULATIVE_SOURCE,
    RAW_RUNTIME_ID_AT_SEMANTIC_SEAM,
    INVALID_SEMANTIC_DESCRIPTOR,
    MISSING_EVENT_TIME_WITNESS,
    STALE_BEFORE_WITNESS,
    STALE_AFTER_WITNESS,
    HISTORY_A_PROJECTION_INCOMPLETE,
    PERSPECTIVE_MISMATCH,
    INVALID_REFERENCE_SLOT,
    MISSING_ORDER_AUTHORITY,
    UNSUPPORTED_REFERENCE_KIND,
    INVALID_IDENTITY_DISCLOSURE,
    RAW_EVENT_REFERENCE_UNSUPPORTED,
    RAW_EVENT_REFERENCE_MISMATCH,
    RAW_EVENT_DEFINITION_MISMATCH,
    RAW_EVENT_ORDER_AUTHORITY_MISMATCH,
}

/** Non-wire diagnostic; it deliberately carries no entity IDs, card names, or hidden values. */
internal data class HistoryCFailure(
    val code: HistoryCFailureCode,
)

internal sealed interface HistoryCReferenceAuthorityResult {
    data class Accepted(
        val evidence: HistoryCReferenceEvidenceV1,
    ) : HistoryCReferenceAuthorityResult

    data class Rejected(
        val failure: HistoryCFailure,
    ) : HistoryCReferenceAuthorityResult
}

package com.wingedsheep.gameserver.policy

/** Typed fail-closed outcomes for the ML controller/session seam. */
enum class PolicySeatFailureCode {
    CONTROLLER_AUTHORITY_INVALID,
    SESSION_NOT_READY,
    UNSUPPORTED_MULLIGAN_DECISION,
    UNSUPPORTED_STRUCTURED_DECISION,
    STALE_INFERENCE,
    INVALID_RESPONSE,
    EXACT_BINDING_FAILURE,
    EXECUTION_REJECTED,
    WORKER_STARTUP_FAILURE,
    WORKER_PROTOCOL_FAILURE,
    WORKER_TIMEOUT,
    WORKER_CRASH,
    DUPLICATE_IN_FLIGHT,
    RUNTIME_CLOSED,
    CONFIGURATION_INVALID,
}

class PolicySeatFailure(
    val code: PolicySeatFailureCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

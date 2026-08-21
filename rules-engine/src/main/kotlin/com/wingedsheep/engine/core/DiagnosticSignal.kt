package com.wingedsheep.engine.core

/**
 * The high-level category of an unsupported path encountered by a trusted episode.
 *
 * This is deliberately not serializable and is carried only as transient execution metadata.
 * The model-facing observation and the persisted Magic state remain unchanged.
 */
enum class DiagnosticKind {
    UNSUPPORTED_CARD,
    UNSUPPORTED_DECISION,
    UNSUPPORTED_RULE_OR_MECHANIC,
    NATIVE_POLICY_FALLBACK,
}

/** Stable machine-readable identifiers for known diagnostic boundaries. */
enum class DiagnosticCode(val kind: DiagnosticKind) {
    CARD_DEFINITION_MISSING(DiagnosticKind.UNSUPPORTED_CARD),
    STRUCTURED_DECISION_DOMAIN_MISSING(DiagnosticKind.UNSUPPORTED_DECISION),
    CHAIN_COPY_COST_UNSUPPORTED(DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC),
    ANY_PLAYER_MAY_PAY_COST_UNSUPPORTED(DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC),
    SACRIFICE_AND_PAY_COST_UNSUPPORTED(DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC),
    PREVENT_DAMAGE_CONFIGURATION_UNSUPPORTED(DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC),
    LIBRARY_DESTINATION_UNSUPPORTED(DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC),
    ACTIVATED_ABILITY_SHAPE_UNSUPPORTED(DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC),
    PAYMENT_DOMAIN_UNSUPPORTED(DiagnosticKind.UNSUPPORTED_DECISION),
    SKIP_NEXT_DRAW_TARGET_UNSUPPORTED(DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC),
    TRUSTED_NATIVE_POLICY_FALLBACK(DiagnosticKind.NATIVE_POLICY_FALLBACK),
}

/**
 * A typed, deterministic, non-wire signal emitted at the first semantic boundary that knows a
 * trusted path is unsupported. It intentionally carries no card names, entity IDs, or exception
 * text so it cannot disclose private state through a later observation.
 */
data class DiagnosticSignal(val code: DiagnosticCode) {
    /** The category is part of the stable code contract and cannot disagree with it. */
    val kind: DiagnosticKind get() = code.kind

    /** Stable string form for harnesses that export aggregate reports. */
    val semanticCode: String get() = code.name
}

/** Fail-closed exception used after an authoritative unsupported signal was recorded. */
class UnsupportedPathFailure(
    val diagnostics: List<DiagnosticSignal>,
    message: String = "Trusted execution encountered an unsupported path",
) : IllegalStateException(message) {
    init {
        require(diagnostics.isNotEmpty()) { "UnsupportedPathFailure requires at least one diagnostic" }
    }
}

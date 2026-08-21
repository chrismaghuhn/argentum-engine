package com.wingedsheep.gym

import com.wingedsheep.engine.core.DiagnosticKind
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.sdk.model.EntityId

/**
 * Immutable, episode-scoped evidence for unsupported trusted-environment paths.
 *
 * The ledger is deliberately separate from [com.wingedsheep.engine.state.GameState]. Counts are
 * derived from typed events, so there is one source of truth and diagnostic-only changes cannot
 * affect Magic semantics, observation privacy, or state digests.
 */
data class EpisodeDiagnostics(
    val events: List<DiagnosticSignal> = emptyList(),
    val projectionCursor: ProjectionCursor? = null,
) {
    val unsupportedCardCount: Int get() = count(DiagnosticKind.UNSUPPORTED_CARD)
    val unsupportedDecisionCount: Int get() = count(DiagnosticKind.UNSUPPORTED_DECISION)
    val unsupportedRuleCount: Int get() = count(DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC)
    val unsupportedRuleOrMechanicCount: Int get() = unsupportedRuleCount
    val nativePolicyFallbackCount: Int get() = count(DiagnosticKind.NATIVE_POLICY_FALLBACK)
    val totalCount: Int get() = events.size

    fun record(signals: List<DiagnosticSignal>): EpisodeDiagnostics =
        if (signals.isEmpty()) this else copy(events = events + signals)

    /**
     * Record an observation invariant at most once for one immutable state generation and
     * perspective. The cursor is retained with the ledger so fork/snapshot/restore preserve the
     * exactly-once boundary even when the failed observation is retried.
     */
    fun recordObservation(
        cursor: ProjectionCursor,
        signals: List<DiagnosticSignal>,
    ): EpisodeDiagnostics {
        if (signals.isEmpty() || projectionCursor == cursor) return this
        return copy(events = events + signals, projectionCursor = cursor)
    }

    private fun count(kind: DiagnosticKind): Int = events.count { it.kind == kind }

    companion object {
        val EMPTY: EpisodeDiagnostics = EpisodeDiagnostics()
    }
}

/** Stable cursor for observation diagnostics within one environment episode. */
data class ProjectionCursor(
    val generation: Long,
    val perspectivePlayerId: EntityId,
)

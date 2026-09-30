package com.wingedsheep.ai.engine

import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.state.GameState

/**
 * Result of simulating an action through the engine.
 */
sealed interface SimulationResult {
    val state: GameState
    val events: List<GameEvent>

    /**
     * The position the action's own resolution settled into: the first quiet state, before a
     * simulator built with `resolveThroughCombatDamage` carried the game on into combat damage.
     * The same as [state] whenever that did not happen.
     *
     * [state] is what a leaf is *scored* on; this is what it is *compared* on. [StateProgress]
     * asks whether an action got anywhere, and the only position it can fairly be compared with
     * is the one the AI acted from — never one the game has since moved on from by itself.
     */
    val settledState: GameState get() = state

    /** The action completed fully — no further input needed. */
    data class Terminal(
        override val state: GameState,
        override val events: List<GameEvent>,
        override val settledState: GameState = state,
    ) : SimulationResult

    /** The action paused mid-resolution — a decision is required. */
    data class NeedsDecision(
        override val state: GameState,
        val decision: PendingDecision,
        override val events: List<GameEvent>
    ) : SimulationResult

    /** The action was illegal or failed validation. */
    data class Illegal(
        override val state: GameState,
        override val events: List<GameEvent>,
        val reason: String
    ) : SimulationResult
}

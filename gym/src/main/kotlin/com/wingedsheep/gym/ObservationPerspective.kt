package com.wingedsheep.gym

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId

/**
 * Resolves the perspective used by a public Gym observation.
 *
 * An active game is viewed from its pending-decision player or priority holder. Once the game is
 * terminal or truncated, there is no acting player; the configured seat remains the stable public
 * perspective. Replay-facing projections must use this same rule instead of falling back to a
 * stale terminal priority.
 */
internal object ObservationPerspective {
    fun resolve(
        state: GameState,
        playerIds: List<EntityId>,
        fallbackPerspectivePlayerIndex: Int,
        truncated: Boolean = false,
    ): EntityId? {
        val actingPlayer = if (state.gameOver || truncated) {
            null
        } else {
            state.pendingDecision?.playerId ?: state.priorityPlayerId
        }
        return actingPlayer ?: playerIds.getOrNull(fallbackPerspectivePlayerIndex)
    }
}

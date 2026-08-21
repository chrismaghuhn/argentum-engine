package com.wingedsheep.engine.handlers.continuations

import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/**
 * Converts an entity ID to the appropriate [ChosenTarget] subtype
 * based on the entity's current zone.
 *
 * Every card zone is searched, not just the graveyard: a [ChosenTarget.Permanent] fallback for a
 * card sitting in exile (Blade of the Swarm targeting a warp-exiled card) or in hand is treated as
 * "not on the battlefield" by resolution-time re-validation and fizzles the whole spell/ability.
 */
private val CARD_ZONES = listOf(Zone.GRAVEYARD, Zone.EXILE, Zone.HAND, Zone.LIBRARY)

fun entityIdToChosenTarget(state: GameState, entityId: EntityId): ChosenTarget {
    return when {
        entityId in state.turnOrder -> ChosenTarget.Player(entityId)
        entityId in state.getBattlefield() -> ChosenTarget.Permanent(entityId)
        entityId in state.stack -> ChosenTarget.Spell(entityId)
        else -> {
            for (zone in CARD_ZONES) {
                val owner = state.turnOrder.find { playerId ->
                    entityId in state.getZone(ZoneKey(playerId, zone))
                }
                if (owner != null) return ChosenTarget.Card(entityId, owner, zone)
            }
            ChosenTarget.Permanent(entityId)
        }
    }
}

/**
 * Merges the result of a sub-operation with accumulated events and continues.
 *
 * Handles the common paused/error/success branching pattern:
 * - Paused: returns immediately with merged events
 * - Error: returns immediately with merged events and error
 * - Success: recursively checks for more continuations (if checkForMore provided)
 *            or returns success with merged events
 */
fun mergeAndContinue(
    result: ExecutionResult,
    events: List<GameEvent>,
    checkForMore: CheckForMore? = null
): ExecutionResult {
    if (result.isPaused) {
        return ExecutionResult.paused(
            result.state,
            result.pendingDecision!!,
            events + result.events,
            diagnostics = result.diagnostics,
        )
    }

    if (!result.isSuccess) {
        return ExecutionResult(
            state = result.state,
            events = events + result.events,
            error = result.error,
            diagnostics = result.diagnostics,
        )
    }

    val mergedEvents = events + result.events
    return if (checkForMore != null) {
        checkForMore(result.newState, mergedEvents).withDiagnosticsFrom(result.diagnostics)
    } else {
        ExecutionResult.success(result.newState, mergedEvents, result.diagnostics)
    }
}

/**
 * Preserve diagnostics while a continuation hands its state and events to the next continuation.
 * The callback only accepts state/events for historical API reasons, so the transient sidecar has
 * to be explicitly merged at this boundary.
 */
fun continueWithDiagnostics(
    result: ExecutionResult,
    checkForMore: CheckForMore,
    state: GameState = result.state,
    events: List<GameEvent> = result.events,
): ExecutionResult = checkForMore(state, events).withDiagnosticsFrom(result.diagnostics)

/** Effect-pipeline overload for the same continuation boundary. */
fun continueWithDiagnostics(
    result: EffectResult,
    checkForMore: CheckForMore,
    state: GameState = result.state,
    events: List<GameEvent> = result.events,
): ExecutionResult = continueWithDiagnostics(result.toExecutionResult(), checkForMore, state, events)

/** Prefix transient diagnostics without changing the wire-visible result shape. */
fun ExecutionResult.withDiagnosticsFrom(prefix: List<com.wingedsheep.engine.core.DiagnosticSignal>): ExecutionResult =
    if (prefix.isEmpty()) this else copy(diagnostics = prefix + diagnostics)

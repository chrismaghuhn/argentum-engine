package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameInitializer
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.session.SpectatorSeat
import com.wingedsheep.gameserver.session.SpectatorStateBuilder
import com.wingedsheep.sdk.model.EntityId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Diagnostics re-executed from a compact replay together with the trust status of that replay.
 *
 * An empty [diagnostics] list is only evidence of zero unsupported paths when [fidelity] is
 * [ReplayFidelity.EXACT]. A prefix from a replay that stopped early or lacks its v3 checkpoint
 * proof must never be consumed as a zero-diagnostics proof.
 */
data class ReconstructedDiagnostics(
    val diagnostics: List<DiagnosticSignal>,
    val fidelity: ReplayFidelity,
    /** Recorded action index at which re-execution stopped, when it diverged. */
    val divergedAtAction: Int? = null,
    /** Human-readable cause of divergence or an unverified replay proof. */
    val failure: String? = null,
)

/** A replay reconstructed back into the snapshot + delta stream the client replay viewer consumes. */
data class ReconstructedReplay(
    val initialSnapshot: ServerMessage.SpectatorStateUpdate,
    val deltas: List<SpectatorReplayDelta>,
    val fidelity: ReplayFidelity = ReplayFidelity.UNVERIFIED,
    /** Frame index the re-simulation stopped at, when [fidelity] is [ReplayFidelity.DIVERGED]. */
    val divergedAtFrame: Int? = null,
    /** Human-readable cause of divergence or an unverified v3 proof, for logs and the viewer badge. */
    val divergenceReason: String? = null,
) {
    val frameCount: Int get() = 1 + deltas.size
    val isComplete: Boolean get() = fidelity != ReplayFidelity.DIVERGED
}

/**
 * Re-simulates a [CompactReplay] to regenerate exactly what was (or would have been) shown live.
 *
 * Because the engine is deterministic — same seed + same seat ids + same decks + same ordered
 * actions ⇒ byte-identical [GameState] sequence (entity ids included; the engine never mints a
 * UUID) — we can rebuild the initial state with [GameInitializer], fold the recorded actions
 * through [ActionProcessor], and run the *same* [SpectatorStateBuilder] / [SpectatorReplayDiffCalculator]
 * the live broadcast used. The result is the `{initialSnapshot, deltas}` shape the client's
 * `reconstructSnapshots()` already understands, and any single frame's full unmasked state for the
 * "share frame as scenario" path.
 *
 * ## Surviving deploys
 * That determinism argument holds across *time* only if the engine is also unchanged, which over a
 * long-lived project it never is. Two defences apply:
 *
 * 1. **Pinned cards** ([ReplayCardPin]) — the replay carries the compiled definitions it ran on and
 *    they shadow the live corpus for this reconstruction, so card edits (by far the most common
 *    change) stop mattering.
 * 2. **Checkpoints** ([ReplayFingerprint]) — for what pinning can't cover (core rules changes,
 *    tokens, wished-for cards) the recorder left position fingerprints behind, re-checked as we
 *    fold, so drift is caught instead of rendered.
 *
 * When either defence reports a problem we stop at the last frame we can vouch for and mark the
 * result [ReplayFidelity.DIVERGED]; [ReplayService] then falls back to the presentation stream
 * materialized at record time ([ReplayPresentation]) so the viewer still sees the whole game.
 */
@Component
class ReplayReconstructor(
    private val cardRegistry: CardRegistry,
    // Same registry the live game was created with, so re-stamped printing images match
    // byte-for-byte. Nullable to mirror GameInitializer / GameSession (tests pass null).
    private val printingRegistry: PrintingRegistry?,
    private val tokenArtRegistry: com.wingedsheep.engine.registry.TokenArtRegistry? = null,
) {
    private val logger = LoggerFactory.getLogger(ReplayReconstructor::class.java)

    /** Rebuild the full snapshot + delta stream for [replay]. */
    fun reconstruct(replay: CompactReplay): ReconstructedReplay {
        val engine = engineFor(replay)
        val setup = replay.setup
        val seats = setup.players.map { SpectatorSeat(EntityId(it.playerId), it.name) }

        val deltas = ArrayList<SpectatorReplayDelta>(replay.actions.size)

        var initialSnapshot: ServerMessage.SpectatorStateUpdate? = null
        var previousSnapshot: ServerMessage.SpectatorStateUpdate? = null
        val forward = foldReplay(
            replay = replay,
            engine = engine,
            catchCallbackFailures = false,
            onFrame = { frame, state ->
                val snapshot = engine.spectatorStateBuilder.buildState(
                    state,
                    seats,
                    setup.seatRoster,
                    replay.gameId,
                )
                if (frame == 0) {
                    initialSnapshot = snapshot
                    previousSnapshot = snapshot
                } else {
                    val previous = checkNotNull(previousSnapshot)
                    deltas.add(SpectatorReplayDiffCalculator.computeDelta(previous, snapshot))
                    previousSnapshot = snapshot
                }
            },
        )
        if (forward.divergedAtAction != null && forward.failure != null) {
            val action = replay.actions.getOrNull(forward.divergedAtAction)
            logger.warn(
                "Replay {} (recorded on {}) diverged at action {} ({}): {} — truncating to {} frames",
                replay.gameId,
                replay.engineVersion,
                forward.divergedAtAction,
                action?.let { it::class.simpleName } ?: "initial checkpoint",
                forward.failure,
                1 + deltas.size,
            )
        }

        val initial = initialSnapshot ?: engine.spectatorStateBuilder.buildState(
            forward.initialState,
            seats,
            setup.seatRoster,
            replay.gameId,
        )
        return ReconstructedReplay(
            initialSnapshot = initial,
            deltas = deltas,
            fidelity = forward.fidelity,
            divergedAtFrame = forward.divergedAtAction,
            divergenceReason = forward.failure ?: forward.unverifiedReason,
        )
    }

    /**
     * Re-execute the recorded input stream and return the transient rules diagnostics produced by
     * that execution together with replay fidelity. This deliberately does not add anything to
     * [CompactReplay]: replay parity is proved by running the same [ActionProcessor] path used by
     * [reconstruct], not by persisting a second diagnostic stream.
     *
     * If the initial checkpoint or an action fails, the returned result is [ReplayFidelity.DIVERGED]
     * even when the collected prefix is empty. If every action applies, the normal reconstruction
     * fidelity still distinguishes a fully checkpointed v3 replay from an unverified legacy or
     * checkpoint-less replay.
     */
    fun reconstructDiagnostics(replay: CompactReplay): ReconstructedDiagnostics {
        val forward = foldReplay(
            replay = replay,
            engine = engineFor(replay),
            catchCallbackFailures = false,
        )
        return ReconstructedDiagnostics(
            diagnostics = forward.diagnostics,
            fidelity = forward.fidelity,
            divergedAtAction = forward.divergedAtAction,
            failure = forward.failure ?: forward.unverifiedReason,
        )
    }

    /**
     * Fold one replay once, exposing only internal state to same-module adapters at each verified
     * boundary. The callbacks are additive observation hooks around the existing replay fold; they
     * never execute actions and cannot alter the reconstructed state.
     */
    internal fun replayForward(
        replay: CompactReplay,
        onBeforeAction: ((index: Int, state: GameState, action: GameAction) -> Unit)? = null,
        onFrame: ((afterActionCount: Int, state: GameState) -> Unit)? = null,
    ): ReplayForwardResult = foldReplay(
        replay = replay,
        engine = engineFor(replay),
        onBeforeAction = onBeforeAction,
        onFrame = onFrame,
        catchCallbackFailures = true,
    )

    private fun foldReplay(
        replay: CompactReplay,
        engine: ReplayEngine,
        onBeforeAction: ((index: Int, state: GameState, action: GameAction) -> Unit)? = null,
        onFrame: ((afterActionCount: Int, state: GameState) -> Unit)? = null,
        catchCallbackFailures: Boolean = false,
    ): ReplayForwardResult {
        var state = engine.initialState(replay)
        val initialState = state
        val diagnostics = mutableListOf<DiagnosticSignal>()
        val verifiedCheckpoints = mutableSetOf<Int>()
        var initialCheckpointVerified = false
        var tailCheckpointVerified = false

        // v3 includes the zero-action state in the same tail-checkpoint contract. Legacy records
        // deliberately retain their previous behavior and did not verify an initial checkpoint.
        if (ReplayCheckpointPolicy.requiresTailCheckpoint(replay.version)) {
            when (val initialCheck = engine.verifyCheckpoint(replay, state, afterActionCount = 0)) {
                is CheckpointCheck.Mismatch -> return ReplayForwardResult(
                    initialState = initialState,
                    finalState = state,
                    fidelity = ReplayFidelity.DIVERGED,
                    appliedActionCount = 0,
                    divergedAtAction = 0,
                    failure = initialCheck.failure,
                    unverifiedReason = null,
                    initialCheckpointVerified = false,
                    intermediateCheckpointsVerified = false,
                    tailCheckpointVerified = false,
                    diagnostics = diagnostics,
                )

                CheckpointCheck.Match -> {
                    initialCheckpointVerified = true
                    verifiedCheckpoints += 0
                    if (replay.actions.isEmpty()) tailCheckpointVerified = true
                }

                CheckpointCheck.None -> Unit
            }
        }

        fun callbackFailure(index: Int, message: String): ReplayForwardResult = ReplayForwardResult(
            initialState = initialState,
            finalState = state,
            fidelity = ReplayFidelity.DIVERGED,
            appliedActionCount = index,
            divergedAtAction = index,
            failure = message,
            unverifiedReason = null,
            initialCheckpointVerified = initialCheckpointVerified,
            intermediateCheckpointsVerified = verifiedIntermediateCheckpoints(
                replay,
                verifiedCheckpoints,
                index,
            ),
            tailCheckpointVerified = false,
            diagnostics = diagnostics,
        )

        if (catchCallbackFailures) {
            try {
                onFrame?.invoke(0, state)
            } catch (failure: Exception) {
                return callbackFailure(0, "public replay frame at action 0 failed: ${failure.message}")
            }
        } else {
            onFrame?.invoke(0, state)
        }

        for ((index, action) in replay.actions.withIndex()) {
            if (catchCallbackFailures) {
                try {
                    onBeforeAction?.invoke(index, state, action)
                } catch (failure: Exception) {
                    return callbackFailure(
                        index,
                        "public replay boundary at action $index failed: ${failure.message}",
                    )
                }
            } else {
                onBeforeAction?.invoke(index, state, action)
            }

            val step = engine.applyAction(replay, state, action, index)
            diagnostics += step.diagnostics
            if (step.failure != null) {
                return ReplayForwardResult(
                    initialState = initialState,
                    finalState = state,
                    fidelity = ReplayFidelity.DIVERGED,
                    appliedActionCount = index,
                    divergedAtAction = index,
                    failure = step.failure,
                    unverifiedReason = null,
                    initialCheckpointVerified = initialCheckpointVerified,
                    intermediateCheckpointsVerified = verifiedIntermediateCheckpoints(
                        replay,
                        verifiedCheckpoints,
                        index,
                    ),
                    tailCheckpointVerified = false,
                    diagnostics = diagnostics,
                )
            }
            state = step.state!!
            if (step.checkpointVerified) {
                verifiedCheckpoints += index + 1
                if (index + 1 == replay.actions.size) tailCheckpointVerified = true
            }

            if (catchCallbackFailures) {
                try {
                    onFrame?.invoke(index + 1, state)
                } catch (failure: Exception) {
                    return callbackFailure(
                        index + 1,
                        "public replay frame at action ${index + 1} failed: ${failure.message}",
                    )
                }
            } else {
                onFrame?.invoke(index + 1, state)
            }
        }

        val unverifiedReason = if (ReplayCheckpointPolicy.requiresTailCheckpoint(replay.version)) {
            val tailCount = replay.checkpoints.count { it.afterActionCount == replay.actions.size }
            when {
                tailCount == 0 ->
                    "v3 replay has no checkpoint for the action-stream tail at ${replay.actions.size}"
                tailCount > 1 ->
                    "v3 replay has duplicate tail checkpoints at ${replay.actions.size}"
                !tailCheckpointVerified ->
                    "v3 replay tail checkpoint at ${replay.actions.size} was not verified"
                replay.checkpoints.any { it.afterActionCount !in 0..replay.actions.size } ->
                    "v3 replay contains a checkpoint outside the applied action stream"
                else -> null
            }
        } else null

        val fidelity = when {
            unverifiedReason != null -> ReplayFidelity.UNVERIFIED
            replay.checkpoints.isEmpty() -> ReplayFidelity.UNVERIFIED
            else -> ReplayFidelity.EXACT
        }
        return ReplayForwardResult(
            initialState = initialState,
            finalState = state,
            fidelity = fidelity,
            appliedActionCount = replay.actions.size,
            divergedAtAction = null,
            failure = null,
            unverifiedReason = unverifiedReason,
            initialCheckpointVerified = initialCheckpointVerified,
            intermediateCheckpointsVerified = verifiedIntermediateCheckpoints(
                replay,
                verifiedCheckpoints,
                replay.actions.size,
            ),
            tailCheckpointVerified = tailCheckpointVerified,
            diagnostics = diagnostics,
        )
    }

    private fun verifiedIntermediateCheckpoints(
        replay: CompactReplay,
        verifiedCheckpoints: Set<Int>,
        appliedActionCount: Int,
    ): Boolean {
        val expected = replay.checkpoints
            .map { it.afterActionCount }
            .filter { it in 1 until replay.actions.size }
            .toSet()
        return expected.all { it <= appliedActionCount && it in verifiedCheckpoints }
    }

    /**
     * The full, unmasked [GameState] at [frame] (0 = initial state, N = after the Nth action).
     * Powers the "share frame as scenario" path. Returns null if the frame is out of range or the
     * replay diverges before reaching it — a shared scenario must be the real position or nothing.
     */
    fun reconstructStateAt(replay: CompactReplay, frame: Int): GameState? {
        if (frame < 0 || frame > replay.actions.size) return null
        val engine = engineFor(replay)
        var state = engine.initialState(replay)
        for (index in 0 until frame) {
            val step = engine.applyAction(replay, state, replay.actions[index], index)
            if (step.failure != null) {
                logger.warn(
                    "Replay {} diverged at action {} while seeking frame {}: {}",
                    replay.gameId, index, frame, step.failure,
                )
                return null
            }
            state = step.state!!
        }
        return state
    }

    /**
     * Engine services bound to this replay's pinned card definitions. Built per reconstruction
     * because the pinned corpus differs per replay; the overlay is a thin child registry, so this
     * costs a handful of map inserts rather than a copy of the corpus.
     */
    private fun engineFor(replay: CompactReplay): ReplayEngine =
        ReplayEngine(ReplayCardPin.overlay(cardRegistry, replay.pinnedCards), printingRegistry, tokenArtRegistry)
}

/** Internal result of the shared authoritative replay fold used by replay consumers. */
internal data class ReplayForwardResult(
    val initialState: GameState,
    val finalState: GameState,
    val fidelity: ReplayFidelity,
    /** Number of action transitions whose resulting state was accepted by the fold. */
    val appliedActionCount: Int,
    /** Action coordinate that stopped the fold, or null when the input stream was consumed. */
    val divergedAtAction: Int?,
    /** A hard fold/callback failure; null when the fold consumed all actions. */
    val failure: String?,
    /** A consumed stream whose replay proof was incomplete. */
    val unverifiedReason: String?,
    val initialCheckpointVerified: Boolean,
    val intermediateCheckpointsVerified: Boolean,
    val tailCheckpointVerified: Boolean,
    val diagnostics: List<DiagnosticSignal>,
)

/** Outcome of folding one recorded action: a new state, or the reason we can't trust it. */
private class StepResult(
    val state: GameState?,
    val failure: String?,
    val checkpointVerified: Boolean = false,
    val diagnostics: List<DiagnosticSignal> = emptyList(),
)

private sealed interface CheckpointCheck {
    data object None : CheckpointCheck
    data object Match : CheckpointCheck
    data class Mismatch(val failure: String) : CheckpointCheck
}

/**
 * A [ReplayReconstructor] run bound to one replay's card corpus — the engine plumbing plus the
 * yield / decision-rebind / checkpoint bookkeeping that folding a recorded stream needs.
 */
private class ReplayEngine(
    cardRegistry: CardRegistry,
    printingRegistry: PrintingRegistry?,
    tokenArtRegistry: com.wingedsheep.engine.registry.TokenArtRegistry? = null,
) {
    private val actionProcessor = ActionProcessor(EngineServices(cardRegistry, printingRegistry, tokenArtRegistry))
    private val gameInitializer = GameInitializer(cardRegistry, printingRegistry)
    val spectatorStateBuilder = SpectatorStateBuilder(cardRegistry, ClientStateTransformer(cardRegistry))

    fun initialState(replay: CompactReplay): GameState {
        val setup = replay.setup
        val config = GameConfig(
            players = setup.players.map {
                PlayerConfig(
                    name = it.name,
                    deck = it.deck,
                    startingLife = it.startingLife,
                    playerId = EntityId(it.playerId),
                    commanderCardName = it.commanderCardName,
                )
            },
            startingHandSize = setup.startingHandSize,
            skipMulligans = setup.skipMulligans,
            useHandSmoother = setup.useHandSmoother,
            handSmootherCandidates = setup.handSmootherCandidates,
            startingPlayerIndex = setup.startingPlayerIndex,
            format = setup.format,
            attackMode = setup.attackMode,
            teams = setup.teams,
            seed = setup.seed,
        )
        return applyYields(gameInitializer.initializeGame(config).state, replay.yields, afterActionCount = 0)
    }

    /**
     * Apply the action at [index], re-apply any yields set at that point, and verify the checkpoint
     * stamped there. Returns a failure reason instead of a state when the action doesn't apply or
     * the position no longer matches what was recorded.
     */
    fun applyAction(replay: CompactReplay, state: GameState, action: GameAction, index: Int): StepResult {
        val result = actionProcessor.process(state, rebind(action, state)).result
        if (result.error != null) {
            return StepResult(
                state = null,
                failure = "action rejected: ${result.error}",
                diagnostics = result.diagnostics,
            )
        }
        if (result.diagnostics.isNotEmpty()) {
            return StepResult(
                state = null,
                failure = "unsupported rules path: ${result.diagnostics.joinToString { it.semanticCode }}",
                diagnostics = result.diagnostics,
            )
        }

        val afterActionCount = index + 1
        // Re-apply any yields set right after this action was originally applied, so the engine's
        // auto-answers reproduce on the next iteration exactly as they did live.
        val next = applyYields(result.state, replay.yields, afterActionCount)

        return when (val checkpoint = verifyCheckpoint(replay, next, afterActionCount)) {
            CheckpointCheck.None -> StepResult(next, null, diagnostics = result.diagnostics)
            CheckpointCheck.Match -> StepResult(
                next,
                null,
                checkpointVerified = true,
                diagnostics = result.diagnostics,
            )
            is CheckpointCheck.Mismatch -> StepResult(
                state = null,
                failure = checkpoint.failure,
                diagnostics = result.diagnostics,
            )
        }
    }

    fun verifyCheckpoint(
        replay: CompactReplay,
        state: GameState,
        afterActionCount: Int,
    ): CheckpointCheck {
        val checkpoints = replay.checkpoints.filter { it.afterActionCount == afterActionCount }
        if (checkpoints.isEmpty()) return CheckpointCheck.None

        val actual = ReplayFingerprint.of(state, replay.version)
        if (checkpoints.all { it.fingerprint == actual }) return CheckpointCheck.Match

        val expected = checkpoints.joinToString { it.fingerprint }
        return CheckpointCheck.Mismatch(
            "position drifted from the recording after $afterActionCount actions " +
                "(recorded [$expected], re-simulated $actual)",
        )
    }

    /**
     * Re-apply every recorded yield whose [ReplayYieldEntry.afterActionCount] equals [afterActionCount]
     * (i.e. it was originally set right after that many actions had been applied). Mirrors
     * [com.wingedsheep.gameserver.session.GameSession.setAbilityYield] and friends so the engine's
     * auto-answers reproduce identically. Almost always a no-op (most games carry no yields).
     */
    private fun applyYields(state: GameState, yields: List<ReplayYieldEntry>, afterActionCount: Int): GameState {
        if (yields.isEmpty()) return state
        var current = state
        for (entry in yields) {
            if (entry.afterActionCount != afterActionCount) continue
            val playerId = EntityId(entry.playerId)
            current = when (entry.op) {
                ReplayYieldOp.SET -> current.withYield(playerId, entry.identity!!, entry.kind!!)
                ReplayYieldOp.CLEAR_ABILITY -> current.withoutYield(playerId, entry.identity!!)
                ReplayYieldOp.CLEAR_ALL -> current.withoutYields(playerId)
            }
        }
        return current
    }

    /**
     * Re-bind a recorded action to the current reconstructed state. Decision ids are minted from a
     * UUID each run, so a recorded [SubmitDecision] carries the *original* run's id; we retarget it
     * at the id the freshly created pending decision actually has. The choice payload (targets,
     * cards, numbers — all by deterministic entity id) is untouched, so the outcome is identical.
     */
    private fun rebind(action: GameAction, state: GameState): GameAction {
        if (action !is SubmitDecision) return action
        val pendingId = state.pendingDecision?.id ?: return action
        if (pendingId == action.response.decisionId) return action
        return action.copy(response = action.response.withDecisionId(pendingId))
    }
}

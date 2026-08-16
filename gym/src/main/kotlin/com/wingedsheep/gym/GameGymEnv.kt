package com.wingedsheep.gym

import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.gym.contract.ActionRegistry
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.ResolvedAction
import com.wingedsheep.gym.service.PerspectiveMode
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.gym.service.SnapshotHandle
import com.wingedsheep.sdk.model.EntityId

/**
 * [GymEnv] adapter over a [GameEnvironment] — a game of Magic.
 *
 * Holds the per-env bookkeeping that used to live in `MultiEnvService.EnvEntry`
 * (perspective, the live [ActionRegistry] from the last observation) so the
 * service layer can treat every env type the same. The underlying
 * [GameEnvironment] is left untouched, since the trainer SPI drives it directly.
 */
class GameGymEnv(
    val environment: GameEnvironment,
    private var perspectivePlayerIndex: Int,
    private val observationBuilder: ObservationBuilder,
    private var perspectiveMode: PerspectiveMode = PerspectiveMode.FIXED_SEAT,
) : GymEnv {

    @Volatile
    private var registry: ActionRegistry = ActionRegistry.EMPTY

    /**
     * Last valid information-set owner. Used only when an agent-to-act env reaches
     * a terminal state, where the engine no longer has an actor to select.
     */
    private var lastPerspectivePlayerId: EntityId? = null

    override val isTerminal: Boolean get() = environment.state.gameOver

    override fun observe(): ObservationResult = build()

    override fun step(actionId: Int): ObservationResult {
        executeResolved(registry.resolve(actionId), actionId)
        return build()
    }

    override fun fork(): GymEnv {
        val forked = GameGymEnv(
            environment = environment.fork(),
            perspectivePlayerIndex = perspectivePlayerIndex,
            observationBuilder = observationBuilder,
            perspectiveMode = perspectiveMode,
        )
        forked.lastPerspectivePlayerId = lastPerspectivePlayerId
        forked.build()
        return forked
    }

    // --- game-only operations (used by MultiEnvService via cast) -------------

    /** Re-initialise the underlying game in place, preserving perspective configuration. */
    fun reset(gameConfig: GameConfig): ObservationResult {
        lastPerspectivePlayerId = null
        environment.reset(gameConfig)
        return build()
    }

    /**
     * Re-initialise the game and atomically replace its perspective contract.
     * This prevents an env reused by a trainer from retaining stale fixed-seat
     * settings from its previous episode.
     */
    fun reset(
        gameConfig: GameConfig,
        perspectiveMode: PerspectiveMode,
        perspectivePlayerIndex: Int,
    ): ObservationResult {
        this.perspectiveMode = perspectiveMode
        this.perspectivePlayerIndex = perspectivePlayerIndex
        return reset(gameConfig)
    }

    /** Submit a raw `DecisionResponse` while paused on a complex decision. */
    fun submitDecision(response: DecisionResponse): ObservationResult {
        val pending = environment.state.pendingDecision
            ?: throw IllegalStateException("Env is not paused on a decision")
        check(response.decisionId == pending.id) {
            "Decision ID mismatch: response=${response.decisionId}, pending=${pending.id}"
        }
        environment.step(SubmitDecision(pending.playerId, response))
        return build()
    }

    fun snapshot(codec: SnapshotCodec): SnapshotHandle =
        codec.save(
            state = environment.state,
            playerIds = environment.playerIds,
            stepCount = environment.stepCount,
        )

    fun restore(codec: SnapshotCodec, handle: SnapshotHandle): ObservationResult {
        val snap = codec.load(handle)
        environment.restore(snap.state, snap.playerIds, snap.stepCount)
        return build()
    }

    // --- internals -----------------------------------------------------------

    private fun build(): ObservationResult {
        val perspective = resolvePerspectivePlayer()
        val result = observationBuilder.build(
            environment.state, perspective, environment.legalActions()
        )
        registry = result.registry
        return result
    }

    private fun resolvePerspectivePlayer(): EntityId {
        val fixedSeat = environment.playerIds.getOrNull(perspectivePlayerIndex)
            ?: throw IllegalStateException("Env has no player at index $perspectivePlayerIndex")

        val perspective = when (perspectiveMode) {
            PerspectiveMode.FIXED_SEAT -> fixedSeat
            PerspectiveMode.AGENT_TO_ACT ->
                environment.agentToAct ?: lastPerspectivePlayerId ?: fixedSeat
        }

        check(perspective in environment.playerIds) {
            "Perspective player $perspective is not part of this environment"
        }
        lastPerspectivePlayerId = perspective
        return perspective
    }

    private fun executeResolved(resolved: ResolvedAction, actionId: Int) {
        when (resolved) {
            is ResolvedAction.Legal -> environment.step(resolved.action)
            is ResolvedAction.Decision -> {
                val pending = environment.state.pendingDecision
                    ?: throw IllegalStateException("Registry has a decision response but env is not paused")
                environment.step(SubmitDecision(pending.playerId, resolved.response))
            }
            ResolvedAction.Unknown ->
                throw IllegalArgumentException("Action ID $actionId is not valid for the current step")
        }
    }
}

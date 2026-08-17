package com.wingedsheep.gym

import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.gym.contract.ActionRegistry
import com.wingedsheep.gym.contract.ActionPayloadRequirements
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.ResolvedAction
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.gym.service.SnapshotHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

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
    private val perspectivePlayerIndex: Int,
    private val observationBuilder: ObservationBuilder
) : GymEnv {

    @Volatile
    private var registry: ActionRegistry = ActionRegistry.EMPTY

    private val actionSerialization = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        ignoreUnknownKeys = false
    }

    override val isTerminal: Boolean get() = environment.state.gameOver
    override val isTruncated: Boolean get() = environment.isTruncated

    override fun observe(): ObservationResult = build()

    override fun step(actionId: Int): ObservationResult {
        val resolved = registry.resolve(actionId)
        if (resolved is ResolvedAction.Legal &&
            ActionPayloadRequirements.requiresStructuredAction(resolved.legalAction)
        ) {
            throw IllegalArgumentException(
                "Action ID $actionId requires a structured action payload; " +
                    "complete actionSemantics and submit it with the action ID"
            )
        }
        executeResolved(resolved, actionId)
        return build()
    }

    /**
     * Execute an action-ID candidate with an external controller's explicit choice payload.
     * [actionPayload] is an overlay on the action's `actionSemantics`; the registry supplies
     * opaque/runtime fields such as generated ability IDs. No target, payment, mode, or ordering
     * is selected by this method.
     */
    fun step(actionId: Int, actionPayload: JsonObject): ObservationResult {
        val resolved = registry.resolve(actionId)
        val legal = resolved as? ResolvedAction.Legal
            ?: throw IllegalArgumentException(
                "Action ID $actionId does not resolve to a legal game-action candidate"
            )
        val submitted = materializeAction(legal.action, actionPayload)
        environment.stepFromCandidate(legal.action, submitted)
        return build()
    }

    override fun fork(): GymEnv =
        GameGymEnv(environment.fork(), perspectivePlayerIndex, observationBuilder)
            .also { it.build() }

    // --- game-only operations (used by MultiEnvService via cast) -------------

    /** Re-initialise the underlying game in place. */
    fun reset(gameConfig: GameConfig, maxSteps: Int? = null): ObservationResult {
        environment.reset(gameConfig, maxSteps)
        return build()
    }

    /** Submit a raw `DecisionResponse` while paused on a complex decision. */
    fun submitDecision(
        response: DecisionResponse,
        actorId: com.wingedsheep.sdk.model.EntityId? = null
    ): ObservationResult {
        val pending = environment.state.pendingDecision
            ?: throw IllegalStateException("Env is not paused on a decision")
        check(actorId == null || actorId == pending.playerId) {
            "Decision actor mismatch: actor=$actorId, expected=${pending.playerId}"
        }
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
            maxSteps = environment.maxSteps,
        )

    fun restore(codec: SnapshotCodec, handle: SnapshotHandle): ObservationResult {
        val snap = codec.load(handle)
        environment.restore(snap.state, snap.playerIds, snap.stepCount, snap.maxSteps)
        return build()
    }

    // --- internals -----------------------------------------------------------

    private fun build(): ObservationResult {
        val perspective = environment.playerIds.getOrNull(perspectivePlayerIndex)
            ?: throw IllegalStateException("Env has no player at index $perspectivePlayerIndex")
        val result = observationBuilder.build(
            environment.state,
            perspective,
            environment.legalActions(),
            truncated = environment.isTruncated
        )
        registry = result.registry
        return result
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

    private fun materializeAction(template: GameAction, payload: JsonObject): GameAction {
        val templateJson = actionSerialization
            .encodeToJsonElement(GameAction.serializer(), template)
            .jsonObject
        val expectedType = templateJson["type"]
        val suppliedType = payload["type"]
        require(suppliedType == null || suppliedType == expectedType) {
            "Structured action type does not match action candidate"
        }

        val merged = buildJsonObject {
            templateJson.forEach { (key, value) -> put(key, value) }
            payload.forEach { (key, value) ->
                // ObservationBuilder replaces ActivateAbility.abilityId with the stable
                // abilityKey so semantic observations do not expose runtime handles. The
                // registry template supplies the real handle when the payload is decoded.
                if (key != "abilityKey") put(key, value)
            }
        }
        return actionSerialization.decodeFromJsonElement(GameAction.serializer(), merged)
    }
}

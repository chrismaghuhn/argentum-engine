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
import com.wingedsheep.gym.contract.TrainingObservation
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
 * (fallback perspective, the live [ActionRegistry] from the last observation)
 * so the service layer can treat every env type the same. While a game is
 * active, observations are routed to [GameEnvironment.agentToAct] so every
 * player can drive the same 1v1 environment. The configured perspective is
 * retained only for states without an acting player (terminal or truncated).
 * Every externally callable step and decision-submission method is the trusted
 * strict-control boundary: it commits one validated Rules transition and never
 * enters [GameEnvironment]'s legacy simulator quiet-state loop. Legacy AI/MCTS
 * callers continue to use [GameEnvironment.step] directly.
 * Legacy [GameEnvironment.step] semantics remain untouched for those callers.
 */
class GameGymEnv(
    val environment: GameEnvironment,
    perspectivePlayerIndex: Int,
    private val observationBuilder: ObservationBuilder
) : GymEnv {

    private var fallbackPerspectivePlayerIndex: Int = perspectivePlayerIndex

    @Volatile
    private var registry: ActionRegistry = ActionRegistry.EMPTY

    /** The last wire observation and registry; repeated reads of one state are a no-op. */
    @Volatile
    private var cachedObservation: ObservationResult? = null

    /** Step generation, rather than the privacy-projected digest, guards the cache. */
    @Volatile
    private var cachedStepCount: Int? = null

    /** The perspective used to build [cachedObservation], not just its game-state generation. */
    @Volatile
    private var cachedPerspectivePlayerId: com.wingedsheep.sdk.model.EntityId? = null

    /** Env-local action handles are never reused, including after reset/restore. */
    private var nextActionId: Int = 0

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
     * opaque/runtime fields such as generated ability IDs. The caller must include every
     * required target, payment, mode, combat, or ordering field; this method selects none.
     */
    fun step(actionId: Int, actionPayload: JsonObject): ObservationResult {
        val resolved = registry.resolve(actionId)
        val legal = resolved as? ResolvedAction.Legal
            ?: throw IllegalArgumentException(
                "Action ID $actionId does not resolve to a legal game-action candidate"
            )
        val missingFields = ActionPayloadRequirements.missingRequiredFields(
            legal.legalAction,
            actionPayload
        )
        require(missingFields.isEmpty()) {
            "Structured action payload is missing explicit field(s): ${missingFields.joinToString()}; " +
                "copy actionSemantics and fill every required choice"
        }
        val submitted = materializeAction(legal.action, actionPayload)
        environment.stepFromCandidateStrict(legal.action, submitted)
        return build()
    }

    override fun fork(): GymEnv =
        GameGymEnv(environment.fork(), fallbackPerspectivePlayerIndex, observationBuilder)
            .also { it.build() }

    // --- game-only operations (used by MultiEnvService via cast) -------------

    /** Re-initialise the underlying game in place. */
    fun reset(
        gameConfig: GameConfig,
        perspectivePlayerIndex: Int = fallbackPerspectivePlayerIndex,
        maxSteps: Int? = null
    ): ObservationResult {
        cachedObservation = null
        cachedStepCount = null
        cachedPerspectivePlayerId = null
        environment.reset(gameConfig, maxSteps)
        require(perspectivePlayerIndex in environment.playerIds.indices) {
            "perspectivePlayerIndex=$perspectivePlayerIndex out of range for ${environment.playerIds.size} players"
        }
        fallbackPerspectivePlayerIndex = perspectivePlayerIndex
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
        environment.stepStrict(SubmitDecision(pending.playerId, response))
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
        cachedObservation = null
        cachedStepCount = null
        cachedPerspectivePlayerId = null
        environment.restore(snap.state, snap.playerIds, snap.stepCount, snap.maxSteps)
        return build()
    }

    // --- internals -----------------------------------------------------------

    private fun build(): ObservationResult {
        val perspective = environment.agentToAct
            ?: environment.playerIds.getOrNull(fallbackPerspectivePlayerIndex)
            ?: throw IllegalStateException(
                "Env has no player at fallback perspective index $fallbackPerspectivePlayerIndex"
            )
        val result = observationBuilder.build(
            environment.state,
            perspective,
            environment.legalActions(),
            truncated = environment.isTruncated
        )
        val rawObservation = result.observation as? TrainingObservation
            ?: error("GameGymEnv requires a TrainingObservation")
        val previous = cachedObservation
        if (previous != null &&
            cachedStepCount == environment.stepCount &&
            cachedPerspectivePlayerId == perspective
        ) {
            registry = previous.registry
            return previous
        }

        val rawIds = rawObservation.legalActions.map { it.actionId }
        val freshIds = rawIds.map { allocateActionId() }
        val idMapping = rawIds.zip(freshIds).toMap()
        val remappedObservation = rawObservation.copy(
            legalActions = rawObservation.legalActions.mapIndexed { index, action ->
                action.copy(actionId = freshIds[index])
            }
        )
        val remapped = ObservationResult(
            observation = remappedObservation,
            registry = result.registry.remapIds(idMapping)
        )
        cachedObservation = remapped
        cachedStepCount = environment.stepCount
        cachedPerspectivePlayerId = perspective
        registry = remapped.registry
        return remapped
    }

    private fun allocateActionId(): Int {
        check(nextActionId != Int.MAX_VALUE) { "Action ID space exhausted for this environment" }
        return nextActionId++
    }

    private fun executeResolved(resolved: ResolvedAction, actionId: Int) {
        when (resolved) {
            is ResolvedAction.Legal -> environment.stepStrict(resolved.action)
            is ResolvedAction.Decision -> {
                val pending = environment.state.pendingDecision
                    ?: throw IllegalStateException("Registry has a decision response but env is not paused")
                environment.stepStrict(SubmitDecision(pending.playerId, resolved.response))
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

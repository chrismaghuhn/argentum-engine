package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidation
import com.wingedsheep.gym.contract.ActionRegistry
import com.wingedsheep.gym.contract.ActionPayloadRequirements
import com.wingedsheep.gym.contract.AttackDeclarationDomainSubmission
import com.wingedsheep.gym.contract.BlockerDeclarationDomainSubmission
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ManaColorDomainSubmission
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
    val diagnostics: EpisodeDiagnostics get() = environment.diagnostics

    /** Typed lifecycle closure; null means the current episode is still open. */
    val episodeClosure: EpisodeClosureV1? get() = environment.episodeClosure

    override fun observe(): ObservationResult = build()

    override fun step(actionId: Int): ObservationResult = classifyExternalFailure {
        val resolved = registry.resolve(actionId)
        if (resolved is ResolvedAction.Legal) {
            ActionPayloadRequirements.requireTargetDomainSupported(resolved.legalAction)
            AttackDeclarationDomainSubmission.requireSupported(resolved.legalAction)
            BlockerDeclarationDomainSubmission.requireSupported(resolved.legalAction)
            ManaColorDomainSubmission.requireSupported(resolved.legalAction)
            requireActionPaymentPlan(resolved, resolved.action, actionId)
        }
        if (resolved is ResolvedAction.Legal &&
            observationBuilder.requiresStructuredActionFor(environment.state, resolved.legalAction)
        ) {
            throw IllegalArgumentException(
                "Action ID $actionId requires a structured action payload; " +
                    "complete actionSemantics and submit it with the action ID"
            )
        }
        executeResolved(resolved, actionId)
        build()
    }

    /**
     * Execute an action-ID candidate with an external controller's explicit choice payload.
     * [actionPayload] is an overlay on the action's `actionSemantics`; the registry supplies
     * opaque/runtime fields such as generated ability IDs. The caller must include every
     * required target, payment, mode, combat, or ordering field; this method selects none.
     */
    fun step(actionId: Int, actionPayload: JsonObject): ObservationResult = classifyExternalFailure {
        val resolved = registry.resolve(actionId)
        val legal = resolved as? ResolvedAction.Legal
            ?: throw IllegalArgumentException(
                "Action ID $actionId does not resolve to a legal game-action candidate"
            )
        ActionPayloadRequirements.requireTargetDomainSupported(legal.legalAction)
        AttackDeclarationDomainSubmission.requireSupported(legal.legalAction)
        BlockerDeclarationDomainSubmission.requireSupported(legal.legalAction)
        ManaColorDomainSubmission.requireSupported(legal.legalAction)
        val missingFields = observationBuilder.missingRequiredFieldsFor(
            environment.state,
            legal.legalAction,
            actionPayload,
        )
        require(missingFields.isEmpty()) {
            "Structured action payload is missing explicit field(s): ${missingFields.joinToString()}; " +
                "copy actionSemantics and fill every required choice"
        }
        val submitted = materializeAction(legal.action, actionPayload)
        AttackDeclarationDomainSubmission.requireWithinRegisteredDomain(legal.legalAction, submitted)
        BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(legal.legalAction, submitted)
        ManaColorDomainSubmission.requireWithinRegisteredDomain(legal.legalAction, submitted)
        ActionPayloadRequirements.requireTargetPayloadPartition(legal.legalAction, submitted)
        requireActionPaymentPlan(legal, submitted, actionId)
        environment.stepFromCandidateStrict(legal.legalAction, submitted)
        build()
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
    ): ObservationResult = classifyExternalFailure {
        val pending = requireNotNull(environment.state.pendingDecision) {
            "Env is not paused on a decision"
        }
        require(actorId == null || actorId == pending.playerId) {
            "Decision actor mismatch: actor=$actorId, expected=${pending.playerId}"
        }
        require(response.decisionId == pending.id) {
            "Decision ID mismatch: response=${response.decisionId}, pending=${pending.id}"
        }
        environment.stepStrict(SubmitDecision(pending.playerId, response))
        build()
    }

    fun snapshot(codec: SnapshotCodec): SnapshotHandle =
        codec.save(
            state = environment.state,
            playerIds = environment.playerIds,
            stepCount = environment.stepCount,
            maxSteps = environment.maxSteps,
            diagnostics = environment.diagnostics,
            projectionGeneration = environment.projectionGeneration,
            failureClosure = environment.episodeClosure as? EpisodeClosureV1.Failed,
        )

    fun restore(codec: SnapshotCodec, handle: SnapshotHandle): ObservationResult {
        val snap = codec.load(handle)
        cachedObservation = null
        cachedStepCount = null
        cachedPerspectivePlayerId = null
        environment.restore(
            state = snap.state,
            playerIds = snap.playerIds,
            stepCount = snap.stepCount,
            maxSteps = snap.maxSteps,
            diagnostics = snap.diagnostics,
            projectionGeneration = snap.projectionGeneration,
            failureClosure = snap.failureClosure,
        )
        return build()
    }

    // --- internals -----------------------------------------------------------

    private fun build(): ObservationResult =
        try {
            buildObservation()
        } catch (failure: UnsupportedPathFailure) {
            environment.recordFailure(EpisodeFailureReason.UNSUPPORTED_DIAGNOSTIC)
            throw failure
        } catch (failure: RuntimeException) {
            environment.recordFailure(EpisodeFailureReason.OBSERVATION_FAILURE)
            throw failure
        }

    private fun buildObservation(): ObservationResult {
        val perspective = ObservationPerspective.resolve(
            state = environment.state,
            playerIds = environment.playerIds,
            fallbackPerspectivePlayerIndex = fallbackPerspectivePlayerIndex,
            truncated = environment.isTruncated,
        )
            ?: throw IllegalStateException(
                "Env has no player at fallback perspective index $fallbackPerspectivePlayerIndex"
            )
        val result = observationBuilder.build(
            environment.state,
            perspective,
            environment.legalActions(),
            truncated = environment.isTruncated
        )
        if (result.diagnostics.isNotEmpty()) {
            environment.recordObservationDiagnostics(
                environment.projectionCursor(perspective),
                result.diagnostics,
            )
            throw UnsupportedPathFailure(result.diagnostics)
        }
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
            registry = result.registry.remapIds(idMapping),
            diagnostics = result.diagnostics,
        )
        cachedObservation = remapped
        cachedStepCount = environment.stepCount
        cachedPerspectivePlayerId = perspective
        registry = remapped.registry
        return remapped
    }

    private inline fun <T> classifyExternalFailure(block: () -> T): T =
        try {
            block()
        } catch (failure: UnsupportedPathFailure) {
            environment.recordFailure(EpisodeFailureReason.UNSUPPORTED_DIAGNOSTIC)
            throw failure
        } catch (failure: IllegalArgumentException) {
            environment.recordFailure(EpisodeFailureReason.PUBLIC_CHOICE_REJECTED)
            throw failure
        } catch (failure: RuntimeException) {
            environment.recordFailure(EpisodeFailureReason.ENGINE_EXCEPTION)
            throw failure
        }

    private fun allocateActionId(): Int {
        check(nextActionId != Int.MAX_VALUE) { "Action ID space exhausted for this environment" }
        return nextActionId++
    }

    private fun executeResolved(resolved: ResolvedAction, actionId: Int) {
        when (resolved) {
            is ResolvedAction.Legal -> {
                BlockerDeclarationDomainSubmission.requireSupported(resolved.legalAction)
                ManaColorDomainSubmission.requireSupported(resolved.legalAction)
                requireActionPaymentPlan(resolved, resolved.action, actionId)
                // Keep the registry's complete LegalAction certificate bound to the live
                // candidate even for an action-ID-only call. This prevents a stale blocker handle
                // from reaching the compatibility execution path after the live domain changes.
                environment.stepFromCandidateStrict(resolved.legalAction, resolved.action)
            }
            is ResolvedAction.Decision -> {
                val pending = requireNotNull(environment.state.pendingDecision) {
                    "Registry has a decision response but env is not paused"
                }
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

    /**
     * A trusted Gym submission for an action-level or target-bound mana payment must carry the
     * complete public plan. The legacy engine still supports AutoPay/FromPool/Explicit(source IDs)
     * for non-Gym callers, but none of those forms is a valid policy contract here.
     */
    private fun requireActionPaymentPlan(
        resolved: ResolvedAction.Legal,
        submitted: GameAction,
        actionId: Int,
    ) {
        if (resolved.legalAction.manaCostString == null) return

        val registeredView = registeredViewFor(actionId)
        val currentTargetPayment = if (submitted is ActivateAbility) {
            currentTargetPaymentSnapshot(resolved.legalAction)
        } else {
            null
        }
        val registeredTargetPayment = registeredView.targetPaymentDomain
        val currentTargetPaymentDomain = currentTargetPayment?.view?.targetPaymentDomain
        if (registeredTargetPayment != null || currentTargetPaymentDomain != null) {
            requireTargetPaymentPlan(
                resolved = resolved,
                submitted = submitted,
                registeredView = registeredView,
                currentSnapshot = currentTargetPayment,
            )
            return
        }

        if (observationBuilder.paymentDomainV5For(environment.state, resolved.legalAction) == null) {
            throw UnsupportedPathFailure(
                listOf(DiagnosticSignal(DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED))
            )
        }

        val strategy = when (submitted) {
            is ActivateAbility -> submitted.paymentStrategy
            is CastSpell -> submitted.paymentStrategy
            is CycleCard -> submitted.paymentStrategy
            else -> throw IllegalArgumentException("Structured action changed its action type")
        }

        if (submitted is CycleCard) {
            val explicitV3 = strategy as? PaymentStrategy.ExplicitV3
                ?: throw IllegalArgumentException(
                    "CycleCard payment must submit PaymentStrategy.ExplicitV3; automatic, pool, and legacy payments are not allowed"
                )
            require(explicitV3.paymentPlan != null) {
                "CycleCard payment must submit a complete PaymentPlanV3; source IDs alone are not sufficient"
            }
            return
        }

        when (strategy) {
            is PaymentStrategy.ExplicitV3 -> {
                require(strategy.paymentPlan != null) {
                    "${resolved.legalAction.actionType} payment must submit a complete PaymentPlanV3; source IDs alone are not sufficient"
                }
            }
            else -> throw IllegalArgumentException(
                "${resolved.legalAction.actionType} payment must submit a complete PaymentPlanV3; automatic, pool, and legacy payments are not allowed"
            )
        }
    }

    /** The public view registered for this opaque action handle; never recomputed from live state. */
    private fun registeredViewFor(actionId: Int): LegalActionView =
        (cachedObservation?.observation as? TrainingObservation)
            ?.legalActions
            ?.singleOrNull { it.actionId == actionId }
            ?: throw IllegalArgumentException(
                "Action ID $actionId has no registered observation snapshot",
            )

    private data class CurrentTargetPaymentSnapshot(
        val legalAction: LegalAction,
        val view: LegalActionView,
    )

    /**
     * Re-enumerate only the selected current Rules action without touching the observation cache,
     * action-handle allocator, diagnostic ledger, or action cursor. This is the live counterpart
     * of the registered [LegalActionView] held by [cachedObservation].
     */
    private fun currentTargetPaymentSnapshot(
        registeredAction: LegalAction,
    ): CurrentTargetPaymentSnapshot {
        val currentActions = environment.legalActions()
        val currentAction = currentActions.firstOrNull { candidate ->
            environment.isCurrentActionCandidate(candidate.action, registeredAction.action)
        } ?: throw IllegalArgumentException(
            "Registered action is no longer present in the current Rules action set",
        )
        val perspective = ObservationPerspective.resolve(
            state = environment.state,
            playerIds = environment.playerIds,
            fallbackPerspectivePlayerIndex = fallbackPerspectivePlayerIndex,
            truncated = environment.isTruncated,
        )
            ?: throw IllegalArgumentException("Current target-payment action has no acting player")
        val result = observationBuilder.build(
            state = environment.state,
            perspectivePlayerId = perspective,
            legalActions = listOf(currentAction),
            truncated = environment.isTruncated,
        )
        if (result.diagnostics.isNotEmpty()) {
            throw UnsupportedPathFailure(result.diagnostics)
        }
        val observation = result.observation as? TrainingObservation
            ?: throw IllegalStateException("GameGymEnv requires a TrainingObservation")
        val view = observation.legalActions.singleOrNull()
            ?: throw IllegalArgumentException(
                "Current Rules action has no complete public observation view",
            )
        return CurrentTargetPaymentSnapshot(currentAction, view)
    }

    private fun requireTargetPaymentPlan(
        resolved: ResolvedAction.Legal,
        submitted: GameAction,
        registeredView: LegalActionView,
        currentSnapshot: CurrentTargetPaymentSnapshot?,
    ) {
        val registeredRelation = registeredView.targetPaymentDomain
        val current = currentSnapshot
            ?: throw IllegalArgumentException("Target-bound payment domain is stale")
        val currentRelation = current.view.targetPaymentDomain
        require(registeredRelation != null && currentRelation != null) {
            "Target-bound payment domain is unavailable or stale"
        }
        require(registeredView.targetDomain == current.view.targetDomain) {
            "Registered target domain is stale for the current action"
        }
        require(registeredRelation == currentRelation) {
            "Registered target-payment domain is stale for the current action"
        }

        val activate = submitted as? ActivateAbility
            ?: throw IllegalArgumentException("Target-bound payment requires ActivateAbility")
        val selectedTarget = activate.targets.singleOrNull() as? ChosenTarget.Permanent
            ?: throw IllegalArgumentException(
                "Target-bound payment requires exactly one permanent target",
            )
        val registeredBinding = registeredRelation.targetBindings
            .singleOrNull { it.target == selectedTarget.entityId }
            ?: throw IllegalArgumentException("Submitted target is outside the registered payment domain")
        val currentBinding = currentRelation.targetBindings
            .singleOrNull { it.target == selectedTarget.entityId }
            ?: throw IllegalArgumentException("Submitted target is outside the current payment domain")
        require(registeredBinding == currentBinding) {
            "Selected target-payment binding is stale"
        }
        require(currentBinding.affordable) {
            "Selected target-payment binding is unaffordable"
        }

        val explicitV3 = activate.paymentStrategy as? PaymentStrategy.ExplicitV3
            ?: throw IllegalArgumentException(
                "Target-bound payment must submit PaymentStrategy.ExplicitV3",
            )
        val plan = explicitV3.paymentPlan
            ?: throw IllegalArgumentException("Target-bound payment must submit a complete PaymentPlanV3")
        when (val validation = observationBuilder.validateTargetPaymentPlanV3(
            state = environment.state,
            template = current.legalAction,
            submitted = activate,
            plan = plan,
            expectedRequiredCost = currentBinding.paymentDomain.requiredCost,
        )) {
            is PaymentPlanValidation.AcceptedV3 -> Unit
            is PaymentPlanValidation.Rejected -> throw IllegalArgumentException(
                "Target-bound PaymentPlanV3 rejected: ${validation.reason}",
            )
            else -> throw IllegalStateException("Unexpected target-bound payment validation result")
        }
    }
}

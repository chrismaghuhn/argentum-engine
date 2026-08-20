package com.wingedsheep.gym

import com.wingedsheep.ai.engine.DecisionResponder
import com.wingedsheep.ai.engine.GameSimulator
import com.wingedsheep.ai.engine.SimulationResult
import com.wingedsheep.ai.engine.evaluation.BoardEvaluator
import com.wingedsheep.ai.engine.evaluation.CompositeBoardEvaluator
import com.wingedsheep.ai.engine.evaluation.BoardPresence
import com.wingedsheep.ai.engine.evaluation.CardAdvantage
import com.wingedsheep.ai.engine.evaluation.LifeDifferential
import com.wingedsheep.ai.engine.evaluation.Tempo
import com.wingedsheep.ai.engine.evaluation.ThreatAssessment
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId

/**
 * A stateful game environment for AI agents, MCTS, and reinforcement learning.
 *
 * Wraps the rules engine's immutable [GameState] + [ActionProcessor] into a
 * mutable environment with a simple `reset` / `step` / `legalActions` API.
 *
 * ## Key properties
 *
 * - **Forkable for MCTS:** [fork] creates a new environment pointing at the same
 *   immutable [GameState]. Since state is never mutated in place, forking is free.
 * - **Decision-aware:** When an action pauses for a [PendingDecision], the environment
 *   exposes it via [pendingDecision] and [decisionOptions]. The caller submits a
 *   [SubmitDecision] action through [step] to continue.
 * - **No server dependencies:** Runs entirely within the rules-engine module.
 *
 * ## Usage
 *
 * ```kotlin
 * val env = GameEnvironment.create(cardRegistry)
 * env.reset(GameConfig(
 *     players = listOf(PlayerConfig("Alice", deck1), PlayerConfig("Bob", deck2)),
 *     skipMulligans = true
 * ))
 *
 * while (!env.isTerminal) {
 *     val actions = env.legalActions()
 *     val chosen = actions.random() // or policy network, MCTS, etc.
 *     val result = env.step(chosen.action)
 * }
 *
 * println("Winner: ${env.winnerId}")
 * ```
 *
 * ## MCTS usage
 *
 * ```kotlin
 * fun mcts(env: GameEnvironment, iterations: Int): LegalAction {
 *     val actions = env.legalActions()
 *     val scores = DoubleArray(actions.size)
 *     repeat(iterations) { i ->
 *         val idx = i % actions.size
 *         val child = env.fork()
 *         child.step(actions[idx].action)
 *         scores[idx] += rollout(child)
 *     }
 *     return actions[scores.indices.maxBy { scores[it] }]
 * }
 *
 * fun rollout(env: GameEnvironment): Double {
 *     val playerId = env.agentToAct ?: return env.evaluate(env.playerIds[0])
 *     while (!env.isTerminal) {
 *         val actions = env.legalActions()
 *         env.step(actions.random().action)
 *     }
 *     return env.evaluate(playerId)
 * }
 * ```
 */
class GameEnvironment private constructor(
    private val cardRegistry: CardRegistry,
    private val processor: ActionProcessor,
    private val enumerator: LegalActionEnumerator,
    private val evaluator: BoardEvaluator,
    private val simulator: GameSimulator
) {
    // =========================================================================
    // State
    // =========================================================================

    /** Current immutable game state. */
    var state: GameState = GameState()
        private set

    /** Player entity IDs in turn order, set after [reset]. */
    var playerIds: List<EntityId> = emptyList()
        private set

    /** Cumulative events since the last [reset]. */
    var events: List<GameEvent> = emptyList()
        private set

    /** Events from the most recent [step] call. */
    var lastStepEvents: List<GameEvent> = emptyList()
        private set

    /** Total number of actions submitted since [reset]. */
    var stepCount: Int = 0
        private set

    /** Optional episode horizon configured by the Gym adapter. */
    var maxSteps: Int? = null
        private set

    // =========================================================================
    // Queries
    // =========================================================================

    /** True if the game has ended (someone won, drew, or decked out). */
    val isTerminal: Boolean get() = state.gameOver

    /** True when the configured horizon ended an otherwise non-terminal episode. */
    val isTruncated: Boolean
        get() = !isTerminal && maxSteps?.let { stepCount >= it } == true

    /** The winner's entity ID, or null if the game is ongoing or a draw. */
    val winnerId: EntityId? get() = state.winnerId

    /** The player who currently needs to act (has priority or a pending decision). */
    val agentToAct: EntityId?
        get() = if (isTerminal || isTruncated) null
        else state.pendingDecision?.playerId ?: state.priorityPlayerId

    /** Non-null when the engine is paused waiting for a player decision. */
    val pendingDecision: PendingDecision? get() = state.pendingDecision

    /** Current turn number. */
    val turnNumber: Int get() = state.turnNumber

    // =========================================================================
    // Core API
    // =========================================================================

    /**
     * Initialize a new game and return the opening state.
     *
     * Replaces any existing game state. Set [GameConfig.skipMulligans] to `true`
     * for training runs where you don't want the mulligan phase.
     */
    fun reset(config: GameConfig, maxSteps: Int? = null): StepResult {
        require(maxSteps == null || maxSteps > 0) { "maxSteps must be positive when supplied" }
        val initializer = GameInitializer(cardRegistry)
        val initResult = initializer.initializeGame(config)
        state = initResult.state
        playerIds = initResult.playerIds
        events = initResult.events
        lastStepEvents = initResult.events
        stepCount = 0
        this.maxSteps = maxSteps
        return buildStepResult(initResult.events)
    }

    /**
     * Submit an action and advance the game state.
     *
     * This processes the action through the engine and auto-resolves trivial
     * decisions (single-target, forced selections, mana autopay). It stops when:
     * - A non-trivial [PendingDecision] needs player input
     * - Priority returns to a player with meaningful choices
     * - The game ends
     *
     * @param action Any [GameAction] — typically a [LegalAction.action] from [legalActions],
     *               or a [SubmitDecision] when responding to a [pendingDecision].
     * @return [StepResult] with the new state, events, rewards, and termination flag.
     * @throws IllegalStateException if the game hasn't been started via [reset].
     */
    fun step(action: GameAction): StepResult {
        check(playerIds.isNotEmpty()) { "Call reset() before step()" }
        check(!isTerminal) { "Cannot step a terminal environment" }
        check(!isTruncated) { "Cannot step a truncated environment" }

        // The Gym boundary is an action-space boundary, not merely a thin wrapper around the
        // processor.  A caller may hold an action from an older observation, or may submit an
        // action for the other player.  The rules engine validates the action's local shape, but
        // it intentionally does not know which candidate list this environment exposed.  Keep
        // stale/non-owner actions fail-closed here before simulation can advance the horizon.
        validateActionMembership(action)
        return simulateAndCommit(action)
    }

    /**
     * Execute a caller-completed action while retaining the action-ID candidate binding.
     *
     * [candidate] is the targetless/payment-template action held by the current Gym registry;
     * [submitted] contains the external controller's explicit choices. The current candidate is
     * re-enumerated before execution, and the same membership normalization used by [step] is
     * applied. The engine remains authoritative for target legality, costs, and all other rules.
     */
    fun stepFromCandidate(candidate: GameAction, submitted: GameAction): StepResult {
        check(playerIds.isNotEmpty()) { "Call reset() before step()" }
        check(!isTerminal) { "Cannot step a terminal environment" }
        check(!isTruncated) { "Cannot step a truncated environment" }
        require(candidate !is SubmitDecision && submitted !is SubmitDecision) {
            "Structured action payloads are only valid for legal game actions"
        }

        val currentActions = legalActions()
        require(currentActions.any { it.action == candidate }) {
            "Action candidate is not in the current legal action set for ${agentToAct}: $candidate"
        }
        require(isCurrentActionCandidate(candidate, submitted)) {
            "Structured action does not belong to the selected current legal candidate: $submitted"
        }

        return simulateAndCommit(submitted)
    }

    private fun validateActionMembership(action: GameAction) {
        if (action !is SubmitDecision) {
            val currentActions = legalActions()
            val isCurrentAction = currentActions.any { candidate ->
                isCurrentActionCandidate(candidate.action, action)
            }
            require(isCurrentAction) {
                "Action is not in the current legal action set for ${agentToAct}: $action"
            }
        } else {
            val pending = state.pendingDecision
            require(pending != null) { "No pending decision to respond to" }
            require(action.playerId == pending.playerId) {
                "Decision belongs to ${pending.playerId}, not ${action.playerId}"
            }
        }
    }

    private fun simulateAndCommit(action: GameAction): StepResult {

        val simResult = if (action is SubmitDecision) {
            simulator.simulateDecision(state, action.response)
        } else {
            simulator.simulate(state, action)
        }

        // Do not install an illegal simulation result as if it were a successful step.  Besides
        // hiding the error, doing that would incorrectly consume one unit of the Gym horizon.
        if (simResult is SimulationResult.Illegal) {
            throw IllegalArgumentException(simResult.reason)
        }

        state = simResult.state
        events = events + simResult.events
        lastStepEvents = simResult.events
        stepCount++

        return buildStepResult(simResult.events)
    }

    /**
     * Enumerate all legal actions for the player who needs to act.
     *
     * Uses [EnumerationMode.ACTIONS_ONLY] to skip expensive auto-tap preview
     * computation (not needed for AI/MCTS).
     *
     * If a [PendingDecision] is active, returns an empty list — use
     * [decisionOptions] instead to get the available decision responses,
     * or construct a [SubmitDecision] manually.
     *
     * @return Legal actions for [agentToAct], or empty if no one has priority.
     */
    fun legalActions(): List<LegalAction> {
        val playerId = agentToAct ?: return emptyList()
        if (state.pendingDecision != null) return emptyList()
        if (state.gameOver || isTruncated) return emptyList()
        // A multi-requirement spell can have a legal first slot while a later mandatory slot has
        // no candidate. The engine keeps that metadata for client diagnostics, but it is not an
        // executable Gym action: selecting it would only produce "No valid targets available" at
        // simulation time. Keep the action space executable so random agents cannot manufacture
        // an Illegal result from a supposedly legal observation.
        return enumerator.enumerate(state, playerId, EnumerationMode.ACTIONS_ONLY)
            .filterNot { it.hasUnfillableTargetRequirement }
    }

    /**
     * Match a submitted action against the current enumerator templates without treating
     * caller-supplied choice assignments as stale. Enumerators deliberately emit templates for
     * casts, abilities, combat, cycling, turn-face-up, Crew, Saddle, and payment-only special
     * actions; the client or AI fills the choice fields before submitting the action. Every
     * branch below retains the candidate's actor and runtime/source identity fields. The rules
     * engine remains authoritative for validating the submitted choices.
     */
    internal fun isCurrentActionCandidate(candidate: GameAction, submitted: GameAction): Boolean =
        when {
            candidate == submitted -> true
            candidate is DeclareAttackers && submitted is DeclareAttackers ->
                candidate.playerId == submitted.playerId
            candidate is DeclareBlockers && submitted is DeclareBlockers ->
                candidate.playerId == submitted.playerId
            candidate is OrderBlockers && submitted is OrderBlockers ->
                candidate.playerId == submitted.playerId &&
                    candidate.attackerId == submitted.attackerId
            candidate is CastSpell && submitted is CastSpell ->
                normalizeCastSpellForMembership(candidate) == normalizeCastSpellForMembership(submitted)
            candidate is ActivateAbility && submitted is ActivateAbility ->
                normalizeActivateAbilityForMembership(candidate) == normalizeActivateAbilityForMembership(submitted)
            candidate is CycleCard && submitted is CycleCard ->
                candidate.playerId == submitted.playerId && candidate.cardId == submitted.cardId
            candidate is PlotCard && submitted is PlotCard ->
                candidate.playerId == submitted.playerId && candidate.cardId == submitted.cardId
            candidate is ForetellCard && submitted is ForetellCard ->
                candidate.playerId == submitted.playerId && candidate.cardId == submitted.cardId
            candidate is SuspendCardFromHand && submitted is SuspendCardFromHand ->
                candidate.playerId == submitted.playerId && candidate.cardId == submitted.cardId
            candidate is TypecycleCard && submitted is TypecycleCard ->
                candidate.playerId == submitted.playerId && candidate.cardId == submitted.cardId
            candidate is CrewVehicle && submitted is CrewVehicle ->
                candidate.playerId == submitted.playerId &&
                    candidate.vehicleId == submitted.vehicleId &&
                    candidate.crewAbilityKey == submitted.crewAbilityKey
            candidate is SaddleMount && submitted is SaddleMount ->
                candidate.playerId == submitted.playerId && candidate.mountId == submitted.mountId
            candidate is TurnFaceUp && submitted is TurnFaceUp ->
                candidate.playerId == submitted.playerId &&
                    candidate.sourceId == submitted.sourceId &&
                    candidate.procedureIndex == submitted.procedureIndex
            candidate is UnlockRoomDoor && submitted is UnlockRoomDoor ->
                candidate.playerId == submitted.playerId &&
                    candidate.roomId == submitted.roomId &&
                    candidate.faceId == submitted.faceId
            else -> false
        }

    /**
     * Normalize caller-filled target/payment payloads while retaining the cast variant identity.
     * LegalAction metadata is deliberately richer than the targetless engine action template: an
     * AI may fill convoke, improvise, additional-cost, mode, X, or target choices before submit.
     * The engine remains authoritative for validating those choices; Gym membership only checks
     * that the underlying cast candidate is still current.
     */
    private fun normalizeCastSpellForMembership(action: CastSpell): CastSpell = action.copy(
        targets = emptyList(),
        damageDistribution = null,
        modeTargetsOrdered = emptyList(),
        modeDamageDistribution = emptyMap(),
        paymentStrategy = PaymentStrategy.AutoPay,
        additionalCostPayment = null,
        alternativePayment = null,
        chosenModes = emptyList(),
        xValue = null,
    )

    /** Normalize caller-filled target/payment/choice payloads for an activated-ability template. */
    private fun normalizeActivateAbilityForMembership(action: ActivateAbility): ActivateAbility = action.copy(
        targets = emptyList(),
        damageDistribution = null,
        costPayment = null,
        manaColorChoice = null,
        xValue = null,
        repeatCount = 1,
        paymentStrategy = PaymentStrategy.AutoPay,
        // Equip payment is a candidate identity: NORMAL and FREE_FIRST_EQUIP are distinct
        // server-offered actions. Other alternative-payment assignments are caller-filled
        // resource choices and remain normalized away as before.
        alternativePayment = action.alternativePayment
            ?.takeIf { it.equipPayment != null }
            ?.copy(
                delvedCards = emptyList(),
                convokedCreatures = emptyMap(),
                harmonizeCreature = null,
                tapForGenericPermanents = emptySet(),
            ),
    )

    /**
     * Evaluate the current board state from a player's perspective.
     *
     * Uses the engine's built-in composite evaluator (life, board presence,
     * card advantage, threats, tempo). Returns [Double.MAX_VALUE]/2 for wins,
     * -[Double.MAX_VALUE]/2 for losses.
     *
     * For terminal states, prefer checking [winnerId] directly.
     *
     * @param playerId The player whose perspective to evaluate from.
     * @return A score where higher is better for [playerId].
     */
    fun evaluate(playerId: EntityId): Double {
        return evaluator.evaluate(state, state.projectedState, playerId)
    }

    /**
     * Create a new environment forked from the current state.
     *
     * Since [GameState] is immutable, this is essentially free — the new
     * environment references the same state object. Mutations via [step]
     * on either environment are independent.
     *
     * This is the primary mechanism for MCTS tree expansion.
     */
    fun fork(): GameEnvironment {
        val forked = GameEnvironment(cardRegistry, processor, enumerator, evaluator, simulator)
        forked.state = state
        forked.playerIds = playerIds
        forked.events = emptyList() // forked environments start with clean event history
        forked.lastStepEvents = emptyList()
        forked.stepCount = stepCount
        forked.maxSteps = maxSteps
        return forked
    }

    /**
     * Restore this environment to a previously-captured [state] and roster.
     *
     * Intended for MCTS rollouts and the snapshot/restore flow in
     * `MultiEnvService`. Because [GameState] is fully immutable, this is an
     * O(1) reference swap — no deep copy.
     *
     * @param state The game state to install.
     * @param playerIds The player turn order associated with [state].
     * @param stepCount Optional step-counter to restore; defaults to 0.
     */
    fun restore(
        state: GameState,
        playerIds: List<EntityId>,
        stepCount: Int = 0,
        maxSteps: Int? = this.maxSteps
    ) {
        require(stepCount >= 0) { "stepCount must not be negative" }
        require(maxSteps == null || maxSteps > 0) { "maxSteps must be positive when supplied" }
        this.state = state
        this.playerIds = playerIds
        this.events = emptyList()
        this.lastStepEvents = emptyList()
        this.stepCount = stepCount
        this.maxSteps = maxSteps
    }

    /**
     * Get the terminal reward for each player.
     *
     * - Win = +1.0
     * - Loss = -1.0
     * - Draw / ongoing = 0.0
     */
    fun terminalRewards(): Map<EntityId, Double> {
        if (!isTerminal) return playerIds.associateWith { 0.0 }
        return playerIds.associateWith { pid ->
            when (winnerId) {
                pid -> 1.0
                null -> 0.0 // draw
                else -> -1.0
            }
        }
    }

    // =========================================================================
    // Convenience
    // =========================================================================

    /**
     * Play a complete game with the given action selectors.
     *
     * Each selector maps a player ID to a function that picks an action from the
     * legal action list, or picks a decision response for pending decisions.
     * For players not in [agents], the built-in heuristic AI is used.
     *
     * @param config Game configuration.
     * @param agents Per-player action selectors.
     * @param maxSteps Safety limit to prevent infinite games (default 2000).
     * @return Final [StepResult] with terminal rewards.
     */
    fun playGame(
        config: GameConfig,
        agents: Map<EntityId, ActionSelector> = emptyMap(),
        maxSteps: Int = 2000
    ): StepResult {
        reset(config, maxSteps = maxSteps)

        while (!isTerminal && !isTruncated) {
            val player = agentToAct ?: break
            val selector = agents[player]

            val action = if (pendingDecision != null) {
                val decision = pendingDecision!!
                // A selector signals "I don't handle decisions" by throwing
                // UnsupportedOperationException (see RandomActionSelector); fall back to the
                // built-in responder in that case, as its contract promises.
                val response = try {
                    selector?.respondToDecision(state, decision)
                } catch (e: UnsupportedOperationException) {
                    null
                } ?: defaultDecisionResponder.respond(state, decision, player)
                SubmitDecision(player, response)
            } else {
                val actions = legalActions()
                if (actions.isEmpty()) break
                selector?.selectAction(state, actions) ?: actions.first { it.affordable }.action
            }

            step(action)
        }

        return buildStepResult(lastStepEvents)
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private val defaultDecisionResponder: DecisionResponder by lazy {
        DecisionResponder(simulator, evaluator)
    }

    private fun buildStepResult(stepEvents: List<GameEvent>): StepResult {
        return StepResult(
            state = state,
            events = stepEvents,
            reward = terminalRewards(),
            terminated = isTerminal,
            truncated = isTruncated,
            agentToAct = agentToAct,
            pendingDecision = pendingDecision,
            info = StepInfo(
                turnNumber = turnNumber,
                stepCount = stepCount,
                winnerId = winnerId,
                phase = state.phase,
                step = state.step
            )
        )
    }

    companion object {
        /**
         * Create a new [GameEnvironment] with the default evaluator.
         *
         * @param cardRegistry Registry containing all card definitions to be used.
         * @param evaluator Board evaluator for [evaluate] calls. Defaults to the
         *                  engine's composite evaluator (life, board, cards, threats, tempo).
         */
        fun create(
            cardRegistry: CardRegistry,
            evaluator: BoardEvaluator = defaultEvaluator()
        ): GameEnvironment {
            val services = EngineServices(cardRegistry)
            val processor = ActionProcessor(services, computeUndo = false)
            val enumerator = LegalActionEnumerator.create(cardRegistry)
            val simulator = GameSimulator(cardRegistry, processor, enumerator)
            return GameEnvironment(cardRegistry, processor, enumerator, evaluator, simulator)
        }

        /**
         * Default composite evaluator matching [com.wingedsheep.ai.engine.AIPlayer.defaultEvaluator].
         */
        fun defaultEvaluator(): BoardEvaluator = CompositeBoardEvaluator(
            listOf(
                1.0 to LifeDifferential,
                1.5 to BoardPresence,
                1.0 to CardAdvantage,
                1.2 to ThreatAssessment,
                0.6 to Tempo
            )
        )
    }
}

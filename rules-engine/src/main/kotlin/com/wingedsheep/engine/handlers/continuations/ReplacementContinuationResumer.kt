package com.wingedsheep.engine.handlers.continuations

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.replacement.*
import com.wingedsheep.engine.state.GameState

/**
 * Continuation resumers for the replacement effect system.
 *
 * Handles:
 * - [ReplacementChoiceContinuation] — player chose between competing replacements
 *   (decision-driven resumer)
 * - [ReplacementResolveContinuation] — after a replacement chain completes,
 *   resume the original context (auto-resumer)
 */
class ReplacementContinuationResumer(
    private val processor: ReplacementEffectProcessor,
    private val services: EngineServices
) : ContinuationResumerModule, AutoResumerModule {

    private val castSpellHandler by lazy {
        com.wingedsheep.engine.handlers.actions.spell.CastSpellHandler.create(services)
    }
    private val activateAbilityHandler by lazy {
        com.wingedsheep.engine.handlers.actions.ability.ActivateAbilityHandler.create(services)
    }
    private val moveCollectionExecutor by lazy {
        com.wingedsheep.engine.handlers.effects.library.MoveCollectionExecutor(
            cardRegistry = services.cardRegistry,
            targetFinder = com.wingedsheep.engine.handlers.TargetFinder(),
        )
    }
    private val costPaymentContinuationResumer by lazy {
        CostPaymentContinuationResumer(services)
    }

    override fun resumers(): List<ContinuationResumer<*>> = listOf(
        resumer(ReplacementChoiceContinuation::class, ::resumeReplacementChoice),
        resumer(OptionalReplacementContinuation::class, ::resumeOptionalReplacement)
    )

    override fun autoResumers(): List<AutoResumer<*>> = listOf(
        autoResumer(ReplacementResolveContinuation::class) { state, continuation, events, checkForMore ->
            resumeReplacementResolve(state, continuation, events, checkForMore)
        },
        autoResumer(ZoneChangeContinuation::class) { state, continuation, events, checkForMore ->
            resumeZoneChange(state, continuation, events, checkForMore)
        }
    )

    private fun resumeOptionalReplacement(
        state: GameState,
        continuation: OptionalReplacementContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is YesNoResponse) {
            return ExecutionResult.error(state, "Expected yes/no response for optional replacement")
        }

        val stateWithRemainder = continuation.pendingEvent.remainderContinuation(state)
            ?.let { state.pushContinuation(it) }
            ?: state

        val result = if (response.choice) {
            processor.applySingle(
                state = stateWithRemainder,
                gathered = continuation.gathered,
                event = continuation.pendingEvent,
                alreadyApplied = continuation.alreadyApplied,
            )
        } else {
            processor.processAfterOptionalDecline(
                state = stateWithRemainder,
                event = continuation.pendingEvent,
                gathered = continuation.gathered,
                context = continuation.context,
                alreadyApplied = continuation.alreadyApplied,
            )
        }

        return handleOptionalProcessorResult(
            result = result,
            event = continuation.pendingEvent,
            state = stateWithRemainder,
            context = continuation.context,
            checkForMore = checkForMore,
        )
    }

    /**
     * The optional-decline path needs the unchanged state when the processor
     * returns Pass. Keep this small state-aware wrapper separate from the
     * outcome branch above so the normal replacement-choice code stays intact.
     */
    private fun handleOptionalProcessorResult(
        result: ProcessorResult,
        event: PendingGameEvent,
        state: GameState,
        context: EffectContext?,
        checkForMore: CheckForMore
    ): ExecutionResult {
        return when (result) {
            is ProcessorResult.Paused -> ExecutionResult.paused(result.state, result.decision)
            is ProcessorResult.Pass -> {
                val performFrame = event.performContinuation(state)
                val stateToResume = performFrame?.let { state.pushContinuation(it) } ?: state
                checkForMore(stateToResume, emptyList())
            }
            is ProcessorResult.Resolved -> handleResolvedOptionalResult(result, context, checkForMore)
        }
    }

    private fun handleResolvedOptionalResult(
        result: ProcessorResult.Resolved,
        context: EffectContext?,
        checkForMore: CheckForMore
    ): ExecutionResult {
        val stateAfterLifecycle = if (result.identity is ReplacementEffectIdentity.FloatingIdentity) {
            processor.consumeFloatingEffect(result.state, result.identity.floatingId)
        } else result.state

        return when (val outcome = result.outcome) {
            is ReplacementOutcome.Replaced -> {
                val execCtx = result.executionContext ?: context
                handleReplacedOutcome(stateAfterLifecycle, outcome, execCtx, checkForMore)
            }
            is ReplacementOutcome.Consumed -> checkForMore(stateAfterLifecycle, emptyList())
            is ReplacementOutcome.Modified -> {
                val performFrame = outcome.modifiedEvent.performContinuation(stateAfterLifecycle)
                val cleared = stateAfterLifecycle.copy(activeReplacementChain = null)
                val stateToResume = performFrame?.let { cleared.pushContinuation(it) } ?: cleared
                checkForMore(stateToResume, emptyList())
            }
        }
    }

    private fun resumeZoneChange(
        state: GameState,
        continuation: ZoneChangeContinuation,
        events: List<GameEvent>,
        checkForMore: CheckForMore
    ): ExecutionResult {
        val pending = continuation.pendingEvent
        return when (val completion = pending.completion) {
            PendingGameEvent.PlainZoneChangeCompletion -> {
                val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
                    .performPendingZoneChange(state, pending)
                checkForMore(transition.state, events + transition.events)
            }
            is PendingGameEvent.MoveEffectZoneChangeCompletion -> {
                val resolvedContext = completion.context.copy(
                    resolvedZoneChange = com.wingedsheep.engine.handlers.ResolvedZoneChange(
                        entityId = pending.entityId,
                        fromZoneKey = pending.fromZoneKey,
                        destinationZone = pending.destinationZone,
                        entryOptions = pending.entryOptions.copy(
                            skipZoneChangeReplacementEffects = true,
                            precomputedRedirect = pending.redirectResult,
                        ),
                    )
                )
                val result = services.effectExecutorRegistry.execute(
                    state,
                    completion.effect,
                    resolvedContext,
                )
                if (result.isPaused) {
                    return ExecutionResult(
                        result.state,
                        events + result.events,
                        result.error,
                        result.pendingDecision,
                        result.triggersAlreadyProcessed,
                    )
                }
                checkForMore(result.state, events + result.events)
            }
            is PendingGameEvent.ActivateAbilityZoneChangeCompletion -> {
                val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
                    .performPendingZoneChange(state, pending)
                val result = activateAbilityHandler.execute(transition.state, completion.action)
                if (result.isPaused) {
                    return result.copy(events = events + transition.events + result.events)
                }
                checkForMore(result.state, events + transition.events + result.events)
            }
            is PendingGameEvent.CastSpellZoneChangeCompletion -> {
                val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
                    .performPendingZoneChange(state, pending)
                val result = castSpellHandler.execute(transition.state, completion.action)
                if (result.isPaused) {
                    return result.copy(events = events + transition.events + result.events)
                }
                checkForMore(result.state, events + transition.events + result.events)
            }
            PendingGameEvent.LibraryRevealZoneChangeCompletion -> {
                val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
                    .performPendingZoneChange(state, pending)
                val revealed = com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
                    .markRevealed(transition.state, listOf(pending.entityId), transition.state.turnOrder.toSet())
                checkForMore(revealed, events + transition.events)
            }
            PendingGameEvent.StackSpellToLibraryZoneChangeCompletion -> {
                val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
                    .performPendingZoneChange(state, pending)
                val spellName = transition.state.getEntity(pending.entityId)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.name
                    ?: "Unknown"
                val revealed = com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
                    .markRevealed(transition.state, listOf(pending.entityId), transition.state.turnOrder.toSet())
                checkForMore(
                    revealed,
                    events + SpellCounteredEvent(pending.entityId, spellName) + transition.events,
                )
            }
            is PendingGameEvent.StackSpellDispositionZoneChangeCompletion -> {
                val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
                    .performPendingZoneChange(state, pending)
                val disposition = if (completion.fizzled) {
                    SpellFizzledEvent(
                        spellEntityId = pending.entityId,
                        cardName = completion.cardName,
                        reason = completion.reason ?: "All targets are invalid",
                    )
                } else {
                    SpellCounteredEvent(
                        spellEntityId = pending.entityId,
                        cardName = completion.cardName,
                    )
                }
                checkForMore(transition.state, events + disposition + transition.events)
            }
            is PendingGameEvent.ResumePendingDecisionZoneChangeCompletion -> {
                val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
                    .performPendingZoneChange(state, pending)
                ExecutionResult.paused(
                    transition.state,
                    completion.pendingDecision,
                    events + transition.events,
                )
            }
            is PendingGameEvent.CostPaymentZoneChangeCompletion -> {
                val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
                    .performPendingZoneChange(state, pending)
                costPaymentContinuationResumer.resumeAfterCommanderZoneChange(
                    state = transition.state,
                    completion = completion,
                    priorEvents = events + transition.events,
                    checkForMore = checkForMore,
                )
            }
            is PendingGameEvent.MoveCollectionZoneChangeCompletion -> {
                val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
                    .performPendingZoneChange(state, pending)
                val result = moveCollectionExecutor.moveCardsToZone(
                    state = transition.state,
                    context = completion.context,
                    cards = completion.cards,
                    destination = completion.destination,
                    destPlayerId = completion.destPlayerId,
                    revealed = completion.revealed,
                    moveType = completion.moveType,
                    faceDown = completion.faceDown,
                    noRegenerate = completion.noRegenerate,
                    storeMovedAs = completion.storeMovedAs,
                    underOwnersControl = completion.underOwnersControl,
                    revealToSelf = completion.revealToSelf,
                    linkToSource = completion.linkToSource,
                    unlinkFromSource = completion.unlinkFromSource,
                    addCounterType = completion.addCounterType,
                    markEnteredViaSourceAbility = completion.markEnteredViaSourceAbility,
                    startCardIndex = completion.nextCardIndex,
                    completedCardIds = completion.completedCardIds,
                    completedLibraryOwnerIds = completion.completedLibraryOwnerIds,
                    clearMovedLibraryReveals = completion.clearMovedLibraryReveals,
                    orderCompletion = completion.orderCompletion,
                )
                if (result.isPaused) {
                    return result.toExecutionResult().copy(
                        events = events + transition.events + result.events,
                    )
                }
                val completedPhysicalMove = if (completion.clearMovedLibraryReveals) {
                    result.copy(
                        state = com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
                            .clearReveals(result.state, completion.cards),
                    )
                } else {
                    result
                }
                val completed = moveCollectionExecutor.applyPostMoveMetadata(
                    result = completedPhysicalMove,
                    context = completion.context,
                    cards = completion.cards,
                    destination = completion.destination,
                    linkToSource = completion.linkToSource,
                    unlinkFromSource = completion.unlinkFromSource,
                    addCounterType = completion.addCounterType,
                    markEnteredViaSourceAbility = completion.markEnteredViaSourceAbility,
                )
                val withOrderEvent = completion.orderCompletion?.let { order ->
                    completed.copy(
                        events = completed.events + LibraryReorderedEvent(
                            playerId = order.playerId,
                            cardCount = order.cardCount,
                            source = order.source,
                        ),
                    )
                } ?: completed
                val stateWithCollections = exposeCollectionsToNextFrame(
                    withOrderEvent.state,
                    withOrderEvent.updatedCollections,
                    withOrderEvent.updatedStoredNumbers,
                    withOrderEvent.updatedChosenValues,
                )
                checkForMore(
                    stateWithCollections,
                    events + transition.events + withOrderEvent.events,
                )
            }
        }
    }

    /**
     * Resume after the player chose one of multiple competing replacement
     * effects (CR 616.1e).
     *
     * Delegates outcome computation to [ReplacementEffectProcessor.applySingle],
     * then manages lifecycle (NextUse shield consumption) before resuming
     * the original context.
     */
    private fun resumeReplacementChoice(
        state: GameState,
        continuation: ReplacementChoiceContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is OptionChosenResponse) {
            return ExecutionResult.error(state, "Expected option choice response for replacement")
        }

        val chosenIndex = response.optionIndex
        if (chosenIndex < 0 || chosenIndex >= continuation.options.size + continuation.declineOptional.size) {
            return ExecutionResult.error(state, "Invalid replacement choice index: $chosenIndex")
        }

        // A mixed-priority-group choice can explicitly decline an optional
        // replacement. The mandatory candidates remain applicable and are
        // reconsidered by the normal processor pass.
        if (chosenIndex >= continuation.options.size) {
            val declineIndex = chosenIndex - continuation.options.size
            val declined = continuation.declineOptional.getOrNull(declineIndex)
                ?: return ExecutionResult.error(state, "Invalid optional replacement decline index: $declineIndex")
            val stateWithRemaining = continuation.pendingEvent.remainderContinuation(state)
                ?.let { state.pushContinuation(it) }
                ?: state
            val result = processor.processAfterOptionalDecline(
                state = stateWithRemaining,
                event = continuation.pendingEvent,
                gathered = declined,
                context = continuation.context,
                alreadyApplied = continuation.alreadyApplied,
            )
            return handleOptionalProcessorResult(
                result = result,
                event = continuation.pendingEvent,
                state = stateWithRemaining,
                context = continuation.context,
                checkForMore = checkForMore,
            )
        }

        val chosen = continuation.options[chosenIndex]

        // The processor's applySingle() builds the execution context from floating-shield
        // data when applicable, returning it in ProcessorResult.Resolved.executionContext.
        // Pass continuation.context for condition evaluation during recursive processing.
        val context = continuation.context

        // CR 616 chooses which replacement is applied next; it does not answer a separate
        // optional replacement choice. In particular, a stolen Commander can be selected by
        // its controller as the next replacement, but CR 903.9b still asks the Commander owner.
        // Keep the event unchanged and hand the chosen optional candidate to its own prompt
        // before applySingle() is allowed to modify the event.
        if (continuation.pendingEvent.isOptionalReplacement(chosen, state)) {
            val promptResult = continuation.pendingEvent.createOptionalPrompt(
                decisionId = java.util.UUID.randomUUID().toString(),
                gathered = chosen,
                state = state,
                context = context,
                alreadyApplied = continuation.alreadyApplied,
            )
            if (promptResult != null) {
                val stateWithDecision = state
                    .withPendingDecision(promptResult.decision)
                    .pushContinuation(promptResult.continuation)
                return ExecutionResult.paused(stateWithDecision, promptResult.decision)
            }
        }

        // Push domain-specific remainder continuation (e.g. remaining draws
        // in the draw loop) before the replacement resolves, so it sits below
        // any ReplacementResolveContinuation in the stack and can resume after
        // the replacement effect completes.
        val stateWithRemaining = continuation.pendingEvent.remainderContinuation(state)
            ?.let { state.pushContinuation(it) }
            ?: state

        // Compute the outcome.
        val result = processor.applySingle(
            state = stateWithRemaining,
            gathered = chosen,
            event = continuation.pendingEvent,
            alreadyApplied = continuation.alreadyApplied
        )

        return when (result) {
            is ProcessorResult.Resolved -> {
                // Consume NextUse floating-effect shield if applicable (caller's lifecycle responsibility).
                val stateAfterLifecycle = if (result.identity is ReplacementEffectIdentity.FloatingIdentity) {
                    processor.consumeFloatingEffect(result.state, result.identity.floatingId)
                } else {
                    result.state
                }
                when (val outcome = result.outcome) {
                    is ReplacementOutcome.Replaced -> {
                        val execCtx = result.executionContext ?: context
                        handleReplacedOutcome(stateAfterLifecycle, outcome, execCtx, checkForMore)
                    }
                    is ReplacementOutcome.Consumed -> checkForMore(stateAfterLifecycle, emptyList())
                    is ReplacementOutcome.Modified -> {
                        // Unlike Replaced/Consumed — where the replacement *is* what happens —
                        // a Modified outcome leaves the (modified) event still to be performed.
                        // The call site that would have performed it returned when this paused,
                        // so the event supplies a frame that performs it on resume. Without
                        // this the whole instruction is silently dropped.
                        val performFrame = outcome.modifiedEvent.performContinuation(stateAfterLifecycle)
                        // CR 614.5 is per-event: this event is done being replaced, so the
                        // chain must not leak into the events performing it carries.
                        val cleared = stateAfterLifecycle.copy(activeReplacementChain = null)
                        val stateToResume = performFrame?.let { cleared.pushContinuation(it) } ?: cleared
                        checkForMore(stateToResume, emptyList())
                    }
                }
            }
            is ProcessorResult.Paused -> {
                ExecutionResult.paused(result.state, result.decision)
            }
            is ProcessorResult.Pass -> {
                // Shouldn't happen — the chosen effect was matched
                error("resumeReplacementChoice returned a Pass result")
            }
        }
    }

    /**
     * Auto-resume after a replacement chain has fully resolved. Pops the
     * [ReplacementResolveContinuation] and calls checkForMore so the original
     * execution context resumes.
     */
    private fun resumeReplacementResolve(
        state: GameState,
        continuation: ReplacementResolveContinuation,
        events: List<GameEvent>,
        checkForMore: CheckForMore
    ): ExecutionResult {
        // The new effect has completed executing. Resume the original context
        // by calling checkForMore.
        return checkForMore(state, events)
    }

    /**
     * Execute the replacement effect for a [ReplacementOutcome.Replaced],
     * then push a [ReplacementResolveContinuation] so the original context
     * resumes after the new effect completes.
     */
    private fun handleReplacedOutcome(
        state: GameState,
        outcome: ReplacementOutcome.Replaced,
        context: EffectContext?,
        checkForMore: CheckForMore
    ): ExecutionResult {
        val resumeContinuation = ReplacementResolveContinuation(
            decisionId = "pending"
        )

        val stateWithResumeFrame = state.pushContinuation(resumeContinuation)

        // Execute the new effect
        if (context != null) {
            // The processor stamped activeReplacementChain onto stateWithResumeFrame
            // with all effects applied in this chain, so nested effect execution
            // won't re-trigger them. Clear the chain after execution so the
            // ReplacementResolveContinuation and any remaining draws resume fresh.
            val effectResult = services.effectExecutorRegistry.execute(stateWithResumeFrame, outcome.newEffect, context)
            if (effectResult.isPaused) {
                // Clear chain on pause so subsequent execution is unaffected.
                val clearedState = effectResult.state.copy(activeReplacementChain = null)
                return ExecutionResult(clearedState, effectResult.events, effectResult.error, effectResult.pendingDecision, effectResult.triggersAlreadyProcessed)
            }
            val clearedState = effectResult.state.copy(activeReplacementChain = null)
            return checkForMore(clearedState, effectResult.events)
        }

        return checkForMore(stateWithResumeFrame, emptyList())
    }
}

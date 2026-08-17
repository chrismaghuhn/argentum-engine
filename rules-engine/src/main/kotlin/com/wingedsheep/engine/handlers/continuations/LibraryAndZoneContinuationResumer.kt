package com.wingedsheep.engine.handlers.continuations

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.handlers.actions.spell.CastSpellHandler
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.ZoneMovementUtils
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.handlers.effects.ZoneEntryOptions
import com.wingedsheep.engine.handlers.effects.library.CascadeExecutor
import com.wingedsheep.engine.handlers.effects.library.ChooseOnePerCategoryExecutor
import com.wingedsheep.engine.handlers.effects.library.CastFromCollectionWithoutPayingCostExecutor
import com.wingedsheep.engine.handlers.effects.library.ExileFromTopRepeatingExecutor
import com.wingedsheep.engine.handlers.effects.library.AuraHostLegality
import com.wingedsheep.engine.handlers.effects.library.MoveCollectionExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayWithoutPayingCostComponent
import com.wingedsheep.engine.state.permissions.MayPlayPermission
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CastAnyNumberFromCollectionWithoutPayingCostEffect
import com.wingedsheep.sdk.scripting.effects.CastFromCollectionWithoutPayingCostEffect
import com.wingedsheep.sdk.scripting.effects.SearchDestination
import com.wingedsheep.sdk.scripting.effects.SelectionRestriction
import com.wingedsheep.sdk.scripting.effects.ZonePlacement

class LibraryAndZoneContinuationResumer(
    private val services: com.wingedsheep.engine.core.EngineServices
) : ContinuationResumerModule, AutoResumerModule {

    private val castSpellHandler: CastSpellHandler by lazy { CastSpellHandler.create(services) }
    private val targetFinder = TargetFinder()
    private val auraHostLegality = AuraHostLegality(services.cardRegistry, targetFinder)
    private val moveCollectionExecutor by lazy {
        MoveCollectionExecutor(cardRegistry = services.cardRegistry, targetFinder = targetFinder)
    }
    private val cascadeExecutor by lazy {
        CascadeExecutor(cardRegistry = services.cardRegistry)
    }
    private val effectRunner: EffectContinuationRunner by lazy {
        EffectContinuationRunner(services.effectExecutorRegistry)
    }

    override fun resumers(): List<ContinuationResumer<*>> = listOf(
        resumer(ReturnFromGraveyardContinuation::class, ::resumeReturnFromGraveyard),
        resumer(MoveCollectionOrderContinuation::class, ::resumeMoveCollectionOrder),
        resumer(PutOnBottomOfLibraryContinuation::class, ::resumePutOnBottomOfLibrary),
        resumer(PutFromHandContinuation::class, ::resumePutFromHand),
        resumer(SelectFromCollectionContinuation::class, ::resumeSelectFromCollection),
        resumer(ChooseOnePerCategoryContinuation::class, ::resumeChooseOnePerCategory),
        resumer(ChoosePileContinuation::class, ::resumeChoosePile),
        resumer(SelectTargetPipelineContinuation::class, ::resumeSelectTargetPipeline),
        resumer(MoveCollectionAuraTargetContinuation::class, ::resumeMoveCollectionAuraTarget),
        resumer(PutOntoBattlefieldAttachedToChosenContinuation::class, ::resumePutOntoBattlefieldAttachedToChosen),
        resumer(PutOnTopOrBottomContinuation::class, ::resumePutOnTopOrBottom),
        resumer(CascadeMayCastContinuation::class, ::resumeCascadeMayCast),
        resumer(DiscoverMayCastContinuation::class, ::resumeDiscoverMayCast),
        resumer(CastFromCollectionTargetsContinuation::class, ::resumeCastFromCollectionTargets),
        resumer(CastAnyNumberFromCollectionContinuation::class, ::resumeCastAnyNumberFromCollection)
    )

    override fun autoResumers(): List<AutoResumer<*>> = listOf(
        autoResumer(CascadeAfterBottomContinuation::class) { state, continuation, events, checkForMore ->
            continueCascadeAfterBottom(state, continuation, events, checkForMore)
        },
        autoResumer(DiscoverAfterBottomContinuation::class) { state, continuation, events, checkForMore ->
            continueDiscoverAfterBottom(state, continuation, events, checkForMore)
        },
        autoResumer(DiscoverNoHitBottomContinuation::class) { state, continuation, events, checkForMore ->
            continueDiscoverNoHitBottom(state, continuation, events, checkForMore)
        },
        autoResumer(ExileFromTopRepeatingContinuation::class) { state, continuation, events, checkForMore ->
            val result = ExileFromTopRepeatingExecutor().resumeAfterMatch(state, continuation)
            if (result.isPaused) {
                ExecutionResult.paused(
                    result.state,
                    result.pendingDecision!!,
                    events + result.events,
                )
            } else {
                checkForMore(result.state, events + result.events)
            }
        }
    )

    fun resumeReturnFromGraveyard(
        state: GameState,
        continuation: ReturnFromGraveyardContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is CardsSelectedResponse) {
            return ExecutionResult.error(state, "Expected card selection response for graveyard search")
        }

        val playerId = continuation.playerId
        val selectedCards = response.selectedCards

        // Empty selection — no card returned
        if (selectedCards.isEmpty()) {
            return checkForMore(state, emptyList())
        }

        val cardId = selectedCards.first()
        val graveyardZone = ZoneKey(playerId, Zone.GRAVEYARD)

        // Validate card is still in graveyard
        if (cardId !in state.getZone(graveyardZone)) {
            return checkForMore(state, emptyList())
        }

        val destZone = when (continuation.destination) {
            SearchDestination.HAND -> Zone.HAND
            SearchDestination.BATTLEFIELD -> Zone.BATTLEFIELD
            else -> return ExecutionResult.error(state, "Unsupported destination: ${continuation.destination}")
        }

        // Hand entry is a CR 903.9b replacement boundary. Battlefield entry remains a plain
        // transition because Commander replacement does not apply to it.
        val transitionResult = if (destZone == Zone.HAND) {
            ZoneTransitionService.moveToZoneWithReplacements(
                state = state,
                entityId = cardId,
                destinationZone = destZone,
                options = ZoneEntryOptions(controllerId = playerId),
                fromZoneKey = ZoneKey(playerId, Zone.GRAVEYARD),
                context = EffectContext(sourceId = continuation.sourceId, controllerId = playerId),
                completion = com.wingedsheep.engine.replacement.PendingGameEvent
                    .PlainZoneChangeCompletion,
            ).let { result ->
                if (result.isPaused) return result.toExecutionResult()
                com.wingedsheep.engine.handlers.effects.ZoneTransitionResult(
                    state = result.state,
                    events = result.events,
                    actualDestination = result.state.zones.entries
                        .firstOrNull { (_, cards) -> cardId in cards }?.key?.zoneType,
                )
            }
        } else {
            ZoneTransitionService.moveToZone(
                state, cardId, destZone,
                ZoneEntryOptions(controllerId = playerId),
                ZoneKey(playerId, Zone.GRAVEYARD)
            )
        }

        return checkForMore(transitionResult.state, transitionResult.events)
    }

    /**
     * Resume after player ordered cards for a MoveCollection with ControllerChooses order.
     *
     * The response contains the card IDs in the new order (first = new top of library).
     * Re-enter MoveCollectionExecutor so every actual cross-zone move uses the canonical
     * ZoneTransitionService/replacement pipeline, including Commander 903.9b.
     */
    fun resumeMoveCollectionOrder(
        state: GameState,
        continuation: MoveCollectionOrderContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is OrderedResponse) {
            return ExecutionResult.error(state, "Expected ordered response for MoveCollection order")
        }

        val orderedCards = response.orderedObjects
        if (orderedCards.toSet() != continuation.cards.toSet() || orderedCards.size != continuation.cards.size) {
            return ExecutionResult.error(state, "Ordered response does not match the cards being moved")
        }

        val context = continuation.context
            ?: EffectContext(sourceId = continuation.sourceId, controllerId = continuation.playerId)
        val destination = com.wingedsheep.sdk.scripting.effects.CardDestination.ToZone(
            zone = continuation.destinationZone,
            player = continuation.destinationPlayer,
            placement = continuation.placement,
        )
        // ZoneTransitionService inserts one card at a time. Top insertion prepends, so process
        // the player's chosen order back-to-front to preserve "first = new top" semantics.
        val cardsForMovement = if (continuation.placement == ZonePlacement.Bottom) {
            orderedCards
        } else {
            orderedCards.asReversed()
        }
        val result = moveCollectionExecutor.moveCardsToZone(
            state = state,
            context = context,
            cards = cardsForMovement,
            destination = destination,
            destPlayerId = continuation.destinationPlayerId,
            revealed = continuation.revealed,
            moveType = continuation.moveType,
            faceDown = continuation.faceDown,
            noRegenerate = continuation.noRegenerate,
            storeMovedAs = continuation.storeMovedAs,
            underOwnersControl = continuation.underOwnersControl,
            revealToSelf = continuation.revealToSelf,
            linkToSource = continuation.linkToSource,
            unlinkFromSource = continuation.unlinkFromSource,
            addCounterType = continuation.addCounterType,
            markEnteredViaSourceAbility = continuation.markEnteredViaSourceAbility,
            orderCompletion = MoveCollectionOrderCompletion(
                playerId = continuation.playerId,
                cardCount = orderedCards.size,
                source = continuation.sourceName,
            ),
        )
        if (result.isPaused) return result.toExecutionResult()
        if (!result.isSuccess) return result.toExecutionResult()

        val completedMove = moveCollectionExecutor.applyPostMoveMetadata(
            result = result,
            context = context,
            cards = orderedCards,
            destination = destination,
            linkToSource = continuation.linkToSource,
            unlinkFromSource = continuation.unlinkFromSource,
            addCounterType = continuation.addCounterType,
            markEnteredViaSourceAbility = continuation.markEnteredViaSourceAbility,
        )
        val completed = completedMove.copy(
            events = completedMove.events + LibraryReorderedEvent(
                playerId = continuation.playerId,
                cardCount = orderedCards.size,
                source = continuation.sourceName,
            ),
        )

        val stateWithCollections = exposeCollectionsToNextFrame(
            completed.state,
            completed.updatedCollections,
            completed.updatedStoredNumbers,
            completed.updatedChosenValues,
        )
        return checkForMore(
            stateWithCollections,
            completed.events,
        )
    }

    /**
     * Resume after player ordered cards to put on the bottom of their library.
     *
     * Same as resumeReorderLibrary but places cards on the BOTTOM of the library
     * instead of the top. Used for effects like Erratic Explosion.
     */
    fun resumePutOnBottomOfLibrary(
        state: GameState,
        continuation: PutOnBottomOfLibraryContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is OrderedResponse) {
            return ExecutionResult.error(state, "Expected ordered response for library bottom reorder")
        }

        val playerId = continuation.playerId
        val orderedCards = response.orderedObjects
        val libraryZone = ZoneKey(playerId, Zone.LIBRARY)

        // Get current library
        val currentLibrary = state.getZone(libraryZone).toMutableList()

        // Remove the reordered cards from the library (they should already be removed by the executor,
        // but filter just in case)
        val cardsSet = orderedCards.toSet()
        val remainingLibrary = currentLibrary.filter { it !in cardsSet }

        // Place the cards on the BOTTOM in the player's chosen order
        val newLibrary = remainingLibrary + orderedCards

        // Update the library zone
        val newState = state.copy(
            zones = state.zones + (libraryZone to newLibrary)
        )

        val events = listOf(
            LibraryReorderedEvent(
                playerId = playerId,
                cardCount = orderedCards.size,
                source = continuation.sourceName
            )
        )

        return checkForMore(newState, events)
    }

    fun resumePutFromHand(
        state: GameState,
        continuation: PutFromHandContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is CardsSelectedResponse) {
            return ExecutionResult.error(state, "Expected card selection response for put-from-hand")
        }

        // Player selected 0 cards — declined
        if (response.selectedCards.isEmpty()) {
            return checkForMore(state, emptyList())
        }

        val cardId = response.selectedCards.first()
        val playerId = continuation.playerId
        val handZone = ZoneKey(playerId, Zone.HAND)

        // Verify card is still in hand
        if (cardId !in state.getZone(handZone)) {
            return checkForMore(state, emptyList())
        }

        // Delegate zone movement to ZoneTransitionService for full entry setup (including Saga entry)
        val transitionResult = com.wingedsheep.engine.handlers.effects.ZoneTransitionService.moveToZone(
            state, cardId, Zone.BATTLEFIELD,
            com.wingedsheep.engine.handlers.effects.ZoneEntryOptions(
                controllerId = playerId,
                tapped = continuation.entersTapped
            ),
            ZoneKey(playerId, Zone.HAND)
        )

        return checkForMore(transitionResult.state, transitionResult.events)
    }

    /**
     * Resume after a player chose a target for an Aura entering via MoveCollectionEffect.
     * Moves the aura from current zone to battlefield with AttachedToComponent.
     */
    fun resumeMoveCollectionAuraTarget(
        state: GameState,
        continuation: MoveCollectionAuraTargetContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is TargetsResponse) {
            return ExecutionResult.error(state, "Expected targets response for aura target selection")
        }

        val targetIds = response.selectedTargets[0] ?: emptyList()
        if (targetIds.isEmpty()) {
            return ExecutionResult.error(state, "No target selected for aura")
        }

        val targetId = targetIds.first()
        val auraId = continuation.auraId
        val destPlayerId = continuation.destPlayerId

        // Use MoveCollectionExecutor's helper to move aura to battlefield with attachment
        val executor = com.wingedsheep.engine.handlers.effects.library.MoveCollectionExecutor(
            cardRegistry = services.cardRegistry,
            targetFinder = services.targetFinder
        )
        val (newState, moveEvents) = executor.moveAuraToBattlefield(state, auraId, targetId, destPlayerId)

        // Continue with remaining auras
        val remainingAuras = continuation.remainingAuras
        if (remainingAuras.isNotEmpty()) {
            val nextAuraId = remainingAuras.first()
            val nextRemaining = remainingAuras.drop(1)

            // When underOwnersControl, use the next aura's owner as its controller
            val nextControllerId = if (continuation.underOwnersControl) {
                val e = newState.getEntity(nextAuraId)
                e?.get<OwnerComponent>()?.playerId
                    ?: e?.get<CardComponent>()?.ownerId
                    ?: continuation.controllerId
            } else continuation.controllerId

            val nextCardComponent = newState.getEntity(nextAuraId)?.get<CardComponent>()
            val nextCardDef = nextCardComponent?.let { services.cardRegistry.getCard(it.cardDefinitionId) }
            val nextAuraTarget = nextCardDef?.script?.auraTarget

            if (nextAuraTarget == null) {
                // Skip this aura, continue to next
                return resumeMoveCollectionAuraTarget(
                    newState,
                    continuation.copy(
                        auraId = nextAuraId,
                        controllerId = nextControllerId,
                        destPlayerId = nextControllerId,
                        remainingAuras = nextRemaining,
                        decisionId = "skip"
                    ),
                    response,
                    checkForMore
                )
            }

            val legalTargets = services.targetFinder.findLegalTargets(
                state = newState,
                requirement = nextAuraTarget,
                controllerId = nextControllerId,
                sourceId = nextAuraId,
                ignoreTargetingRestrictions = true
            )

            if (legalTargets.isEmpty()) {
                // No targets — Aura stays in current zone (Rule 303.4g), continue to next
                if (nextRemaining.isNotEmpty()) {
                    return resumeMoveCollectionAuraTarget(
                        newState,
                        continuation.copy(
                            auraId = nextRemaining.first(),
                            controllerId = nextControllerId,
                            destPlayerId = nextControllerId,
                            remainingAuras = nextRemaining.drop(1),
                            decisionId = "skip"
                        ),
                        response,
                        checkForMore
                    )
                }
                return checkForMore(newState, moveEvents)
            }

            // Pause for next aura target
            val decisionId = java.util.UUID.randomUUID().toString()
            val auraName = nextCardComponent.name
            val requirementInfo = TargetRequirementInfo(
                index = 0,
                description = nextAuraTarget.description,
                minTargets = 1,
                maxTargets = 1
            )
            val decision = ChooseTargetsDecision(
                id = decisionId,
                playerId = nextControllerId,
                prompt = "Choose what $auraName enchants",
                context = DecisionContext(
                    sourceId = nextAuraId,
                    sourceName = auraName,
                    phase = DecisionPhase.RESOLUTION
                ),
                targetRequirements = listOf(requirementInfo),
                legalTargets = mapOf(0 to legalTargets)
            )

            val nextContinuation = MoveCollectionAuraTargetContinuation(
                decisionId = decisionId,
                auraId = nextAuraId,
                controllerId = nextControllerId,
                destPlayerId = nextControllerId,
                remainingAuras = nextRemaining,
                sourceId = continuation.sourceId,
                sourceName = continuation.sourceName,
                underOwnersControl = continuation.underOwnersControl
            )

            val stateWithDecision = newState.withPendingDecision(decision)
            val stateWithContinuation = stateWithDecision.pushContinuation(nextContinuation)

            return ExecutionResult(
                state = stateWithContinuation,
                events = moveEvents,
                pendingDecision = decision
            )
        }

        return checkForMore(newState, moveEvents)
    }

    /**
     * Resume after the controller chooses a host for a card put onto the battlefield attached to
     * a chosen permanent (One Last Job mode 3). Moves the Aura/Equipment to the battlefield under
     * the controller's control and attaches it to the chosen host, reusing the permanent-agnostic
     * [com.wingedsheep.engine.handlers.effects.library.MoveCollectionExecutor.moveAuraToBattlefield].
     */
    fun resumePutOntoBattlefieldAttachedToChosen(
        state: GameState,
        continuation: PutOntoBattlefieldAttachedToChosenContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is TargetsResponse) {
            return ExecutionResult.error(state, "Expected targets response for attach-host selection")
        }

        val hostIds = response.selectedTargets[0] ?: emptyList()
        if (hostIds.isEmpty()) {
            // No host chosen — leave the card where it is (mode does nothing).
            return checkForMore(state, emptyList())
        }
        val hostId = hostIds.first()

        // Host must still be on the battlefield.
        if (!state.getBattlefield().contains(hostId)) {
            return checkForMore(state, emptyList())
        }

        val executor = com.wingedsheep.engine.handlers.effects.library.MoveCollectionExecutor(
            cardRegistry = services.cardRegistry,
            targetFinder = services.targetFinder
        )
        val (newState, events) = executor.moveAuraToBattlefield(
            state, continuation.cardId, hostId, continuation.controllerId
        )

        return checkForMore(newState, events)
    }

    fun resumeSelectFromCollection(
        state: GameState,
        continuation: SelectFromCollectionContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is CardsSelectedResponse) {
            return ExecutionResult.error(state, "Expected card selection response for SelectFromCollection")
        }

        // Rebuild the selection context from the serialized continuation. A legal option can
        // become stale in a replay/fork, so do not normalize an Aura whose host disappeared;
        // reject the response instead of silently substituting a different card.
        val selectionContext = EffectContext(
            sourceId = continuation.sourceId,
            controllerId = continuation.playerId,
            pipeline = PipelineState(storedCollections = continuation.storedCollections)
        )

        // DecisionValidators rejects restriction-violating responses before this continuation
        // runs. Keep this defense-in-depth check fail-closed: a malformed response must never be
        // normalized by silently dropping later cards into the remainder collection.
        val acceptedSet: Set<EntityId> = if (continuation.restrictions.isEmpty()) {
            response.selectedCards.toSet()
        } else {
            val kept = mutableSetOf<EntityId>()
            val claimedTypes = mutableSetOf<com.wingedsheep.sdk.core.CardType>()
            val claimedColors = mutableSetOf<com.wingedsheep.sdk.core.Color>()
            val claimedNames = mutableSetOf<String>()
            val claimedLandTypes = mutableSetOf<com.wingedsheep.sdk.core.Subtype>()
            val claimedPowers = mutableSetOf<Int>()
            var runningManaValue = 0
            var runningPower = 0
            // A card's projected power (after continuous effects), or 0 if undefined. Used by
            // TotalPowerAtMost — battlefield P/T must read projection (CLAUDE.md).
            fun projectedPowerOf(cardId: EntityId): Int =
                state.projectedState.getPower(cardId) ?: 0
            // A card's fixed (printed) power, or null for cards with no fixed power.
            fun fixedPowerOf(cardId: EntityId): Int? =
                state.getEntity(cardId)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.baseStats?.basePower
            // Basic land subtypes a card has (Plains/Island/Swamp/Mountain/Forest), for OnePerBasicLandType.
            fun basicLandTypesOf(cardId: EntityId): Set<com.wingedsheep.sdk.core.Subtype> =
                state.getEntity(cardId)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.typeLine?.subtypes
                    ?.filter { it.value in com.wingedsheep.sdk.core.Subtype.ALL_BASIC_LAND_TYPES }
                    ?.toSet() ?: emptySet()
            for (cardId in response.selectedCards) {
                val acceptsAllRestrictions = continuation.restrictions.all { restriction ->
                    when (restriction) {
                        is SelectionRestriction.OnePerCardType -> {
                            val cardTypes = state.getEntity(cardId)
                                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                                ?.typeLine?.cardTypes ?: emptySet()
                            cardTypes.isEmpty() || cardTypes.none { it in claimedTypes }
                        }
                        is SelectionRestriction.OnePerColor -> {
                            val cardColors = state.getEntity(cardId)
                                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                                ?.colors ?: emptySet()
                            // Colourless cards are not constrained by this restriction.
                            cardColors.isEmpty() || cardColors.none { it in claimedColors }
                        }
                        is SelectionRestriction.OnePerCardName -> {
                            val cardName = state.getEntity(cardId)
                                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                                ?.name
                            cardName == null || cardName !in claimedNames
                        }
                        is SelectionRestriction.TotalManaValueAtMost -> {
                            val mv = state.getEntity(cardId)
                                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                                ?.manaValue ?: 0
                            runningManaValue + mv <= restriction.max
                        }
                        is SelectionRestriction.TotalPowerAtMost -> {
                            runningPower + projectedPowerOf(cardId) <= restriction.max
                        }
                        is SelectionRestriction.OnePerBasicLandType -> {
                            val types = basicLandTypesOf(cardId)
                            // A typeless land can't be kept; a typed land needs all its types free.
                            types.isNotEmpty() && types.none { it in claimedLandTypes }
                        }
                        is SelectionRestriction.OnePerPower -> {
                            // A card with no fixed power can't be kept; otherwise its power must be free.
                            val power = fixedPowerOf(cardId)
                            power != null && power !in claimedPowers
                        }
                        is SelectionRestriction.ReducedMinimumIfMatches -> true
                        is SelectionRestriction.MaxAffordablePayment ->
                            // A pure count cap, already folded into the decision's maxSelections
                            // at decision-build time and enforced by response validation; game
                            // state can't change while the decision is pending, so there is
                            // nothing to re-check per card here.
                            true
                        is SelectionRestriction.AuraMustHaveLegalHost ->
                            auraHostLegality.isSelectionEligible(state, cardId, selectionContext)
                    }
                }
                if (acceptsAllRestrictions) {
                    kept += cardId
                    // Update restriction bookkeeping for subsequent picks.
                    for (restriction in continuation.restrictions) {
                        when (restriction) {
                            is SelectionRestriction.OnePerCardType -> {
                                claimedTypes += state.getEntity(cardId)
                                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                                    ?.typeLine?.cardTypes ?: emptySet()
                            }
                            is SelectionRestriction.OnePerColor -> {
                                claimedColors += state.getEntity(cardId)
                                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                                    ?.colors ?: emptySet()
                            }
                            is SelectionRestriction.OnePerCardName -> {
                                val cardName = state.getEntity(cardId)
                                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                                    ?.name
                                if (cardName != null) claimedNames += cardName
                            }
                            is SelectionRestriction.TotalManaValueAtMost -> {
                                runningManaValue += state.getEntity(cardId)
                                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                                    ?.manaValue ?: 0
                            }
                            is SelectionRestriction.TotalPowerAtMost -> {
                                runningPower += projectedPowerOf(cardId)
                            }
                            is SelectionRestriction.OnePerBasicLandType -> {
                                claimedLandTypes += basicLandTypesOf(cardId)
                            }
                            is SelectionRestriction.OnePerPower -> {
                                fixedPowerOf(cardId)?.let { claimedPowers += it }
                            }
                            is SelectionRestriction.ReducedMinimumIfMatches -> {
                                // Response validation enforces the conditional minimum.
                            }
                            is SelectionRestriction.MaxAffordablePayment -> {
                                // Count cap — no per-card bookkeeping (see the accept check above).
                            }
                            is SelectionRestriction.AuraMustHaveLegalHost -> {
                                // Per-card legality is re-evaluated above; it has no aggregate state.
                            }
                        }
                    }
                } else {
                    return ExecutionResult.error(
                        state,
                        "Selection violates a card-selection restriction for $cardId"
                    )
                }
            }
            kept
        }

        val selected = continuation.allCards.filter { it in acceptedSet }
        val remainder = continuation.allCards.filter { it !in acceptedSet }

        // Build the updated collections
        val updatedCollections = continuation.storedCollections.toMutableMap()
        updatedCollections[continuation.storeSelected] = selected
        if (continuation.storeRemainder != null) {
            updatedCollections[continuation.storeRemainder] = remainder
        }

        // Inject updated collections into the consumer frame beneath (if any)
        val newState = exposeCollectionsToNextFrame(state, updatedCollections)

        return checkForMore(newState, emptyList())
    }

    /**
     * Resume after one chooser answered one category of a
     * [com.wingedsheep.sdk.scripting.effects.ChooseOnePerCategoryEffect] ("chooses a permanent they
     * control of each permanent type"): record the pick and re-enter the collect loop, which either
     * asks the next question or — once every chooser is done — publishes the picks so the
     * downstream "…the rest" steps can act on them.
     */
    fun resumeChooseOnePerCategory(
        state: GameState,
        continuation: ChooseOnePerCategoryContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is CardsSelectedResponse) {
            return ExecutionResult.error(state, "Expected card selection response for ChooseOnePerCategory")
        }

        val result = ChooseOnePerCategoryExecutor().collectPicks(
            state = state,
            effect = continuation.effect,
            storedCollections = continuation.storedCollections,
            pendingPlayers = continuation.pendingPlayers,
            startCategory = continuation.categoryIndex + 1,
            picks = continuation.picks + response.selectedCards,
            sourceId = continuation.sourceId
        )

        if (result.isPaused) {
            return ExecutionResult.paused(result.state, result.pendingDecision!!, result.events)
        }

        // Republish the pipeline's collections alongside the picks so the consumer frame sees both
        // the original pool and the kept set.
        val merged = continuation.storedCollections + result.updatedCollections
        return checkForMore(exposeCollectionsToNextFrame(result.state, merged), result.events)
    }

    /**
     * Resume after the chooser picked one of two pre-existing piles via
     * [com.wingedsheep.sdk.scripting.effects.ChoosePileEffect]. Routes pile A
     * or pile B (per [OptionChosenResponse.optionIndex]) to [storeChosenAs],
     * and the other to [storeOtherAs], on the next [EffectContinuation].
     */
    fun resumeChoosePile(
        state: GameState,
        continuation: ChoosePileContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is OptionChosenResponse) {
            return ExecutionResult.error(state, "Expected option choice response for ChoosePile")
        }
        val (chosen, other) = when (response.optionIndex) {
            0 -> continuation.pileAIds to continuation.pileBIds
            1 -> continuation.pileBIds to continuation.pileAIds
            else -> return ExecutionResult.error(
                state,
                "Invalid pile index for ChoosePile: ${response.optionIndex}"
            )
        }

        val updatedCollections = continuation.storedCollections.toMutableMap()
        updatedCollections[continuation.storeChosenAs] = chosen
        updatedCollections[continuation.storeOtherAs] = other

        val newState = exposeCollectionsToNextFrame(state, updatedCollections)

        return checkForMore(newState, emptyList())
    }

    /**
     * Resume after a player selected a target during a pipeline effect (SelectTargetEffect).
     *
     * Extracts the selected target IDs from the [TargetsResponse], stores them under
     * [SelectTargetPipelineContinuation.storeAs], and injects the updated collections
     * into the next [EffectContinuation] on the stack.
     */
    fun resumeSelectTargetPipeline(
        state: GameState,
        continuation: SelectTargetPipelineContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is TargetsResponse) {
            return ExecutionResult.error(state, "Expected targets response for SelectTargetPipeline")
        }

        val selectedTargetIds = response.selectedTargets[0] ?: emptyList()

        // Build the updated collections
        val updatedCollections = continuation.storedCollections.toMutableMap()
        updatedCollections[continuation.storeAs] = selectedTargetIds

        // Inject updated collections into the consumer frame beneath (if any)
        val newState = exposeCollectionsToNextFrame(state, updatedCollections)

        return checkForMore(newState, emptyList())
    }

    /**
     * Resume after a card's owner chose top or bottom of their library.
     * Moves the card to the chosen position via ZoneTransitionService, or — if the
     * target is a spell on the stack — counters the spell and places it directly
     * onto the chosen end of the owner's library.
     */
    fun resumePutOnTopOrBottom(
        state: GameState,
        continuation: PutOnTopOrBottomContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is OptionChosenResponse) {
            return ExecutionResult.error(state, "Expected option choice response for top/bottom of library")
        }

        if (response.optionIndex !in continuation.options.indices) {
            return ExecutionResult.error(state, "Invalid option index: ${response.optionIndex}")
        }

        val chosenPosition = continuation.positions.getOrNull(response.optionIndex)
            ?: run {
                // Backwards-compatible fallback: continuations serialised before
                // `positions` was added carry only option strings.
                when (continuation.options[response.optionIndex]) {
                    "Top of library" -> com.wingedsheep.sdk.scripting.effects.LibraryChoicePosition.Top
                    "Second from top of library" -> com.wingedsheep.sdk.scripting.effects.LibraryChoicePosition.SecondFromTop
                    "Bottom of library" -> com.wingedsheep.sdk.scripting.effects.LibraryChoicePosition.Bottom
                    else -> return ExecutionResult.error(state, "Unknown library position option")
                }
            }

        val placement = when (chosenPosition) {
            com.wingedsheep.sdk.scripting.effects.LibraryChoicePosition.Top ->
                com.wingedsheep.engine.handlers.effects.LibraryPlacement.Top
            com.wingedsheep.sdk.scripting.effects.LibraryChoicePosition.SecondFromTop ->
                com.wingedsheep.engine.handlers.effects.LibraryPlacement.NthFromTop(1)
            com.wingedsheep.sdk.scripting.effects.LibraryChoicePosition.Bottom ->
                com.wingedsheep.engine.handlers.effects.LibraryPlacement.Bottom
        }

        val cardId = continuation.cardId

        // Case 1: target is a spell on the stack — remove from stack and place in library.
        if (cardId in state.stack) {
            return resumePutSpellOnTopOrBottom(state, cardId, continuation.ownerId, placement, checkForMore)
        }

        // Case 2: target is in a zone (battlefield or elsewhere) — use ZoneTransitionService.
        val currentZone = state.zones.entries.firstOrNull { (_, entities) -> cardId in entities }?.key
            ?: return checkForMore(state, emptyList()) // Card no longer exists in any zone

        val transitionResult = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
            .moveToZoneWithReplacements(
                state = state,
                entityId = cardId,
                destinationZone = Zone.LIBRARY,
                options = com.wingedsheep.engine.handlers.effects.ZoneEntryOptions(
                    controllerId = continuation.ownerId,
                    libraryPlacement = placement,
                ),
                fromZoneKey = currentZone,
                context = EffectContext(
                    sourceId = continuation.sourceId,
                    controllerId = continuation.ownerId,
                ),
                completion = com.wingedsheep.engine.replacement.PendingGameEvent
                    .LibraryRevealZoneChangeCompletion,
            )

        if (transitionResult.isPaused) return transitionResult.toExecutionResult()
        if (!transitionResult.isSuccess) return transitionResult.toExecutionResult()

        // The card was visible to everyone before the move (battlefield or stack) and the owner's
        // choice of position was public, so all players know where it ended up. Mark it revealed
        // to every player so each library viewer shows the card face-up at its new slot.
        val finalState = if (cardId in transitionResult.state.getZone(ZoneKey(continuation.ownerId, Zone.LIBRARY))) {
            com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
                .markRevealed(transitionResult.state, listOf(cardId), transitionResult.state.turnOrder.toSet())
        } else {
            transitionResult.state
        }

        return checkForMore(finalState, transitionResult.events)
    }

    /**
     * Handle the stack case for [PutOnTopOrBottomContinuation]: counter the spell
     * (remove from stack + strip stack components) and insert it into the owner's
     * library at the chosen end. Can't-be-countered spells still follow the effect
     * per the general MTG rules — putting a spell into its owner's library is not
     * countering it in the technical sense, so we always move it.
     */
    private fun resumePutSpellOnTopOrBottom(
        state: GameState,
        spellId: EntityId,
        ownerId: EntityId,
        placement: com.wingedsheep.engine.handlers.effects.LibraryPlacement,
        checkForMore: CheckForMore
    ): ExecutionResult {
        val spellContainer = state.getEntity(spellId)
            ?: return checkForMore(state, emptyList())
        val spellName = spellContainer.get<CardComponent>()?.name ?: "Unknown"
        val transitionResult = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
            .moveToZoneWithReplacements(
                state = state,
                entityId = spellId,
                destinationZone = Zone.LIBRARY,
                options = com.wingedsheep.engine.handlers.effects.ZoneEntryOptions(
                    controllerId = ownerId,
                    libraryPlacement = placement,
                ),
                fromZoneKey = ZoneKey(ownerId, Zone.STACK),
                context = EffectContext(sourceId = null, controllerId = ownerId),
                completion = com.wingedsheep.engine.replacement.PendingGameEvent
                    .StackSpellToLibraryZoneChangeCompletion,
            )
        if (transitionResult.isPaused) return transitionResult.toExecutionResult()
        if (!transitionResult.isSuccess) return transitionResult.toExecutionResult()

        val newState = com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
            .markRevealed(transitionResult.state, listOf(spellId), transitionResult.state.turnOrder.toSet())
        val events = listOf(SpellCounteredEvent(spellId, spellName)) + transitionResult.events
        return checkForMore(newState, events)
    }

    /**
     * Resume cascade resolution (CR 702.85a) after the controller answers
     * "cast this card without paying its mana cost?".
     *
     * On **No** every exiled card — including the would-be cascade card — is
     * shuffled onto the bottom of the controller's library.
     *
     * On **Yes** the other exiled cards (the lands and any other non-hit cards
     * skipped past during the walk) are bottomed first. The cascade card is
     * granted [MayPlayPermission] + [PlayWithoutPayingCostComponent] so the
     * synthesized cast resolves to a free cast, then [CastSpellHandler] is
     * invoked directly to put the spell on the stack. If the cast pauses for
     * targets / X / modes, that pause is bubbled up unchanged — the leftover
     * bottoming has already happened, so the cascade resolution is effectively
     * complete. If the cast errors (no legal targets, etc.) the cascade card
     * is bottomed too, since it ultimately wasn't cast.
     */
    fun resumeCascadeMayCast(
        state: GameState,
        continuation: CascadeMayCastContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is YesNoResponse) {
            return ExecutionResult.error(state, "Expected yes/no response for cascade may-cast")
        }

        if (!response.choice) {
            val result = CascadeExecutor.bottomRandomizeWithReplacements(
                state = state,
                playerId = continuation.playerId,
                cards = continuation.exiledCards,
                context = EffectContext(
                    sourceId = continuation.sourceId,
                    controllerId = continuation.playerId,
                ),
                cardRegistry = services.cardRegistry,
            )
            if (result.isPaused) {
                return ExecutionResult.paused(result.state, result.pendingDecision!!, result.events)
            }
            if (!result.isSuccess) return result.toExecutionResult()
            return checkForMore(result.state, result.events)
        }

        // Yes — bottom the other exiled cards now, then attempt the free cast.
        val others = continuation.exiledCards.filter { it != continuation.cascadeCardId }
        val afterBottomContinuation = CascadeAfterBottomContinuation(
            decisionId = "pending",
            playerId = continuation.playerId,
            sourceId = continuation.sourceId,
            cascadeCardId = continuation.cascadeCardId,
        )
        val hasCommanderRemainder = state.format.usesCommanders && others.any { cardId ->
            state.getEntity(cardId)?.has<com.wingedsheep.engine.state.components.identity.CommanderComponent>() == true
        }
        val bottomInputState = if (hasCommanderRemainder) {
            state.pushContinuation(afterBottomContinuation)
        } else state
        val bottomResult = CascadeExecutor.bottomRandomizeWithReplacements(
            state = bottomInputState,
            playerId = continuation.playerId,
            cards = others,
            context = EffectContext(
                sourceId = continuation.sourceId,
                controllerId = continuation.playerId,
            ),
            cardRegistry = services.cardRegistry,
        )
        if (bottomResult.isPaused) {
            return ExecutionResult.paused(
                bottomResult.state,
                bottomResult.pendingDecision!!,
                bottomResult.events,
            )
        }
        if (!bottomResult.isSuccess) return bottomResult.toExecutionResult()
        val afterBottom = if (hasCommanderRemainder) {
            bottomResult.state.popContinuation().second
        } else bottomResult.state
        return continueCascadeAfterBottom(
            state = afterBottom,
            continuation = afterBottomContinuation,
            events = bottomResult.events,
            checkForMore = checkForMore,
        )
    }

    /** Continue the cascade YES branch once all non-hit cards have reached their bottom boundary. */
    private fun continueCascadeAfterBottom(
        state: GameState,
        continuation: CascadeAfterBottomContinuation,
        events: List<GameEvent>,
        checkForMore: CheckForMore,
    ): ExecutionResult {
        val targetPrep = CastFromCollectionWithoutPayingCostExecutor.prepareTargetSelection(
            state = state,
            cardId = continuation.cascadeCardId,
            casterId = continuation.playerId,
            cardRegistry = services.cardRegistry,
            targetFinder = targetFinder,
        )
        if (targetPrep is CastFromCollectionWithoutPayingCostExecutor.TargetPrep.NoLegalTargets) {
            val tail = CascadeExecutor.bottomRandomizeWithReplacements(
                state = state,
                playerId = continuation.playerId,
                cards = listOf(continuation.cascadeCardId),
                context = EffectContext(
                    sourceId = continuation.sourceId,
                    controllerId = continuation.playerId,
                ),
                cardRegistry = services.cardRegistry,
            )
            if (tail.isPaused) {
                return ExecutionResult.paused(tail.state, tail.pendingDecision!!, events + tail.events)
            }
            if (!tail.isSuccess) return tail.toExecutionResult()
            return checkForMore(tail.state, events + tail.events)
        }

        // Grant free-cast permission so the synthesized cast pays nothing.
        val (permId, stateWithGrant) = CastFromCollectionWithoutPayingCostExecutor.grantFreeCast(
            state = state,
            cardId = continuation.cascadeCardId,
            controllerId = continuation.playerId,
            sourceId = continuation.sourceId,
        )

        if (targetPrep is CastFromCollectionWithoutPayingCostExecutor.TargetPrep.NeedsTargets) {
            val targetsContinuation = targetPrep.continuation.copy(
                grantedPermissionId = permId,
                onCastFailure = FreeCastFallback.BOTTOM_OF_LIBRARY,
            )
            val pausedState = stateWithGrant
                .pushContinuation(targetsContinuation)
                .withPendingDecision(targetPrep.decision)
                .withPriority(continuation.playerId)
            return ExecutionResult.paused(pausedState, targetPrep.decision, events + targetPrep.event)
        }

        // Hand priority to the cascade controller for the synthesized cast. The cast
        // happens during cascade resolution rather than on a normal priority window.
        val stateForCast = stateWithGrant.copy(priorityPlayerId = continuation.playerId)
        val castResult = castSpellHandler.execute(
            stateForCast,
            CastSpell(continuation.playerId, continuation.cascadeCardId),
        )

        if (castResult.error != null) {
            val revoked = CastFromCollectionWithoutPayingCostExecutor.revokeFreeCast(
                stateWithGrant, continuation.cascadeCardId, permId
            )
            val tail = CascadeExecutor.bottomRandomizeWithReplacements(
                state = revoked,
                playerId = continuation.playerId,
                cards = listOf(continuation.cascadeCardId),
                context = EffectContext(
                    sourceId = continuation.sourceId,
                    controllerId = continuation.playerId,
                ),
                cardRegistry = services.cardRegistry,
            )
            if (tail.isPaused) {
                return ExecutionResult.paused(tail.state, tail.pendingDecision!!, events + tail.events)
            }
            if (!tail.isSuccess) return tail.toExecutionResult()
            return checkForMore(tail.state, events + tail.events)
        }

        // CastSpellHandler already detected + stacked this cast's triggers; propagate the flag
        // so SubmitDecisionHandler doesn't re-scan the SpellCastEvent and double-fire them.
        if (castResult.pendingDecision != null) {
            // The cast paused (for target / X / mode selection). The leftover
            // bottoming is already done; let the cast's own continuations finish
            // the cast on resume.
            return ExecutionResult.paused(
                castResult.state,
                castResult.pendingDecision,
                events + castResult.events
            ).copy(triggersAlreadyProcessed = castResult.triggersAlreadyProcessed)
        }

        return checkForMore(castResult.state, events + castResult.events)
            .copy(triggersAlreadyProcessed = castResult.triggersAlreadyProcessed)
    }

    /**
     * Resume after the controller answers "cast the discovered card for free, or put it into
     * your hand?" during a [com.wingedsheep.sdk.scripting.effects.DiscoverEffect] (CR 701.57a).
     *
     * In both branches the *other* exiled cards are bottom-randomized first. Then:
     *  - **Cast** (yes): the discovered card is granted a free cast (like [CascadeExecutor]) and
     *    synthesized through the normal cast machinery, so target / X / mode prompts surface and the
     *    cast's "whenever you cast a spell (from exile)" triggers are stacked exactly once (the
     *    `triggersAlreadyProcessed` flag is propagated so they aren't re-scanned). If the cast can't
     *    initiate — no legal target, etc. — the card falls back to the controller's hand, per
     *    "If you don't cast it, put that card into your hand."
     *  - **Hand** (no): the discovered card is moved straight to the controller's hand.
     *
     * Any [DiscoverMayCastContinuation.thenEffect] then resolves last, with the discovered card
     * published to [DiscoverMayCastContinuation.storeDiscoveredAs] so it can be read (Hit the
     * Mother Lode's "…create Treasure tokens equal to the difference"). In the cast branch it is
     * pre-pushed as an [EffectContinuation] so it runs after the cast even if the cast pauses.
     */
    fun resumeDiscoverMayCast(
        state: GameState,
        continuation: DiscoverMayCastContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is YesNoResponse) {
            return ExecutionResult.error(state, "Expected yes/no response for discover may-cast")
        }

        val others = continuation.exiledCards.filter { it != continuation.discoveredCardId }
        val afterBottomContinuation = DiscoverAfterBottomContinuation(
            decisionId = "pending",
            discover = continuation,
            cast = response.choice,
        )
        val hasCommanderRemainder = state.format.usesCommanders && others.any { cardId ->
            state.getEntity(cardId)?.has<com.wingedsheep.engine.state.components.identity.CommanderComponent>() == true
        }
        val bottomInputState = if (hasCommanderRemainder) {
            state.pushContinuation(afterBottomContinuation)
        } else state
        val bottomResult = CascadeExecutor.bottomRandomizeWithReplacements(
            state = bottomInputState,
            playerId = continuation.playerId,
            cards = others,
            context = EffectContext(
                sourceId = continuation.sourceId,
                controllerId = continuation.playerId,
            ),
            cardRegistry = services.cardRegistry,
        )
        if (bottomResult.isPaused) {
            return ExecutionResult.paused(
                bottomResult.state,
                bottomResult.pendingDecision!!,
                bottomResult.events,
            )
        }
        if (!bottomResult.isSuccess) return bottomResult.toExecutionResult()
        val afterBottom = if (hasCommanderRemainder) {
            bottomResult.state.popContinuation().second
        } else bottomResult.state
        return continueDiscoverAfterBottom(
            state = afterBottom,
            continuation = afterBottomContinuation,
            events = bottomResult.events,
            checkForMore = checkForMore,
        )
    }

    /** Continue discover once all non-discovered exiled cards have crossed their bottom boundary. */
    private fun continueDiscoverAfterBottom(
        state: GameState,
        continuation: DiscoverAfterBottomContinuation,
        events: List<GameEvent>,
        checkForMore: CheckForMore,
    ): ExecutionResult {
        val discover = continuation.discover
        val discovered = discover.discoveredCardId
        val discoveredCollections = discover.storeDiscoveredAs
            ?.let { mapOf(it to listOf(discovered)) }
            ?: emptyMap()

        if (!continuation.cast) {
            // Put the discovered card into the controller's hand, then run the follow-up. The
            // follow-up is pushed below the pending zone change so a 903.9b answer resumes it
            // through the ordinary continuation runner.
            return moveDiscoverCardToHand(
                state, discovered, discover, discoveredCollections, events, checkForMore
            )
        }

        // A non-modal targeted spell (Zombify) can't carry targets through the synthesized
        // CastSpell — surface the ChooseTargetsDecision first, exactly as
        // CastFromCollectionWithoutPayingCostExecutor does. If a required slot has no legal
        // targets the cast can't initiate (CR 601.2c) and the card goes to hand instead. Checked
        // *before* granting so the card reaches hand without a lingering free-cast grant.
        val targetPrep = CastFromCollectionWithoutPayingCostExecutor.prepareTargetSelection(
            state = state,
            cardId = discovered,
            casterId = discover.playerId,
            cardRegistry = services.cardRegistry,
            targetFinder = targetFinder,
        )
        if (targetPrep is CastFromCollectionWithoutPayingCostExecutor.TargetPrep.NoLegalTargets) {
            return moveDiscoverCardToHand(
                state, discovered, discover, discoveredCollections, events, checkForMore
            )
        }

        // Cast branch: grant a free cast and synthesize it through the normal cast machinery —
        // mirroring CascadeExecutor's may-cast rather than the CastFromCollection effect, so the
        // cast's "whenever you cast a spell (from exile)" triggers are stacked exactly once
        // (Quintorius Kand).
        val (permId, granted) = CastFromCollectionWithoutPayingCostExecutor.grantFreeCast(
            state = state,
            cardId = discovered,
            controllerId = discover.playerId,
            sourceId = discover.sourceId,
        )

        // The follow-up [thenEffect] is pre-pushed as an EffectContinuation so it resolves after
        // the cast even if the cast pauses for targets / X.
        var stateForCast = granted
        if (discover.thenEffect != null) {
            val thenCtx = EffectContext(
                sourceId = discover.sourceId,
                controllerId = discover.playerId,
                pipeline = PipelineState.EMPTY.copy(storedCollections = discoveredCollections)
            )
            stateForCast = stateForCast.pushContinuation(
                EffectContinuation(
                    decisionId = "pending",
                    remainingEffects = listOf(discover.thenEffect),
                    effectContext = thenCtx
                )
            )
        }

        if (targetPrep is CastFromCollectionWithoutPayingCostExecutor.TargetPrep.NeedsTargets) {
            val targetsContinuation = targetPrep.continuation.copy(
                grantedPermissionId = permId,
                onCastFailure = FreeCastFallback.HAND,
            )
            val pausedState = stateForCast
                .pushContinuation(targetsContinuation)
                .withPendingDecision(targetPrep.decision)
                .withPriority(discover.playerId)
            return ExecutionResult.paused(pausedState, targetPrep.decision, events + targetPrep.event)
        }

        val stateReady = stateForCast.copy(priorityPlayerId = discover.playerId)
        val castResult = castSpellHandler.execute(stateReady, CastSpell(discover.playerId, discovered))

        if (castResult.error != null) {
            // The cast couldn't initiate — pop the pre-pushed follow-up, revoke the unused
            // free-cast grant, put the discovered card into hand ("If you don't cast it, put
            // that card into your hand"), then run the follow-up (Hit the Mother Lode still
            // makes its Treasures — a card was discovered).
            val withoutThen = CastFromCollectionWithoutPayingCostExecutor.revokeFreeCast(
                if (discover.thenEffect != null) stateForCast.popContinuation().second else stateForCast,
                discovered,
                permId,
            )
            return moveDiscoverCardToHand(
                withoutThen, discovered, discover, discoveredCollections, events, checkForMore
            )
        }

        if (castResult.pendingDecision != null) {
            // The cast paused (targets / X); the pre-pushed follow-up runs when it resumes.
            return ExecutionResult.paused(castResult.state, castResult.pendingDecision, events + castResult.events)
                .copy(triggersAlreadyProcessed = castResult.triggersAlreadyProcessed)
        }

        // Cast succeeded synchronously; checkForMore drains the pre-pushed follow-up continuation
        // (the card's thenEffect plus the DiscoveredEvent emit tail). CastSpellHandler already
        // stacked this cast's triggers (e.g. Quintorius Kand's "whenever you cast a spell from
        // exile"); propagate the flag so SubmitDecisionHandler doesn't re-scan the SpellCastEvent and
        // double-fire them. But that flag also suppresses scanning of the DiscoveredEvent the tail
        // emits (CR 701.57b) — a genuinely new event CastSpellHandler never saw — so scan its
        // "whenever you discover" triggers here.
        return scanDiscoveredEventTriggers(
            checkForMore(castResult.state, events + castResult.events)
                .copy(triggersAlreadyProcessed = castResult.triggersAlreadyProcessed)
        )
    }

    /** Finish discover's library-exhausted branch after its bottom moves have resolved. */
    private fun continueDiscoverNoHitBottom(
        state: GameState,
        continuation: DiscoverNoHitBottomContinuation,
        events: List<GameEvent>,
        checkForMore: CheckForMore,
    ): ExecutionResult {
        val finalCardId = continuation.exiledCards.lastOrNull()
        val finalCardMv = finalCardId
            ?.let { state.getEntity(it)?.get<CardComponent>()?.manaValue }
        val runThen = continuation.effect.thenEffect?.takeIf {
            finalCardMv != null && finalCardMv <= continuation.threshold
        }
        val discoveredCollections = continuation.effect.storeDiscoveredAs
            ?.let { key -> finalCardId?.let { mapOf(key to listOf(it)) } }
            ?: emptyMap()
        val tail = com.wingedsheep.sdk.scripting.effects.CompositeEffect(
            listOfNotNull(
                runThen,
                com.wingedsheep.sdk.scripting.effects.EmitDiscoveredEventEffect(continuation.threshold),
            )
        )
        val result = services.effectExecutorRegistry.execute(
            state,
            tail,
            EffectContext(
                sourceId = continuation.context.sourceId,
                controllerId = continuation.context.controllerId,
                pipeline = PipelineState.EMPTY.copy(storedCollections = discoveredCollections),
            )
        )
        if (result.isPaused) {
            return ExecutionResult.paused(
                result.state,
                result.pendingDecision!!,
                events + result.events,
            )
        }
        return checkForMore(result.state, events + result.events)
    }

    /**
     * Detect and process "whenever you discover" triggers (CR 701.57 — Curator of Sun's Creation)
     * from any [DiscoveredEvent] in [result]'s events. Used only on the discover **cast-for-free**
     * branch, which returns `triggersAlreadyProcessed = true` to protect the discovered card's own
     * `SpellCastEvent` from a re-scan — a flag that would otherwise also suppress the DiscoveredEvent
     * emitted by the discover tail. Detecting it here keeps the SpellCastEvent protected while still
     * firing discover watchers. No-op when the result paused (the emit tail hasn't run yet) or has no
     * DiscoveredEvent.
     */
    private fun scanDiscoveredEventTriggers(result: ExecutionResult): ExecutionResult {
        if (!result.isSuccess || result.isPaused) return result
        val discoveredEvents = result.events.filterIsInstance<com.wingedsheep.engine.core.DiscoveredEvent>()
        if (discoveredEvents.isEmpty()) return result
        val triggers = services.triggerDetector.detectTriggers(result.state, discoveredEvents)
        if (triggers.isEmpty()) return result
        val processed = services.triggerProcessor.processTriggers(result.state, triggers)
        val events = result.events + processed.events
        return if (processed.isPaused) {
            ExecutionResult.paused(processed.state, processed.pendingDecision!!, events)
                .copy(triggersAlreadyProcessed = true)
        } else {
            ExecutionResult.success(processed.newState, events)
                .copy(triggersAlreadyProcessed = true)
        }
    }

    /**
     * Move a discovered card into its controller's hand through the CR 903.9b boundary.
     *
     * Discover has already made its may-cast decision by the time this helper runs. The normal
     * follow-up therefore sits below the pending zone-change frame, so both a synchronous move and
     * a later Commander YES/NO answer resume through the same [CheckForMore] path.
     */
    private fun moveDiscoverCardToHand(
        state: GameState,
        cardId: EntityId,
        continuation: DiscoverMayCastContinuation,
        discoveredCollections: Map<String, List<EntityId>>,
        leadingEvents: List<GameEvent>,
        checkForMore: CheckForMore,
    ): ExecutionResult {
        val thenEffect = continuation.thenEffect
        val stateWithFollowUp = if (thenEffect == null) {
            state
        } else {
            state.pushContinuation(
                EffectContinuation(
                    decisionId = "pending",
                    remainingEffects = listOf(thenEffect),
                    effectContext = EffectContext(
                        sourceId = continuation.sourceId,
                        controllerId = continuation.playerId,
                        pipeline = PipelineState.EMPTY.copy(storedCollections = discoveredCollections),
                    ),
                )
            )
        }

        val moveResult = ZoneTransitionService.moveToZoneWithReplacements(
            state = stateWithFollowUp,
            entityId = cardId,
            destinationZone = Zone.HAND,
            options = ZoneEntryOptions(controllerId = continuation.playerId),
            context = EffectContext(
                sourceId = continuation.sourceId,
                controllerId = continuation.playerId,
            ),
            completion = com.wingedsheep.engine.replacement.PendingGameEvent
                .PlainZoneChangeCompletion,
        )
        if (moveResult.isPaused) {
            return moveResult.toExecutionResult().copy(
                events = leadingEvents + moveResult.events,
            )
        }
        if (!moveResult.isSuccess) return moveResult.toExecutionResult()
        return checkForMore(moveResult.state, leadingEvents + moveResult.events)
    }

    /** Run a discover [DiscoverMayCastContinuation.thenEffect] (if any) with the discovered card published. */
    private fun runDiscoverThenEffect(
        state: GameState,
        continuation: DiscoverMayCastContinuation,
        discoveredCollections: Map<String, List<EntityId>>,
        leadingEvents: List<com.wingedsheep.engine.core.GameEvent>,
        checkForMore: CheckForMore
    ): ExecutionResult {
        val thenEffect = continuation.thenEffect
            ?: return checkForMore(state, leadingEvents)
        val ctx = EffectContext(
            sourceId = continuation.sourceId,
            controllerId = continuation.playerId,
            pipeline = PipelineState.EMPTY.copy(storedCollections = discoveredCollections)
        )
        val result = effectRunner.executeRemainingEffects(state, listOf(thenEffect), ctx)
        if (result.isPaused) {
            return ExecutionResult.paused(result.state, result.pendingDecision!!, leadingEvents + result.events)
        }
        return checkForMore(result.state, leadingEvents + result.events)
    }


    /**
     * Resume after the controller picks targets for a free synthesized cast triggered by
     * [com.wingedsheep.sdk.scripting.effects.CastFromCollectionWithoutPayingCostEffect].
     *
     * Flattens the per-requirement target picks into a `List<ChosenTarget>` (via
     * [entityIdToChosenTarget]), invokes the normal cast pipeline, and bubbles any further
     * pause (X selection, modal-target prompts on a card that turned out to be modal, etc.)
     * through unchanged.
     */
    fun resumeCastFromCollectionTargets(
        state: GameState,
        continuation: CastFromCollectionTargetsContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is TargetsResponse) {
            return ExecutionResult.error(
                state,
                "Expected targets response for free-cast target selection"
            )
        }

        val chosenTargets = response.selectedTargets.entries
            .sortedBy { it.key }
            .flatMap { (_, ids) -> ids.map { entityIdToChosenTarget(state, it) } }

        val stateForCast = state.copy(priorityPlayerId = continuation.casterId)
        val castResult = castSpellHandler.execute(
            stateForCast,
            CastSpell(continuation.casterId, continuation.cardId, chosenTargets),
        )

        if (castResult.error != null) {
            // Cast still couldn't initiate (e.g., targets became illegal between selection
            // and resolution). Revoke the unused free-cast grant and send the card to the
            // owning flow's fallback zone (discover → hand, cascade → bottom of library) so
            // it isn't stranded in exile; checkForMore keeps the rest of the trigger's
            // resolution (e.g. a discover follow-up frame) alive.
            var cleaned = CastFromCollectionWithoutPayingCostExecutor.revokeFreeCast(
                state, continuation.cardId, continuation.grantedPermissionId
            )
            val fallbackEvents = mutableListOf<GameEvent>()
            when (continuation.onCastFailure) {
                FreeCastFallback.LEAVE -> {}
                FreeCastFallback.HAND -> {
                    val moveResult = ZoneTransitionService.moveToZoneWithReplacements(
                        state = cleaned,
                        entityId = continuation.cardId,
                        destinationZone = Zone.HAND,
                        options = ZoneEntryOptions(controllerId = continuation.casterId),
                        context = EffectContext(
                            sourceId = null,
                            controllerId = continuation.casterId,
                        ),
                        completion = com.wingedsheep.engine.replacement.PendingGameEvent
                            .PlainZoneChangeCompletion,
                    )
                    if (moveResult.isPaused) {
                        return moveResult.toExecutionResult().copy(
                            events = fallbackEvents + moveResult.events,
                        )
                    }
                    if (moveResult.isSuccess) {
                        cleaned = moveResult.state
                        fallbackEvents.addAll(moveResult.events)
                    }
                }
                FreeCastFallback.BOTTOM_OF_LIBRARY -> {
                    val bottomResult = CascadeExecutor.bottomRandomizeWithReplacements(
                        state = cleaned,
                        playerId = continuation.casterId,
                        cards = listOf(continuation.cardId),
                        context = EffectContext(
                            sourceId = null,
                            controllerId = continuation.casterId,
                        ),
                        cardRegistry = services.cardRegistry,
                    )
                    if (bottomResult.isPaused) {
                        return ExecutionResult.paused(
                            bottomResult.state,
                            bottomResult.pendingDecision!!,
                            fallbackEvents + bottomResult.events,
                        )
                    }
                    if (!bottomResult.isSuccess) return bottomResult.toExecutionResult()
                    cleaned = bottomResult.state
                    fallbackEvents.addAll(bottomResult.events)
                }
            }
            return checkForMore(cleaned, fallbackEvents)
        }

        // The cast initiated. Publish the cast card so an enclosing IfYouDoEffect frame beneath
        // (Kaervek's "If you do, you lose 2 life") sees a non-empty collection.
        val castCollections = continuation.storeCastTo?.let { mapOf(it to listOf(continuation.cardId)) }
            ?: emptyMap()

        // CastSpellHandler already detected + stacked this cast's triggers (e.g. Quintorius Kand's
        // "whenever you cast a spell from exile"); propagate the flag so SubmitDecisionHandler
        // doesn't re-scan the SpellCastEvent and double-fire them.
        if (castResult.pendingDecision != null) {
            val exposed = exposeCollectionsToNextFrame(castResult.state, castCollections)
            return ExecutionResult.paused(
                exposed,
                castResult.pendingDecision,
                castResult.events,
            ).copy(triggersAlreadyProcessed = castResult.triggersAlreadyProcessed)
        }

        val exposed = exposeCollectionsToNextFrame(castResult.state, castCollections)
        // Shared by every free-cast-with-targets flow (cascade, discover, suspend, …). When a
        // *discovered* targeted spell is cast for free, the discover tail's DiscoveredEvent rides
        // this batch under triggersAlreadyProcessed = true and would be suppressed — scan it (no-op
        // for the non-discover callers, which emit no DiscoveredEvent).
        return scanDiscoveredEventTriggers(
            checkForMore(exposed, castResult.events)
                .copy(triggersAlreadyProcessed = castResult.triggersAlreadyProcessed)
        )
    }

    /**
     * Resume a [CastAnyNumberFromCollectionContinuation] — one iteration of the
     * "cast any number of them for free" loop.
     *
     * The controller picked 0..1 cards from the still-castable set:
     *  - **0** → done; uncast cards stay in exile (no later-in-turn permission was granted).
     *  - **1** → cast it for free, then loop over the rest. Both steps run through
     *    [effectRunner]: it casts the single chosen card via
     *    [CastFromCollectionWithoutPayingCostEffect] (which handles target / X / mode pauses
     *    exactly as Cascade and Shiko do — and, going through `CastSpellHandler.execute`
     *    directly, ignores card-type timing) and then re-runs
     *    [CastAnyNumberFromCollectionWithoutPayingCostEffect] over the remaining cards. The
     *    runner's per-effect `EffectContinuation` makes a paused cast auto-resume into the
     *    next loop iteration.
     *
     * The chosen card is keyed under a private collection name and the loop collection is
     * trimmed to the remainder, so each iteration's bookkeeping is self-contained.
     */
    fun resumeCastAnyNumberFromCollection(
        state: GameState,
        continuation: CastAnyNumberFromCollectionContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore
    ): ExecutionResult {
        if (response !is CardsSelectedResponse) {
            return ExecutionResult.error(state, "Expected card selection response for free-cast loop")
        }

        val ctx = continuation.effectContext
        val collection = ctx.pipeline.storedCollections[continuation.from].orEmpty()
        val chosenId = response.selectedCards.firstOrNull()

        // Declined, or a stale / no-longer-offered pick: end the loop. Uncast cards remain
        // wherever they are.
        if (chosenId == null || chosenId !in collection) {
            return checkForMore(state, emptyList())
        }

        val singleKey = "${continuation.from}\$next"
        val remaining = collection - chosenId
        val loopContext = ctx.copy(
            pipeline = ctx.pipeline.copy(
                storedCollections = ctx.pipeline.storedCollections +
                    (singleKey to listOf(chosenId)) +
                    (continuation.from to remaining)
            )
        )

        val effects = listOf(
            CastFromCollectionWithoutPayingCostEffect(from = singleKey, payManaCost = continuation.payManaCost),
            CastAnyNumberFromCollectionWithoutPayingCostEffect(
                from = continuation.from,
                payManaCost = continuation.payManaCost,
            ),
        )
        val result = effectRunner.executeRemainingEffects(state, effects, loopContext)
        if (result.isPaused) return result.toExecutionResult()
        return checkForMore(result.state, result.events.toList())
    }
}

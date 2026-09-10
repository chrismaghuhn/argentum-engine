package com.wingedsheep.engine.mechanics

import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.HandRevealedEvent
import com.wingedsheep.engine.core.LibrarySearchedEvent
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.LookedAtCardsEvent
import com.wingedsheep.engine.core.TurnFaceUpEvent
import com.wingedsheep.engine.core.TurnedFaceDownEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.KnownInformationAcquisitionReason
import com.wingedsheep.engine.state.components.player.KnownInformationAudience
import com.wingedsheep.engine.state.components.player.KnownInformationFactV1
import com.wingedsheep.engine.state.components.player.KnownInformationFactKind
import com.wingedsheep.engine.state.components.player.KnownInformationLedgerComponentV1
import com.wingedsheep.engine.state.components.player.KnownInformationLedgerOrdering
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/**
 * Rules-owned state operations for the perspective-scoped known-information ledger.
 *
 * This is intentionally separate from the model-facing History-A event DTO. The ledger stores
 * runtime object/incarnation witnesses in immutable [GameState] so Rules, fork, snapshot, restore,
 * and replay all use one state authority. History-C must translate these witnesses before any
 * learner-facing reference is created.
 */
object KnownInformationLedger {

    private val PUBLIC_ZONES = setOf(
        Zone.BATTLEFIELD,
        Zone.GRAVEYARD,
        Zone.STACK,
        Zone.EXILE,
        Zone.COMMAND,
    )

    /** Return the perspective component, or an empty component for a player with no extra facts. */
    fun forPlayer(state: GameState, perspectivePlayerId: EntityId): KnownInformationLedgerComponentV1 =
        state.getEntity(perspectivePlayerId)
            ?.get<KnownInformationLedgerComponentV1>()
            ?: KnownInformationLedgerComponentV1.EMPTY

    /**
     * Record identity, current-zone membership, and (for a library card) current position for each
     * authorized perspective. The audience and reason are supplied by the authoritative producer;
     * this function never infers them from card names or UI text.
     */
    fun recordCards(
        state: GameState,
        cardIds: Collection<EntityId>,
        perspectivePlayerIds: Collection<EntityId>,
        audience: KnownInformationAudience,
        acquisitionReason: KnownInformationAcquisitionReason,
        includeLibraryPositions: Boolean = false,
    ): GameState {
        if (cardIds.isEmpty() || perspectivePlayerIds.isEmpty()) return state

        val orderedCards = cardIds.distinct()
        val orderedPerspectives = perspectivePlayerIds
            .distinct()
            .filter { it in state.turnOrder && state.hasEntity(it) }
            .sortedBy { state.turnOrder.indexOf(it) }
        if (orderedPerspectives.isEmpty()) return state

        var newState = state
        for (perspectivePlayerId in orderedPerspectives) {
            val originalLedger = forPlayer(newState, perspectivePlayerId)
            var ledger = originalLedger
            for (cardId in orderedCards) {
                val location = locate(newState, cardId) ?: continue
                val card = newState.getEntity(cardId)?.get<CardComponent>() ?: continue
                val stamp = newState.objectIdentityStamps[cardId] ?: continue
                val facts = factsFor(
                    cardId = cardId,
                    stamp = stamp,
                    card = card,
                    location = location,
                    audience = audience,
                    acquisitionReason = acquisitionReason,
                    acquiredAtEpoch = ledger.knowledgeEpoch + 1L,
                    includeLibraryPosition = includeLibraryPositions,
                )
                for (fact in facts) {
                    ledger = ledger.withFact(fact)
                }
            }
            if (ledger != originalLedger) {
                newState = putLedger(newState, perspectivePlayerId, ledger)
            }
        }
        return newState
    }

    /**
     * Invalidate identity facts for explicit authoritative knowledge-loss IDs.
     *
     * The caller supplies the affected objects from a Rules-owned ambiguity/invalidation producer;
     * this method never searches by card name or reconstructs the affected set heuristically.
     * Epoch finalization at the committed ActionProcessor boundary records the semantic change.
     */
    fun invalidateIdentityFacts(
        state: GameState,
        cardIds: Collection<EntityId>,
    ): GameState {
        val invalidated = cardIds.toSet()
        if (invalidated.isEmpty()) return state

        var newState = state
        for (perspectivePlayerId in state.turnOrder) {
            val component = state.getEntity(perspectivePlayerId)
                ?.get<KnownInformationLedgerComponentV1>()
                ?: continue
            val retained = component.activeFacts.filterNot {
                it.subjectEntityId in invalidated && it.factKind == KnownInformationFactKind.IDENTITY
            }
            if (retained != component.activeFacts) {
                newState = putLedger(newState, perspectivePlayerId, component.copy(activeFacts = retained))
            }
        }
        return newState
    }

    /** Record exact library order known to one perspective at an authoritative reorder producer. */
    fun recordLibraryOrder(
        state: GameState,
        perspectivePlayerId: EntityId,
        orderedCardIds: Collection<EntityId>,
        libraryOwnerId: EntityId = perspectivePlayerId,
        audience: KnownInformationAudience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
        acquisitionReason: KnownInformationAcquisitionReason =
            KnownInformationAcquisitionReason.PRIVATE_LIBRARY_LOOK,
        objectIncarnationAlreadyAdvanced: Boolean = false,
    ): GameState {
        val orderedIds = orderedCardIds.distinct()
        var prepared = invalidateObjectFacts(
            invalidateLibraryPositions(state, libraryOwnerId),
            orderedIds,
        )
        if (!objectIncarnationAlreadyAdvanced) {
            prepared = prepared.reincarnateObjects(orderedIds)
        }
        return recordCards(
            state = prepared,
            cardIds = orderedIds,
            perspectivePlayerIds = listOf(perspectivePlayerId),
            audience = audience,
            acquisitionReason = acquisitionReason,
            includeLibraryPositions = true,
        )
    }

    /**
     * Mark an exact producer-owned order reacquisition for the current committed transition.
     * This is internal Rules transition metadata, not a learner-facing fact or a source-event
     * coordinate. The central post-pass consumes it so conservative membership invalidation cannot
     * erase positions that were explicitly re-established later in the same transition.
     */
    fun markLibraryOrderReacquired(state: GameState, libraryOwnerId: EntityId): GameState =
        state.copy(
            pendingLibraryOrderReacquisitionOwners =
                state.pendingLibraryOrderReacquisitionOwners + libraryOwnerId,
        )

    /**
     * Apply authoritative invalidation and epoch semantics after one Rules action result.
     *
     * The result already contains producer-side visibility writes (for example GatherCards and
     * MoveCollection). This pass removes facts whose object stamp is no longer current, invalidates
     * only library position/order on shuffle, adds final public-zone facts, upgrades newly acquired
     * library access facts when the committed search event is present, and increments each affected
     * perspective exactly once for the transition.
     */
    fun applyAfterAction(
        beforeState: GameState,
        result: ExecutionResult,
        cardRegistry: CardRegistry,
    ): ExecutionResult {
        if (result.error != null) return result

        val events = result.events
        var state = result.state
        val zoneChangedIds = events.filterIsInstance<ZoneChangeEvent>()
            .map(ZoneChangeEvent::entityId)
            .toSet()
        val shuffledLibraryOwners = events.filterIsInstance<LibraryShuffledEvent>()
            .map(LibraryShuffledEvent::playerId)
            .distinct()
        val libraryMembershipOwners = events.filterIsInstance<ZoneChangeEvent>()
            .filter { it.fromZone == Zone.LIBRARY || it.toZone == Zone.LIBRARY }
            .map(ZoneChangeEvent::ownerId)
            .distinct()
        val producerReacquiredLibraryOwners = state.pendingLibraryOrderReacquisitionOwners
        if (shuffledLibraryOwners.isNotEmpty()) {
            state = reincarnateKnownShuffleObjects(
                beforeState = beforeState,
                state = state,
                libraryOwners = shuffledLibraryOwners,
                cardRegistry = cardRegistry,
            )
        }
        val stampChangedIds = state.objectIdentityStamps.keys.filter { cardId ->
            beforeState.objectIdentityStamps[cardId] != state.objectIdentityStamps[cardId]
        }.toSet()
        if (stampChangedIds.isNotEmpty()) {
            state = rebaseKnownLibraryFacts(
                beforeState = beforeState,
                state = state,
                stampChangedIds = stampChangedIds - zoneChangedIds,
            )
        }
        val staleObjectIds = zoneChangedIds + stampChangedIds
        if (staleObjectIds.isNotEmpty()) {
            state = dropStaleObjectFacts(state, staleObjectIds)
        }

        for (ownerId in (shuffledLibraryOwners + libraryMembershipOwners).distinct()) {
            if (ownerId !in producerReacquiredLibraryOwners) {
                state = invalidateLibraryPositions(state, ownerId)
            }
        }
        state = state.copy(pendingLibraryOrderReacquisitionOwners = emptySet())

        for (event in events) {
            state = when (event) {
                is CardsRevealedEvent -> {
                    val ids = event.cardIds.filter { it !in zoneChangedIds }
                    recordCards(
                        state = state,
                        cardIds = ids,
                        perspectivePlayerIds = state.turnOrder,
                        audience = KnownInformationAudience.PUBLIC,
                        acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
                    )
                }

                is HandRevealedEvent -> {
                    val ids = event.cardIds.filter { it !in zoneChangedIds }
                    recordCards(
                        state = state,
                        cardIds = ids,
                        perspectivePlayerIds = state.turnOrder,
                        audience = KnownInformationAudience.PUBLIC,
                        acquisitionReason = KnownInformationAcquisitionReason.HAND_REVEAL,
                    )
                }

                is HandLookedAtEvent -> {
                    val ids = event.cardIds.filter { it !in zoneChangedIds }
                    recordCards(
                        state = state,
                        cardIds = ids,
                        perspectivePlayerIds = listOf(event.viewingPlayerId),
                        audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
                        acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_HAND_LOOK,
                    )
                }

                is LookedAtCardsEvent -> {
                    val ids = event.cardIds.filter { it !in zoneChangedIds }
                    recordCards(
                        state = state,
                        cardIds = ids,
                        perspectivePlayerIds = listOf(event.playerId),
                        audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
                        acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_LIBRARY_LOOK,
                    )
                }

                is TurnFaceUpEvent -> {
                    if (isPubliclyIdentityVisible(state, event.entityId, cardRegistry)) {
                        recordCards(
                            state = state,
                            cardIds = listOf(event.entityId),
                            perspectivePlayerIds = state.turnOrder,
                            audience = KnownInformationAudience.PUBLIC,
                            acquisitionReason = KnownInformationAcquisitionReason.PUBLIC_REVEAL,
                        )
                    } else {
                        state
                    }
                }

                is TurnedFaceDownEvent ->
                    invalidateIdentityForUnauthorizedPerspectives(state, event.entityId, cardRegistry)

                else -> state
            }
        }

        // A draw is an authoritative library-to-hand transition even when the Rules event batch
        // does not also contain a ZoneChangeEvent. Carry only facts that the perspective already
        // held for this exact old incarnation; never infer knowledge from the draw itself.
        state = recordKnownDrawContinuity(
            beforeState = beforeState,
            state = state,
            events = events,
        )

        // A zone change can preserve a fact for a perspective that could identify the old object
        // before the move, or can acquire a fact for a perspective that can identify the new object
        // afterward (for example, a draw into that player's hand). Use the existing visibility
        // authority at both boundaries. This records only the current destination and never a
        // hidden position; the new object stamp remains an internal witness rather than an alias.
        state = recordVisibleZoneChangeKnowledge(
            beforeState = beforeState,
            state = state,
            zoneChangedIds = zoneChangedIds,
            cardRegistry = cardRegistry,
        )
        state = recordCurrentlyVisibleLibraryCards(state, cardRegistry)
        state = recordCurrentlyAuthorizedNonPublicIdentities(state, cardRegistry)

        state = upgradeNewSearchFacts(beforeState, state, events)
        state = finalizeEpochs(beforeState, state)
        return if (state === result.state) result else result.copy(state = state)
    }

    private fun recordKnownDrawContinuity(
        beforeState: GameState,
        state: GameState,
        events: List<GameEvent>,
    ): GameState {
        val drawnCards = events
            .filterIsInstance<CardsDrawnEvent>()
            .flatMap { event ->
                event.cardIds.filter { cardId ->
                    cardId in beforeState.getLibrary(event.playerId) &&
                        cardId in state.getHand(event.playerId)
                }
            }
            .distinct()
        if (drawnCards.isEmpty()) return state

        var newState = state
        for (cardId in drawnCards) {
            val beforeStamp = beforeState.objectIdentityStamps[cardId] ?: continue
            val afterStamp = state.objectIdentityStamps[cardId] ?: continue
            if (beforeStamp == afterStamp) continue

            for (perspectivePlayerId in beforeState.turnOrder) {
                val previousFacts = KnownInformationLedger
                    .forPlayer(beforeState, perspectivePlayerId)
                    .activeFacts
                    .filter {
                        it.subjectEntityId == cardId &&
                            it.objectIdentityStamp == beforeStamp
                    }
                val previousIdentity = previousFacts.firstOrNull {
                    it.factKind == KnownInformationFactKind.IDENTITY &&
                        it.knownZone == Zone.LIBRARY
                } ?: continue
                val previousZone = previousFacts.firstOrNull {
                    it.factKind == KnownInformationFactKind.ZONE_MEMBERSHIP &&
                        it.knownZone == Zone.LIBRARY
                } ?: continue
                val currentLedger = forPlayer(newState, perspectivePlayerId)
                val acquiredAtEpoch = currentLedger.knowledgeEpoch + 1L
                val transferredIdentity = previousIdentity.copy(
                    objectIdentityStamp = afterStamp,
                    knownZone = Zone.HAND,
                    knownPosition = null,
                    acquisitionReason = KnownInformationAcquisitionReason.VISIBLE_ZONE_TRANSITION,
                    acquiredAtEpoch = acquiredAtEpoch,
                )
                val transferredZone = previousZone.copy(
                    objectIdentityStamp = afterStamp,
                    knownZone = Zone.HAND,
                    knownPosition = null,
                    acquisitionReason = KnownInformationAcquisitionReason.VISIBLE_ZONE_TRANSITION,
                    acquiredAtEpoch = acquiredAtEpoch,
                )
                val transferred = currentLedger
                    .withFact(transferredIdentity)
                    .withFact(transferredZone)
                newState = if (transferred == currentLedger) {
                    newState
                } else {
                    putLedger(newState, perspectivePlayerId, transferred)
                }
            }
        }
        return newState
    }

    private fun factsFor(
        cardId: EntityId,
        stamp: Long,
        card: CardComponent,
        location: Location,
        audience: KnownInformationAudience,
        acquisitionReason: KnownInformationAcquisitionReason,
        acquiredAtEpoch: Long,
        includeLibraryPosition: Boolean,
    ): List<KnownInformationFactV1> = buildList {
        add(
            KnownInformationFactV1(
                subjectEntityId = cardId,
                objectIdentityStamp = stamp,
                factKind = KnownInformationFactKind.IDENTITY,
                cardDefinitionId = card.cardDefinitionId,
                knownZone = location.zone,
                audience = audience,
                acquisitionReason = acquisitionReason,
                acquiredAtEpoch = acquiredAtEpoch,
            )
        )
        add(
            KnownInformationFactV1(
                subjectEntityId = cardId,
                objectIdentityStamp = stamp,
                factKind = KnownInformationFactKind.ZONE_MEMBERSHIP,
                knownZone = location.zone,
                audience = audience,
                acquisitionReason = acquisitionReason,
                acquiredAtEpoch = acquiredAtEpoch,
            )
        )
        if (includeLibraryPosition && location.zone == Zone.LIBRARY && location.position != null) {
            add(
                KnownInformationFactV1(
                    subjectEntityId = cardId,
                    objectIdentityStamp = stamp,
                    factKind = KnownInformationFactKind.POSITION_OR_ORDER,
                    knownZone = Zone.LIBRARY,
                    knownPosition = location.position,
                    audience = audience,
                    acquisitionReason = acquisitionReason,
                    acquiredAtEpoch = acquiredAtEpoch,
                )
            )
        }
    }

    private fun KnownInformationLedgerComponentV1.withFact(
        candidate: KnownInformationFactV1,
    ): KnownInformationLedgerComponentV1 {
        val existingIndex = activeFacts.indexOfFirst { it.sameFactKeyAs(candidate) }
        if (existingIndex < 0) {
            return copy(
                activeFacts = (activeFacts + candidate)
                    .sortedWith(KnownInformationLedgerOrdering.comparator)
            )
        }

        val existing = activeFacts[existingIndex]
        val merged = existing.copy(
            cardDefinitionId = candidate.cardDefinitionId ?: existing.cardDefinitionId,
            knownZone = candidate.knownZone ?: existing.knownZone,
            knownPosition = candidate.knownPosition ?: existing.knownPosition,
            audience = mergeAudience(existing.audience, candidate.audience),
            acquisitionReason = preferredReason(existing.acquisitionReason, candidate.acquisitionReason),
        )
        return copy(activeFacts = activeFacts.toMutableList().also { it[existingIndex] = merged }
            .sortedWith(KnownInformationLedgerOrdering.comparator))
    }

    private fun dropStaleObjectFacts(
        state: GameState,
        changedIds: Set<EntityId>,
    ): GameState {
        var newState = state
        for (perspectivePlayerId in state.turnOrder) {
            val component = state.getEntity(perspectivePlayerId)
                ?.get<KnownInformationLedgerComponentV1>()
                ?: continue
            val retained = component.activeFacts.filter { fact ->
                if (fact.subjectEntityId !in changedIds) return@filter true
                val currentStamp = state.objectIdentityStamps[fact.subjectEntityId]
                state.hasEntity(fact.subjectEntityId) &&
                    locate(state, fact.subjectEntityId) != null &&
                    currentStamp == fact.objectIdentityStamp
            }
            if (retained != component.activeFacts) {
                newState = putLedger(newState, perspectivePlayerId, component.copy(activeFacts = retained))
            }
        }
        return newState
    }

    private fun rebaseKnownLibraryFacts(
        beforeState: GameState,
        state: GameState,
        stampChangedIds: Set<EntityId>,
    ): GameState {
        if (stampChangedIds.isEmpty()) return state
        var newState = state
        for (perspectivePlayerId in beforeState.turnOrder) {
            val before = beforeState.getEntity(perspectivePlayerId)
                ?.get<KnownInformationLedgerComponentV1>()
            val currentBeforeRebase = forPlayer(state, perspectivePlayerId)
            val sourceFacts = (before?.activeFacts.orEmpty() + currentBeforeRebase.activeFacts)
                .distinctBy { FactKey(it.subjectEntityId, it.objectIdentityStamp, it.factKind) }
            val rebased = sourceFacts.filter { fact ->
                fact.subjectEntityId in stampChangedIds &&
                    fact.knownZone == Zone.LIBRARY &&
                    fact.factKind != KnownInformationFactKind.POSITION_OR_ORDER &&
                    locate(state, fact.subjectEntityId)?.zone == Zone.LIBRARY
            }.mapNotNull { fact ->
                val currentStamp = state.objectIdentityStamps[fact.subjectEntityId]
                    ?: return@mapNotNull null
                fact.copy(
                    objectIdentityStamp = currentStamp,
                    knownZone = Zone.LIBRARY,
                    knownPosition = null,
                )
            }
            if (rebased.isEmpty()) continue
            val currentLedger = forPlayer(newState, perspectivePlayerId)
            val merged = (currentLedger.activeFacts + rebased)
                .distinctBy { FactKey(it.subjectEntityId, it.objectIdentityStamp, it.factKind) }
                .sortedWith(KnownInformationLedgerOrdering.comparator)
            if (merged != currentLedger.activeFacts) {
                newState = putLedger(newState, perspectivePlayerId, currentLedger.copy(activeFacts = merged))
            }
        }
        return newState
    }

    private fun invalidateObjectFacts(
        state: GameState,
        objectIds: Collection<EntityId>,
    ): GameState {
        val invalidated = objectIds.toSet()
        if (invalidated.isEmpty()) return state
        var newState = state
        for (perspectivePlayerId in state.turnOrder) {
            val component = state.getEntity(perspectivePlayerId)
                ?.get<KnownInformationLedgerComponentV1>()
                ?: continue
            val retained = component.activeFacts.filterNot { it.subjectEntityId in invalidated }
            if (retained != component.activeFacts) {
                newState = putLedger(newState, perspectivePlayerId, component.copy(activeFacts = retained))
            }
        }
        return newState
    }

    private fun invalidateLibraryPositions(state: GameState, ownerId: EntityId): GameState {
        val currentLibrary = state.getZone(ZoneKey(ownerId, Zone.LIBRARY)).toSet()
        if (currentLibrary.isEmpty()) return state
        var newState = state
        for (perspectivePlayerId in state.turnOrder) {
            val component = state.getEntity(perspectivePlayerId)
                ?.get<KnownInformationLedgerComponentV1>()
                ?: continue
            val retained = component.activeFacts.filterNot { fact ->
                fact.factKind == KnownInformationFactKind.POSITION_OR_ORDER &&
                    fact.knownZone == Zone.LIBRARY &&
                    fact.subjectEntityId in currentLibrary
            }
            if (retained != component.activeFacts) {
                newState = putLedger(newState, perspectivePlayerId, component.copy(activeFacts = retained))
            }
        }
        return newState
    }

    private fun upgradeNewSearchFacts(
        beforeState: GameState,
        state: GameState,
        events: List<GameEvent>,
    ): GameState {
        val searchers = events.filterIsInstance<LibrarySearchedEvent>()
            .map(LibrarySearchedEvent::playerId)
            .distinct()
        if (searchers.isEmpty()) return state

        var newState = state
        for (searcher in searchers) {
            val beforeKeys = forPlayer(beforeState, searcher).activeFacts.map { it.factKey() }.toSet()
            val component = forPlayer(newState, searcher)
            val upgraded = component.activeFacts.map { fact ->
                if (
                    fact.factKey() !in beforeKeys &&
                    fact.audience == KnownInformationAudience.PERSPECTIVE_PRIVATE &&
                    fact.acquisitionReason == KnownInformationAcquisitionReason.PRIVATE_LIBRARY_LOOK
                ) {
                    fact.copy(acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_SEARCH)
                } else {
                    fact
                }
            }.sortedWith(KnownInformationLedgerOrdering.comparator)
            if (upgraded != component.activeFacts) {
                newState = putLedger(newState, searcher, component.copy(activeFacts = upgraded))
            }
        }
        return newState
    }

    private fun finalizeEpochs(beforeState: GameState, state: GameState): GameState {
        var newState = state
        for (perspectivePlayerId in state.turnOrder) {
            val before = beforeState.getEntity(perspectivePlayerId)
                ?.get<KnownInformationLedgerComponentV1>()
            val after = state.getEntity(perspectivePlayerId)
                ?.get<KnownInformationLedgerComponentV1>()
            if (before == null && after == null) continue

            val beforeSemantics = before?.activeFacts?.map { it.semanticKey() }?.toSet() ?: emptySet()
            val afterSemantics = after?.activeFacts?.map { it.semanticKey() }?.toSet() ?: emptySet()
            val changed = beforeSemantics != afterSemantics
            val beforeEpoch = before?.knowledgeEpoch ?: 0L
            val desiredEpoch = if (changed) beforeEpoch + 1L else beforeEpoch
            val current = after ?: KnownInformationLedgerComponentV1(
                knowledgeEpoch = desiredEpoch,
            )
            val normalized = current.copy(
                knowledgeEpoch = desiredEpoch,
                activeFacts = current.activeFacts.sortedWith(KnownInformationLedgerOrdering.comparator),
            )
            if (normalized != after) {
                newState = putLedger(newState, perspectivePlayerId, normalized)
            }
        }
        return newState
    }

    private fun recordVisibleZoneChangeKnowledge(
        beforeState: GameState,
        state: GameState,
        zoneChangedIds: Set<EntityId>,
        cardRegistry: CardRegistry,
    ): GameState {
        if (zoneChangedIds.isEmpty()) return state
        val visibility = Visibility(cardRegistry)
        var newState = state
        for (cardId in zoneChangedIds) {
            val beforeVisible = beforeState.turnOrder.filter { perspectivePlayerId ->
                visibility.isEntityIdentityVisibleTo(beforeState, cardId, perspectivePlayerId)
            }
            val afterVisible = state.turnOrder.filter { perspectivePlayerId ->
                visibility.isEntityIdentityVisibleTo(state, cardId, perspectivePlayerId)
            }
            val authorizedPerspectives = (beforeVisible + afterVisible).distinct()
            if (authorizedPerspectives.isEmpty()) continue

            val wasPublic = beforeState.turnOrder.all { it in beforeVisible }
            val isPublic = state.turnOrder.all { it in afterVisible }
            for (perspectivePlayerId in authorizedPerspectives) {
                newState = recordCards(
                    state = newState,
                    cardIds = listOf(cardId),
                    perspectivePlayerIds = listOf(perspectivePlayerId),
                    audience = if (wasPublic || isPublic) {
                        KnownInformationAudience.PUBLIC
                    } else {
                        KnownInformationAudience.PERSPECTIVE_PRIVATE
                    },
                    acquisitionReason = KnownInformationAcquisitionReason.VISIBLE_ZONE_TRANSITION,
                )
            }
        }
        return newState
    }

    private fun reincarnateKnownShuffleObjects(
        beforeState: GameState,
        state: GameState,
        libraryOwners: Collection<EntityId>,
        cardRegistry: CardRegistry,
    ): GameState {
        val visibility = Visibility(cardRegistry)
        val objects = linkedSetOf<EntityId>()
        for (ownerId in libraryOwners) {
            val library = beforeState.getZone(ZoneKey(ownerId, Zone.LIBRARY)).toSet()
            val top = beforeState.getLibrary(ownerId).firstOrNull()
            if (top != null && beforeState.turnOrder.any {
                    visibility.isEntityIdentityVisibleTo(beforeState, top, it)
                }) {
                objects += top
            }
            for (perspectivePlayerId in beforeState.turnOrder) {
                objects += forPlayer(beforeState, perspectivePlayerId).activeFacts
                    .filter {
                        it.factKind == KnownInformationFactKind.POSITION_OR_ORDER &&
                            it.knownZone == Zone.LIBRARY &&
                            it.subjectEntityId in library
                    }
                    .map { it.subjectEntityId }
            }
            objects += library.filter { cardId ->
                beforeState.turnOrder.any { perspectivePlayerId ->
                    visibility.isCardRevealedTo(beforeState, cardId, perspectivePlayerId)
                }
            }
        }

        val eligible = objects.filter { cardId ->
            beforeState.objectIdentityStamps[cardId] != null &&
                state.objectIdentityStamps[cardId] == beforeState.objectIdentityStamps[cardId]
        }
        return state.reincarnateObjects(eligible)
    }

    private fun recordCurrentlyVisibleLibraryCards(
        state: GameState,
        cardRegistry: CardRegistry,
    ): GameState {
        val visibility = Visibility(cardRegistry)
        var newState = state
        for (ownerId in state.turnOrder) {
            val topCardId = state.getLibrary(ownerId).firstOrNull() ?: continue
            val visiblePerspectives = state.turnOrder.filter { perspectivePlayerId ->
                visibility.isEntityIdentityVisibleTo(state, topCardId, perspectivePlayerId)
            }
            if (visiblePerspectives.isEmpty()) continue
            val audience = if (visiblePerspectives.size == state.turnOrder.size) {
                KnownInformationAudience.PUBLIC
            } else {
                KnownInformationAudience.PERSPECTIVE_PRIVATE
            }
            for (perspectivePlayerId in visiblePerspectives) {
                newState = recordCards(
                    state = newState,
                    cardIds = listOf(topCardId),
                    perspectivePlayerIds = listOf(perspectivePlayerId),
                    audience = audience,
                    acquisitionReason = KnownInformationAcquisitionReason.CONTINUOUS_IDENTITY_VISIBILITY,
                    includeLibraryPositions = true,
                )
            }
        }
        return newState
    }

    private fun recordCurrentlyAuthorizedNonPublicIdentities(
        state: GameState,
        cardRegistry: CardRegistry,
    ): GameState {
        val visibility = Visibility(cardRegistry)
        val candidates = linkedMapOf<EntityId, EntityId?>()
        for (ownerId in state.turnOrder) {
            state.getHand(ownerId).forEach { cardId -> candidates.putIfAbsent(cardId, ownerId) }
            state.getExile(ownerId).filter { cardId ->
                state.getEntity(cardId)?.has<com.wingedsheep.engine.state.components.identity.FaceDownComponent>() == true
            }.forEach { cardId -> candidates.putIfAbsent(cardId, ownerId) }
            state.getBattlefield(ownerId).filter { cardId ->
                state.getEntity(cardId)?.has<com.wingedsheep.engine.state.components.identity.FaceDownComponent>() == true
            }.forEach { cardId ->
                candidates.putIfAbsent(
                    cardId,
                    state.projectedState.getController(cardId)
                        ?: state.getEntity(cardId)?.get<CardComponent>()?.ownerId,
                )
            }
        }
        state.stack.filter { cardId ->
            state.getEntity(cardId)?.has<com.wingedsheep.engine.state.components.identity.FaceDownComponent>() == true ||
                state.getEntity(cardId)?.get<com.wingedsheep.engine.state.components.stack.SpellOnStackComponent>()
                    ?.castFaceDown == true
        }.forEach { cardId ->
            candidates.putIfAbsent(
                cardId,
                state.getEntity(cardId)?.get<com.wingedsheep.engine.state.components.stack.SpellOnStackComponent>()
                    ?.casterId,
            )
        }

        var newState = state
        for ((cardId, naturallyAuthorizedPlayerId) in candidates) {
            val visiblePerspectives = state.turnOrder.filter { perspectivePlayerId ->
                perspectivePlayerId != naturallyAuthorizedPlayerId &&
                visibility.isEntityIdentityVisibleTo(state, cardId, perspectivePlayerId)
            }
            if (visiblePerspectives.isEmpty()) continue
            val audience = if (visiblePerspectives.size == state.turnOrder.size) {
                KnownInformationAudience.PUBLIC
            } else {
                KnownInformationAudience.PERSPECTIVE_PRIVATE
            }
            for (perspectivePlayerId in visiblePerspectives) {
                newState = recordCards(
                    state = newState,
                    cardIds = listOf(cardId),
                    perspectivePlayerIds = listOf(perspectivePlayerId),
                    audience = audience,
                    acquisitionReason = KnownInformationAcquisitionReason.CONTINUOUS_IDENTITY_VISIBILITY,
                )
            }
        }
        return newState
    }

    /**
     * Turning a public object face down can make its printed identity unavailable to a perspective
     * without changing its zone or incarnation. Keep public zone membership, but remove identity
     * facts for perspectives that the existing [Visibility] authority no longer authorizes. A
     * perspective-specific reveal/look marker is deliberately honored by that same authority; the
     * ledger does not invent a second face-down visibility rule.
     */
    private fun invalidateIdentityForUnauthorizedPerspectives(
        state: GameState,
        entityId: EntityId,
        cardRegistry: CardRegistry,
    ): GameState {
        val visibility = Visibility(cardRegistry)
        var newState = state
        for (perspectivePlayerId in state.turnOrder) {
            if (visibility.isEntityIdentityVisibleTo(state, entityId, perspectivePlayerId)) continue
            val component = state.getEntity(perspectivePlayerId)
                ?.get<KnownInformationLedgerComponentV1>()
                ?: continue
            val retained = component.activeFacts.filterNot {
                it.subjectEntityId == entityId && it.factKind == KnownInformationFactKind.IDENTITY
            }
            if (retained != component.activeFacts) {
                newState = putLedger(newState, perspectivePlayerId, component.copy(activeFacts = retained))
            }
        }
        return newState
    }

    private fun isPubliclyIdentityVisible(
        state: GameState,
        entityId: EntityId,
        cardRegistry: CardRegistry,
    ): Boolean {
        val location = locate(state, entityId) ?: return false
        if (location.zone !in PUBLIC_ZONES) return false
        val visibility = Visibility(cardRegistry)
        return state.turnOrder.all { perspectivePlayerId ->
            visibility.isEntityIdentityVisibleTo(state, entityId, perspectivePlayerId)
        }
    }

    private fun putLedger(
        state: GameState,
        perspectivePlayerId: EntityId,
        component: KnownInformationLedgerComponentV1,
    ): GameState {
        require(perspectivePlayerId in state.turnOrder) {
            "Known-information ledger perspective is not in the game roster: $perspectivePlayerId"
        }
        return state.updateEntity(perspectivePlayerId) { container -> container.with(component) }
    }

    private fun locate(state: GameState, entityId: EntityId): Location? {
        val zoneEntry = state.zones.entries
            .sortedWith(compareBy({ it.key.ownerId.value }, { it.key.zoneType.ordinal }))
            .firstOrNull { (_, ids) -> entityId in ids }
        if (zoneEntry != null) {
            return Location(
                zone = zoneEntry.key.zoneType,
                position = if (zoneEntry.key.zoneType == Zone.LIBRARY) {
                    zoneEntry.value.indexOf(entityId)
                } else {
                    null
                },
            )
        }
        val stackPosition = state.stack.indexOf(entityId)
        return if (stackPosition >= 0) Location(Zone.STACK, stackPosition) else null
    }

    private data class Location(
        val zone: Zone,
        val position: Int?,
    )

    private data class FactKey(
        val subjectEntityId: EntityId,
        val objectIdentityStamp: Long,
        val factKind: KnownInformationFactKind,
    )

    private data class FactSemanticKey(
        val factKey: FactKey,
        val cardDefinitionId: String?,
        val knownZone: Zone?,
        val knownPosition: Int?,
    )

    private fun KnownInformationFactV1.factKey(): FactKey = FactKey(
        subjectEntityId,
        objectIdentityStamp,
        factKind,
    )

    private fun KnownInformationFactV1.sameFactKeyAs(other: KnownInformationFactV1): Boolean =
        factKey() == other.factKey()

    private fun KnownInformationFactV1.semanticKey(): FactSemanticKey = FactSemanticKey(
        factKey = factKey(),
        cardDefinitionId = cardDefinitionId,
        knownZone = knownZone,
        knownPosition = knownPosition,
    )

    private fun mergeAudience(
        existing: KnownInformationAudience,
        candidate: KnownInformationAudience,
    ): KnownInformationAudience = if (
        existing == KnownInformationAudience.PUBLIC || candidate == KnownInformationAudience.PUBLIC
    ) {
        KnownInformationAudience.PUBLIC
    } else {
        KnownInformationAudience.PERSPECTIVE_PRIVATE
    }

    private fun preferredReason(
        existing: KnownInformationAcquisitionReason,
        candidate: KnownInformationAcquisitionReason,
    ): KnownInformationAcquisitionReason = when {
        candidate == KnownInformationAcquisitionReason.PUBLIC_REVEAL -> candidate
        candidate == KnownInformationAcquisitionReason.HAND_REVEAL -> candidate
        candidate == KnownInformationAcquisitionReason.VISIBLE_ZONE_TRANSITION -> candidate
        existing == KnownInformationAcquisitionReason.PUBLIC_REVEAL -> existing
        existing == KnownInformationAcquisitionReason.HAND_REVEAL -> existing
        existing == KnownInformationAcquisitionReason.VISIBLE_ZONE_TRANSITION -> existing
        else -> existing
    }
}

package com.wingedsheep.engine.mechanics

import com.wingedsheep.engine.core.CardsRevealedEvent
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

    /** Record exact library order known to one perspective at an authoritative reorder producer. */
    fun recordLibraryOrder(
        state: GameState,
        perspectivePlayerId: EntityId,
        orderedCardIds: Collection<EntityId>,
        libraryOwnerId: EntityId = perspectivePlayerId,
        audience: KnownInformationAudience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
        acquisitionReason: KnownInformationAcquisitionReason =
            KnownInformationAcquisitionReason.PRIVATE_LIBRARY_LOOK,
    ): GameState = recordCards(
        state = invalidateLibraryPositions(state, libraryOwnerId),
        cardIds = orderedCardIds,
        perspectivePlayerIds = listOf(perspectivePlayerId),
        audience = audience,
        acquisitionReason = acquisitionReason,
        includeLibraryPositions = true,
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
        if (zoneChangedIds.isNotEmpty()) {
            state = dropStaleObjectFacts(state, zoneChangedIds)
        }

        val shuffledLibraryOwners = events.filterIsInstance<LibraryShuffledEvent>()
            .map(LibraryShuffledEvent::playerId)
            .distinct()
        for (ownerId in shuffledLibraryOwners) {
            state = invalidateLibraryPositions(state, ownerId)
        }

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

        state = upgradeNewSearchFacts(beforeState, state, events)
        state = finalizeEpochs(beforeState, state)
        return if (state === result.state) result else result.copy(state = state)
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

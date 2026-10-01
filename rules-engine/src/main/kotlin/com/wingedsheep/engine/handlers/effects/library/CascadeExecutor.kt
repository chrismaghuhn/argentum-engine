package com.wingedsheep.engine.handlers.effects.library

import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.CascadeMayCastContinuation
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.GameEvent as EngineGameEvent
import com.wingedsheep.engine.handlers.DecisionHandler
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.handlers.effects.LibraryPlacement
import com.wingedsheep.engine.handlers.effects.ZoneEntryOptions
import com.wingedsheep.engine.handlers.effects.ZoneMovementUtils
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CascadeEffect
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.ZonePlacement
import com.wingedsheep.sdk.scripting.references.Player
import kotlin.reflect.KClass
import com.wingedsheep.engine.core.Outcome

/**
 * Executor for [CascadeEffect] (CR 702.85).
 *
 * The cascade trigger fires with the triggering spell pointed at by
 * [EffectContext.triggeringEntityId] — either a spell with cascade or, as on
 * Wildsear, Scouring Maw, a spell that was granted cascade. The executor reads
 * that spell's mana value to derive the threshold and walks the controller's
 * library top-down, exiling cards until a nonland card with mana value strictly
 * less than the threshold is exiled (the "cascade card") or the library is
 * exhausted.
 *
 * The "may cast / bottom remainder" flow then follows CR 702.85a:
 *
 *  - If a cascade card was found, pause with a yes/no decision and push a
 *    [com.wingedsheep.engine.core.CascadeMayCastContinuation]; the resumer in
 *    [com.wingedsheep.engine.handlers.continuations.LibraryAndZoneContinuationResumer]
 *    casts the card for free on yes and bottom-randomizes every uncast exiled
 *    card afterwards.
 *  - If no qualifying card was found, every exiled card is bottom-randomized
 *    here and no spell is offered.
 */
class CascadeExecutor(
    private val zones: ZoneTransitionService,
    private val decisionHandler: DecisionHandler = DecisionHandler()
) : EffectExecutor<CascadeEffect> {

    override val effectType: KClass<CascadeEffect> = CascadeEffect::class

    override fun execute(
        state: GameState,
        effect: CascadeEffect,
        context: EffectContext
    ): EffectResult {
        val controllerId = context.controllerId
        val triggeringSpellId = context.triggeringEntityId
            ?: return EffectResult.success(state)

        val threshold = state.getEntity(triggeringSpellId)
            ?.get<CardComponent>()?.manaValue
            ?: return EffectResult.success(state)

        var currentState = state
        val allEvents = mutableListOf<EngineGameEvent>()
        val exiledCards = mutableListOf<EntityId>()
        var cascadeCard: EntityId? = null

        val library = currentState.getZone(ZoneKey(controllerId, Zone.LIBRARY))
        for (cardId in library) {
            exiledCards.add(cardId)
            val card = currentState.getEntity(cardId)?.get<CardComponent>()
            if (card != null && !card.typeLine.isLand && card.manaValue < threshold) {
                cascadeCard = cardId
                break
            }
        }

        if (exiledCards.isEmpty()) {
            return EffectResult.success(currentState)
        }

        val sourceName = context.sourceId?.let {
            currentState.getEntity(it)?.get<CardComponent>()?.name
        }
        allEvents.add(
            CardsRevealedEvent(
                revealingPlayerId = controllerId,
                cardIds = exiledCards.toList(),
                cardNames = exiledCards.map { id ->
                    currentState.getEntity(id)?.get<CardComponent>()?.name ?: "Unknown"
                },
                imageUris = exiledCards.map { id ->
                    currentState.getEntity(id)?.get<CardComponent>()?.imageUri
                },
                source = sourceName
            )
        )

        for (cardId in exiledCards) {
            val result = ZoneMovementUtils.moveCardToZone(zones, currentState, cardId, Zone.EXILE)
            if (result.outcome is Outcome.Done) {
                currentState = result.state
                allEvents.addAll(result.events)
            }
        }

        if (cascadeCard == null) {
            // Library exhausted without a qualifying card — bottom-randomize everything
            // exiled this way and finish, no may-cast offered.
            val bottomResult = bottomRandomizeWithReplacements(
                zones = zones,
                state = currentState,
                playerId = controllerId,
                cards = exiledCards,
                context = context,
            )
            if (bottomResult.outcome is Outcome.Paused) {
                return EffectResult.propagatePause(
                    bottomResult.state,
                    allEvents + bottomResult.events,
                    bottomResult.diagnostics,
                )
            }
            if (bottomResult.outcome is Outcome.Rejected) return bottomResult
            return EffectResult.success(
                bottomResult.state,
                allEvents + bottomResult.events,
                bottomResult.diagnostics,
            )
        }

        val cascadeName = currentState.getEntity(cascadeCard)
            ?.get<CardComponent>()?.name ?: "the exiled card"
        val continuation = CascadeMayCastContinuation(
            playerId = controllerId,
            sourceId = context.sourceId,
            objectReferences = context.objectReferences,
            exiledCards = exiledCards.toList(),
            cascadeCardId = cascadeCard
        )

        val pause = decisionHandler.createYesNoDecision(
            state = currentState,
            playerId = controllerId,
            sourceId = context.sourceId,
            sourceName = sourceName,
            prompt = "Cast $cascadeName without paying its mana cost?",
            yesText = "Cast for free",
            noText = "Decline",
            phase = DecisionPhase.RESOLUTION,
            answer = continuation
        )

        val stateWithCont = pause.state

        return EffectResult.propagatePause(
            stateWithCont,
            allEvents + pause.events
        )
    }

    companion object {
        /**
         * Bottom-randomize a cascade remainder through MoveCollectionExecutor when a Commander
         * is among the cards. That executor owns the serializable per-card pending-zone adapter;
         * the old synchronous helper remains for discover's non-pausing legacy path.
         */
        fun bottomRandomizeWithReplacements(
            zones: ZoneTransitionService,
            state: GameState,
            playerId: EntityId,
            cards: List<EntityId>,
            context: EffectContext,
        ): EffectResult {
            val hasCommander = state.format.usesCommanders && cards.any { cardId ->
                state.getEntity(cardId)?.has<com.wingedsheep.engine.state.components.identity.CommanderComponent>() == true
            }
            if (!hasCommander) {
                var updatedState = state
                val events = bottomRandomize(zones, state, playerId, cards) { updatedState = it }
                return EffectResult.success(updatedState, events)
            }

            val (shuffledCards, shuffledState) = state.nextRandom { shuffle(cards) }
            val destination = CardDestination.ToZone(
                zone = Zone.LIBRARY,
                player = Player.You,
                placement = ZonePlacement.Bottom,
            )
            return MoveCollectionExecutor(zones, zones.cardRegistry).moveCardsToZone(
                state = shuffledState,
                context = context,
                cards = shuffledCards,
                destination = destination,
                destPlayerId = playerId,
            )
        }

        /**
         * Move [cards] (each currently in exile) to the bottom of [playerId]'s library
         * in a random order. Returns the emitted zone-change events; the caller wires
         * the updated state through [updateState] so we can keep [currentState] mutable
         * outside the helper.
         */
        fun bottomRandomize(
            zones: ZoneTransitionService,
            state: GameState,
            playerId: EntityId,
            cards: List<EntityId>,
            updateState: (GameState) -> Unit
        ): List<EngineGameEvent> {
            val events = mutableListOf<EngineGameEvent>()
            var current = state
            val (shuffledCards, advanced) = current.nextRandom { shuffle(cards) }
            current = advanced
            for (cardId in shuffledCards) {
                val result = zones.moveToZone(
                    state = current,
                    entityId = cardId,
                    destinationZone = Zone.LIBRARY,
                    options = ZoneEntryOptions(
                        controllerId = playerId,
                        libraryPlacement = LibraryPlacement.Bottom
                    )
                )
                current = result.state
                events.addAll(result.events)
            }
            updateState(current)
            return events
        }
    }
}

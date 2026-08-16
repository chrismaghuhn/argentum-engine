package com.wingedsheep.engine.handlers.effects.library

import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.ExileFromTopRepeatingContinuation
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.GameEvent as EngineGameEvent
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.handlers.effects.TargetResolutionUtils
import com.wingedsheep.engine.handlers.effects.DamageUtils
import com.wingedsheep.engine.handlers.effects.ZoneMovementUtils
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.handlers.effects.ZoneEntryOptions
import com.wingedsheep.engine.replacement.PendingGameEvent
import com.wingedsheep.engine.handlers.effects.ReplacementEffectUtils
import com.wingedsheep.engine.handlers.effects.BattlefieldFilterUtils
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.ExileFromTopRepeatingEffect
import kotlin.reflect.KClass

/**
 * Executor for [ExileFromTopRepeatingEffect].
 *
 * Implements the Demonlord Belzenlok-style repeating exile mechanic:
 * 1. Exile cards from top of library until a card matching the filter is found
 * 2. Put that card into the player's hand
 * 3. If the card's mana value >= threshold, repeat from step 1
 * 4. After the process ends, the source deals damage to the controller equal to
 *    the number of cards put into their hand (all at once, per rulings).
 *
 * Edge cases:
 * - Empty library during iteration: process stops, damage is dealt for cards already put in hand
 * - No nonland card found: process stops (no card put in hand for that iteration)
 */
class ExileFromTopRepeatingExecutor : EffectExecutor<ExileFromTopRepeatingEffect> {

    override val effectType: KClass<ExileFromTopRepeatingEffect> = ExileFromTopRepeatingEffect::class

    private val predicateEvaluator = PredicateEvaluator()

    override fun execute(
        state: GameState,
        effect: ExileFromTopRepeatingEffect,
        context: EffectContext
    ): EffectResult = executeFrom(state, effect, context, initialCardsToHand = 0, shouldContinue = true)

    /** Continue the loop after a pending hand-boundary move has physically completed. */
    fun resumeAfterMatch(
        state: GameState,
        continuation: ExileFromTopRepeatingContinuation,
    ): EffectResult {
        val ownerId = state.getEntity(continuation.matchCardId)
            ?.get<com.wingedsheep.engine.state.components.identity.OwnerComponent>()?.playerId
            ?: continuation.context.controllerId
        val arrivedInHand = state.getZone(ZoneKey(ownerId, Zone.HAND)).contains(continuation.matchCardId)
        return executeFrom(
            state = state,
            effect = continuation.effect,
            context = continuation.context,
            initialCardsToHand = continuation.cardsToHand + if (arrivedInHand) 1 else 0,
            shouldContinue = continuation.repeatAfterMatch,
        )
    }

    private fun executeFrom(
        state: GameState,
        effect: ExileFromTopRepeatingEffect,
        context: EffectContext,
        initialCardsToHand: Int,
        shouldContinue: Boolean,
    ): EffectResult {
        val controllerId = context.controllerId
        val sourceId = context.sourceId

        val predicateContext = PredicateContext.fromEffectContext(context)
        var currentState = state
        val allEvents = mutableListOf<EngineGameEvent>()
        var cardsToHand = initialCardsToHand

        // Repeat loop: exile until match, put in hand, repeat if MV >= threshold
        var continueProcess = shouldContinue
        while (continueProcess) {
            val libraryZone = ZoneKey(controllerId, Zone.LIBRARY)
            val library = currentState.getZone(libraryZone)

            if (library.isEmpty()) break

            val allExiled = mutableListOf<EntityId>()
            var matchCard: EntityId? = null

            // Exile cards from top until we find one matching the filter
            for (cardId in library) {
                allExiled.add(cardId)

                if (predicateEvaluator.matches(currentState, currentState.projectedState, cardId, effect.matchFilter, predicateContext)) {
                    matchCard = cardId
                    break
                }
            }

            // Emit reveal event for all exiled cards
            if (allExiled.isNotEmpty()) {
                val cardNames = allExiled.map { cardId ->
                    currentState.getEntity(cardId)?.get<CardComponent>()?.name ?: "Unknown"
                }
                val imageUris = allExiled.map { cardId ->
                    currentState.getEntity(cardId)?.get<CardComponent>()?.imageUri
                }
                val sourceName = sourceId?.let { currentState.getEntity(it)?.get<CardComponent>()?.name }

                allEvents.add(
                    CardsRevealedEvent(
                        revealingPlayerId = controllerId,
                        cardIds = allExiled.toList(),
                        cardNames = cardNames,
                        imageUris = imageUris,
                        source = sourceName
                    )
                )
            }

            // Exile all non-match cards (lands that were passed over)
            val cardsToExile = if (matchCard != null) {
                allExiled.filter { it != matchCard }
            } else {
                allExiled
            }

            for (cardId in cardsToExile) {
                val exileResult = ZoneMovementUtils.moveCardToZone(currentState, cardId, Zone.EXILE)
                if (exileResult.isSuccess) {
                    currentState = exileResult.state
                    allEvents.addAll(exileResult.events)
                }
            }

            // Put match card in hand
            if (matchCard != null) {
                // Check if we should repeat before the move creates a new object/zone identity.
                val matchManaValue = currentState.getEntity(matchCard)
                    ?.get<CardComponent>()?.manaValue ?: 0
                val repeatAfterMatch = matchManaValue >= effect.repeatIfManaValueAtLeast
                val outerContinuation = ExileFromTopRepeatingContinuation(
                    decisionId = "pending",
                    effect = effect,
                    context = context,
                    cardsToHand = cardsToHand,
                    matchCardId = matchCard,
                    repeatAfterMatch = repeatAfterMatch,
                )
                val handResult = ZoneTransitionService.moveToZoneWithReplacements(
                    state = currentState.pushContinuation(outerContinuation),
                    entityId = matchCard,
                    destinationZone = Zone.HAND,
                    options = ZoneEntryOptions(controllerId = controllerId),
                    context = context,
                    completion = PendingGameEvent.PlainZoneChangeCompletion,
                )
                if (handResult.isPaused) {
                    return EffectResult.paused(
                        handResult.state,
                        handResult.pendingDecision!!,
                        allEvents + handResult.events,
                    )
                }
                if (!handResult.isSuccess) {
                    return EffectResult.error(
                        handResult.state,
                        handResult.error ?: "Matching card could not be moved to hand",
                    )
                }

                // The continuation is only needed when the pending move paused. Remove the
                // sentinel we pushed for the synchronous path before continuing this loop.
                val (_, stateWithoutOuterContinuation) = handResult.state.popContinuation()
                currentState = stateWithoutOuterContinuation
                if (currentState.getZone(ZoneKey(ownerIdOf(currentState, matchCard), Zone.HAND)).contains(matchCard)) {
                    cardsToHand++
                }
                allEvents.addAll(handResult.events)
                continueProcess = repeatAfterMatch
            } else {
                // No match found (library exhausted without finding a matching card)
                continueProcess = false
            }
        }

        // Deal damage all at once (per rulings)
        if (cardsToHand > 0 && effect.damagePerCard > 0) {
            val totalDamage = cardsToHand * effect.damagePerCard
            val damageResult = DamageUtils.dealDamageToTarget(
                currentState, controllerId, totalDamage, sourceId
            )
            currentState = damageResult.state
            allEvents.addAll(damageResult.events)
        }

        return EffectResult.success(currentState, allEvents)
    }

    private fun ownerIdOf(state: GameState, entityId: EntityId): EntityId =
        state.getEntity(entityId)
            ?.get<com.wingedsheep.engine.state.components.identity.OwnerComponent>()?.playerId
            ?: state.turnOrder.firstOrNull()
            ?: error("No player available to resolve owner for $entityId")
}

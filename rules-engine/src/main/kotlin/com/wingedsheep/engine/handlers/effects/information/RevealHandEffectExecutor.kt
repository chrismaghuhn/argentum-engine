package com.wingedsheep.engine.handlers.effects.information

import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.HandRevealedEvent
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.scripting.effects.RevealHandEffect
import kotlin.reflect.KClass

/**
 * Executor for RevealHandEffect.
 * "Target player reveals their hand" - publicly reveals cards to all players.
 *
 * Unlike LookAtTargetHandExecutor (private viewing), this effect reveals
 * the hand publicly to all players in the game. The revealed cards remain
 * visible to all players for the rest of the game.
 */
class RevealHandEffectExecutor : EffectExecutor<RevealHandEffect> {

    override val effectType: KClass<RevealHandEffect> = RevealHandEffect::class

    override fun execute(
        state: GameState,
        effect: RevealHandEffect,
        context: EffectContext
    ): EffectResult {
        val targetPlayerId = context.resolvePlayerTarget(effect.target)
            ?: return EffectResult.error(state, "No valid target player for reveal hand")

        // Get all cards in the target player's hand
        val handCards = state.getHand(targetPlayerId)

        if (handCards.isEmpty()) {
            // Empty hand - nothing to reveal, but still emit event
            return EffectResult.success(
                state,
                listOf(HandRevealedEvent(targetPlayerId, emptyList()))
            )
        }

        // Get all players in the game
        val allPlayerIds = state.turnOrder

        // Mark each card as revealed to ALL players through the shared visibility/knowledge seam.
        val newState = LibraryRevealUtils.markRevealed(
            state = state,
            cardIds = handCards,
            playerIds = allPlayerIds,
        )

        return EffectResult.success(
            newState,
            listOf(HandRevealedEvent(targetPlayerId, handCards))
        )
    }
}

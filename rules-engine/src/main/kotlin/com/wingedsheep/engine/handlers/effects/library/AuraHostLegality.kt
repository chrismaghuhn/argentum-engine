package com.wingedsheep.engine.handlers.effects.library

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.TargetResolutionUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.references.Player

/**
 * Evaluates the opt-in Aura host restriction for collection selection.
 *
 * This helper deliberately returns only a boolean. It never returns or serializes the legal host
 * IDs, so hidden-zone card selection and later Aura attachment remain separate information
 * domains. Missing card identity, Aura definition, target requirement, or legal host fails closed.
 */
class AuraHostLegality(
    private val cardRegistry: CardRegistry,
    private val targetFinder: TargetFinder,
) {

    fun isSelectionEligible(
        state: GameState,
        cardId: EntityId,
        context: EffectContext,
    ): Boolean {
        val card = state.getEntity(cardId)?.get<CardComponent>() ?: return false
        if (!card.isAura) return true

        val hostControllerId = TargetResolutionUtils.resolvePlayerTarget(
            EffectTarget.PlayerRef(Player.You),
            context,
            state
        ) ?: return false

        return hasLegalHost(state, cardId, hostControllerId)
    }

    /**
     * Check an Aura's printed target requirement without applying normal spell-targeting
     * restrictions. This is the same non-targeting attachment legality used when an Aura enters
     * the battlefield through a non-cast effect.
     */
    private fun hasLegalHost(
        state: GameState,
        auraId: EntityId,
        hostControllerId: EntityId,
    ): Boolean {
        val card = state.getEntity(auraId)?.get<CardComponent>() ?: return false
        if (!card.isAura) return true
        val auraTarget = cardRegistry.getCard(card.cardDefinitionId)?.script?.auraTarget ?: return false
        return targetFinder.findLegalTargets(
            state = state,
            requirement = auraTarget,
            controllerId = hostControllerId,
            sourceId = auraId,
            ignoreTargetingRestrictions = true,
        ).isNotEmpty()
    }
}

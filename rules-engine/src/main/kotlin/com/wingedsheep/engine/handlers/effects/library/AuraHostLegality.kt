package com.wingedsheep.engine.handlers.effects.library

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.TargetResolutionUtils
import com.wingedsheep.engine.mechanics.targeting.PlayerProtectionRules
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.sdk.core.AbilityFlag
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.references.Player

/**
 * Evaluates Aura host legality for both collection selection and the later attachment decision.
 *
 * This helper deliberately returns only a boolean. It never returns or serializes the legal host
 * IDs from [isSelectionEligible], so hidden-zone card selection and later Aura attachment remain
 * separate information domains. The host-stage caller may use [findLegalHosts] to populate its
 * own attachment decision. Missing card identity, Aura definition, target requirement, or legal
 * host fails closed.
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

        return findLegalHosts(state, cardId, hostControllerId).isNotEmpty()
    }

    /**
     * Find an Aura's legal attachment hosts. The target requirement supplies the candidate domain,
     * but normal targeting restrictions are bypassed because this is the non-targeting attachment
     * choice made when an Aura enters the battlefield through a non-cast effect. Restrictions that
     * govern what an Aura may actually enchant — protection and [AbilityFlag.CANT_BE_ENCHANTED] —
     * remain enforced here.
     *
     * The returned IDs are for the later attachment decision only. Collection selection calls this
     * method solely for its non-empty check and never places these IDs in its decision payload.
     */
    fun findLegalHosts(
        state: GameState,
        auraId: EntityId,
        hostControllerId: EntityId,
    ): List<EntityId> {
        val card = state.getEntity(auraId)?.get<CardComponent>() ?: return emptyList()
        if (!card.isAura) return emptyList()
        val auraTarget = cardRegistry.getCard(card.cardDefinitionId)?.script?.auraTarget ?: return emptyList()
        return targetFinder.findLegalTargets(
            state = state,
            requirement = auraTarget,
            controllerId = hostControllerId,
            sourceId = auraId,
            ignoreTargetingRestrictions = true,
        ).filter { hostId -> isLegalAttachmentHost(state, auraId, hostId, hostControllerId, card) }
    }

    private fun isLegalAttachmentHost(
        state: GameState,
        auraId: EntityId,
        hostId: EntityId,
        auraControllerId: EntityId,
        aura: CardComponent,
    ): Boolean {
        if (hostId in state.turnOrder) {
            return !PlayerProtectionRules.isProtectedFromSource(
                state = state,
                playerId = hostId,
                sourceId = auraId,
                casterId = auraControllerId,
            )
        }

        if (hostId !in state.getBattlefield()) return false

        val projected = state.projectedState
        if (projected.hasKeyword(hostId, AbilityFlag.CANT_BE_ENCHANTED)) return false
        if (projected.hasKeyword(hostId, "PROTECTION_FROM_EVERYTHING")) return false

        val hostControllerId = projected.getController(hostId)
            ?: state.getEntity(hostId)?.get<ControllerComponent>()?.playerId
        if (projected.hasKeyword(hostId, "PROTECTION_FROM_EACH_OPPONENT") &&
            hostControllerId != null && hostControllerId != auraControllerId
        ) {
            return false
        }

        val sourceIsProjected = auraId in state.getBattlefield()
        val sourceColors = if (sourceIsProjected) {
            projected.getColors(auraId)
        } else {
            aura.colors.map { it.name }.toSet()
        }
        if (sourceColors.any { color -> projected.hasKeyword(hostId, "PROTECTION_FROM_$color") }) {
            return false
        }

        val sourceSubtypes = if (sourceIsProjected) {
            projected.getSubtypes(auraId)
        } else {
            aura.typeLine.subtypes.map { it.value }.toSet()
        }
        if (sourceSubtypes.any { subtype ->
                projected.hasKeyword(hostId, "PROTECTION_FROM_SUBTYPE_${subtype.uppercase()}")
            }
        ) {
            return false
        }

        val sourceSupertypes = if (sourceIsProjected) {
            projected.getSupertypes(auraId)
        } else {
            aura.typeLine.supertypes.map { it.name }.toSet()
        }
        if (sourceSupertypes.any { supertype ->
                projected.hasKeyword(hostId, "PROTECTION_FROM_SUPERTYPE_${supertype.uppercase()}")
            }
        ) {
            return false
        }

        val sourceCardTypes = if (sourceIsProjected) {
            CardType.entries.filter { cardType -> projected.hasType(auraId, cardType.name) }
                .map { it.name }
                .toSet()
        } else {
            aura.typeLine.cardTypes.map { it.name }.toSet()
        }
        return sourceCardTypes.none { cardType ->
            projected.hasKeyword(hostId, "PROTECTION_FROM_CARDTYPE_${cardType.uppercase()}")
        }
    }
}

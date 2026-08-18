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
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Evaluates Aura host legality for every non-cast Aura-entry path.
 *
 * The Aura's printed target requirement supplies the candidate domain, but normal targeting
 * restrictions are bypassed because the controller is choosing what the entering Aura enchants
 * rather than targeting an object or player (CR 303.4f). Restrictions that govern the actual
 * attachment — protection and [AbilityFlag.CANT_BE_ENCHANTED] — remain enforced here.
 *
 * [isSelectionEligible] returns only a boolean. It never returns or serializes host IDs, keeping
 * hidden-zone card selection separate from the later attachment decision.
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

    /** Find legal hosts for an Aura entity, including Auras still in a hidden zone. */
    fun findLegalHosts(
        state: GameState,
        auraId: EntityId,
        hostControllerId: EntityId,
    ): List<EntityId> {
        val card = state.getEntity(auraId)?.get<CardComponent>() ?: return emptyList()
        if (!card.isAura) return emptyList()
        val definition = cardRegistry.getCard(card.cardDefinitionId) ?: return emptyList()
        return findLegalHosts(
            state = state,
            definition = definition,
            sourceId = auraId,
            hostControllerId = hostControllerId,
            source = sourceCharacteristics(state, auraId, card),
        )
    }

    /**
     * Find legal hosts for an Aura definition that does not have an entity yet, such as an Aura
     * token copy. The definition supplies the printed Aura characteristics before token creation.
     */
    fun findLegalHostsForDefinition(
        state: GameState,
        auraDefinitionId: String,
        hostControllerId: EntityId,
        effectiveSource: PlayerProtectionRules.SourceCharacteristics? = null,
    ): List<EntityId> {
        val definition = cardRegistry.getCard(auraDefinitionId) ?: return emptyList()
        if (!definition.typeLine.isAura) return emptyList()
        return findLegalHosts(
            state = state,
            definition = definition,
            sourceId = null,
            hostControllerId = hostControllerId,
            source = effectiveSource ?: sourceCharacteristics(definition),
        )
    }

    /** Convert the copiable characteristics of a not-yet-created Aura into protection input. */
    fun sourceCharacteristics(
        aura: CardComponent,
    ): PlayerProtectionRules.SourceCharacteristics = PlayerProtectionRules.SourceCharacteristics(
        colors = aura.colors.map { it.name }.toSet(),
        subtypes = aura.typeLine.subtypes.map { it.value }.toSet(),
        supertypes = aura.typeLine.supertypes.map { it.name }.toSet(),
        cardTypes = aura.typeLine.cardTypes.map { it.name }.toSet(),
    )

    private fun findLegalHosts(
        state: GameState,
        definition: CardDefinition,
        sourceId: EntityId?,
        hostControllerId: EntityId,
        source: PlayerProtectionRules.SourceCharacteristics,
    ): List<EntityId> {
        val auraTarget = definition.script.auraTarget ?: return emptyList()
        return targetFinder.findLegalTargets(
            state = state,
            requirement = auraTarget,
            controllerId = hostControllerId,
            sourceId = sourceId,
            ignoreTargetingRestrictions = true,
        ).filter { hostId ->
            isLegalAttachmentHost(state, sourceId, hostId, hostControllerId, source)
        }
    }

    private fun isLegalAttachmentHost(
        state: GameState,
        auraId: EntityId?,
        hostId: EntityId,
        auraControllerId: EntityId,
        source: PlayerProtectionRules.SourceCharacteristics,
    ): Boolean {
        if (hostId in state.turnOrder) {
            return if (auraId != null) {
                !PlayerProtectionRules.isProtectedFromSource(
                    state = state,
                    playerId = hostId,
                    sourceId = auraId,
                    casterId = auraControllerId,
                )
            } else {
                !PlayerProtectionRules.isProtectedFromSourceCharacteristics(
                    state = state,
                    playerId = hostId,
                    source = source,
                    casterId = auraControllerId,
                )
            }
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

        if (source.colors.any { color -> projected.hasKeyword(hostId, "PROTECTION_FROM_$color") }) {
            return false
        }
        if (source.subtypes.any { subtype ->
                projected.hasKeyword(hostId, "PROTECTION_FROM_SUBTYPE_${subtype.uppercase()}")
            }
        ) {
            return false
        }
        if (source.supertypes.any { supertype ->
                projected.hasKeyword(hostId, "PROTECTION_FROM_SUPERTYPE_${supertype.uppercase()}")
            }
        ) {
            return false
        }
        return source.cardTypes.none { cardType ->
            projected.hasKeyword(hostId, "PROTECTION_FROM_CARDTYPE_${cardType.uppercase()}")
        }
    }

    private fun sourceCharacteristics(
        state: GameState,
        auraId: EntityId,
        aura: CardComponent,
    ): PlayerProtectionRules.SourceCharacteristics {
        val projected = state.projectedState
        return if (auraId in state.getBattlefield()) {
            PlayerProtectionRules.SourceCharacteristics(
                colors = projected.getColors(auraId),
                subtypes = projected.getSubtypes(auraId),
                supertypes = projected.getSupertypes(auraId),
                cardTypes = CardType.entries
                    .filter { projected.hasType(auraId, it.name) }
                    .map { it.name }
                    .toSet(),
            )
        } else {
            sourceCharacteristics(aura)
        }
    }

    private fun sourceCharacteristics(
        definition: CardDefinition,
    ): PlayerProtectionRules.SourceCharacteristics = PlayerProtectionRules.SourceCharacteristics(
        colors = definition.colors.map { it.name }.toSet(),
        subtypes = definition.typeLine.subtypes.map { it.value }.toSet(),
        supertypes = definition.typeLine.supertypes.map { it.name }.toSet(),
        cardTypes = definition.typeLine.cardTypes.map { it.name }.toSet(),
    )
}

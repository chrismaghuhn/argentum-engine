package com.wingedsheep.engine.handlers.effects.permanent.attachments

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.library.AuraHostLegality
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId

/**
 * Shared legality seam for generic attachment transfer.
 *
 * The candidate filter and the batch executor both call this class. It deliberately evaluates
 * all applicable attachment types conjunctively: a projected object that is both an Aura and an
 * Equipment must satisfy both the Aura's `enchant` restriction and Equipment's creature-only
 * restriction. The supplied [GameState] is the one projected snapshot for the whole batch.
 */
class AttachmentLegality(
    cardRegistry: CardRegistry,
    targetFinder: TargetFinder,
) {

    private val auraHostLegality = AuraHostLegality(cardRegistry, targetFinder)

    /**
     * True when [attachmentId] is in the frozen/current domain and may legally attach to
     * [targetId] under [controllerId]'s control.
     */
    fun isLegal(
        state: GameState,
        attachmentId: EntityId,
        targetId: EntityId,
        controllerId: EntityId,
        context: EffectContext? = null,
    ): Boolean {
        if (attachmentId !in state.getBattlefield()) return false
        if (targetId !in state.getBattlefield() && targetId !in state.turnOrder) return false
        // CR 301.5c / 303.4d: neither an Equipment nor an Aura can attach to itself.
        if (attachmentId == targetId) return false

        state.getEntity(attachmentId)?.get<CardComponent>() ?: return false
        val projected = state.projectedState
        // CR 310.10: a Battle cannot be attached, even when it also has Aura/Equipment types.
        if (projected.isBattle(attachmentId)) return false

        val isAura = projected.hasSubtype(attachmentId, Subtype.AURA.value)
        val isEquipment = projected.hasSubtype(attachmentId, Subtype.EQUIPMENT.value)
        if (!isAura && !isEquipment) return false

        val effectiveController = projected.getController(attachmentId)
            ?: state.getEntity(attachmentId)?.get<ControllerComponent>()?.playerId
        if (effectiveController != controllerId) return false

        val auraLegal = !isAura || auraHostLegality.isLegalAttachmentTarget(
            state = state,
            auraId = attachmentId,
            hostId = targetId,
            auraControllerId = controllerId,
        )
        val equipmentLegal = !isEquipment || isLegalEquipmentHost(
            state = state,
            projected = projected,
            equipmentId = attachmentId,
            hostId = targetId,
            equipmentControllerId = controllerId,
        )

        return auraLegal && equipmentLegal
    }

    private fun isLegalEquipmentHost(
        state: GameState,
        projected: ProjectedState,
        equipmentId: EntityId,
        hostId: EntityId,
        equipmentControllerId: EntityId,
    ): Boolean {
        // Reconfigure is not modeled by this generic primitive. CR 301.5c therefore requires
        // an Equipment that is currently a creature to fail closed rather than silently treating
        // it as an ordinary Equipment.
        if (projected.isCreature(equipmentId)) return false
        if (hostId.isPlayer(state) || !projected.isCreature(hostId)) return false

        return !isProtectedFromEquipment(
            state = state,
            projected = projected,
            hostId = hostId,
            equipmentId = equipmentId,
            equipmentControllerId = equipmentControllerId,
        )
    }

    private fun isProtectedFromEquipment(
        state: GameState,
        projected: ProjectedState,
        hostId: EntityId,
        equipmentId: EntityId,
        equipmentControllerId: EntityId,
    ): Boolean {
        if (projected.hasKeyword(hostId, "PROTECTION_FROM_EVERYTHING")) return true

        val hostControllerId = projected.getController(hostId)
            ?: state.getEntity(hostId)?.get<ControllerComponent>()?.playerId
        if (projected.hasKeyword(hostId, "PROTECTION_FROM_EACH_OPPONENT") &&
            hostControllerId != null && hostControllerId != equipmentControllerId
        ) {
            return true
        }

        val sourceColors = projected.getColors(equipmentId)
        val sourceSubtypes = projected.getSubtypes(equipmentId)
        val sourceSupertypes = projected.getSupertypes(equipmentId)
        val sourceCardTypes = projected.getTypes(equipmentId)
        return sourceColors.any { color ->
            projected.hasKeyword(hostId, "PROTECTION_FROM_$color")
        } || sourceSubtypes.any { subtype ->
            projected.hasKeyword(hostId, "PROTECTION_FROM_SUBTYPE_${subtype.uppercase()}")
        } || sourceSupertypes.any { supertype ->
            projected.hasKeyword(hostId, "PROTECTION_FROM_SUPERTYPE_${supertype.uppercase()}")
        } || sourceCardTypes.any { cardType ->
            projected.hasKeyword(hostId, "PROTECTION_FROM_CARDTYPE_${cardType.uppercase()}")
        }
    }

    private fun EntityId.isPlayer(state: GameState): Boolean = this in state.turnOrder
}

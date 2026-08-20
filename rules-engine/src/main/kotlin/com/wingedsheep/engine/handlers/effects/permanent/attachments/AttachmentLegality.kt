package com.wingedsheep.engine.handlers.effects.permanent.attachments

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.library.AuraHostLegality
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

        val card = state.getEntity(attachmentId)?.get<CardComponent>() ?: return false
        val projected = state.projectedState
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
        val equipmentLegal = !isEquipment ||
            (!targetId.isPlayer(state) && projected.isCreature(targetId))

        return auraLegal && equipmentLegal
    }

    private fun EntityId.isPlayer(state: GameState): Boolean = this in state.turnOrder
}

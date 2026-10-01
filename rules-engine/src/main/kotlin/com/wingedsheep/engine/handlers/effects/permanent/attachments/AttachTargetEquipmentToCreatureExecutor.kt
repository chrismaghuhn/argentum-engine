package com.wingedsheep.engine.handlers.effects.permanent.attachments

import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.sdk.scripting.effects.AttachTargetEquipmentToCreatureEffect
import kotlin.reflect.KClass

/**
 * Executor for [AttachTargetEquipmentToCreatureEffect].
 * Attaches a targeted Equipment to a targeted creature.
 * Both the Equipment and creature are explicit targets (not the source).
 *
 * Either target may be declared optional ("up to one target"). When either resolves to nothing —
 * the player declined an optional target, or a required target became illegal before resolution
 * (CR 608.2c) — there is nothing to attach, so the effect is a graceful no-op (Raubahn, Bull of
 * Ala Mhigo attaches "up to one target Equipment"; Blacksmith's Talent attaches to "up to one
 * target creature").
 */
class AttachTargetEquipmentToCreatureExecutor : EffectExecutor<AttachTargetEquipmentToCreatureEffect> {

    override val effectType: KClass<AttachTargetEquipmentToCreatureEffect> =
        AttachTargetEquipmentToCreatureEffect::class

    override fun execute(
        state: GameState,
        effect: AttachTargetEquipmentToCreatureEffect,
        context: EffectContext
    ): EffectResult {
        // "up to one" / fizzled target — nothing to attach, so this is a no-op (not an error).
        val equipmentId = context.resolveTarget(effect.equipmentTarget, state)
            ?: return EffectResult.success(state)

        val creatureId = context.resolveTarget(effect.creatureTarget, state)
            ?: return EffectResult.success(state)

        var newState = state

        // Detach from current creature if attached to a different one. Onto its current host the
        // attach does nothing (CR 701.3b), so that host's attachment list is left as it is.
        val currentAttachment = newState.getEntity(equipmentId)?.get<AttachedToComponent>()
        if (currentAttachment != null && currentAttachment.targetId != creatureId) {
            val oldTargetId = currentAttachment.targetId
            newState = newState.updateEntity(oldTargetId) { container ->
                val attachments = container.get<AttachmentsComponent>()
                if (attachments != null) {
                    val updatedIds = attachments.attachedIds.filter { it != equipmentId }
                    if (updatedIds.isEmpty()) {
                        container.without<AttachmentsComponent>()
                    } else {
                        container.with(AttachmentsComponent(updatedIds))
                    }
                } else {
                    container
                }
            }
        }

        // Attach to new creature
        newState = newState.updateEntity(equipmentId) { container ->
            container.with(AttachedToComponent(creatureId))
        }

        newState = newState.updateEntity(creatureId) { container ->
            val existing = container.get<AttachmentsComponent>()?.attachedIds.orEmpty()
            if (equipmentId in existing) container else container.with(AttachmentsComponent(existing + equipmentId))
        }

        return EffectResult.success(newState)
    }
}

package com.wingedsheep.engine.handlers.effects.permanent.attachments

import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.PermanentAttachedEvent
import com.wingedsheep.engine.core.PermanentUnattachedEvent
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.ZoneMovementUtils
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.battlefield.TimestampComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.sdk.model.EntityId

/**
 * Plans and commits one simultaneous attachment-transfer action.
 *
 * All legality and timestamp-overflow checks happen before the first state component is changed.
 * The immutable state is then rebuilt internally as one commit, and the returned event list is
 * handed to the normal trigger pipeline as one batch. [domainAttachments] is the frozen selected
 * collection order used only for event presentation; [orderingDomain] is the frozen subset that
 * was actually host-changing when the order decision was requested; [orderedAttachments] is the
 * explicit CR 613.7m order and is the only input that determines fresh attachment timestamps.
 */
class AttachmentBatchMutation(
    private val legality: AttachmentLegality,
) {

    fun apply(
        state: GameState,
        domainAttachments: List<EntityId>,
        orderingDomain: List<EntityId>,
        orderedAttachments: List<EntityId>,
        targetId: EntityId,
        controllerId: EntityId,
        context: EffectContext? = null,
        lockedAttachmentIdentityStamps: Map<EntityId, Long> = emptyMap(),
    ): EffectResult {
        if (domainAttachments.size != domainAttachments.toSet().size) {
            return EffectResult.error(state, "Attachment selection contains duplicate objects")
        }
        val domain = domainAttachments.toSet()
        val orderDomain = orderingDomain.toSet()
        if (orderingDomain.size != orderDomain.size ||
            orderedAttachments.size != orderDomain.size ||
            orderedAttachments.toSet() != orderDomain ||
            orderedAttachments.any { it !in domain }
        ) {
            return EffectResult.error(state, "Attachment order must be an exact permutation of the ordering domain")
        }
        if (targetId !in state.getBattlefield() && targetId !in state.turnOrder) {
            return EffectResult.error(state, "Attachment destination is no longer a game object")
        }
        if (targetId !in state.turnOrder && context != null &&
            TargetsComponent.isDifferentObject(state, targetId, context.targetEntryStamps)
        ) {
            return EffectResult.error(state, "Attachment destination is a different object than the locked target")
        }

        // Revalidate against the same projected pre-mutation state. An attachment whose legality
        // changed while a decision was pending simply makes no move, matching an illegal attach
        // attempt; malformed/out-of-domain submitted IDs fail closed above.
        val stillLegal = domainAttachments.filter { attachmentId ->
            !TargetsComponent.isDifferentObject(state, attachmentId, lockedAttachmentIdentityStamps) &&
                legality.isLegal(state, attachmentId, targetId, controllerId, context)
        }
        // The order domain is frozen at the decision boundary. A selected object that was not
        // host-changing then must not become a new move after resume without a corresponding
        // CR 613.7m choice; a frozen mover that becomes same-host is simply a no-op.
        val hostChanging = stillLegal.filter { attachmentId ->
            attachmentId in orderDomain &&
                state.getEntity(attachmentId)?.get<AttachedToComponent>()?.targetId != targetId
        }
        val timestampOrder = orderedAttachments.filter { it in hostChanging }

        val baseTimestamp = maxOf(
            state.timestamp,
            state.getBattlefield().mapNotNull { id ->
                state.getEntity(id)?.get<TimestampComponent>()?.timestamp
            }.maxOrNull() ?: Long.MIN_VALUE,
        )
        val finalTimestamp = try {
            Math.addExact(baseTimestamp, timestampOrder.size.toLong())
        } catch (_: ArithmeticException) {
            return EffectResult.error(state, "Attachment timestamp allocation overflow")
        }
        val timestamps = timestampOrder.mapIndexed { index, attachmentId ->
            attachmentId to Math.addExact(baseTimestamp, index.toLong() + 1L)
        }.toMap()

        // Commit only after every precondition above has passed. The loop is an implementation
        // detail of rebuilding immutable state; no intermediate state or event is observable.
        var newState = state
        val events = mutableListOf<GameEvent>()
        for (attachmentId in hostChanging) {
            val currentAttachment = state.getEntity(attachmentId)?.get<AttachedToComponent>()
            if (currentAttachment?.targetId == targetId) continue

            val attachmentContainer = state.getEntity(attachmentId)
            val attachmentName = attachmentContainer?.get<CardComponent>()?.name ?: "Attachment"
            val attachmentController = attachmentContainer?.get<ControllerComponent>()?.playerId
                ?: controllerId

            if (currentAttachment != null) {
                newState = ZoneMovementUtils.cleanupReverseAttachmentLink(newState, attachmentId)
                newState = newState.updateEntity(attachmentId) { it.without<AttachedToComponent>() }
                events += PermanentUnattachedEvent(
                    attachmentId = attachmentId,
                    attachmentName = attachmentName,
                    attachedToId = currentAttachment.targetId,
                    controllerId = attachmentController,
                )
            }

            newState = newState.updateEntity(attachmentId) { container ->
                container
                    .with(AttachedToComponent(targetId))
                    .with(TimestampComponent(timestamps.getValue(attachmentId)))
            }
            newState = newState.updateEntity(targetId) { container ->
                val existing = container.get<AttachmentsComponent>()?.attachedIds.orEmpty()
                container.with(AttachmentsComponent(existing.filter { it != attachmentId } + attachmentId))
            }
            events += PermanentAttachedEvent(
                attachmentId = attachmentId,
                attachmentName = attachmentName,
                attachedToId = targetId,
                controllerId = attachmentController,
            )
        }

        return EffectResult.success(newState.copy(timestamp = finalTimestamp), events)
    }
}

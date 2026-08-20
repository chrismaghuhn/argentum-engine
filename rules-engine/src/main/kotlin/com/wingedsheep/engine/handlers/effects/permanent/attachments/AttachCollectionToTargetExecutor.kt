package com.wingedsheep.engine.handlers.effects.permanent.attachments

import com.wingedsheep.engine.core.AttachCollectionOrderContinuation
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.handlers.DecisionHandler
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.sdk.scripting.effects.AttachCollectionToTargetEffect
import kotlin.reflect.KClass

/**
 * Executes the generic Way-A attachment transfer primitive.
 *
 * Selection has already happened in the pipeline. This executor only revalidates that selected
 * domain, asks for CR 613.7m relative order when at least two objects will actually change host,
 * and delegates the single state commit to [AttachmentBatchMutation].
 */
class AttachCollectionToTargetExecutor(
    private val decisionHandler: DecisionHandler,
    private val attachmentLegality: AttachmentLegality,
) : EffectExecutor<AttachCollectionToTargetEffect> {

    override val effectType: KClass<AttachCollectionToTargetEffect> = AttachCollectionToTargetEffect::class

    private val batchMutation = AttachmentBatchMutation(attachmentLegality)

    override fun execute(
        state: GameState,
        effect: AttachCollectionToTargetEffect,
        context: EffectContext,
    ): EffectResult {
        val selected = context.pipeline.storedCollections[effect.from]
            ?: return EffectResult.error(state, "No collection named '${effect.from}' in storedCollections")
        val targetId = context.resolveTarget(effect.target, state)
            ?: return EffectResult.error(state, "No valid attachment destination")

        if (selected.size != selected.toSet().size) {
            return EffectResult.error(state, "Attachment selection contains duplicate objects")
        }

        val eligible = selected.filter { attachmentId ->
            attachmentLegality.isLegal(state, attachmentId, targetId, context.controllerId, context)
        }
        val hostChanging = eligible.filter { attachmentId ->
            state.getEntity(attachmentId)?.get<AttachedToComponent>()?.targetId != targetId
        }

        if (hostChanging.size >= 2) {
            val decisionResult = decisionHandler.createOrderDecision(
                state = state,
                playerId = context.controllerId,
                sourceId = context.sourceId,
                sourceName = context.sourceId?.let { state.getEntity(it)?.let { entity ->
                    entity.get<com.wingedsheep.engine.state.components.identity.CardComponent>()?.name
                } },
                prompt = "Choose the relative timestamp order of the attachments",
                objects = hostChanging,
                phase = DecisionPhase.RESOLUTION,
            )
            val pendingDecision = decisionResult.pendingDecision
                ?: return EffectResult.error(state, "Ordering decision was not created")
            val continuation = AttachCollectionOrderContinuation(
                decisionId = pendingDecision.id,
                effect = effect,
                effectContext = context,
                selectedAttachments = selected,
                orderingDomain = hostChanging,
                targetId = targetId,
                selectedAttachmentIdentityStamps = selected.mapNotNull { attachmentId ->
                    val stamp = state.objectIdentityStamps[attachmentId]
                        ?: state.getEntity(attachmentId)
                            ?.get<BattlefieldEntryTimestampComponent>()
                            ?.timestamp
                    stamp?.let { attachmentId to it }
                }.toMap(),
            )
            return EffectResult.paused(
                decisionResult.state.pushContinuation(continuation),
                pendingDecision,
                decisionResult.events,
            )
        }

        return batchMutation.apply(
            state = state,
            domainAttachments = selected,
            orderingDomain = hostChanging,
            orderedAttachments = hostChanging,
            targetId = targetId,
            controllerId = context.controllerId,
            context = context,
        )
    }
}

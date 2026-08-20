package com.wingedsheep.engine.handlers.continuations

import com.wingedsheep.engine.core.AttachCollectionOrderContinuation
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.handlers.effects.permanent.attachments.AttachmentBatchMutation
import com.wingedsheep.engine.handlers.effects.permanent.attachments.AttachmentLegality
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.core.OrderedResponse

/** Resumes the explicit CR 613.7m ordering boundary for generic attachment transfer. */
class AttachmentContinuationResumer(
    private val services: EngineServices,
) : ContinuationResumerModule {

    private val batchMutation = AttachmentBatchMutation(
        AttachmentLegality(services.cardRegistry, services.targetFinder)
    )

    override fun resumers(): List<ContinuationResumer<*>> = listOf(
        resumer(AttachCollectionOrderContinuation::class, ::resumeOrder)
    )

    private fun resumeOrder(
        state: GameState,
        continuation: AttachCollectionOrderContinuation,
        response: DecisionResponse,
        checkForMore: CheckForMore,
    ): ExecutionResult {
        if (response !is OrderedResponse) {
            return ExecutionResult.error(state, "Expected ordering response for attachment transfer")
        }

        val reboundTarget = continuation.effectContext.resolveTarget(continuation.effect.target, state)
        if (reboundTarget == null || reboundTarget != continuation.targetId) {
            return ExecutionResult.error(state, "Attachment destination no longer matches the locked target")
        }

        val result = batchMutation.apply(
            state = state,
            domainAttachments = continuation.selectedAttachments,
            orderingDomain = continuation.orderingDomain,
            orderedAttachments = response.orderedObjects,
            targetId = continuation.targetId,
            controllerId = continuation.effectContext.controllerId,
            context = continuation.effectContext,
            lockedAttachmentIdentityStamps = continuation.selectedAttachmentIdentityStamps,
        )
        if (result.error != null) return result.toExecutionResult()
        return checkForMore(result.state, result.events)
    }
}

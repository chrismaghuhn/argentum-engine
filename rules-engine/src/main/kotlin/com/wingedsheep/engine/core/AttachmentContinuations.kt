package com.wingedsheep.engine.core

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.AttachCollectionToTargetEffect
import kotlinx.serialization.Serializable

/**
 * Resume a generic attachment transfer after the controller orders the host-changing objects.
 * The full effect context is retained so normal target binding, target-entry identity stamps,
 * pipeline state and controller semantics survive serialization, fork and replay.
 */
@Serializable
data class AttachCollectionOrderContinuation(
    override val decisionId: String,
    val effect: AttachCollectionToTargetEffect,
    val effectContext: EffectContext,
    /** The selected collection, frozen before the ordering decision. */
    val selectedAttachments: List<EntityId>,
    /** The selected objects that were host-changing when the decision was requested. */
    val orderingDomain: List<EntityId>,
    /** The bound target identity at the decision boundary. */
    val targetId: EntityId,
    /** CR 400.7 identity stamps for selected battlefield objects at the order boundary. */
    val selectedAttachmentIdentityStamps: Map<EntityId, Long> = emptyMap(),
) : ContinuationFrame

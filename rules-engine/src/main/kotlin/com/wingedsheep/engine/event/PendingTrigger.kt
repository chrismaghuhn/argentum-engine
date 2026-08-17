package com.wingedsheep.engine.event

import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.TriggeredAbility

/**
 * The serializable payload for one possible occurrence of a delayed trigger.
 *
 * This deliberately mirrors the state carried by [PendingTrigger] without referring back to
 * [PendingTrigger] itself.  A recursive `PendingTrigger -> List<PendingTrigger>` field would make
 * the generated kotlinx-serialization descriptor recursive and, more importantly, would make it
 * too easy for a queued occurrence marker to disappear when a pending-trigger continuation is
 * snapshotted.  The detector-only marker therefore carries these ordinary, replay-safe candidates.
 */
@kotlinx.serialization.Serializable
data class DelayedTriggerOccurrenceCandidate(
    val ability: TriggeredAbility,
    val sourceId: EntityId,
    val sourceName: String,
    val controllerId: EntityId,
    val granterId: EntityId? = null,
    val triggerContext: TriggerContext,
    val consumesDelayedTriggerId: String? = null,
    val sagaChapterInfo: SagaChapterInfo? = null,
    val carriedPipeline: com.wingedsheep.engine.handlers.PipelineState? = null,
) {
    fun toPendingTrigger(): PendingTrigger = PendingTrigger(
        ability = ability,
        sourceId = sourceId,
        sourceName = sourceName,
        controllerId = controllerId,
        granterId = granterId,
        triggerContext = triggerContext,
        consumesDelayedTriggerId = consumesDelayedTriggerId,
        sagaChapterInfo = sagaChapterInfo,
        carriedPipeline = carriedPipeline,
    )
}

/**
 * A triggered ability that is waiting to go on the stack.
 */
@kotlinx.serialization.Serializable
data class PendingTrigger(
    val ability: TriggeredAbility,
    val sourceId: EntityId,
    val sourceName: String,
    val controllerId: EntityId,
    /**
     * The permanent whose `GrantTriggeredAbility` static granted this triggered ability, when it is
     * a granted ability (e.g. an Equipment granting an attack trigger to the equipped creature).
     * Carried onto the stack so the resolving effect can reference the granter (CR 201.5a) via
     * [com.wingedsheep.engine.handlers.EffectContext.granterId] — e.g. Dire Blunderbuss's "sacrifice
     * an artifact other than Dire Blunderbuss". Null for the source's own printed abilities.
     */
    val granterId: EntityId? = null,
    val triggerContext: TriggerContext,
    /**
     * When set, this pending trigger came from a one-shot event-based delayed triggered
     * ability ([DelayedTriggeredAbility.fireOnce]); the delayed trigger with this id is
     * removed from game state the moment this trigger fires (goes on the stack), so a later
     * matching event the same turn won't fire it again.
     */
    val consumesDelayedTriggerId: String? = null,
    /**
     * Set on Saga chapter abilities so that, when this ability resolves, the engine can emit a
     * [com.wingedsheep.engine.core.SagaChapterResolvedEvent] (the cue for "whenever the final
     * chapter ability of a Saga you control resolves" — Tom Bombadil).
     */
    val sagaChapterInfo: SagaChapterInfo? = null,
    /**
     * Pipeline state carried from a `ReflexiveTriggerEffect`'s action half into this synthetic
     * reflexive ability, threaded onto [com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent]
     * when this pending trigger is placed on the stack. Null for ordinary triggered abilities.
     */
    val carriedPipeline: com.wingedsheep.engine.handlers.PipelineState? = null,
    /**
     * CR 603.7b marker emitted by the delayed-trigger detector when several matching occurrences
     * happen simultaneously. The marker is converted into a normal pending decision by
     * [com.wingedsheep.engine.event.TriggerProcessor]. It is intentionally serializable because
     * callers may queue detected triggers below another continuation before processing them.
     */
    val occurrenceChoice: List<DelayedTriggerOccurrenceCandidate> = emptyList()
)

fun PendingTrigger.toOccurrenceCandidate(): DelayedTriggerOccurrenceCandidate =
    DelayedTriggerOccurrenceCandidate(
        ability = ability,
        sourceId = sourceId,
        sourceName = sourceName,
        controllerId = controllerId,
        granterId = granterId,
        triggerContext = triggerContext,
        consumesDelayedTriggerId = consumesDelayedTriggerId,
        sagaChapterInfo = sagaChapterInfo,
        carriedPipeline = carriedPipeline,
    )

/**
 * Identifies a Saga chapter ability and which chapter it is, carried from trigger detection
 * through stack resolution so a [com.wingedsheep.engine.core.SagaChapterResolvedEvent] can be
 * emitted on resolution.
 */
@kotlinx.serialization.Serializable
data class SagaChapterInfo(
    val chapterNumber: Int,
    val finalChapterNumber: Int
) {
    val isFinalChapter: Boolean get() = chapterNumber >= finalChapterNumber
}

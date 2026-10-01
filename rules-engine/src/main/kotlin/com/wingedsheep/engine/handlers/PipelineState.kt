package com.wingedsheep.engine.handlers

import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/**
 * The target bound to [name]. The per-slot handle `id[0]` that `targets(...)` hands out also
 * answers to a requirement the announcement-time target lock narrowed to its one chosen slot
 * ("up to two", one chosen): [EffectContext.buildNamedTargets] binds such a requirement under
 * the bare `id`.
 */
fun Map<String, ChosenTarget>.boundTarget(name: String): ChosenTarget? =
    this[name] ?: name.takeIf { it.endsWith("[0]") }?.let { this[it.removeSuffix("[0]")] }

/**
 * State carried through pipeline effect execution (Gather → Select → Move).
 *
 * This groups the fields that are only relevant during pipeline effect chains,
 * keeping [EffectContext] focused on core effect execution concerns.
 */
@Serializable
data class PipelineState(
    /** Named card collections for pipeline effects (GatherCards → SelectFromCollection → MoveCollection) */
    val storedCollections: Map<String, List<EntityId>> = emptyMap(),
    /** Named targets map for BoundVariable resolution (target name -> chosen target) */
    val namedTargets: Map<String, ChosenTarget> = emptyMap(),
    /** Named values chosen by the player during pipeline execution (e.g., creature type, color). */
    val chosenValues: Map<String, String> = emptyMap(),
    /** Named numeric values stored by pipeline effects (e.g., cards not drawn). */
    val storedNumbers: Map<String, Int> = emptyMap(),
    /** Named string lists stored by pipeline effects (e.g., chosen creature types). */
    val storedStringLists: Map<String, List<String>> = emptyMap(),
    /**
     * Named lists of subtype sets produced by `GatherSubtypesEffect`. Each entry is
     * `List<Set<String>>` — one subtype set per source entity in the order they were
     * gathered. Consumed by `CardPredicate.HasSubtypeInEachStoredGroup`.
     */
    val storedSubtypeGroups: Map<String, List<Set<String>>> = emptyMap(),
) {
    companion object {
        val EMPTY = PipelineState()

        /** Reserved metadata published by ChooseSpell alongside its selected card collection. */
        fun spellFaceKey(collection: String): String = "$collection:spellFace"

        /**
         * Pipeline collection name under which a batch trigger seeds the entities it captured
         * (the matching permanents in a `PermanentsEnteredEvent` batch). Aliases the SDK-side
         * contract [com.wingedsheep.sdk.scripting.effects.IterationSpace.TRIGGER_CAPTURED_COLLECTION]
         * so card definitions (which only see the SDK) and the engine name the same collection.
         */
        const val TRIGGER_CAPTURED_COLLECTION =
            com.wingedsheep.sdk.scripting.effects.IterationSpace.TRIGGER_CAPTURED_COLLECTION
    }
}

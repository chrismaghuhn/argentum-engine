package com.wingedsheep.engine.mechanics.stack

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.TargetingSourceType
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.mechanics.targeting.TargetValidator
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.stack.*
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.*

/** A serialized target component needs resolution-time validation unless it is targetless. */
internal fun TargetsComponent.hasResolutionTargetPayload(): Boolean =
    targets.isNotEmpty() || targetRequirements.any { it.count != 0 }

/**
 * The CR 608.2b target re-check a spell or ability makes as it resolves: every target is checked
 * again for legality (zone, object identity, shroud / hexproof / protection, and the requirement's
 * own filter) and the illegal ones are dropped. Shared by [SpellResolver] and [AbilityResolver].
 *
 * Each *locked* target slot is re-checked by the engine's canonical cast-time [TargetValidator]
 * ([TargetValidator.filterTargetsAtResolution]) — one validator for announcement-time locking and
 * every resolution path, so the two can't disagree. It keeps the original slot positions, carries
 * the trigger's damage / defending-player context into the filter re-check, and fails closed on a
 * malformed payload. The resolution-only set restriction ([targetsSharingAController]) is layered
 * on top.
 */
internal class ResolutionTargetValidator(
    /** The engine's canonical cast-time validator; CR 608.2b re-checks every locked slot with it. */
    private val targetValidator: TargetValidator
) {
    /**
     * Filter a locked target payload for resolution (CR 608.2b): individually-illegal targets are
     * removed when at least one target remains legal, and the result keeps a position-preserving
     * [TargetValidator.ResolutionTargetPayload.alignedTargets] view (null in each dropped slot) so
     * positional references never shift onto a later survivor. An empty
     * [TargetValidator.ResolutionTargetPayload.targets] means every target is illegal.
     */
    fun filterAtResolution(
        state: GameState,
        targets: List<ChosenTarget>,
        requirements: List<TargetRequirement>,
        casterId: EntityId,
        sourceColors: Set<Color> = emptySet(),
        sourceSubtypes: Set<String> = emptySet(),
        sourceId: EntityId? = null,
        xValue: Int? = null,
        /**
         * The object-identity stamps captured when these targets were chosen
         * ([TargetsComponent.targetEntryStamps]) — an object that left its zone and came back in
         * the meantime is a different object and no longer a legal target (CR 400.7).
         */
        targetEntryStamps: Map<EntityId, Long> = emptyMap(),
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        triggeringEntityId: EntityId? = null,
        triggeringPlayerId: EntityId? = null,
        defendingPlayerId: EntityId? = null,
        damageSourceId: EntityId? = null,
        damageRecipientId: EntityId? = null,
        damageRecipientKind: DamageRecipientKind = DamageRecipientKind.UNKNOWN,
        damageRecipientKinds: DamageRecipientKindSet = DamageRecipientKindSet.UNKNOWN,
        damageSourceLastKnownSnapshot: EntitySnapshot? = null,
        damageRecipientLastKnownSnapshot: EntitySnapshot? = null,
        /**
         * Pipeline collections available at resolution time (e.g. the amassed Army under
         * `EffectTarget.AmassedArmy`, from a `ReflexiveTriggerEffect`'s carried pipeline) — the
         * CR 608.2b re-validation re-checks the target filter, and a filter like Grishnákh's
         * "power <= the amassed Army's power" needs this to resolve the referenced entity, or every
         * target wrongly fails re-validation as unresolvable.
         */
        storedCollections: Map<String, List<EntityId>> = emptyMap()
    ): TargetValidator.ResolutionTargetPayload {
        val payload = targetValidator.filterTargetsAtResolution(
            state = state,
            targets = targets,
            requirements = requirements,
            casterId = casterId,
            sourceColors = sourceColors,
            sourceSubtypes = sourceSubtypes,
            sourceId = sourceId,
            xValue = xValue,
            targetEntryStamps = targetEntryStamps,
            targetingSourceType = targetingSourceType,
            triggeringEntityId = triggeringEntityId,
            triggeringPlayerId = triggeringPlayerId,
            defendingPlayerId = defendingPlayerId,
            damageSourceId = damageSourceId,
            damageRecipientId = damageRecipientId,
            damageRecipientKind = damageRecipientKind,
            damageRecipientKinds = damageRecipientKinds,
            damageSourceLastKnownSnapshot = damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = damageRecipientLastKnownSnapshot,
            storedCollections = storedCollections
        )
        if (payload.targets.isEmpty() || payload.alignedTargets.size != targets.size) return payload
        val legalIndices = payload.alignedTargets.indices.filter { payload.alignedTargets[it] != null }.toSet()
        val sharingController =
            targetsSharingAController(state.projectedState, state, targets, legalIndices, requirements)
        if (sharingController.isEmpty()) return payload
        val aligned = payload.alignedTargets.mapIndexed { index, target ->
            if (index in sharingController) null else target
        }
        return TargetValidator.ResolutionTargetPayload(
            targets = aligned.filterNotNull(),
            alignedTargets = aligned
        )
    }

    /**
     * "Two target creatures controlled by different players" (Run Away Together) is a restriction on the
     * *set* of targets, so it is re-checked as one at resolution: when two still-legal targets of a
     * `differentControllers` requirement now share a controller, both are illegal (the card's ruling —
     * "if both creatures are controlled by the same player …, both targets are illegal").
     *
     * The per-opponent distribution shape ("for each opponent, up to one target creature that player
     * controls" — `dynamicMaxCount` set) is excluded: there each target is tied to its own player, so a
     * control change makes only the moved creature illegal, and which one moved isn't recorded here.
     * A target that already left the battlefield contributes no controller.
     *
     * Requirements stored on a stack object are locked at announcement (CR 601.2c), which resolves a
     * `dynamicMaxCount` into a fixed count, so a locked per-opponent requirement is re-checked as a set
     * too.
     */
    private fun targetsSharingAController(
        projected: ProjectedState,
        state: GameState,
        targets: List<ChosenTarget>,
        legalIndices: Set<Int>,
        targetRequirements: List<TargetRequirement>,
    ): Set<Int> {
        val byRequirement = legalIndices
            .filter { targets[it] is ChosenTarget.Permanent }
            .groupBy { index ->
                (getRequirementForTargetIndex(index, targetRequirements) as? TargetObject)
                    ?.takeIf { it.differentControllers && it.dynamicMaxCount == null }
            }
        return byRequirement.flatMap { (requirement, indices) ->
            if (requirement == null) return@flatMap emptyList()
            indices
                .groupBy { index ->
                    val id = (targets[index] as ChosenTarget.Permanent).entityId
                    projected.getController(id) ?: state.getEntity(id)?.get<ControllerComponent>()?.playerId
                }
                .values
                .filter { it.size > 1 }
                .flatten()
        }.toSet()
    }

    /**
     * Find the TargetRequirement that corresponds to a given target index.
     * Requirements are matched to targets in order, with each requirement
     * consuming `count` targets.
     */
    private fun getRequirementForTargetIndex(
        targetIndex: Int,
        requirements: List<TargetRequirement>
    ): TargetRequirement? {
        var idx = 0
        for (req in requirements) {
            val end = idx + req.count
            if (targetIndex in idx until end) return req
            idx = end
        }
        return null
    }
}

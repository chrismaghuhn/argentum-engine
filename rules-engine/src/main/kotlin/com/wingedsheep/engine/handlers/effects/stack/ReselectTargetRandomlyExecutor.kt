package com.wingedsheep.engine.handlers.effects.stack

import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.ReselectTargetRandomlyEffect
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import kotlin.reflect.KClass

/**
 * Executor for ReselectTargetRandomlyEffect.
 * "If it has a single target, reselect its target at random."
 *
 * Uses context.triggeringEntityId to find the spell/ability on the stack.
 * If it has exactly one target, finds all legal targets and randomly picks one.
 * If it has zero or multiple targets, does nothing.
 */
class ReselectTargetRandomlyExecutor : EffectExecutor<ReselectTargetRandomlyEffect> {

    override val effectType: KClass<ReselectTargetRandomlyEffect> = ReselectTargetRandomlyEffect::class

    private val targetFinder = TargetFinder()

    override fun execute(
        state: GameState,
        effect: ReselectTargetRandomlyEffect,
        context: EffectContext
    ): EffectResult {
        // 1. Get the triggering spell/ability from context
        val triggeringEntityId = context.triggeringEntityId
            ?: return EffectResult.success(state)

        val stackEntity = state.getEntity(triggeringEntityId)
            ?: return EffectResult.success(state)

        // 2. Check if it has exactly one target
        val targetsComponent = stackEntity.get<TargetsComponent>()
            ?: return EffectResult.success(state)

        val spellTargets = targetsComponent.targets
        if (spellTargets.size != 1) {
            return EffectResult.success(state)
        }

        val currentTarget = spellTargets.first()
        val targetRequirements = targetsComponent.targetRequirements

        // 3. Find all legal targets
        val legalTargets = findLegalTargets(
            state = state,
            currentTarget = currentTarget,
            targetRequirements = targetRequirements,
            controllerId = context.controllerId,
            sourceId = triggeringEntityId,
            predicateContext = predicateContextForRetarget(state, triggeringEntityId, context),
        )

        if (legalTargets.isEmpty()) {
            // No legal targets at all — keep current target
            return EffectResult.success(state)
        }

        // 4. Randomly pick one (may be the same as current — per ruling)
        val (chosenTargetId, stateAfterPick) = state.nextRandom { pick(legalTargets) }

        // 5. Build the new ChosenTarget based on what was chosen
        val newTarget = buildChosenTarget(stateAfterPick, chosenTargetId, currentTarget)
            ?: return EffectResult.success(stateAfterPick)

        // 6. Update the target on the stack entity
        val newTargetsComponent = TargetsComponent.capture(
            stateAfterPick,
            listOf(newTarget),
            targetsComponent.targetRequirements
        )
        val newState = stateAfterPick.updateEntity(triggeringEntityId) { container ->
            container.with(newTargetsComponent)
        }

        // 7. Emit event for the game log
        val spellName = stackEntity.get<CardComponent>()?.name
            ?: stackEntity.get<com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent>()?.sourceName
            ?: stackEntity.get<com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent>()?.sourceName
            ?: "spell or ability"
        val oldTargetId = getTargetEntityId(currentTarget)
        val newTargetId = getTargetEntityId(newTarget)
        val oldTargetName = oldTargetId?.let { resolveEntityName(state, it) } ?: "unknown"
        val newTargetName = newTargetId?.let { resolveEntityName(state, it) } ?: "unknown"
        val sourceName = context.sourceId?.let { state.getEntity(it)?.get<CardComponent>()?.name } ?: "Grip of Chaos"

        val events = if (oldTargetId != newTargetId) {
            listOf(com.wingedsheep.engine.core.TargetReselectedEvent(
                spellOrAbilityName = spellName,
                oldTargetName = oldTargetName,
                newTargetName = newTargetName,
                sourceName = sourceName
            ))
        } else {
            emptyList()
        }

        return EffectResult.success(newState, events)
    }

    /**
     * Find all legal targets for the spell/ability (including the current target).
     * Per ruling: "If there are multiple legal targets, it may still choose the original one."
     */
    private fun findLegalTargets(
        state: GameState,
        currentTarget: ChosenTarget,
        targetRequirements: List<TargetRequirement>,
        controllerId: EntityId,
        sourceId: EntityId,
        predicateContext: PredicateContext,
    ): List<EntityId> {
        val requirement = targetRequirements.firstOrNull()
        return if (requirement != null) {
            targetFinder.findLegalTargets(
                state = state,
                requirement = requirement,
                controllerId = controllerId,
                sourceId = sourceId,
                pipelineContext = predicateContext,
                requireAuthoritativeContext = true,
            )
        } else {
            // A malformed/legacy stack payload without a requirement retains the old structural
            // fallback, but a real requirement always goes through the authoritative context gate.
            findTargetsByCurrentType(state, currentTarget)
        }
    }

    private fun predicateContextForRetarget(
        state: GameState,
        stackObjectId: EntityId,
        context: EffectContext,
    ): PredicateContext {
        val base = PredicateContext.fromEffectContext(context)
        val container = state.getEntity(stackObjectId)
        container?.get<SpellOnStackComponent>()?.let { spell ->
            return base.copy(
                sourceId = stackObjectId,
                xValue = spell.xValue,
            )
        }
        container?.get<ActivatedAbilityOnStackComponent>()?.let { ability ->
            return base.copy(
                sourceId = stackObjectId,
                xValue = ability.xValue,
            )
        }
        container?.get<TriggeredAbilityOnStackComponent>()?.let { ability ->
            return base.copy(
                sourceId = stackObjectId,
                triggeringEntityId = ability.triggeringEntityId,
                triggeringPlayerId = ability.triggeringPlayerId,
                xValue = ability.xValue,
                chosenValues = ability.carriedPipeline?.chosenValues ?: emptyMap(),
                storedStringLists = ability.carriedPipeline?.storedStringLists ?: emptyMap(),
                storedSubtypeGroups = ability.carriedPipeline?.storedSubtypeGroups ?: emptyMap(),
                storedCollections = ability.carriedPipeline?.storedCollections ?: emptyMap(),
            )
        }
        return base.copy(sourceId = stackObjectId, xValue = null)
    }

    private fun findTargetsByCurrentType(
        state: GameState,
        currentTarget: ChosenTarget,
    ): List<EntityId> {
        return when (currentTarget) {
            is ChosenTarget.Permanent -> {
                state.getBattlefield()
            }
            is ChosenTarget.Player -> {
                state.turnOrder.filter { state.hasEntity(it) }
            }
            else -> {
                val id = getTargetEntityId(currentTarget)
                if (id != null) listOf(id) else emptyList()
            }
        }
    }

    private fun buildChosenTarget(
        state: GameState,
        chosenId: EntityId,
        currentTarget: ChosenTarget
    ): ChosenTarget? {
        // Check if it's a player
        if (state.turnOrder.contains(chosenId)) {
            return ChosenTarget.Player(chosenId)
        }
        // Check if it's on the battlefield
        if (state.getBattlefield().contains(chosenId)) {
            return ChosenTarget.Permanent(chosenId)
        }
        // Check if it's on the stack
        if (state.stack.contains(chosenId)) {
            return ChosenTarget.Spell(chosenId)
        }
        // Fallback: keep structure of current target
        return when (currentTarget) {
            is ChosenTarget.Permanent -> ChosenTarget.Permanent(chosenId)
            is ChosenTarget.Player -> ChosenTarget.Player(chosenId)
            is ChosenTarget.Spell -> ChosenTarget.Spell(chosenId)
            is ChosenTarget.Card -> ChosenTarget.Card(chosenId, currentTarget.ownerId, currentTarget.zone)
        }
    }

    private fun getTargetEntityId(target: ChosenTarget): EntityId? {
        return when (target) {
            is ChosenTarget.Permanent -> target.entityId
            is ChosenTarget.Player -> target.playerId
            is ChosenTarget.Card -> target.cardId
            is ChosenTarget.Spell -> target.spellEntityId
        }
    }

    private fun resolveEntityName(state: GameState, entityId: EntityId): String {
        val entity = state.getEntity(entityId) ?: return "unknown"
        // Check if it's a card (permanent, spell, etc.)
        entity.get<CardComponent>()?.name?.let { return it }
        // Check if it's a player
        entity.get<com.wingedsheep.engine.state.components.identity.PlayerComponent>()?.let {
            return it.name
        }
        return "unknown"
    }
}

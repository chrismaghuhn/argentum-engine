package com.wingedsheep.engine.handlers.effects.stack

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.mechanics.targeting.TargetValidator
import com.wingedsheep.engine.mechanics.targeting.pendingTargetRequirementInfo
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CopyTargetTriggeredAbilityEffect
import kotlin.reflect.KClass

/**
 * Executor for [CopyTargetTriggeredAbilityEffect].
 *
 * Copies a targeted triggered ability on the stack and pushes the copy as a new
 * [TriggeredAbilityOnStackComponent] entity. If the original ability has targets, the
 * copy's controller may choose new targets (Rule 707.10c). Modal choices and inherited
 * values (triggering entity, X, counters, etc.) are preserved per Rule 707.10.
 *
 * Per Rule 707.10: "A copy of a spell or ability isn't cast or activated. The copy is
 * created on the stack. The copy's controller is the controller of the spell or ability
 * that created it."
 */
class CopyTargetTriggeredAbilityExecutor(
    private val cardRegistry: CardRegistry,
    private val targetFinder: TargetFinder = TargetFinder()
) : EffectExecutor<CopyTargetTriggeredAbilityEffect> {

    override val effectType: KClass<CopyTargetTriggeredAbilityEffect> =
        CopyTargetTriggeredAbilityEffect::class

    private val targetValidator = TargetValidator()

    override fun execute(
        state: GameState,
        effect: CopyTargetTriggeredAbilityEffect,
        context: EffectContext
    ): EffectResult {
        val abilityEntityId = context.resolveTarget(effect.target)
            ?: return EffectResult.error(state, "No target triggered ability to copy")

        val container = state.getEntity(abilityEntityId)
            ?: return EffectResult.error(state, "Target ability entity not found on stack")

        val sourceAbility = container.get<TriggeredAbilityOnStackComponent>()
            ?: return EffectResult.error(state, "Target entity is not a triggered ability on stack")

        val targetsComponent = container.get<TargetsComponent>()
        val targetRequirements = targetsComponent?.targetRequirements ?: emptyList()

        // No targets — clone directly and push
        if (targetRequirements.isEmpty()) {
            val copy = cloneAbility(sourceAbility, context.controllerId)
            val stackResolver = StackResolver(cardRegistry = cardRegistry)
            return EffectResult.from(stackResolver.putTriggeredAbility(state, copy))
        }

        // Targets exist — prompt the copy controller to choose new targets.
        return promptForCopyTargets(state, context, abilityEntityId, targetRequirements)
    }

    private fun promptForCopyTargets(
        state: GameState,
        context: EffectContext,
        abilityEntityId: EntityId,
        targetRequirements: List<com.wingedsheep.sdk.scripting.targets.TargetRequirement>
    ): EffectResult {
        val legalTargetsMap = mutableMapOf<Int, List<EntityId>>()
        val sourcePredicateContext = state.getEntity(abilityEntityId)
            ?.get<TriggeredAbilityOnStackComponent>()
            ?.let { source ->
                com.wingedsheep.engine.handlers.PredicateContext(
                    controllerId = context.controllerId,
                    sourceId = abilityEntityId,
                    triggeringEntityId = source.triggeringEntityId,
                    triggeringPlayerId = source.triggeringPlayerId,
                    xValue = source.xValue,
                    storedCollections = source.carriedPipeline?.storedCollections ?: emptyMap(),
                    chosenValues = source.carriedPipeline?.chosenValues ?: emptyMap(),
                    storedStringLists = source.carriedPipeline?.storedStringLists ?: emptyMap(),
                    storedSubtypeGroups = source.carriedPipeline?.storedSubtypeGroups ?: emptyMap(),
                )
            }
            ?: com.wingedsheep.engine.handlers.PredicateContext.fromEffectContext(context)
        for ((index, requirement) in targetRequirements.withIndex()) {
            val legalTargets = targetFinder.findLegalTargets(
                state = state,
                requirement = requirement,
                controllerId = context.controllerId,
                sourceId = context.sourceId,
                pipelineContext = sourcePredicateContext,
                requireAuthoritativeContext = true,
            )
            legalTargetsMap[index] = legalTargets
        }

        val sourceAbility = state.getEntity(abilityEntityId)
            ?.get<TriggeredAbilityOnStackComponent>()
        val pendingTargetContext = context.copy(
            sourceId = abilityEntityId,
            xValue = sourceAbility?.xValue ?: context.xValue,
            triggeringEntityId = sourceAbility?.triggeringEntityId ?: context.triggeringEntityId,
            triggeringPlayerId = sourceAbility?.triggeringPlayerId ?: context.triggeringPlayerId,
            pipeline = sourceAbility?.carriedPipeline ?: context.pipeline,
        )
        val targetReqInfos = targetRequirements.mapIndexed { index, requirement ->
            targetValidator.pendingTargetRequirementInfo(
                state = state,
                index = index,
                requirement = requirement,
                context = pendingTargetContext,
                legalTargetCount = legalTargetsMap[index].orEmpty().size,
            ).orReturnUnsupported { return it.toEffectError(state) }
        }

        // If no legal targets for any requirement, skip copy (no-op).
        if (legalTargetsMap.any { (_, targets) -> targets.isEmpty() }) {
            return EffectResult.success(state)
        }

        val decisionId = "copy-triggered-ability-target-${System.nanoTime()}"
        val sourceName = state.getEntity(abilityEntityId)
            ?.get<TriggeredAbilityOnStackComponent>()?.sourceName ?: "ability"

        val continuation = CopyTriggeredAbilityTargetContinuation(
            decisionId = decisionId,
            abilityEntityId = abilityEntityId,
            controllerId = context.controllerId,
            targetRequirements = targetRequirements
        )

        val decision = ChooseTargetsDecision(
            id = decisionId,
            playerId = context.controllerId,
            prompt = "Choose new targets for copy of $sourceName's ability",
            context = DecisionContext(
                phase = DecisionPhase.CASTING,
                sourceName = sourceName,
                effectHint = "Copy of triggered ability"
            ),
            targetRequirements = targetReqInfos,
            legalTargets = legalTargetsMap
        )

        val stateWithDecision = state.withPendingDecision(decision)
        val stateWithContinuation = stateWithDecision.pushContinuation(continuation)

        return EffectResult.paused(stateWithContinuation, decision)
    }

    companion object {
        /**
         * Clone a source triggered ability into a fresh component. The copy inherits
         * every cast-time value (triggering entity, X, counter counts, modal choices,
         * chosen modes, damage distribution) per Rule 707.10, and is controlled
         * by [copyController] per Rule 707.10.
         */
        fun cloneAbility(
            source: TriggeredAbilityOnStackComponent,
            copyController: EntityId
        ): TriggeredAbilityOnStackComponent {
            return source.copy(
                controllerId = copyController,
                description = "Copy of ${source.description}"
            )
        }
    }
}

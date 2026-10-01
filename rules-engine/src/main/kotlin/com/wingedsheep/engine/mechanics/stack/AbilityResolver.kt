package com.wingedsheep.engine.mechanics.stack

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutorRegistry
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.handlers.TargetingSourceType
import com.wingedsheep.engine.handlers.effects.library.ChooseCreatureTypePipelineExecutor
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.*
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.targets.*

/**
 * Resolves a triggered or activated ability (CR 608.2): the intervening-"if" re-check (CR 608.2a)
 * for a triggered ability, the target re-check (CR 608.2b), then its effect.
 */
internal class AbilityResolver(
    private val effects: EffectExecutorRegistry,
    private val targetValidator: ResolutionTargetValidator,
    private val conditionEvaluator: ConditionEvaluator
) {
    /** Evaluates a triggered ability's intervening-"if" as it resolves (CR 603.4). */
    /**
     * Resolve a triggered ability.
     */
    fun resolveTriggeredAbility(
        state: GameState,
        abilityId: EntityId,
        container: ComponentContainer
    ): ExecutionResult {
        val abilityComponent = container.get<TriggeredAbilityOnStackComponent>()!!
        val targetsComponent = container.get<TargetsComponent>()

        // The resolution-time context the two checks below and the effect itself all read: trigger
        // payload, last-known info, captured batch and all. Built up front because CR 608.2a's
        // intervening-"if" is evaluated against it *before* CR 608.2b touches the targets. The
        // targets it carries are the stored ones — having a stored target in this context does not
        // make it legal; legality is 608.2b's business, not the context's.
        val chosenTargets = targetsComponent?.targets ?: emptyList()
        val targetReqs = targetsComponent?.targetRequirements ?: emptyList()
        var context = EffectContext.forTriggeredAbility(
            abilityComponent,
            targets = chosenTargets,
            targetRequirements = targetReqs,
            state = state
        ).forAbilityResolution(state, abilityId)

        // CR 608.2a, then CR 608.2b — in that lettered order. 608.2a: "If a triggered ability has
        // an intervening 'if' clause, it checks whether the clause's condition is true. If it
        // isn't, the ability is removed from the stack and does nothing. Otherwise, it continues to
        // resolve." Only a *continuing* resolution reaches 608.2b's target-legality check, so when
        // both have gone false the intervening-"if" is what ends the resolution and what the fizzle
        // reports.

        // CR 608.2a / CR 603.4's second check. Fizzles through the same event as an illegal-target
        // fizzle rather than silently doing nothing.
        //
        // Only [TriggeredAbilityOnStackComponent.interveningIf] reaches here. A
        // `triggerRestriction` ("...attacks *while* you control a Dinosaur") is a CR 603.2
        // restriction on the trigger event and was already spent when the ability triggered;
        // re-checking it would fizzle abilities that must resolve.
        abilityComponent.interveningIf?.let { condition ->
            if (!conditionEvaluator.evaluate(state, condition, context)) {
                return abilityFizzled(
                    state, abilityId, abilityComponent.sourceId, abilityComponent.description,
                    "Intervening-if condition is no longer true",
                    sourceEndpointAuthority = abilityComponent.sourceEndpointAuthority,
                    sourceObjectIncarnationStamp = abilityComponent.sourceObjectIncarnationStamp,
                )
            }
        }

        // CR 608.2b — validate targets (including protection check, CR 702.16). A malformed locked
        // payload (a mandatory slot with no target) is still a target payload and is rejected here.
        val sourceCard = state.getEntity(abilityComponent.sourceId)?.get<CardComponent>()
        val sourceColors = sourceCard?.colors ?: emptySet()
        val sourceSubtypes = sourceCard?.typeLine?.subtypes?.map { it.value }?.toSet() ?: emptySet()
        if (targetsComponent?.hasResolutionTargetPayload() == true) {
            val triggerContext = abilityComponent.triggerContext
            val resolutionPayload = targetValidator.filterAtResolution(
                state = state,
                targets = targetsComponent.targets,
                requirements = targetsComponent.targetRequirements,
                casterId = abilityComponent.controllerId,
                sourceColors = sourceColors,
                sourceSubtypes = sourceSubtypes,
                sourceId = abilityComponent.sourceId,
                xValue = abilityComponent.xValue,
                targetEntryStamps = targetsComponent.targetEntryStamps,
                targetingSourceType = TargetingSourceType.TRIGGERED_ABILITY,
                triggeringEntityId = triggerContext?.triggeringEntityId,
                triggeringPlayerId = triggerContext?.triggeringPlayerId,
                // The trigger event's defending player and damage source / recipient, so a filter
                // naming them ("target creature defending player controls", "the source of that
                // damage") re-checks against the same event-time objects it was chosen against.
                defendingPlayerId = triggerContext?.defendingPlayerId,
                damageSourceId = triggerContext?.damageSourceEntityId,
                damageRecipientId = triggerContext?.damageRecipientEntityId,
                damageRecipientKind = triggerContext?.damageRecipientKind ?: DamageRecipientKind.UNKNOWN,
                damageRecipientKinds = triggerContext?.effectiveDamageRecipientKinds
                    ?: DamageRecipientKindSet.UNKNOWN,
                damageSourceLastKnownSnapshot = triggerContext?.damageSourceLastKnownSnapshot,
                damageRecipientLastKnownSnapshot = triggerContext?.damageRecipientLastKnownSnapshot,
                storedCollections = abilityComponent.carriedPipeline?.storedCollections ?: emptyMap(),
            )
            if (resolutionPayload.targets.isEmpty()) {
                // Fizzle - remove ability entity
                return abilityFizzled(
                    state, abilityId, abilityComponent.sourceId, abilityComponent.description,
                    "All targets are invalid",
                    sourceEndpointAuthority = abilityComponent.sourceEndpointAuthority,
                    sourceObjectIncarnationStamp = abilityComponent.sourceObjectIncarnationStamp,
                )
            }
            val aligned = resolutionPayload.alignedTargets
            context = context.copy(
                targets = resolutionPayload.targets,
                alignedTargets = aligned,
                targetEntryStamps = targetsComponent.targetEntryStamps,
                pipeline = context.pipeline.copy(
                    namedTargets = EffectContext.buildNamedTargets(targetReqs, aligned) +
                        (abilityComponent.carriedPipeline?.namedTargets ?: emptyMap()),
                ),
            )
        }

        // Execute the effect after CR 608.2a and CR 608.2b have both passed.
        return executeThenLeaveStack(
            state, abilityId, abilityComponent.effect, context,
            resolvedEvents = listOf(
                AbilityResolvedEvent(
                    abilityComponent.sourceId,
                    abilityComponent.description
                )
            ) + sagaChapterResolvedEvents(abilityComponent)
        )
    }

    /**
     * Resolve an activated ability.
     */
    fun resolveActivatedAbility(
        state: GameState,
        abilityId: EntityId,
        container: ComponentContainer
    ): ExecutionResult {
        val abilityComponent = container.get<ActivatedAbilityOnStackComponent>()!!
        val targetsComponent = container.get<TargetsComponent>()

        // Validate targets (including protection check - Rule 702.16)
        val sourceCard = state.getEntity(abilityComponent.sourceId)?.get<CardComponent>()
        val sourceColors = sourceCard?.colors ?: emptySet()
        val sourceSubtypes = sourceCard?.typeLine?.subtypes?.map { it.value }?.toSet() ?: emptySet()
        val activatedReqs = targetsComponent?.targetRequirements ?: emptyList()
        // Resolution-time legality (CR 608.2b): drop individually-illegal targets, not just the
        // all-invalid fizzle. `activatedTargets` is the compacted list every executor reads as
        // `context.targets`; `alignedActivatedTargets` keeps a `null` in each dropped slot so a
        // sub-effect referencing a now-illegal target through its [EffectTarget.BoundVariable]
        // resolves to `null` and fizzles (mirrors resolveSpell) instead of silently consuming a
        // still-legal later target whose position shifted forward in the compacted list — e.g.
        // Stiltzkin's "If they do, you draw" when the donated permanent left in response.
        val activatedTargets: List<ChosenTarget>
        val alignedActivatedTargets: List<ChosenTarget?>
        if (targetsComponent?.hasResolutionTargetPayload() == true) {
            val resolutionPayload = targetValidator.filterAtResolution(
                state = state,
                targets = targetsComponent.targets,
                requirements = targetsComponent.targetRequirements,
                casterId = abilityComponent.controllerId,
                sourceColors = sourceColors,
                sourceSubtypes = sourceSubtypes,
                sourceId = abilityComponent.sourceId,
                xValue = abilityComponent.xValue,
                targetEntryStamps = targetsComponent.targetEntryStamps,
                targetingSourceType = TargetingSourceType.ACTIVATED_ABILITY,
            )
            if (resolutionPayload.targets.isEmpty()) {
                return abilityFizzled(
                    state, abilityId, abilityComponent.sourceId, abilityComponent.sourceName,
                    "All targets are invalid",
                    sourceEndpointAuthority = abilityComponent.sourceEndpointAuthority,
                    sourceObjectIncarnationStamp = abilityComponent.sourceObjectIncarnationStamp,
                )
            }
            activatedTargets = resolutionPayload.targets
            alignedActivatedTargets = resolutionPayload.alignedTargets
        } else {
            activatedTargets = targetsComponent?.targets ?: emptyList()
            alignedActivatedTargets = activatedTargets
        }

        // Execute the effect
        val context = activatedAbilityContext(
            state, abilityId, abilityComponent, activatedReqs, activatedTargets, alignedActivatedTargets,
            targetEntryStamps = targetsComponent?.targetEntryStamps ?: emptyMap()
        )
        return executeThenLeaveStack(
            state, abilityId, abilityComponent.effect, context,
            resolvedEvents = listOf(
                AbilityResolvedEvent(
                    abilityComponent.sourceId,
                    abilityComponent.sourceName
                )
            )
        )
    }

    private fun activatedAbilityContext(
        state: GameState,
        abilityId: EntityId,
        abilityComponent: ActivatedAbilityOnStackComponent,
        activatedReqs: List<TargetRequirement>,
        activatedTargets: List<ChosenTarget>,
        alignedActivatedTargets: List<ChosenTarget?>,
        targetEntryStamps: Map<EntityId, Long>
    ): EffectContext =
        EffectContext(
            sourceId = abilityComponent.sourceId,
            controllerId = abilityComponent.controllerId,
            granterId = abilityComponent.granterId,
            abilityIdentity = abilityComponent.abilityIdentity,
            activatedAbility = abilityComponent.activatedAbility,
            sourceFaceChanges = abilityComponent.sourceFaceChanges,
            sourceBattlefieldTimestamp = abilityComponent.sourceBattlefieldTimestamp,
            objectReferences = abilityComponent.objectReferences,
            targets = activatedTargets,
            alignedTargets = alignedActivatedTargets,
            // Identity stamps of the locked targets (CR 400.7), so a nested re-check made while the
            // ability resolves can tell a target that left and came back from the one chosen.
            targetEntryStamps = targetEntryStamps,
            targetingSourceType = TargetingSourceType.ACTIVATED_ABILITY,
            sacrificedPermanents = abilityComponent.sacrificedPermanents,
            xValue = abilityComponent.xValue,
            tappedPermanents = abilityComponent.tappedPermanents,
            tappedEntitySnapshots = abilityComponent.tappedEntitySnapshots,
            exiledAsCostCards = abilityComponent.exiledAsCostCards,
            discardedAsCostCards = abilityComponent.discardedAsCostCards,
            countersRemovedAsCost = abilityComponent.countersRemovedAsCost,
            lastKnownSourceCounters = abilityComponent.lastKnownSourceCounters,
            lastKnownSourceSnapshot = abilityComponent.lastKnownSourceSnapshot,
            lastKnownSourceAttachments = abilityComponent.lastKnownSourceAttachments,
            damageDistribution = abilityComponent.damageDistribution,
            pipeline = PipelineState(
                namedTargets = EffectContext.buildNamedTargets(activatedReqs, alignedActivatedTargets),
                // A "Reveal the creature type you chose" cost hands its type to the effect under the
                // same key a mid-pipeline ChooseOption write uses, so CardPredicate
                // .HasSubtypeFromVariable reads it without knowing where it came from.
                chosenValues = abilityComponent.revealedNotedCreatureType
                    ?.let { mapOf(ChooseCreatureTypePipelineExecutor.CHOSEN_CREATURE_TYPE_KEY to it) }
                    ?: emptyMap()
            )
        ).forAbilityResolution(state, abilityId)

    /**
     * An ability that ends its resolution early (CR 608.2a / 608.2b) is removed from the stack and
     * does nothing; it reports an [AbilityFizzledEvent] with [reason], carrying the stack object's
     * source endpoint authority and source incarnation so a history consumer can bind the fizzle to
     * the exact source object the ability came from.
     */
    private fun abilityFizzled(
        state: GameState,
        abilityId: EntityId,
        sourceId: EntityId,
        description: String,
        reason: String,
        sourceEndpointAuthority: AbilityTriggeredSourceEndpointAuthority?,
        sourceObjectIncarnationStamp: Long?,
    ): ExecutionResult =
        ExecutionResult.success(
            state.removeEntity(abilityId),
            listOf(
                AbilityFizzledEvent(
                    sourceId = sourceId,
                    description = description,
                    reason = reason,
                    sourceEndpointAuthority = sourceEndpointAuthority,
                    sourceObjectIncarnationStamp = sourceObjectIncarnationStamp,
                )
            )
        )

    /**
     * Run the ability's effect, then remove the ability from the stack (CR 608.2n) and report
     * [resolvedEvents] after the effect's own events. Any unsupported-path diagnostics the effect
     * recorded travel on the result either way.
     */
    private fun executeThenLeaveStack(
        state: GameState,
        abilityId: EntityId,
        effect: Effect,
        context: EffectContext,
        resolvedEvents: List<GameEvent>
    ): ExecutionResult {
        val effectResult = effects.execute(state, effect, context)

        // If effect is paused awaiting a decision, return paused state
        // The ability entity stays removed (it's off the stack), but the decision must resolve
        if (effectResult.outcome is Outcome.Paused) {
            val pausedState = effectResult.state.removeEntity(abilityId)
            return ExecutionResult.propagatePause(
                pausedState,
                effectResult.events,
                diagnostics = effectResult.diagnostics
            )
        }

        var newState = effectResult.newState

        // Remove the ability entity
        newState = newState.removeEntity(abilityId)
        return ExecutionResult.success(
            newState,
            effectResult.events + resolvedEvents,
            diagnostics = effectResult.diagnostics
        )
    }

    /**
     * A Saga chapter ability resolving emits SagaChapterResolvedEvent so "whenever the final
     * chapter ability of a Saga you control resolves" triggers (Tom Bombadil) can detect it.
     */
    private fun sagaChapterResolvedEvents(abilityComponent: TriggeredAbilityOnStackComponent): List<GameEvent> =
        abilityComponent.sagaChapterInfo?.let { info ->
            listOf(
                SagaChapterResolvedEvent(
                    sagaId = abilityComponent.sourceId,
                    controllerId = abilityComponent.controllerId,
                    chapterNumber = info.chapterNumber,
                    finalChapterNumber = info.finalChapterNumber,
                    isFinalChapter = info.isFinalChapter
                )
            )
        } ?: emptyList()
}

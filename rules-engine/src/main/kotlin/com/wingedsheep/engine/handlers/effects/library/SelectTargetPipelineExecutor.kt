package com.wingedsheep.engine.handlers.effects.library

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.mechanics.targeting.TargetValidator
import com.wingedsheep.engine.mechanics.targeting.pendingTargetRequirementInfo
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.SelectTargetEffect
import kotlin.reflect.KClass

/**
 * Executor for SelectTargetEffect — mid-resolution pipeline targeting.
 *
 * Finds legal targets using [TargetFinder], then:
 * - **No legal targets** → stores empty collection, pipeline continues
 * - **Single legal target (non-optional)** → auto-selects, stores in [updatedCollections]
 * - **Multiple legal targets, or a single one the player may decline** → creates
 *   [ChooseTargetsDecision], pushes [SelectTargetPipelineContinuation], returns paused
 *
 * The requirement is single-target by construction — [createDecision] offers one slot — so a
 * requirement asking for more is rejected up front rather than turned into a decision no response
 * can satisfy.
 */
class SelectTargetPipelineExecutor(
    private val targetFinder: TargetFinder,
    /** Builds the pending target metadata; defaults to one over the finder's (the engine's) evaluator. */
    private val targetValidator: TargetValidator = TargetValidator(targetFinder.predicateEvaluator),
) : EffectExecutor<SelectTargetEffect> {

    override val effectType: KClass<SelectTargetEffect> = SelectTargetEffect::class

    override fun execute(
        state: GameState,
        effect: SelectTargetEffect,
        context: EffectContext
    ): EffectResult {
        val controllerId = context.controllerId
        val sourceId = context.sourceId

        val legalTargets = try {
            targetFinder.findLegalTargets(
                state = state,
                requirement = effect.requirement,
                controllerId = controllerId,
                sourceId = sourceId,
                // A non-targeting choice ("choose a player") isn't limited by hexproof or shroud.
                ignoreTargetingRestrictions = effect.nonTargeting,
                // The resolving ability's whole predicate context — its granter, so a target filter
                // can exclude it via StatePredicate.IsGrantingPermanent (Dire Blunderbuss's "an
                // artifact other than Dire Blunderbuss", CR 201.5a), its pipeline values and trigger
                // facts — so every filter is evaluated against authoritative facts or fails closed.
                pipelineContext = com.wingedsheep.engine.handlers.PredicateContext.fromEffectContext(context),
                requireAuthoritativeContext = true,
            )
        } catch (unsupported: UnsupportedPathFailure) {
            // A missing predicate fact is not an ordinary empty collection. Preserve the typed
            // diagnostic at this direct effect boundary so callers can fail closed without
            // interpreting the gap as a no-target success.
            return EffectResult.error(
                state = state,
                message = unsupported.message ?: "Authoritative target predicate context is unavailable",
                diagnostics = unsupported.diagnostics,
            )
        }

        // This executor is the pending-decision seam: even an empty candidate list must first pass
        // the authoritative metadata conversion. Otherwise an unresolved X/count or aggregate
        // constraint can silently become a successful empty collection instead of a fail-closed
        // unsupported result. Synthesized Discover/Cascade casts use a separate executor and keep
        // their required-no-target fallback before metadata conversion.
        val requirementInfo = targetValidator.pendingTargetRequirementInfo(
            state = state,
            index = 0,
            requirement = effect.requirement,
            context = context,
            legalTargetCount = legalTargets.size,
            // The client shows this line as the prompt; an authored prompt says what the choice is for.
            description = effect.prompt ?: effect.requirement.description,
        ).orReturnUnsupported { return it.toEffectError(state) }

        if (legalTargets.isEmpty()) {
            // No legal targets — store empty collection, pipeline continues gracefully.
            // The metadata gate above has already established that no unsupported domain was
            // hidden by this no-op.
            return EffectResult.success(state).copy(
                updatedCollections = mapOf(effect.storeAs to emptyList())
            )
        }

        if (legalTargets.size == 1 && effect.requirement.requiresExactlyOneTarget) {
            // Single mandatory legal target — auto-select
            return EffectResult.success(state).copy(
                updatedCollections = mapOf(effect.storeAs to legalTargets)
            )
        }

        // Multiple legal targets or an optional singleton — pause for player decision
        return createDecision(state, context, effect, legalTargets, requirementInfo)
    }

    private fun createDecision(
        state: GameState,
        context: EffectContext,
        effect: SelectTargetEffect,
        legalTargets: List<EntityId>,
        requirementInfo: TargetRequirementInfo,
    ): EffectResult {
        val controllerId = context.controllerId
        val sourceName = context.sourceId?.let { state.getEntity(it)?.get<CardComponent>()?.name }

        require(effect.requirement.count == 1) {
            "SelectTargetEffect offers one target slot, but ${effect.requirement.description} asks " +
                "for ${effect.requirement.count}"
        }

        val decision = { decisionId: String -> ChooseTargetsDecision(
            id = decisionId,
            playerId = controllerId,
            prompt = effect.description,
            context = DecisionContext(
                sourceId = context.sourceId,
                sourceName = sourceName,
                phase = DecisionPhase.RESOLUTION
            ),
            targetRequirements = listOf(requirementInfo),
            legalTargets = mapOf(0 to legalTargets)
        ) }

        val continuation = SelectTargetPipelineContinuation(
            playerId = controllerId,
            sourceId = context.sourceId,
            objectReferences = context.objectReferences,
            sourceName = sourceName,
            storeAs = effect.storeAs,
            storedCollections = context.pipeline.storedCollections
        )

        return EffectResult.from(state.suspendForDecision(decision, continuation))
    }
}

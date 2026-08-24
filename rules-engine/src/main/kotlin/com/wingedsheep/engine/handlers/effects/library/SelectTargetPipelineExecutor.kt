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
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Executor for SelectTargetEffect — mid-resolution pipeline targeting.
 *
 * Finds legal targets using [TargetFinder], then:
 * - **No legal targets** → stores empty collection, pipeline continues
 * - **Single legal target (non-optional)** → auto-selects, stores in [updatedCollections]
 * - **Multiple legal targets** → creates [ChooseTargetsDecision], pushes
 *   [SelectTargetPipelineContinuation], returns paused
 */
class SelectTargetPipelineExecutor(
    private val targetFinder: TargetFinder = TargetFinder(),
    private val targetValidator: TargetValidator = TargetValidator(),
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
                // Carry the resolving ability's granter so a target filter can exclude it via
                // StatePredicate.IsGrantingPermanent — e.g. Dire Blunderbuss's "an artifact other than
                // Dire Blunderbuss" (CR 201.5a). Only granterId is threaded; other context fields keep
                // their prior (null) defaults so no existing SelectTargetEffect changes behavior.
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
        ).orReturnUnsupported { return it.toEffectError(state) }

        if (legalTargets.isEmpty()) {
            // No legal targets — store empty collection, pipeline continues gracefully.
            // The metadata gate above has already established that no unsupported domain was
            // hidden by this no-op.
            return EffectResult.success(state).copy(
                updatedCollections = mapOf(effect.storeAs to emptyList())
            )
        }

        if (legalTargets.size == 1) {
            // Single legal target — auto-select
            return EffectResult.success(state).copy(
                updatedCollections = mapOf(effect.storeAs to legalTargets)
            )
        }

        // Multiple legal targets — pause for player decision
        return createDecision(state, context, effect, legalTargets, requirementInfo)
    }

    private fun createDecision(
        state: GameState,
        context: EffectContext,
        effect: SelectTargetEffect,
        legalTargets: List<EntityId>,
        requirementInfo: TargetRequirementInfo,
    ): EffectResult {
        val decisionId = UUID.randomUUID().toString()
        val controllerId = context.controllerId
        val sourceName = context.sourceId?.let { state.getEntity(it)?.get<CardComponent>()?.name }

        val decision = ChooseTargetsDecision(
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
        )

        val continuation = SelectTargetPipelineContinuation(
            decisionId = decisionId,
            playerId = controllerId,
            sourceId = context.sourceId,
            sourceName = sourceName,
            storeAs = effect.storeAs,
            storedCollections = context.pipeline.storedCollections
        )

        val stateWithDecision = state.withPendingDecision(decision)
        val stateWithContinuation = stateWithDecision.pushContinuation(continuation)

        return EffectResult.paused(
            stateWithContinuation,
            decision,
            listOf(
                DecisionRequestedEvent(
                    decisionId = decisionId,
                    playerId = controllerId,
                    decisionType = "CHOOSE_TARGETS",
                    prompt = decision.prompt
                )
            )
        )
    }
}

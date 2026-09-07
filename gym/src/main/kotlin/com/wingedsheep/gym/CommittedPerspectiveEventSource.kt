package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.history.HistoryCReferenceAuthority
import com.wingedsheep.gym.history.HistoryCReferenceAuthorityResult
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeV1
import com.wingedsheep.gym.history.HistoryCFailure
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.PerspectiveAliasRegistryV1
import com.wingedsheep.gym.history.PerspectiveReferenceProjectorV1
import com.wingedsheep.gym.history.PerspectiveReferenceProjectionResult

/**
 * Internal token produced only after one successful strict Rules transition.
 *
 * The states and raw events never cross the public model-facing contract. They are retained only
 * long enough for the perspective projector to make event-time visibility decisions.
 */
internal data class CommittedRulesTransition(
    val beforeState: GameState,
    val afterState: GameState,
    val events: List<GameEvent>,
    val sourceStepCount: Int,
)

/**
 * One-transition source seam for perspective-safe event batches.
 *
 * This is deliberately not a history accumulator. It records at most the most recent successful
 * strict Gym transition, never a reset, legacy simulation, fork, restore, failed action, or replay
 * reconstruction. The later history/knowledge/alias slices own accumulation and completeness.
 */
internal class CommittedPerspectiveEventSource(
    cardRegistry: CardRegistry,
    private val captureEnabled: Boolean = true,
) {
    private val projector = PerspectiveEventProjector(cardRegistry)
    private val referenceProjector = PerspectiveReferenceProjectorV1(cardRegistry)

    private var lastTransition: CommittedRulesTransition? = null

    var committedTransitionCount: Int = 0
        private set

    fun clear() {
        lastTransition = null
        committedTransitionCount = 0
    }

    fun capture(transition: CommittedRulesTransition) {
        if (!captureEnabled) return
        require(transition.sourceStepCount > 0) {
            "Committed transition source step count must be positive"
        }
        lastTransition = transition.copy(events = transition.events.toList())
        committedTransitionCount++
    }

    fun projectLast(perspectivePlayerId: EntityId): PerspectiveEventProjectionResult? {
        if (!captureEnabled) return null
        val transition = lastTransition ?: return null
        return projector.project(
            events = transition.events,
            perspectivePlayerId = perspectivePlayerId,
            beforeState = transition.beforeState,
            afterState = transition.afterState,
        )
    }

    /** Validate internal History-C candidates against the last committed before/after witnesses. */
    internal fun lastCommittedReferenceEvidence(
        envelope: HistoryCReferenceEnvelopeV1,
    ): HistoryCReferenceAuthorityResult {
        if (!captureEnabled) {
            return HistoryCReferenceAuthorityResult.Rejected(
                HistoryCFailure(HistoryCFailureCode.FORK_OR_SPECULATIVE_SOURCE),
            )
        }
        val transition = lastTransition ?: return HistoryCReferenceAuthorityResult.Rejected(
            HistoryCFailure(HistoryCFailureCode.UNCOMMITTED_TRANSITION),
        )
        val projection = checkNotNull(projectLast(envelope.perspectivePlayerId)) {
            "Committed History-C projection disappeared after capture"
        }
        return HistoryCReferenceAuthority.validate(transition, projection, envelope)
    }

    /**
     * Project the last successful committed transition through the accepted A+B+C seam.
     *
     * This adapter is internal and obtains A evidence from this source itself; callers cannot
     * turn arbitrary model-facing or speculative state into trusted History-C references.
     */
    internal fun lastCommittedReferenceProjection(
        semanticEpisodeId: String,
        registry: PerspectiveAliasRegistryV1,
        envelope: HistoryCReferenceEnvelopeV1,
    ): PerspectiveReferenceProjectionResult {
        if (!captureEnabled) {
            return PerspectiveReferenceProjectionResult.Rejected(
                HistoryCFailure(HistoryCFailureCode.FORK_OR_SPECULATIVE_SOURCE),
            )
        }
        val transition = lastTransition ?: return PerspectiveReferenceProjectionResult.Rejected(
            HistoryCFailure(HistoryCFailureCode.UNCOMMITTED_TRANSITION),
        )
        return when (val evidence = lastCommittedReferenceEvidence(envelope)) {
            is HistoryCReferenceAuthorityResult.Rejected ->
                PerspectiveReferenceProjectionResult.Rejected(evidence.failure)

            is HistoryCReferenceAuthorityResult.Accepted -> referenceProjector.project(
                semanticEpisodeId = semanticEpisodeId,
                perspectivePlayerId = envelope.perspectivePlayerId,
                transition = transition,
                evidence = evidence.evidence,
                registry = registry,
            )
        }
    }
}

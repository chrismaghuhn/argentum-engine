package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.PaymentPlanV2
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId

/**
 * Result of generic [PaymentPlanV2] validation and materialization.
 *
 * A failed result always carries the exact input state and no events. Callers can therefore
 * safely compose this seam before their action-specific side effects.
 */
data class ExplicitPaymentExecution(
    val state: GameState,
    val events: List<GameEvent>,
    val error: String?,
    val spentManaProvenance: SpentManaProvenance = SpentManaProvenance(),
)

/**
 * The action-agnostic Rules seam for the public ExplicitV2 payment carrier.
 *
 * This class deliberately knows neither CastSpell nor CycleCard. The caller owns the effective
 * cost, the non-null payment context, source exclusions, and the event reason. It validates the
 * submitted choices and materializes only those choices; it never asks the solver to choose a
 * replacement payment.
 */
class ExplicitPaymentPlanExecutor(
    private val manaSolver: ManaSolver,
    private val manaAbilitySideEffectExecutor: ManaAbilitySideEffectExecutor,
) {
    private val validator = PaymentPlanValidator(manaSolver)

    fun executeV2(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        plan: PaymentPlanV2,
        paymentContext: SpellPaymentContext,
        reason: String,
        excludeSources: Set<EntityId> = emptySet(),
    ): ExplicitPaymentExecution {
        val validation = validator.validateV2(
            state = state,
            playerId = playerId,
            cost = cost.canonicalPaymentManaCost(),
            plan = plan,
            spellContext = paymentContext,
            excludeSources = excludeSources,
        )
        val accepted = validation as? PaymentPlanValidation.Accepted
            ?: return ExplicitPaymentExecution(
                state = state,
                events = emptyList(),
                error = (validation as PaymentPlanValidation.Rejected).reason,
            )

        var currentState = state.updateEntity(playerId) { container ->
            container.with(fromManaPool(accepted.materialization.poolAfterFloatingSpend))
        }

        val events = mutableListOf<GameEvent>()
        if (accepted.materialization.sourcePayments.isNotEmpty()) {
            val sideEffectResult = manaAbilitySideEffectExecutor.tapSourcesWithSideEffects(
                state = currentState,
                solution = accepted.solution,
                controllerId = playerId,
            )
            if (!sideEffectResult.success) {
                return ExplicitPaymentExecution(
                    state = state,
                    events = emptyList(),
                    error = "PaymentPlanV2 source activation failed",
                )
            }
            currentState = sideEffectResult.state
            events.addAll(sideEffectResult.events)

            // Only after every selected source ability succeeds do the exact unspent outputs enter
            // the pool. This keeps source side effects and pool materialization atomic to callers.
            currentState = currentState.updateEntity(playerId) { container ->
                container.with(
                    fromManaPool(
                        accepted.materialization.poolAfterSuccessfulSourceProduction(playerId)
                    )
                )
            }
        }

        val spent = accepted.materialization.manaSpent
        events.add(
            ManaSpentEvent(
                playerId = playerId,
                reason = reason,
                white = spent.white,
                blue = spent.blue,
                black = spent.black,
                red = spent.red,
                green = spent.green,
                colorless = spent.colorless,
            )
        )
        return ExplicitPaymentExecution(
            state = currentState,
            events = events,
            error = null,
            spentManaProvenance = accepted.materialization.spentManaProvenance,
        )
    }
}

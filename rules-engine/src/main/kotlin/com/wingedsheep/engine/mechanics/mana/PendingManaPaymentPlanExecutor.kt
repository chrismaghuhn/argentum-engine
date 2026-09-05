package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId

/**
 * Executes the existing explicit V3 payment program when a pending mana-payment response carries
 * one. This is deliberately a narrow adapter, not a second payment language: the plan, validator,
 * source qualification, and ordered executor are the same authorities used by action payment.
 *
 * `null` means the caller received a legacy source-selection response and should retain its
 * historical non-trusted flow. A non-null result has either atomically executed the submitted
 * program or rejected it without mutating the input state.
 */
object PendingManaPaymentPlanExecutor {
    fun executeIfPresent(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        response: ManaSourcesSelectedResponse,
        services: EngineServices,
        reason: String,
        paymentContext: SpellPaymentContext = SpellPaymentContext(),
    ): ExplicitPaymentExecution? {
        val plan = response.paymentPlan ?: return null
        if (response.autoPay || response.selectedSources.isNotEmpty() ||
            response.waterbendPermanents.isNotEmpty() || response.declined
        ) {
            return ExplicitPaymentExecution(
                state = state,
                events = emptyList(),
                error = "Explicit pending payment cannot mix a V3 plan with legacy payment fields",
            )
        }
        return OrderedPaymentProgramExecutor(
            manaSolver = ManaSolver(services.cardRegistry),
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        ).executeV3(
            state = state,
            playerId = playerId,
            cost = cost,
            plan = plan,
            paymentContext = paymentContext,
            reason = reason,
        )
    }
}

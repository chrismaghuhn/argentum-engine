package com.wingedsheep.engine.handlers.actions.combat

import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.handlers.actions.ActionHandler
import com.wingedsheep.engine.state.GameState
import kotlin.reflect.KClass

/**
 * Compatibility decoder for the obsolete OrderBlockers action.
 *
 * Modern combat damage is assigned through CombatResolutionDecision. Keeping
 * this handler registered lets old serialized actions decode, but current
 * gameplay can never execute the old order mutation.
 */
class OrderBlockersHandler : ActionHandler<OrderBlockers> {
    override val actionType: KClass<OrderBlockers> = OrderBlockers::class

    private val obsoleteError =
        "Damage-assignment order is obsolete; submit combat damage assignments"

    override fun validate(state: GameState, action: OrderBlockers): String? = obsoleteError

    override fun execute(state: GameState, action: OrderBlockers): ExecutionResult =
        ExecutionResult.error(state, obsoleteError)
}

package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.core.GameLimits
import com.wingedsheep.engine.mechanics.mana.ManaColorSetResolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CommanderRegistryComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.values.ManaColorSet

/** Resolves dynamic quantities used by cost atoms at the point a cost is checked or paid. */
object CostAmountResolver {

    /**
     * Resolve a cost amount for [controllerId]. A commander-dependent amount is unavailable when
     * the player has no registered commander; an existing colorless commander intentionally
     * resolves to zero.
     */
    fun resolve(
        state: GameState,
        amount: DynamicAmount,
        sourceId: EntityId,
        controllerId: EntityId,
        cardRegistry: CardRegistry?,
    ): Int? {
        if (amount == DynamicAmount.CommanderColorIdentityCount) {
            val cards = cardRegistry ?: return null
            val commanderRegistry = state.getEntity(controllerId)?.get<CommanderRegistryComponent>()
            if (commanderRegistry == null || commanderRegistry.commanderIds.isEmpty()) return null
            return ManaColorSetResolver.resolve(
                colorSet = ManaColorSet.CommanderIdentity,
                state = state,
                projected = state.projectedState,
                sourceId = sourceId,
                controllerId = controllerId,
                cardRegistry = cards,
            ).size
        }

        // CostAtoms.times() represents a repeated dynamic payment as Multiply. Resolve the
        // commander-dependent leaf here so that scaling remains generic and does not fall into the
        // registry-free DynamicAmountEvaluator branch.
        if (amount is DynamicAmount.Multiply) {
            return resolve(state, amount.amount, sourceId, controllerId, cardRegistry)?.let {
                GameLimits.mulClamped(it, amount.multiplier)
            }
        }

        return DynamicAmountEvaluator().evaluate(
            state = state,
            amount = amount,
            context = EffectContext(sourceId = sourceId, controllerId = controllerId),
        )
    }
}

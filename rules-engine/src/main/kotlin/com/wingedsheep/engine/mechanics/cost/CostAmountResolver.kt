package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.mechanics.mana.ManaColorSetResolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
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
        val resolvedAmount = if (amount.containsCommanderColorIdentityCount()) {
            val commanderColorCount = resolveCommanderColorIdentityCount(
                state = state,
                sourceId = sourceId,
                controllerId = controllerId,
                cardRegistry = cardRegistry,
            ) ?: return null
            amount.replaceCommanderColorIdentityCount(commanderColorCount)
        } else {
            amount
        }

        return DynamicAmountEvaluator().evaluate(
            state = state,
            amount = resolvedAmount,
            context = EffectContext(sourceId = sourceId, controllerId = controllerId),
        )
    }

    private fun resolveCommanderColorIdentityCount(
        state: GameState,
        sourceId: EntityId,
        controllerId: EntityId,
        cardRegistry: CardRegistry?,
    ): Int? {
        val cards = cardRegistry ?: return null
        val commanderRegistry = state.getEntity(controllerId)?.get<CommanderRegistryComponent>()
        if (commanderRegistry == null || commanderRegistry.commanderIds.isEmpty()) return null
        if (commanderRegistry.commanderIds.any { commanderId ->
                val commanderCard = state.getEntity(commanderId)?.get<CardComponent>() ?: return@any true
                cards.getCard(commanderCard.cardDefinitionId) == null
            }
        ) return null

        return ManaColorSetResolver.resolve(
            colorSet = ManaColorSet.CommanderIdentity,
            state = state,
            projected = state.projectedState,
            sourceId = sourceId,
            controllerId = controllerId,
            cardRegistry = cards,
        ).size
    }

    /**
     * Cost amounts are evaluated by [DynamicAmountEvaluator] after all special cost-only leaves
     * have been resolved. Keeping the traversal here makes arithmetic composition use the normal
     * evaluator (including its saturating math) instead of sending a nested commander leaf into
     * the registry-free evaluator branch.
     */
    private fun DynamicAmount.containsCommanderColorIdentityCount(): Boolean = when (this) {
        DynamicAmount.CommanderColorIdentityCount -> true
        is DynamicAmount.Add -> left.containsCommanderColorIdentityCount() || right.containsCommanderColorIdentityCount()
        is DynamicAmount.Subtract -> left.containsCommanderColorIdentityCount() || right.containsCommanderColorIdentityCount()
        is DynamicAmount.Multiply -> amount.containsCommanderColorIdentityCount()
        is DynamicAmount.Power -> exponent.containsCommanderColorIdentityCount()
        is DynamicAmount.IfPositive -> amount.containsCommanderColorIdentityCount()
        is DynamicAmount.Max -> left.containsCommanderColorIdentityCount() || right.containsCommanderColorIdentityCount()
        is DynamicAmount.Min -> left.containsCommanderColorIdentityCount() || right.containsCommanderColorIdentityCount()
        is DynamicAmount.Conditional ->
            ifTrue.containsCommanderColorIdentityCount() || ifFalse.containsCommanderColorIdentityCount()
        is DynamicAmount.Divide ->
            numerator.containsCommanderColorIdentityCount() || denominator.containsCommanderColorIdentityCount()
        else -> false
    }

    private fun DynamicAmount.replaceCommanderColorIdentityCount(colorCount: Int): DynamicAmount = when (this) {
        DynamicAmount.CommanderColorIdentityCount -> DynamicAmount.Fixed(colorCount)
        is DynamicAmount.Add -> copy(
            left = left.replaceCommanderColorIdentityCount(colorCount),
            right = right.replaceCommanderColorIdentityCount(colorCount),
        )
        is DynamicAmount.Subtract -> copy(
            left = left.replaceCommanderColorIdentityCount(colorCount),
            right = right.replaceCommanderColorIdentityCount(colorCount),
        )
        is DynamicAmount.Multiply -> copy(
            amount = amount.replaceCommanderColorIdentityCount(colorCount),
        )
        is DynamicAmount.Power -> copy(
            exponent = exponent.replaceCommanderColorIdentityCount(colorCount),
        )
        is DynamicAmount.IfPositive -> copy(
            amount = amount.replaceCommanderColorIdentityCount(colorCount),
        )
        is DynamicAmount.Max -> copy(
            left = left.replaceCommanderColorIdentityCount(colorCount),
            right = right.replaceCommanderColorIdentityCount(colorCount),
        )
        is DynamicAmount.Min -> copy(
            left = left.replaceCommanderColorIdentityCount(colorCount),
            right = right.replaceCommanderColorIdentityCount(colorCount),
        )
        is DynamicAmount.Conditional -> copy(
            ifTrue = ifTrue.replaceCommanderColorIdentityCount(colorCount),
            ifFalse = ifFalse.replaceCommanderColorIdentityCount(colorCount),
        )
        is DynamicAmount.Divide -> copy(
            numerator = numerator.replaceCommanderColorIdentityCount(colorCount),
            denominator = denominator.replaceCommanderColorIdentityCount(colorCount),
        )
        else -> this
    }
}

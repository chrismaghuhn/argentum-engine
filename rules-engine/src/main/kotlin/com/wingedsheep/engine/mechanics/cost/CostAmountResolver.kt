package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.mechanics.mana.ManaColorSetResolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderRegistryComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.AdditionalCost
import com.wingedsheep.sdk.scripting.costs.CostAtom
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

    /**
     * Resolve every mandatory PayLife amount in one cost context and return their total.
     *
     * The individual amounts are all evaluated against the same pre-payment state and source
     * context. A missing commander registry entry, an unresolved dynamic leaf, a negative amount,
     * or integer overflow fails closed. An empty list resolves to zero, which is important for
     * colorless commanders and costs with no life component.
     */
    internal fun resolvePayLifeTotal(
        state: GameState,
        amounts: Iterable<DynamicAmount>,
        sourceId: EntityId,
        controllerId: EntityId,
        cardRegistry: CardRegistry?,
    ): Int? {
        var total = 0
        for (amount in amounts) {
            val resolved = resolve(
                state = state,
                amount = amount,
                sourceId = sourceId,
                controllerId = controllerId,
                cardRegistry = cardRegistry,
            ) ?: return null
            if (resolved < 0 || resolved > Int.MAX_VALUE - total) return null
            total += resolved
        }
        return total
    }

    /** Return the mandatory PayLife leaves of an activated-ability cost. */
    internal fun payLifeAmounts(cost: AbilityCost): List<DynamicAmount> = when (cost) {
        is AbilityCost.Atom -> (cost.atom as? CostAtom.PayLife)?.let { listOf(it.amount) } ?: emptyList()
        is AbilityCost.Composite -> cost.costs.flatMap(::payLifeAmounts)
        else -> emptyList()
    }

    /**
     * Return mandatory PayLife leaves of an additional cost. Choices are alternatives, so their
     * options are intentionally not summed here; the selected option is checked by its own path.
     */
    internal fun payLifeAmounts(cost: AdditionalCost): List<DynamicAmount> = when (cost) {
        is AdditionalCost.Atom -> (cost.atom as? CostAtom.PayLife)?.let { listOf(it.amount) } ?: emptyList()
        is AdditionalCost.Composite -> cost.steps.flatMap(::payLifeAmounts)
        else -> emptyList()
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

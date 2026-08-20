package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.engine.core.GameLimits
import com.wingedsheep.engine.handlers.ConditionEvaluator
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
import com.wingedsheep.sdk.scripting.conditions.AllConditions
import com.wingedsheep.sdk.scripting.conditions.AnyCondition
import com.wingedsheep.sdk.scripting.conditions.Compare
import com.wingedsheep.sdk.scripting.conditions.ComparisonOperator
import com.wingedsheep.sdk.scripting.conditions.Condition
import com.wingedsheep.sdk.scripting.conditions.NotCondition
import com.wingedsheep.sdk.scripting.conditions.NumberMatches
import com.wingedsheep.sdk.scripting.conditions.NumberProperty
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
        return if (amount.containsCommanderColorIdentityCount()) {
            resolveWithCommanderContext(
                state = state,
                amount = amount,
                sourceId = sourceId,
                controllerId = controllerId,
                cardRegistry = cardRegistry,
            )
        } else {
            DynamicAmountEvaluator().evaluate(
                state = state,
                amount = amount,
                context = EffectContext(sourceId = sourceId, controllerId = controllerId),
            )
        }
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
     * Evaluate an amount containing the cost-only commander leaf without flattening it to the
     * outer controller's value. In particular, [DynamicAmount.CountPlayersWith] rebinds its
     * condition to each candidate, so every commander leaf in that condition must use that
     * candidate's commander registry.
     */
    private fun resolveWithCommanderContext(
        state: GameState,
        amount: DynamicAmount,
        sourceId: EntityId,
        controllerId: EntityId,
        cardRegistry: CardRegistry?,
    ): Int? {
        fun resolveNested(nested: DynamicAmount, nestedControllerId: EntityId): Int? =
            resolveWithCommanderContext(
                state = state,
                amount = nested,
                sourceId = sourceId,
                controllerId = nestedControllerId,
                cardRegistry = cardRegistry,
            )

        return when (amount) {
            DynamicAmount.CommanderColorIdentityCount -> resolveCommanderColorIdentityCount(
                state = state,
                sourceId = sourceId,
                controllerId = controllerId,
                cardRegistry = cardRegistry,
            )

            is DynamicAmount.Add -> {
                val left = resolveNested(amount.left, controllerId) ?: return null
                val right = resolveNested(amount.right, controllerId) ?: return null
                GameLimits.addClamped(left, right)
            }

            is DynamicAmount.Subtract -> {
                val left = resolveNested(amount.left, controllerId) ?: return null
                val right = resolveNested(amount.right, controllerId) ?: return null
                GameLimits.subClamped(left, right)
            }

            is DynamicAmount.Multiply -> {
                val value = resolveNested(amount.amount, controllerId) ?: return null
                GameLimits.mulClamped(value, amount.multiplier)
            }

            is DynamicAmount.Power -> {
                val exponent = resolveNested(amount.exponent, controllerId) ?: return null
                GameLimits.powClamped(amount.base, exponent)
            }

            is DynamicAmount.IfPositive -> {
                val value = resolveNested(amount.amount, controllerId) ?: return null
                maxOf(0, value)
            }

            is DynamicAmount.Max -> {
                val left = resolveNested(amount.left, controllerId) ?: return null
                val right = resolveNested(amount.right, controllerId) ?: return null
                maxOf(left, right)
            }

            is DynamicAmount.Min -> {
                val left = resolveNested(amount.left, controllerId) ?: return null
                val right = resolveNested(amount.right, controllerId) ?: return null
                minOf(left, right)
            }

            is DynamicAmount.Divide -> {
                val numerator = resolveNested(amount.numerator, controllerId) ?: return null
                val denominator = resolveNested(amount.denominator, controllerId) ?: return null
                if (denominator == 0) {
                    0
                } else if (amount.roundUp) {
                    ((numerator.toLong() + denominator.toLong() - 1L) / denominator.toLong())
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                } else {
                    numerator / denominator
                }
            }

            is DynamicAmount.Conditional -> {
                val conditionMet = resolveConditionWithCommanderContext(
                    state = state,
                    condition = amount.condition,
                    sourceId = sourceId,
                    controllerId = controllerId,
                    cardRegistry = cardRegistry,
                ) ?: return null
                resolveNested(if (conditionMet) amount.ifTrue else amount.ifFalse, controllerId)
            }

            is DynamicAmount.CountPlayersWith -> {
                val context = EffectContext(sourceId = sourceId, controllerId = controllerId)
                val playerIds = DynamicAmountEvaluator().resolveUnifiedPlayerIds(state, amount.scope, context)
                var count = 0
                for (candidateId in playerIds) {
                    val matches = resolveConditionWithCommanderContext(
                        state = state,
                        condition = amount.condition,
                        sourceId = sourceId,
                        controllerId = candidateId,
                        cardRegistry = cardRegistry,
                    ) ?: return null
                    if (matches) count++
                }
                count
            }

            else -> DynamicAmountEvaluator().evaluate(
                state = state,
                amount = amount,
                context = EffectContext(sourceId = sourceId, controllerId = controllerId),
            )
        }
    }

    /** Evaluate a numeric condition while preserving candidate-specific commander context. */
    private fun resolveConditionWithCommanderContext(
        state: GameState,
        condition: Condition,
        sourceId: EntityId,
        controllerId: EntityId,
        cardRegistry: CardRegistry?,
    ): Boolean? {
        fun resolveNested(amount: DynamicAmount): Int? = resolveWithCommanderContext(
            state = state,
            amount = amount,
            sourceId = sourceId,
            controllerId = controllerId,
            cardRegistry = cardRegistry,
        )

        return when (condition) {
            is Compare -> {
                val left = resolveNested(condition.left) ?: return null
                val right = resolveNested(condition.right) ?: return null
                when (condition.operator) {
                    ComparisonOperator.EQ -> left == right
                    ComparisonOperator.NEQ -> left != right
                    ComparisonOperator.GT -> left > right
                    ComparisonOperator.GTE -> left >= right
                    ComparisonOperator.LT -> left < right
                    ComparisonOperator.LTE -> left <= right
                }
            }

            is NumberMatches -> {
                val value = resolveNested(condition.amount) ?: return null
                when (val property = condition.property) {
                    NumberProperty.Prime -> isPrime(value)
                    NumberProperty.Even -> value % 2 == 0
                    NumberProperty.Odd -> value % 2 != 0
                    is NumberProperty.MultipleOf -> property.divisor != 0 && value % property.divisor == 0
                }
            }

            is AllConditions -> {
                var unresolved = false
                for (nested in condition.conditions) {
                    when (resolveConditionWithCommanderContext(state, nested, sourceId, controllerId, cardRegistry)) {
                        false -> return false
                        true -> Unit
                        null -> unresolved = true
                    }
                }
                if (unresolved) null else true
            }

            is AnyCondition -> {
                var unresolved = false
                for (nested in condition.conditions) {
                    when (resolveConditionWithCommanderContext(state, nested, sourceId, controllerId, cardRegistry)) {
                        true -> return true
                        false -> Unit
                        null -> unresolved = true
                    }
                }
                if (unresolved) null else false
            }

            is NotCondition ->
                resolveConditionWithCommanderContext(state, condition.condition, sourceId, controllerId, cardRegistry)?.not()

            else -> ConditionEvaluator().evaluate(
                state = state,
                condition = condition,
                context = EffectContext(sourceId = sourceId, controllerId = controllerId),
            )
        }
    }

    private fun isPrime(value: Int): Boolean {
        if (value < 2) return false
        if (value < 4) return true
        if (value % 2 == 0) return false
        var divisor = 3
        while (divisor.toLong() * divisor <= value) {
            if (value % divisor == 0) return false
            divisor += 2
        }
        return true
    }

    /**
     * Identify amounts that require the registry-aware traversal above. Ordinary amounts remain
     * on [DynamicAmountEvaluator]; commander-dependent amounts must retain their controller
     * context through every arithmetic and condition branch.
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
            condition.containsCommanderColorIdentityCount() ||
                ifTrue.containsCommanderColorIdentityCount() || ifFalse.containsCommanderColorIdentityCount()
        is DynamicAmount.CountPlayersWith -> condition.containsCommanderColorIdentityCount()
        is DynamicAmount.Divide ->
            numerator.containsCommanderColorIdentityCount() || denominator.containsCommanderColorIdentityCount()
        else -> false
    }

    /**
     * Dynamic amounts can occur inside numeric conditions, not only in the selected branches of a
     * conditional amount. Resolve the commander leaf before the generic condition evaluator sees it;
     * otherwise the registry-free fallback would turn an unavailable commander count into zero.
     */
    private fun Condition.containsCommanderColorIdentityCount(): Boolean = when (this) {
        is Compare -> left.containsCommanderColorIdentityCount() || right.containsCommanderColorIdentityCount()
        is NumberMatches -> amount.containsCommanderColorIdentityCount()
        is AllConditions -> conditions.any { it.containsCommanderColorIdentityCount() }
        is AnyCondition -> conditions.any { it.containsCommanderColorIdentityCount() }
        is NotCondition -> condition.containsCommanderColorIdentityCount()
        else -> false
    }

}

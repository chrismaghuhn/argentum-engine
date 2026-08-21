package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility

/** Exact result of validating a V1 plan; no solver choice is made after this boundary. */
sealed interface PaymentPlanValidation {
    data class Accepted(
        val poolAfterSpend: ManaPool,
        val solution: ManaSolution,
    ) : PaymentPlanValidation

    data class Rejected(val reason: String) : PaymentPlanValidation
}

/**
 * The ordinary source shape that PaymentPlanV1 can represent without hiding another choice.
 * Advanced sources remain available to the legacy engine paths but are deliberately rejected here
 * until their secondary choices, restrictions, riders, and bonus production have public fields.
 */
fun ManaSource.supportsPaymentPlanV1(): Boolean =
    !requiresSacrifice &&
        tapPermanentsSubCost == null &&
        manaAmount == 1 &&
        bonusManaPerTap == 0 &&
        bonusManaColorlessPerTap == 0 &&
        bonusManaColor == null &&
        !bonusManaIsAnyColor &&
        restriction == null &&
        colorRestrictions.isEmpty() &&
        colorRiders.isEmpty() &&
        !hasContextSensitiveAbilities &&
        colorActivationManaCost.isEmpty() &&
        colorPainCost.isEmpty() &&
        colorlessPainCost == 0 &&
        colorsRequiringSacrifice.isEmpty() &&
        ordinaryTapManaAbilitiesOnly()

private fun ManaSource.ordinaryTapManaAbilitiesOnly(): Boolean {
    val abilities = producesColors
        .flatMap(::manaAbilityOptionsFor)
        .plus(manaAbilityOptionsFor(null))
        .distinctBy { it.id.value }
    return abilities.isEmpty() || abilities.all {
        it.cost is AbilityCost.Tap && it.restrictions.isEmpty()
    }
}

/**
 * Validates and materializes the exact choices in [PaymentPlanV1]. It intentionally does not call
 * [ManaSolver.solve]: that would reintroduce the hidden production-color, source, or generic-spend
 * choices this contract exists to expose.
 */
class PaymentPlanValidator(
    private val manaSolver: ManaSolver,
) {
    fun validate(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        plan: PaymentPlanV1,
        spellContext: SpellPaymentContext? = null,
        excludeSources: Set<EntityId> = emptySet(),
    ): PaymentPlanValidation {
        if (cost.symbols.any { it !is ManaSymbol.Colored && it !is ManaSymbol.Colorless && it !is ManaSymbol.Generic }) {
            return PaymentPlanValidation.Rejected("PaymentPlanV1 supports only ordinary fixed mana symbols")
        }
        if (plan.spendAllocation.x.isNotEmpty() ||
            plan.spendAllocation.restricted.isNotEmpty() ||
            plan.spendAllocation.riderBearingSourceIds.isNotEmpty()
        ) {
            return PaymentPlanValidation.Rejected("PaymentPlanV1 does not support X, restricted, or rider allocations")
        }
        if (plan.poolSpend.restricted.isNotEmpty()) {
            return PaymentPlanValidation.Rejected("PaymentPlanV1 does not support restricted pool buckets")
        }
        if (plan.poolSpend.hasNegativeAmount()) {
            return PaymentPlanValidation.Rejected("PaymentPlanV1 pool spend cannot be negative")
        }

        val poolComponent = state.getEntity(playerId)?.get<ManaPoolComponent>() ?: ManaPoolComponent()
        if (poolComponent.restrictedMana.isNotEmpty()) {
            return PaymentPlanValidation.Rejected("PaymentPlanV1 cannot spend a restricted mana pool")
        }
        if (poolComponent.manaBySubtype.isNotEmpty() || poolComponent.manaBySource.isNotEmpty()) {
            return PaymentPlanValidation.Rejected(
                "PaymentPlanV1 cannot spend floating mana with hidden provenance"
            )
        }
        val currentPool = poolComponent.asManaPool()

        val availableSources = manaSolver.findAvailableManaSources(state, playerId, spellContext)
            .filter { it.entityId !in excludeSources }
        val sourcesById = availableSources.associateBy { it.entityId }
        val resolved = linkedMapOf<EntityId, ResolvedActivation>()
        for (activation in plan.sourceActivations) {
            if (activation.sourceId in resolved) {
                return PaymentPlanValidation.Rejected("PaymentPlanV1 activates a source more than once")
            }
            if (activation.secondaryChoices != null) {
                return PaymentPlanValidation.Rejected("PaymentPlanV1 secondary source choices are unsupported")
            }
            if (activation.productionChoice.amount != 1 || activation.productionChoice.bonusChoice != null) {
                return PaymentPlanValidation.Rejected("PaymentPlanV1 supports one ordinary mana per source activation")
            }
            val source = sourcesById[activation.sourceId]
                ?: return PaymentPlanValidation.Rejected("Payment source is not currently available: ${activation.sourceId}")
            if (!source.supportsPaymentPlanV1()) {
                return PaymentPlanValidation.Rejected("Payment source shape is unsupported: ${activation.sourceId}")
            }
            val color = activation.productionChoice.producedColor
            val engineColor = color.asEngineColor()
            if (engineColor != null && engineColor !in source.producesColors) {
                return PaymentPlanValidation.Rejected("Payment source cannot produce ${color.name}: ${activation.sourceId}")
            }
            if (engineColor == null && !source.producesColorless) {
                return PaymentPlanValidation.Rejected("Payment source cannot produce colorless mana: ${activation.sourceId}")
            }
            val abilities = source.manaAbilityOptionsFor(engineColor)
            val resolvedAbility = if (abilities.isNotEmpty()) {
                abilities.firstOrNull { ManaAbilityIdentity.key(it) == activation.manaAbilityKey }
                    ?: return PaymentPlanValidation.Rejected(
                        "Mana ability identity does not match the current source"
                    )
            } else {
                val expectedKey = ManaAbilityIdentity.intrinsic(engineColor)
                if (activation.manaAbilityKey != expectedKey) {
                    return PaymentPlanValidation.Rejected("Mana ability identity is not a stable intrinsic identity")
                }
                engineColor?.let { IntrinsicManaAbilities.lookup(AbilityId.intrinsicMana(it.symbol)) }
            }
            resolved[activation.sourceId] = ResolvedActivation(source, activation, resolvedAbility)
        }

        val allocations = plan.spendAllocation.costUnits.associateBy { it.symbolIndex }
        if (allocations.size != plan.spendAllocation.costUnits.size ||
            allocations.keys != cost.symbols.indices.toSet()
        ) {
            return PaymentPlanValidation.Rejected("PaymentPlanV1 must allocate every cost symbol exactly once")
        }

        val poolAmounts = mutableMapOf<PaymentManaColor, Int>()
        val sourceAmounts = mutableMapOf<EntityId, Int>()
        for ((index, symbol) in cost.symbols.withIndex()) {
            val allocation = allocations[index]!!
            val expectedUnits = when (symbol) {
                is ManaSymbol.Colored, is ManaSymbol.Colorless -> 1
                is ManaSymbol.Generic -> symbol.amount
                else -> error("validated above")
            }
            if (expectedUnits == 0) {
                if (allocation.spends.isNotEmpty()) {
                    return PaymentPlanValidation.Rejected("Zero-cost symbol $index must have an empty spend allocation")
                }
                continue
            }
            if (expectedUnits < 0 || allocation.spends.isEmpty() || allocation.spends.sumOf { it.amount } != expectedUnits) {
                return PaymentPlanValidation.Rejected("Cost symbol $index has an incomplete spend allocation")
            }
            for (spend in allocation.spends) {
                if (spend.amount <= 0 || spend.restrictedBucketKey != null) {
                    return PaymentPlanValidation.Rejected("PaymentPlanV1 spend references must be unrestricted and positive")
                }
                val color = when {
                    spend.sourceId != null && spend.poolColor == null -> {
                        val selected = resolved[spend.sourceId]
                            ?: return PaymentPlanValidation.Rejected("Spend references an unselected source")
                        sourceAmounts[spend.sourceId] = (sourceAmounts[spend.sourceId] ?: 0) + spend.amount
                        selected.activation.productionChoice.producedColor
                    }
                    spend.sourceId == null && spend.poolColor != null -> {
                        poolAmounts[spend.poolColor] = (poolAmounts[spend.poolColor] ?: 0) + spend.amount
                        spend.poolColor
                    }
                    else -> return PaymentPlanValidation.Rejected("Each spend reference must name exactly one origin")
                }
                if (!symbol.accepts(color)) {
                    return PaymentPlanValidation.Rejected("Spend allocation does not satisfy cost symbol $index")
                }
            }
        }

        for ((sourceId, activation) in resolved) {
            if ((sourceAmounts[sourceId] ?: 0) != activation.activation.productionChoice.amount) {
                return PaymentPlanValidation.Rejected("Selected source mana must be allocated exactly once")
            }
        }

        val expectedPoolSpend = com.wingedsheep.engine.core.PoolSpend.fromAmounts(poolAmounts)
        if (expectedPoolSpend != plan.poolSpend) {
            return PaymentPlanValidation.Rejected("poolSpend does not match spendAllocation")
        }
        for (color in PaymentManaColor.entries) {
            val available = if (color == PaymentManaColor.COLORLESS) {
                currentPool.colorless
            } else {
                currentPool.get(color.asEngineColor()!!)
            }
            if (plan.poolSpend.amount(color) > available) {
                return PaymentPlanValidation.Rejected("PaymentPlanV1 spends more floating mana than is available")
            }
        }

        var poolAfterSpend = currentPool
        for (color in PaymentManaColor.entries) {
            val amount = plan.poolSpend.amount(color)
            poolAfterSpend = if (color == PaymentManaColor.COLORLESS) {
                poolAfterSpend.spendColorless(amount) ?:
                    return PaymentPlanValidation.Rejected("Floating colorless mana is unavailable")
            } else {
                poolAfterSpend.spend(color.asEngineColor()!!, amount) ?:
                    return PaymentPlanValidation.Rejected("Floating mana is unavailable")
            }
        }

        // Array order is not a second payment choice. Materialize sources canonically so two
        // equivalent plans cannot change tap/event ordering merely by permuting the submitted list.
        val orderedActivations = resolved.values.sortedBy { it.source.entityId.value }
        val solution = ManaSolution(
            sources = orderedActivations.map { it.source },
            manaProduced = orderedActivations.associate { activation ->
                val color = activation.activation.productionChoice.producedColor.asEngineColor()
                activation.source.entityId to ManaProduction(
                    color = color,
                    amount = if (color == null) 0 else 1,
                    colorless = if (color == null) 1 else 0,
                    manaAbility = activation.ability,
                )
            },
            manaAbilityUses = orderedActivations.associate { activation ->
                activation.source.entityId to ManaAbilityUse(
                    ability = activation.ability,
                    producedColor = activation.activation.productionChoice.producedColor.asEngineColor(),
                )
            },
        )
        return PaymentPlanValidation.Accepted(poolAfterSpend, solution)
    }

    private data class ResolvedActivation(
        val source: ManaSource,
        val activation: SourceActivation,
        val ability: ActivatedAbility?,
    )
}

private fun ManaPoolComponent.asManaPool(): ManaPool = ManaPool(
    white = white,
    blue = blue,
    black = black,
    red = red,
    green = green,
    colorless = colorless,
    restrictedMana = restrictedMana,
    manaBySubtype = manaBySubtype,
    manaBySource = manaBySource,
)

private fun com.wingedsheep.engine.core.PoolSpend.hasNegativeAmount(): Boolean =
    white < 0 || blue < 0 || black < 0 || red < 0 || green < 0 || colorless < 0 ||
        restricted.values.any { it < 0 }

private fun ManaSymbol.accepts(color: PaymentManaColor): Boolean = when (this) {
    is ManaSymbol.Colored -> color == PaymentManaColor.fromEngine(this.color)
    is ManaSymbol.Colorless -> color == PaymentManaColor.COLORLESS
    is ManaSymbol.Generic -> true
    else -> false
}

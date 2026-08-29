package com.wingedsheep.gym

import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.gym.contract.PaymentDomainV5

/**
 * Deterministic test policy for the current public V5 domain.
 *
 * This deliberately consumes only the published domain. It is not production payment logic: it
 * exists so Gym contract tests can submit a complete ExplicitV3 response without reusing
 * ManaSolver, private provenance, or GameState-derived choices.
 */
internal fun paymentPlanV3FromPublic(domain: PaymentDomainV5): PaymentPlanV3? {
    if (domain.outerAtomicCostUnits.isEmpty() && domain.sourceActivationOptions.isEmpty()) {
        return PaymentPlanV3()
    }
    if (domain.initialPoolBuckets.any { it.availableAmount <= 0 }) return null

    data class OutputUnit(val color: PaymentManaColor, val available: Boolean = true)
    data class ResourceState(
        val pool: Map<InitialPoolBucketKeyV1, Int>,
        val outputs: List<List<OutputUnit>>,
    )
    data class AllocationResult(
        val state: ResourceState,
        val allocations: List<PaymentAllocationV1>,
    )
    data class Demand(
        val target: PaymentTargetV1,
        val unit: AtomicManaCostUnitV1,
    )

    val publishedBuckets = domain.initialPoolBuckets.map { it.key to it.availableAmount }
    val initialState = ResourceState(
        pool = linkedMapOf<InitialPoolBucketKeyV1, Int>().apply {
            publishedBuckets.forEach { (key, amount) -> put(key, amount) }
        },
        outputs = emptyList(),
    )

    fun allowedColors(unit: AtomicManaCostUnitV1): Set<PaymentManaColor> = when {
        unit.kind == PaymentCostKindV1.GENERIC -> PaymentManaColor.entries.toSet()
        else -> unit.allowedColors
    }

    fun colorOf(key: InitialPoolBucketKeyV1): PaymentManaColor = when (key) {
        is InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> key.color
        is InitialPoolBucketKeyV1.CertifiedFloatingBucket -> key.key.poolColor
    }

    fun consume(state: ResourceState, resource: ManaResourceRefV1): ResourceState? {
        val pool = state.pool.toMutableMap()
        val outputs = state.outputs.map { it.toMutableList() }.toMutableList()
        when (resource) {
            is ManaResourceRefV1.InitialPoolResource -> {
                val remaining = pool[resource.bucketKey] ?: return null
                if (remaining <= 0) return null
                pool[resource.bucketKey] = remaining - 1
            }

            is ManaResourceRefV1.ActivationOutputUnit -> {
                val activation = outputs.getOrNull(resource.activationIndex) ?: return null
                val output = activation.getOrNull(resource.outputIndex) ?: return null
                if (!output.available) return null
                activation[resource.outputIndex] = output.copy(available = false)
            }
        }
        return ResourceState(pool = pool, outputs = outputs.map { it.toList() })
    }

    fun resourceChoices(state: ResourceState, allowed: Set<PaymentManaColor>): List<Pair<ManaResourceRefV1, PaymentManaColor>> = buildList {
        for ((key, _) in publishedBuckets) {
            if ((state.pool[key] ?: 0) > 0 && colorOf(key) in allowed) {
                add(ManaResourceRefV1.InitialPoolResource(key) to colorOf(key))
            }
        }
        state.outputs.forEachIndexed { activationIndex, outputs ->
            outputs.forEachIndexed { outputIndex, output ->
                if (output.available && output.color in allowed) {
                    add(
                        ManaResourceRefV1.ActivationOutputUnit(activationIndex, outputIndex) to
                            output.color,
                    )
                }
            }
        }
    }

    fun allocateDemands(state: ResourceState, demands: List<Demand>, index: Int = 0): AllocationResult? {
        if (index == demands.size) return AllocationResult(state, emptyList())
        val demand = demands[index]
        for ((resource, _) in resourceChoices(state, allowedColors(demand.unit))) {
            val nextState = consume(state, resource) ?: continue
            val remainder = allocateDemands(nextState, demands, index + 1) ?: continue
            return AllocationResult(
                state = remainder.state,
                allocations = listOf(PaymentAllocationV1(demand.target, resource)) + remainder.allocations,
            )
        }
        return null
    }

    fun appendOutputs(state: ResourceState, colors: List<PaymentManaColor>): ResourceState =
        state.copy(outputs = state.outputs + listOf(colors.map(::OutputUnit)))

    fun productionOutputs(choice: ProductionChoice): List<PaymentManaColor>? {
        val fixed = choice.fixedOutputs
        if (fixed != null) {
            if (choice.amount != 1 || choice.bonusChoice != null ||
                fixed.isEmpty() || fixed.map { it.index } != fixed.indices.toList() ||
                fixed.any { it.amount != 1 }
            ) return null
            return fixed.map { it.color }
        }
        if (choice.amount != 1 || choice.bonusChoice != null) return null
        return listOf(choice.producedColor)
    }

    fun sortedUnits(units: List<AtomicManaCostUnitV1>): List<AtomicManaCostUnitV1> =
        units.sortedWith(compareBy({ it.symbolIndex }, { it.unitIndexWithinSymbol }))

    fun search(
        state: ResourceState,
        usedSources: Set<com.wingedsheep.sdk.model.EntityId>,
        activations: List<SourceActivationV2>,
        activationAllocations: List<List<PaymentAllocationV1>>,
        selectedDamage: Int,
    ): PaymentPlanV3? {
        val outerDemands = sortedUnits(domain.outerAtomicCostUnits).map { unit ->
            Demand(
                target = PaymentTargetV1.OuterCostUnit(unit.symbolIndex, unit.unitIndexWithinSymbol),
                unit = unit,
            )
        }
        val outer = allocateDemands(state, outerDemands)
        if (outer != null) {
            return PaymentPlanV3(
                activations = activations.mapIndexed { index, activation ->
                    activation.copy(activationCostAllocation = activationAllocations[index])
                },
                outerAllocation = outer.allocations,
            )
        }
        if (activations.size >= domain.sourceActivationOptions.size) return null

        for (option in domain.sourceActivationOptions) {
            if (option.sourceId in usedSources) continue
            val costOrder = option.activationCostOrderOptions.firstOrNull() ?: continue
            val costDemands = sortedUnits(option.atomicActivationManaCostUnits).map { unit ->
                Demand(
                    target = PaymentTargetV1.ActivationCostUnit(
                        activationIndex = activations.size,
                        symbolIndex = unit.symbolIndex,
                        unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                    ),
                    unit = unit,
                )
            }
            for (choice in option.productionChoices) {
                val outputs = productionOutputs(choice) ?: continue
                val damage = selectedDamage + option.fixedSelfDamageAmount
                if (domain.fixedSelfDamageBudget != null && damage > domain.fixedSelfDamageBudget) continue
                val paidCost = allocateDemands(state, costDemands) ?: continue
                val activation = SourceActivationV2(
                    sourceId = option.sourceId,
                    manaAbilityKey = option.manaAbilityKey,
                    productionChoice = choice,
                    activationCostOrder = costOrder,
                )
                val result = search(
                    state = appendOutputs(paidCost.state, outputs),
                    usedSources = usedSources + option.sourceId,
                    activations = activations + activation,
                    activationAllocations = activationAllocations + listOf(paidCost.allocations),
                    selectedDamage = damage,
                )
                if (result != null) return result
            }
        }
        return null
    }

    return search(
        state = initialState,
        usedSources = emptySet(),
        activations = emptyList(),
        activationAllocations = emptyList(),
        selectedDamage = 0,
    )
}

package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId

/**
 * Executes the Rules-owned ordered program carried by [PaymentPlanV3].
 *
 * The validator is deliberately run again at this seam. It captures the whole mutable boundary:
 * source freshness, current effective costs, production choices, timing certification, and the
 * single global resource ledger. The executor never asks [ManaSolver] to choose a replacement
 * source, color, allocation, or order.
 *
 * All execution state is local until the complete program has succeeded. A failed source
 * activation, stale re-resolution, or unexpected runtime resource mismatch therefore returns the
 * exact input [GameState] and no events.
 */
class OrderedPaymentProgramExecutor(
    private val manaSolver: ManaSolver,
    private val manaAbilitySideEffectExecutor: ManaAbilitySideEffectExecutor,
) {
    private val validator = PaymentPlanValidator(manaSolver)

    fun executeV3(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        plan: PaymentPlanV3,
        paymentContext: SpellPaymentContext,
        reason: String,
        excludeSources: Set<EntityId> = emptySet(),
    ): ExplicitPaymentExecution {
        val validation = validator.validateV3(
            state = state,
            playerId = playerId,
            cost = cost.canonicalPaymentManaCost(),
            plan = plan,
            spellContext = paymentContext,
            excludeSources = excludeSources,
        )
        val accepted = validation as? PaymentPlanValidation.AcceptedV3
            ?: return ExplicitPaymentExecution(
                state = state,
                events = emptyList(),
                error = (validation as PaymentPlanValidation.Rejected).reason,
            )

        return executeAccepted(
            state = state,
            playerId = playerId,
            program = accepted.program,
            reason = reason,
        )
    }

    private fun executeAccepted(
        state: GameState,
        playerId: EntityId,
        program: ValidatedPaymentProgramV3,
        reason: String,
    ): ExplicitPaymentExecution {
        val initialPool = state.getEntity(playerId)
            ?.get<ManaPoolComponent>()
            ?.toManaPool()
            ?: ManaPool()
        val ledger = RuntimeResourceLedger(program, initialPool)
        var currentState = state
        val events = mutableListOf<GameEvent>()

        for ((activationIndex, activation) in program.activations.withIndex()) {
            var manaComponentSeen = false
            for (component in activation.activationCostOrder) {
                when (component) {
                    ActivationCostComponentRefV1.ManaComponent -> {
                        if (!manaComponentSeen) {
                            manaComponentSeen = true
                            ledger.consumeAll(activation.activationCostAllocation)
                        } else {
                            ledger.fail("PaymentPlanV3 repeats an activation mana component")
                        }
                    }

                    is ActivationCostComponentRefV1.DeterministicNonManaComponent -> {
                        if (component.index != 0) {
                            ledger.fail(
                                "PaymentPlanV3 deterministic activation cost component is unsupported: ${component.index}"
                            )
                        } else {
                            val sideEffectResult = activateSource(
                                state = currentState,
                                activation = activation,
                                controllerId = playerId,
                            )
                            if (!sideEffectResult.success) {
                                return rejected(
                                    state = state,
                                    error = "PaymentPlanV3 source activation failed at node $activationIndex",
                                )
                            }
                            currentState = sideEffectResult.state
                            events += sideEffectResult.events
                        }
                    }
                }

                ledger.failure?.let { error ->
                    return rejected(state = state, error = error)
                }
            }

            if (activation.activationCostAllocation.isNotEmpty() && !manaComponentSeen) {
                return rejected(
                    state = state,
                    error = "PaymentPlanV3 activation allocations have no mana cost component",
                )
            }
            ledger.publishOutputs(activationIndex, activation.outputs.size)
            ledger.failure?.let { error ->
                return rejected(state = state, error = error)
            }
        }

        val outerAllocations = program.allocations.filter { it.target is PaymentTargetV1.OuterCostUnit }
        for (allocation in outerAllocations) {
            val consumed = ledger.consume(allocation)
                ?: return rejected(state = state, error = ledger.failure ?: "PaymentPlanV3 outer payment failed")
            ledger.recordOuterSpend(consumed)
        }

        ledger.finish()?.let { error ->
            return rejected(state = state, error = error)
        }

        var finalPool = ledger.pool
        for ((activationIndex, activation) in program.activations.withIndex()) {
            for ((outputIndex, color) in activation.outputs.withIndex()) {
                val output = ManaResourceRefV1.ActivationOutputUnit(activationIndex, outputIndex)
                if (output !in ledger.consumedOutputs) {
                    finalPool = finalPool.addTracked(
                        color = color,
                        sourceId = activation.source.entityId,
                        subtypes = activation.source.sourceSubtypes,
                        knownToPlayers = setOf(playerId),
                    )
                }
            }
        }

        currentState = currentState.updateEntity(playerId) { container ->
            container.with(fromManaPool(finalPool))
        }
        val spent = ledger.outerSpent
        events += ManaSpentEvent(
            playerId = playerId,
            reason = reason,
            white = spent[PaymentManaColor.WHITE] ?: 0,
            blue = spent[PaymentManaColor.BLUE] ?: 0,
            black = spent[PaymentManaColor.BLACK] ?: 0,
            red = spent[PaymentManaColor.RED] ?: 0,
            green = spent[PaymentManaColor.GREEN] ?: 0,
            colorless = spent[PaymentManaColor.COLORLESS] ?: 0,
        )
        return ExplicitPaymentExecution(
            state = currentState,
            events = events,
            error = null,
            spentManaProvenance = ledger.outerProvenance,
        )
    }

    private fun activateSource(
        state: GameState,
        activation: ValidatedPaymentActivationV3,
        controllerId: EntityId,
    ): ManaSideEffectExecution {
        val selectedUse = ManaAbilityUse(
            ability = activation.ability,
            producedColor = activation.outputs.firstOrNull()?.asEngineColor(),
        )
        return manaAbilitySideEffectExecutor.tapSourcesWithSideEffects(
            state = state,
            solution = ManaSolution(
                sources = listOf(activation.source),
                manaProduced = emptyMap(),
                manaAbilityUses = mapOf(activation.source.entityId to selectedUse),
            ),
            controllerId = controllerId,
        )
    }

    private fun rejected(state: GameState, error: String): ExplicitPaymentExecution =
        ExplicitPaymentExecution(
            state = state,
            events = emptyList(),
            error = error,
        )
}

private data class RuntimeResourceConsumption(
    val color: PaymentManaColor,
    val provenance: SpentManaProvenance,
)

/**
 * Runtime half of the V3 ledger. The validator proves the complete submitted program before this
 * class is created; this local ledger then repeats the resource-availability checks at the exact
 * point at which each ordered component consumes a resource.
 */
private class RuntimeResourceLedger(
    private val program: ValidatedPaymentProgramV3,
    initialPool: ManaPool,
) {
    var pool: ManaPool = initialPool
        private set
    var failure: String? = null
        private set
    val consumedOutputs = mutableSetOf<ManaResourceRefV1.ActivationOutputUnit>()
    private val availableOutputs = mutableSetOf<ManaResourceRefV1.ActivationOutputUnit>()
    private val consumedInitial = mutableMapOf<com.wingedsheep.engine.core.InitialPoolBucketKeyV1, Int>()
    val outerSpent = mutableMapOf<PaymentManaColor, Int>()
    var outerProvenance: SpentManaProvenance = SpentManaProvenance()
        private set

    fun fail(error: String) {
        if (failure == null) failure = error
    }

    fun consumeAll(allocations: List<PaymentAllocationV1>) {
        for (allocation in allocations) {
            consume(allocation) ?: return
        }
    }

    fun consume(allocation: PaymentAllocationV1): RuntimeResourceConsumption? {
        if (failure != null) return null
        val consumed = when (val resource = allocation.resource) {
            is ManaResourceRefV1.InitialPoolResource -> consumeInitial(resource)
            is ManaResourceRefV1.ActivationOutputUnit -> consumeOutput(resource)
        } ?: return null
        return consumed
    }

    fun publishOutputs(activationIndex: Int, outputCount: Int) {
        if (failure != null) return
        for (outputIndex in 0 until outputCount) {
            val output = ManaResourceRefV1.ActivationOutputUnit(activationIndex, outputIndex)
            if (!availableOutputs.add(output)) {
                fail("PaymentPlanV3 activation output became available more than once")
                return
            }
        }
    }

    fun recordOuterSpend(consumption: RuntimeResourceConsumption) {
        if (failure != null) return
        outerSpent[consumption.color] = (outerSpent[consumption.color] ?: 0) + 1
        outerProvenance = mergeProvenance(outerProvenance, consumption.provenance)
    }

    fun finish(): String? {
        if (failure != null) return failure
        if (consumedOutputs != program.consumedActivationOutputs) {
            return "PaymentPlanV3 runtime output ledger differs from preflight"
        }
        if (consumedInitial != program.consumedInitialPool) {
            return "PaymentPlanV3 runtime initial-pool ledger differs from preflight"
        }
        return null
    }

    private fun consumeInitial(
        resource: ManaResourceRefV1.InitialPoolResource,
    ): RuntimeResourceConsumption? {
        val capacity = program.initialPoolBuckets
            .firstOrNull { it.key == resource.bucketKey }
            ?.availableAmount
        if (capacity == null) {
            fail("PaymentPlanV3 runtime initial-pool bucket is unavailable")
            return null
        }
        val consumed = (consumedInitial[resource.bucketKey] ?: 0) + 1
        if (consumed > capacity) {
            fail("PaymentPlanV3 runtime initial-pool bucket capacity is exceeded")
            return null
        }

        val provenance = when (val key = resource.bucketKey) {
            is com.wingedsheep.engine.core.InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> {
                pool = if (key.color == PaymentManaColor.COLORLESS) {
                    pool.spendColorless()
                } else {
                    pool.spend(key.color.asEngineColor()!!)
                } ?: run {
                    fail("PaymentPlanV3 runtime initial-pool mana is unavailable")
                    return null
                }
                SpentManaProvenance()
            }

            is com.wingedsheep.engine.core.InitialPoolBucketKeyV1.CertifiedFloatingBucket -> {
                val updated = pool.consumeCertifiedJoint(mapOf(key.key to 1)) ?: run {
                    fail("PaymentPlanV3 runtime certified floating bucket is unavailable")
                    return null
                }
                pool = updated.first
                updated.second
            }
        }
        consumedInitial[resource.bucketKey] = consumed
        return RuntimeResourceConsumption(resource.bucketKey.paymentColorForRuntime(), provenance)
    }

    private fun consumeOutput(
        resource: ManaResourceRefV1.ActivationOutputUnit,
    ): RuntimeResourceConsumption? {
        val producer = program.activations.getOrNull(resource.activationIndex)
        val color = producer?.outputs?.getOrNull(resource.outputIndex)
        if (producer == null || color == null) {
            fail("PaymentPlanV3 runtime activation output is unavailable")
            return null
        }
        if (resource !in availableOutputs) {
            fail("PaymentPlanV3 activation output was consumed before source activation succeeded")
            return null
        }
        if (!consumedOutputs.add(resource)) {
            fail("PaymentPlanV3 activation output cannot be consumed more than once")
            return null
        }
        return RuntimeResourceConsumption(
            color = color,
            provenance = SpentManaProvenance(
                bySubtype = producer.source.sourceSubtypes.associateWith { 1 },
                sourceIds = setOf(producer.source.entityId),
            ),
        )
    }

    private fun mergeProvenance(
        first: SpentManaProvenance,
        second: SpentManaProvenance,
    ): SpentManaProvenance {
        if (first.isEmpty) return second
        if (second.isEmpty) return first
        val bySubtype = first.bySubtype.toMutableMap()
        for ((subtype, amount) in second.bySubtype) {
            bySubtype[subtype] = (bySubtype[subtype] ?: 0) + amount
        }
        return SpentManaProvenance(
            bySubtype = bySubtype,
            sourceIds = first.sourceIds + second.sourceIds,
        )
    }
}

private fun com.wingedsheep.engine.core.InitialPoolBucketKeyV1.paymentColorForRuntime(): PaymentManaColor =
    when (this) {
        is com.wingedsheep.engine.core.InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> color
        is com.wingedsheep.engine.core.InitialPoolBucketKeyV1.CertifiedFloatingBucket -> key.poolColor
    }

package com.wingedsheep.gym.contract

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
import com.wingedsheep.sdk.model.EntityId

/**
 * Operational fail-closed ceiling on the number of legal next construction steps of one state.
 *
 * This is NOT a Magic maximum. It is checked against the structural per-domain bound
 * [PaymentConstructionStructuralBoundsV1.alternatives] when a construction is admitted, so a
 * domain whose worst-case step could exceed it is refused before the first step instead of
 * failing mid-construction. Derivation and curriculum-envelope coverage:
 * docs/ml/c1-live-payment-choice-boundary-02a.md (TASK 2, Gate F).
 */
const val MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES: Int = 512

/**
 * Operational fail-closed ceiling on the number of construction steps of one complete payment
 * (checked against [PaymentConstructionStructuralBoundsV1.depth] at admission). Not a Magic
 * maximum; see [MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES] for the derivation reference.
 */
const val MAX_PAYMENT_CONSTRUCTION_DEPTH: Int = 256

/**
 * Operational fail-closed ceiling on the work (search nodes, payment-assignment steps, and flow
 * augmentations) the viability check may spend for one [PaymentConstructionGrammarV1.nextSteps]
 * call. Exceeding it is a typed [PaymentConstructionFailureV1.ConstructionBoundExceededV1], never a
 * truncated step list. Unlike the alternatives and depth ceilings, its curriculum coverage is
 * MEASURED (adversarial sweeps over the Akiri/Chevill envelopes), not proven; see the report.
 */
const val MAX_PAYMENT_CONSTRUCTION_VIABILITY_WORK: Long = 1_000_000L

/**
 * JVM-only staged prefix of one sequential payment construction over a published
 * [PaymentDomainV5].
 *
 * The state is pure data: the published domain plus the staged [PaymentPlanV3] fragments. It has
 * no GameState reference, so a staged prefix can be re-validated against a freshly published
 * domain by plain data equality of [domain].
 */
data class PaymentConstructionStateV1(
    val domain: PaymentDomainV5,
    val activations: List<SourceActivationV2> = emptyList(),
    val outerAllocation: List<PaymentAllocationV1> = emptyList(),
    val finalized: Boolean = false,
)

/**
 * One semantic construction choice. Every step carries only indices into, or values copied from,
 * the published domain; none of them ranks or selects anything on the policy's behalf.
 */
sealed interface PaymentConstructionStepV1 {
    /** Append one activation of a published source option (production and cost order chosen). */
    data class ActivateSource(
        val sourceOptionIndex: Int,
        val productionChoiceIndex: Int,
        val activationCostOrderIndex: Int,
    ) : PaymentConstructionStepV1

    /** Pay the next unpaid inner cost unit of the open activation with one resource. */
    data class AllocateActivationCostUnit(
        val target: PaymentTargetV1.ActivationCostUnit,
        val resource: ManaResourceRefV1,
    ) : PaymentConstructionStepV1

    /** Pay the next unpaid outer cost unit with one resource; closes the activation program. */
    data class AllocateOuterCostUnit(
        val target: PaymentTargetV1.OuterCostUnit,
        val resource: ManaResourceRefV1,
    ) : PaymentConstructionStepV1

    /** Complete the construction; legal only when every cost unit is allocated. */
    data object Finalize : PaymentConstructionStepV1
}

/** The bound a [PaymentConstructionFailureV1.ConstructionBoundExceededV1] refers to. */
enum class PaymentConstructionBoundV1 {
    ALTERNATIVES,
    DEPTH,
    VIABILITY_WORK,
}

/** Typed construction failures. The grammar never truncates, guesses, or falls back. */
sealed interface PaymentConstructionFailureV1 {
    val reason: String

    /** The domain publishes a shape outside the reviewed V5 FixedManaAndTapSelf slice. */
    data class UnsupportedDomainShapeV1(override val reason: String) : PaymentConstructionFailureV1

    /** A structural or operational ceiling would be exceeded. */
    data class ConstructionBoundExceededV1(
        val bound: PaymentConstructionBoundV1,
        val limit: Long,
        val observed: Long,
    ) : PaymentConstructionFailureV1 {
        override val reason: String
            get() = "Payment construction $bound bound exceeded: observed=$observed limit=$limit"
    }

    /** The staged prefix, submitted step, or requested materialization is not legal. */
    data class InvalidPartialConstructionV1(override val reason: String) : PaymentConstructionFailureV1

    /** The published domain admits no complete payment at all. */
    data class NoLegalPaymentV1(
        override val reason: String = "The published payment domain admits no complete PaymentPlanV3",
    ) : PaymentConstructionFailureV1
}

sealed interface PaymentConstructionResultV1<out T> {
    data class Ok<out T>(val value: T) : PaymentConstructionResultV1<T>

    data class Failed(val failure: PaymentConstructionFailureV1) : PaymentConstructionResultV1<Nothing>
}

/**
 * Per-domain structural bounds, computable from the published DTO before the first step.
 *
 * [alternatives] bounds the size of every [PaymentConstructionGrammarV1.nextSteps] list:
 * activation steps are at most the sum of production × cost-order combinations of all options,
 * allocation steps at most one per initial-pool bucket plus one per output unit of an activated
 * source (at most one activation per source, each with at most its largest output bundle), plus
 * one Finalize. [depth] bounds the step count of every construction path: per source at most one
 * activation step plus its largest inner cost, plus one step per outer unit, plus Finalize.
 */
data class PaymentConstructionStructuralBoundsV1(
    val alternatives: Long,
    val depth: Long,
)

/**
 * Sequential payment-construction grammar over the public [PaymentDomainV5]
 * (C1_LIVE_PAYMENT_CHOICE_BOUNDARY_02A TASK 2).
 *
 * Canonical construction order, one path per distinct [PaymentPlanV3]:
 *  1. activation phase: append a source activation (at most one per sourceId) and pay its inner
 *     cost units one by one in published unit order from initial-pool buckets or unconsumed
 *     outputs of EARLIER activations;
 *  2. outer phase (the first outer allocation closes the activation program): pay the outer
 *     units one by one in published unit order from initial-pool buckets or any unconsumed
 *     activation output;
 *  3. Finalize.
 * Activations whose outputs stay unspent are legal (the Rules ledger floats them), so they are
 * offered like any other activation.
 *
 * [nextSteps] returns exactly the legal steps from which at least one complete legal plan remains
 * reachable (exact viability check over the colour-abstracted remaining problem), in canonical
 * order: activations by (option, production, cost order), allocations by resource (pool buckets
 * in published order, then activation outputs by activation and output index), Finalize last.
 * The grammar reads only the published DTO; the final Rules preflight remains the authority for
 * legality against the live state.
 */
object PaymentConstructionGrammarV1 {

    /** Admit [domain] and return the empty construction, or a typed refusal. */
    fun initial(domain: PaymentDomainV5): PaymentConstructionResultV1<PaymentConstructionStateV1> {
        admissionFailure(domain)?.let { return PaymentConstructionResultV1.Failed(it) }
        val state = PaymentConstructionStateV1(domain)
        return when (val steps = nextSteps(state)) {
            is PaymentConstructionResultV1.Ok -> PaymentConstructionResultV1.Ok(state)
            is PaymentConstructionResultV1.Failed -> steps
        }
    }

    /** Structural bounds of [domain]; see [PaymentConstructionStructuralBoundsV1]. */
    fun structuralBounds(domain: PaymentDomainV5): PaymentConstructionStructuralBoundsV1 {
        val options = domain.sourceActivationOptions
        val bySource = options.groupBy { it.sourceId }
        val activationSteps = options.sumOf {
            it.productionChoices.size.toLong() * it.activationCostOrderOptions.size.toLong()
        }
        val maxOutputs = bySource.values.sumOf { sourceOptions ->
            sourceOptions.maxOf { option ->
                option.productionChoices.maxOf { outputColors(it)?.size ?: 1 }
            }.toLong()
        }
        val maxInner = bySource.values.sumOf { sourceOptions ->
            1L + sourceOptions.maxOf { it.atomicActivationManaCostUnits.size }.toLong()
        }
        return PaymentConstructionStructuralBoundsV1(
            alternatives = activationSteps + domain.initialPoolBuckets.size + maxOutputs + 1L,
            depth = maxInner + domain.outerAtomicCostUnits.size + 1L,
        )
    }

    /** True once [PaymentConstructionStepV1.Finalize] has been applied. */
    fun isTerminal(state: PaymentConstructionStateV1): Boolean = state.finalized

    /** The complete, canonically ordered set of legal next steps of [state]. */
    fun nextSteps(
        state: PaymentConstructionStateV1,
    ): PaymentConstructionResultV1<List<PaymentConstructionStepV1>> = nextStepsMeasured(state).first

    /** [nextSteps] plus the viability work it spent (measurement hook for the bound derivation). */
    internal fun nextStepsMeasured(
        state: PaymentConstructionStateV1,
    ): Pair<PaymentConstructionResultV1<List<PaymentConstructionStepV1>>, Long> {
        val search = ViabilitySearch(state.domain)
        return nextSteps(state, search) to search.workSpent
    }

    private fun nextSteps(
        state: PaymentConstructionStateV1,
        search: ViabilitySearch,
    ): PaymentConstructionResultV1<List<PaymentConstructionStepV1>> {
        admissionFailure(state.domain)?.let { return PaymentConstructionResultV1.Failed(it) }
        val ledger = when (val replayed = Ledger.replay(state)) {
            is PaymentConstructionResultV1.Ok -> replayed.value
            is PaymentConstructionResultV1.Failed -> return replayed
        }
        if (state.finalized) return PaymentConstructionResultV1.Ok(emptyList())

        val candidates = candidateSteps(state, ledger)
        val viable = try {
            candidates.filter { step ->
                val successor = successor(state, step)
                val successorLedger = (Ledger.replay(successor) as PaymentConstructionResultV1.Ok).value
                search.isViable(successor, successorLedger)
            }
        } catch (exceeded: ViabilityWorkExceeded) {
            return PaymentConstructionResultV1.Failed(
                PaymentConstructionFailureV1.ConstructionBoundExceededV1(
                    bound = PaymentConstructionBoundV1.VIABILITY_WORK,
                    limit = MAX_PAYMENT_CONSTRUCTION_VIABILITY_WORK,
                    observed = exceeded.observed,
                )
            )
        }
        if (viable.size > MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES) {
            return PaymentConstructionResultV1.Failed(
                PaymentConstructionFailureV1.ConstructionBoundExceededV1(
                    bound = PaymentConstructionBoundV1.ALTERNATIVES,
                    limit = MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES.toLong(),
                    observed = viable.size.toLong(),
                )
            )
        }
        if (viable.isEmpty()) {
            val isRoot = state.activations.isEmpty() && state.outerAllocation.isEmpty()
            return PaymentConstructionResultV1.Failed(
                if (isRoot) {
                    PaymentConstructionFailureV1.NoLegalPaymentV1()
                } else {
                    PaymentConstructionFailureV1.InvalidPartialConstructionV1(
                        "The staged payment prefix has no legal completion"
                    )
                }
            )
        }
        return PaymentConstructionResultV1.Ok(viable)
    }

    /**
     * Apply [step]; it must be one of [nextSteps] of [state]. Equivalent to that membership test,
     * but only [step]'s own successor is checked for viability (admission already guarantees the
     * alternatives ceiling).
     */
    fun apply(
        state: PaymentConstructionStateV1,
        step: PaymentConstructionStepV1,
    ): PaymentConstructionResultV1<PaymentConstructionStateV1> {
        admissionFailure(state.domain)?.let { return PaymentConstructionResultV1.Failed(it) }
        val ledger = when (val replayed = Ledger.replay(state)) {
            is PaymentConstructionResultV1.Ok -> replayed.value
            is PaymentConstructionResultV1.Failed -> return replayed
        }
        val notLegal = PaymentConstructionResultV1.Failed(
            PaymentConstructionFailureV1.InvalidPartialConstructionV1(
                "Step is not a legal next payment-construction step: $step"
            )
        )
        if (state.finalized || step !in candidateSteps(state, ledger)) return notLegal
        val next = successor(state, step)
        val nextLedger = (Ledger.replay(next) as PaymentConstructionResultV1.Ok).value
        val viable = try {
            ViabilitySearch(state.domain).isViable(next, nextLedger)
        } catch (exceeded: ViabilityWorkExceeded) {
            return PaymentConstructionResultV1.Failed(
                PaymentConstructionFailureV1.ConstructionBoundExceededV1(
                    bound = PaymentConstructionBoundV1.VIABILITY_WORK,
                    limit = MAX_PAYMENT_CONSTRUCTION_VIABILITY_WORK,
                    observed = exceeded.observed,
                )
            )
        }
        return if (viable) PaymentConstructionResultV1.Ok(next) else notLegal
    }

    /** Pure assembly of the staged steps; only a finalized construction materializes. */
    fun materialize(state: PaymentConstructionStateV1): PaymentConstructionResultV1<PaymentPlanV3> {
        admissionFailure(state.domain)?.let { return PaymentConstructionResultV1.Failed(it) }
        if (!state.finalized) {
            return PaymentConstructionResultV1.Failed(
                PaymentConstructionFailureV1.InvalidPartialConstructionV1(
                    "Only a finalized payment construction can be materialized"
                )
            )
        }
        when (val replayed = Ledger.replay(state)) {
            is PaymentConstructionResultV1.Ok -> Unit
            is PaymentConstructionResultV1.Failed -> return replayed
        }
        return PaymentConstructionResultV1.Ok(
            PaymentPlanV3(
                activations = state.activations,
                outerAllocation = state.outerAllocation,
            )
        )
    }

    private fun admissionFailure(domain: PaymentDomainV5): PaymentConstructionFailureV1? {
        for (option in domain.sourceActivationOptions) {
            if (option.activationSupportKind != PaymentActivationSupportKindV1.FIXED_MANA_AND_TAP_SELF) {
                return PaymentConstructionFailureV1.UnsupportedDomainShapeV1(
                    "Unsupported activation support kind: ${option.activationSupportKind}"
                )
            }
            if (option.deterministicNonManaCosts != listOf(PaymentDeterministicNonManaCostKindV1.TAP_SELF)) {
                return PaymentConstructionFailureV1.UnsupportedDomainShapeV1(
                    "Unsupported deterministic non-mana costs: ${option.deterministicNonManaCosts}"
                )
            }
            if (option.productionChoices.any { outputColors(it) == null }) {
                return PaymentConstructionFailureV1.UnsupportedDomainShapeV1(
                    "Unsupported production choice shape for source ${option.sourceId.value}"
                )
            }
            // The DTO does not require distinct choices; a repeated one would give one plan two
            // construction paths, so it is refused rather than silently deduplicated.
            if (option.productionChoices.distinct().size != option.productionChoices.size ||
                option.activationCostOrderOptions.distinct().size != option.activationCostOrderOptions.size
            ) {
                return PaymentConstructionFailureV1.UnsupportedDomainShapeV1(
                    "Duplicate production choice or cost order for source ${option.sourceId.value}"
                )
            }
        }
        val bounds = structuralBounds(domain)
        if (bounds.alternatives > MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES) {
            return PaymentConstructionFailureV1.ConstructionBoundExceededV1(
                bound = PaymentConstructionBoundV1.ALTERNATIVES,
                limit = MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES.toLong(),
                observed = bounds.alternatives,
            )
        }
        if (bounds.depth > MAX_PAYMENT_CONSTRUCTION_DEPTH) {
            return PaymentConstructionFailureV1.ConstructionBoundExceededV1(
                bound = PaymentConstructionBoundV1.DEPTH,
                limit = MAX_PAYMENT_CONSTRUCTION_DEPTH.toLong(),
                observed = bounds.depth,
            )
        }
        return null
    }

    private fun candidateSteps(
        state: PaymentConstructionStateV1,
        ledger: Ledger,
    ): List<PaymentConstructionStepV1> {
        val domain = state.domain
        val open = ledger.openActivationIndex
        if (open != null) {
            val unit = ledger.innerUnits[open][state.activations[open].activationCostAllocation.size]
            val target = PaymentTargetV1.ActivationCostUnit(open, unit.symbolIndex, unit.unitIndexWithinSymbol)
            return ledger.resourcesFor(unit, producersBefore = open).map {
                PaymentConstructionStepV1.AllocateActivationCostUnit(target, it)
            }
        }
        return buildList {
            if (state.outerAllocation.isEmpty()) {
                for ((optionIndex, option) in domain.sourceActivationOptions.withIndex()) {
                    if (option.sourceId in ledger.usedSources) continue
                    if (!ledger.damageFits(option.fixedSelfDamageAmount)) continue
                    for (productionIndex in option.productionChoices.indices) {
                        for (orderIndex in option.activationCostOrderOptions.indices) {
                            add(PaymentConstructionStepV1.ActivateSource(optionIndex, productionIndex, orderIndex))
                        }
                    }
                }
            }
            val outerIndex = state.outerAllocation.size
            if (outerIndex < domain.outerAtomicCostUnits.size) {
                val unit = domain.outerAtomicCostUnits[outerIndex]
                val target = PaymentTargetV1.OuterCostUnit(unit.symbolIndex, unit.unitIndexWithinSymbol)
                ledger.resourcesFor(unit, producersBefore = state.activations.size).forEach {
                    add(PaymentConstructionStepV1.AllocateOuterCostUnit(target, it))
                }
            } else {
                add(PaymentConstructionStepV1.Finalize)
            }
        }
    }

    /** Unchecked successor; callers guarantee [step] is a candidate of [state]. */
    private fun successor(
        state: PaymentConstructionStateV1,
        step: PaymentConstructionStepV1,
    ): PaymentConstructionStateV1 = when (step) {
        is PaymentConstructionStepV1.ActivateSource -> {
            val option = state.domain.sourceActivationOptions[step.sourceOptionIndex]
            state.copy(
                activations = state.activations + SourceActivationV2(
                    sourceId = option.sourceId,
                    manaAbilityKey = option.manaAbilityKey,
                    productionChoice = option.productionChoices[step.productionChoiceIndex],
                    activationCostOrder = option.activationCostOrderOptions[step.activationCostOrderIndex],
                    activationCostAllocation = emptyList(),
                )
            )
        }

        is PaymentConstructionStepV1.AllocateActivationCostUnit -> {
            val last = state.activations.last()
            state.copy(
                activations = state.activations.dropLast(1) + last.copy(
                    activationCostAllocation = last.activationCostAllocation +
                        PaymentAllocationV1(step.target, step.resource)
                )
            )
        }

        is PaymentConstructionStepV1.AllocateOuterCostUnit -> state.copy(
            outerAllocation = state.outerAllocation + PaymentAllocationV1(step.target, step.resource)
        )

        PaymentConstructionStepV1.Finalize -> state.copy(finalized = true)
    }

    /**
     * Structural replay of a staged prefix against its published domain. It re-derives every
     * ledger fact (L1 one activation per source, earlier-outputs-only inner references, canonical
     * target order, single use of outputs, bucket capacities, colour acceptance, self-damage
     * budget) so an externally held state is never trusted blindly.
     */
    private class Ledger private constructor(
        val domain: PaymentDomainV5,
        val bucketRemaining: IntArray,
        val outputs: List<List<PaymentManaColor>>,
        val consumedOutputs: MutableSet<ManaResourceRefV1.ActivationOutputUnit>,
        val usedSources: MutableSet<EntityId>,
        val innerUnits: MutableList<List<AtomicManaCostUnitV1>>,
        var damage: Long,
        var openActivationIndex: Int?,
    ) {
        /** Activation index -> number of outputs registered (0 while the activation is open). */
        val registeredOutputs = mutableListOf<Int>()

        fun damageFits(amount: Int): Boolean {
            val budget = domain.fixedSelfDamageBudget ?: return true
            return damage + amount <= budget
        }

        fun resourcesFor(unit: AtomicManaCostUnitV1, producersBefore: Int): List<ManaResourceRefV1> = buildList {
            for ((bucketIndex, bucket) in domain.initialPoolBuckets.withIndex()) {
                if (bucketRemaining[bucketIndex] > 0 && accepts(unit, bucketColor(bucket.key))) {
                    add(ManaResourceRefV1.InitialPoolResource(bucket.key))
                }
            }
            for (activationIndex in 0 until minOf(producersBefore, registeredOutputs.size)) {
                for (outputIndex in 0 until registeredOutputs[activationIndex]) {
                    val ref = ManaResourceRefV1.ActivationOutputUnit(activationIndex, outputIndex)
                    if (ref !in consumedOutputs && accepts(unit, outputs[activationIndex][outputIndex])) {
                        add(ref)
                    }
                }
            }
        }

        private fun consume(
            allocation: PaymentAllocationV1,
            unit: AtomicManaCostUnitV1,
            producersBefore: Int,
        ): String? {
            val color = when (val resource = allocation.resource) {
                is ManaResourceRefV1.InitialPoolResource -> {
                    val bucketIndex = domain.initialPoolBuckets.indexOfFirst { it.key == resource.bucketKey }
                    if (bucketIndex < 0) return "allocation references an unpublished initial-pool bucket"
                    if (bucketRemaining[bucketIndex] <= 0) return "initial-pool bucket capacity is exceeded"
                    bucketRemaining[bucketIndex]--
                    bucketColor(resource.bucketKey)
                }

                is ManaResourceRefV1.ActivationOutputUnit -> {
                    if (resource.activationIndex !in 0 until producersBefore ||
                        resource.activationIndex >= registeredOutputs.size ||
                        resource.outputIndex !in 0 until registeredOutputs[resource.activationIndex]
                    ) {
                        return "allocation references an unavailable or later activation output"
                    }
                    if (!consumedOutputs.add(resource)) return "activation output is spent more than once"
                    outputs[resource.activationIndex][resource.outputIndex]
                }
            }
            if (!accepts(unit, color)) return "resource colour does not satisfy its cost unit"
            return null
        }

        companion object {
            fun replay(state: PaymentConstructionStateV1): PaymentConstructionResultV1<Ledger> {
                val domain = state.domain
                val activationOutputs = mutableListOf<List<PaymentManaColor>>()
                val ledger = Ledger(
                    domain = domain,
                    bucketRemaining = IntArray(domain.initialPoolBuckets.size) {
                        domain.initialPoolBuckets[it].availableAmount
                    },
                    outputs = activationOutputs,
                    consumedOutputs = mutableSetOf(),
                    usedSources = mutableSetOf(),
                    innerUnits = mutableListOf(),
                    damage = 0L,
                    openActivationIndex = null,
                )

                fun invalid(reason: String) = PaymentConstructionResultV1.Failed(
                    PaymentConstructionFailureV1.InvalidPartialConstructionV1(reason)
                )

                for ((index, activation) in state.activations.withIndex()) {
                    if (ledger.openActivationIndex != null) {
                        return invalid("activation $index follows an activation with unpaid inner cost")
                    }
                    val option = domain.sourceActivationOptions.firstOrNull {
                        it.sourceId == activation.sourceId && it.manaAbilityKey == activation.manaAbilityKey
                    } ?: return invalid("activation $index is not a published source option")
                    if (activation.productionChoice !in option.productionChoices) {
                        return invalid("activation $index uses an unpublished production choice")
                    }
                    if (activation.activationCostOrder !in option.activationCostOrderOptions) {
                        return invalid("activation $index uses an unpublished cost order")
                    }
                    if (!ledger.usedSources.add(activation.sourceId)) {
                        return invalid("activation $index activates a source more than once")
                    }
                    if (!ledger.damageFits(option.fixedSelfDamageAmount)) {
                        return invalid("activation $index exceeds the fixed self-damage budget")
                    }
                    ledger.damage += option.fixedSelfDamageAmount
                    val units = option.atomicActivationManaCostUnits
                    ledger.innerUnits += units
                    activationOutputs += outputColors(activation.productionChoice)!!
                    ledger.registeredOutputs += 0
                    if (activation.activationCostAllocation.size > units.size) {
                        return invalid("activation $index over-allocates its inner cost")
                    }
                    for ((unitIndex, allocation) in activation.activationCostAllocation.withIndex()) {
                        val unit = units[unitIndex]
                        val expected = PaymentTargetV1.ActivationCostUnit(index, unit.symbolIndex, unit.unitIndexWithinSymbol)
                        if (allocation.target != expected) {
                            return invalid("activation $index allocation $unitIndex is not the canonical next target")
                        }
                        ledger.consume(allocation, unit, producersBefore = index)?.let {
                            return invalid("activation $index allocation $unitIndex: $it")
                        }
                    }
                    if (activation.activationCostAllocation.size < units.size) {
                        ledger.openActivationIndex = index
                    } else {
                        ledger.registeredOutputs[index] = activationOutputs[index].size
                    }
                }

                if (state.outerAllocation.isNotEmpty() && ledger.openActivationIndex != null) {
                    return invalid("outer allocation starts before the open activation is paid")
                }
                if (state.outerAllocation.size > domain.outerAtomicCostUnits.size) {
                    return invalid("outer cost is over-allocated")
                }
                for ((unitIndex, allocation) in state.outerAllocation.withIndex()) {
                    val unit = domain.outerAtomicCostUnits[unitIndex]
                    val expected = PaymentTargetV1.OuterCostUnit(unit.symbolIndex, unit.unitIndexWithinSymbol)
                    if (allocation.target != expected) {
                        return invalid("outer allocation $unitIndex is not the canonical next target")
                    }
                    ledger.consume(allocation, unit, producersBefore = state.activations.size)?.let {
                        return invalid("outer allocation $unitIndex: $it")
                    }
                }
                if (state.finalized &&
                    (ledger.openActivationIndex != null ||
                        state.outerAllocation.size != domain.outerAtomicCostUnits.size)
                ) {
                    return invalid("a finalized construction must allocate every cost unit")
                }
                return PaymentConstructionResultV1.Ok(ledger)
            }
        }
    }

    private class ViabilityWorkExceeded(val observed: Long) : RuntimeException(null, null, false, false)

    /**
     * Exact viability check: does at least one complete legal plan extend a staged prefix?
     *
     * Legality of every remaining allocation depends only on a resource's colour, a bucket's
     * remaining capacity, and output single use, so the remaining problem is abstracted to colour
     * counts of the still-available resources, the remaining sources, the remaining outer demand,
     * and the remaining self-damage budget. Soundness and completeness rest on four facts:
     *  - the open activation's unpaid inner units can only use resources that already exist, so
     *    they are paid first, from every distinct colour multiset the existing resources allow;
     *  - a new activation with no inner cost and no budget-relevant damage costs nothing, so it can
     *    be moved in front of every other new activation, and an activation whose outputs go unused
     *    stays legal (unused outputs float). Hence a source whose only activation shape is free is
     *    activated eagerly ("eager"), and a source whose activations are all free single-output
     *    shapes becomes one optional unit of any colour it can produce ("flexible");
     *  - every other source ("complex": an inner cost, budget-relevant damage, or a choice between
     *    shapes of different output counts) is explored exhaustively, memoized on the abstract state,
     *    paying each inner unit from any existing colour or any flexible unit;
     *  - at every node the outer demand is matched exactly against the fixed colours plus the
     *    flexible units by a maximum flow (unit demands, colour/flexible capacities). A node is
     *    pruned when a necessary Hall condition fails even with every remaining complex source
     *    treated as cost-free (and, over all colours, with its net output only).
     * The exponential part is thereby confined to complex sources; the operational ceiling
     * [MAX_PAYMENT_CONSTRUCTION_VIABILITY_WORK] fails closed if it is ever exceeded.
     */
    private class ViabilitySearch(domain: PaymentDomainV5) {
        private val budget: Long? = domain.fixedSelfDamageBudget?.toLong()
        private val shapesBySource: Map<EntityId, SourceShape> = domain.sourceActivationOptions
            .groupBy { it.sourceId }
            .mapValues { (_, options) -> SourceShape.of(options, budgetRelevant = budget != null) }
        private val memo = HashMap<SearchKey, Boolean>()
        private var memoDemand: List<Int>? = null
        private var work = 0L

        val workSpent: Long
            get() = work

        private fun charge() {
            if (++work > MAX_PAYMENT_CONSTRUCTION_VIABILITY_WORK) throw ViabilityWorkExceeded(work)
        }

        fun isViable(state: PaymentConstructionStateV1, ledger: Ledger): Boolean {
            if (state.finalized) return true
            val available = IntArray(COLOR_COUNT)
            for ((bucketIndex, bucket) in state.domain.initialPoolBuckets.withIndex()) {
                available[bucketColor(bucket.key).ordinal] += ledger.bucketRemaining[bucketIndex]
            }
            for ((activationIndex, count) in ledger.registeredOutputs.withIndex()) {
                for (outputIndex in 0 until count) {
                    if (ManaResourceRefV1.ActivationOutputUnit(activationIndex, outputIndex) !in ledger.consumedOutputs) {
                        available[ledger.outputs[activationIndex][outputIndex].ordinal]++
                    }
                }
            }
            val outerDemand = state.domain.outerAtomicCostUnits
                .drop(state.outerAllocation.size)
                .map(::unitMask)
            val demand = Demand(outerDemand)
            if (state.outerAllocation.isNotEmpty()) return hall(available, demand.subsetSums)

            val remainingBudget = budget?.minus(ledger.damage)
            val open = ledger.openActivationIndex
            val afterOpen = if (open != null) {
                val paid = state.activations[open].activationCostAllocation.size
                val remainingInner = ledger.innerUnits[open].drop(paid).map(::unitMask)
                payments(available, IntArray(SUBSET_COUNT), remainingInner).map { (afterPayment, _) ->
                    afterPayment.also { vector -> ledger.outputs[open].forEach { vector[it.ordinal]++ } }
                }
            } else {
                listOf(available)
            }
            if (memoDemand != outerDemand) {
                memo.clear()
                memoDemand = outerDemand
            }

            val flexible = IntArray(SUBSET_COUNT)
            val eagerOutputs = mutableListOf<Int>()
            val complexCounts = linkedMapOf<SourceShape, Int>()
            for ((sourceId, shape) in shapesBySource) {
                if (sourceId in ledger.usedSources) continue
                when {
                    shape.eagerOutputs != null -> eagerOutputs += shape.eagerOutputs
                    shape.flexibleMask != null -> flexible[shape.flexibleMask]++
                    else -> complexCounts[shape] = (complexCounts[shape] ?: 0) + 1
                }
            }
            val types = complexCounts.keys.sortedBy { it.canonical }
            val counts = IntArray(types.size) { complexCounts.getValue(types[it]) }
            return afterOpen.any { vector ->
                val fixed = vector.copyOf()
                eagerOutputs.forEach { fixed[it]++ }
                search(fixed, flexible.copyOf(), types, counts.copyOf(), remainingBudget, demand)
            }
        }

        private fun search(
            available: IntArray,
            flexible: IntArray,
            types: List<SourceShape>,
            counts: IntArray,
            remainingBudget: Long?,
            demand: Demand,
        ): Boolean {
            charge()
            if (matchable(available, flexible, demand)) return true
            if (!relaxed(available, flexible, types, counts, demand)) return false
            val key = SearchKey(available.toList(), flexible.toList(), types.map { it.canonical }, counts.toList(), remainingBudget)
            memo[key]?.let { return it }
            var result = false
            outer@ for ((typeIndex, type) in types.withIndex()) {
                if (counts[typeIndex] == 0) continue
                for (activation in type.activations) {
                    if (remainingBudget != null && activation.damage > remainingBudget) continue
                    for ((afterPayment, flexibleAfter) in payments(available, flexible, activation.innerMasks)) {
                        activation.outputs.forEach { afterPayment[it]++ }
                        counts[typeIndex]--
                        val found = search(
                            afterPayment,
                            flexibleAfter,
                            types,
                            counts,
                            remainingBudget?.minus(activation.damage),
                            demand,
                        )
                        counts[typeIndex]++
                        if (found) {
                            result = true
                            break@outer
                        }
                    }
                }
            }
            memo[key] = result
            return result
        }

        /** Necessary condition: Hall over colour subsets with complex sources treated as cost-free. */
        private fun relaxed(
            available: IntArray,
            flexible: IntArray,
            types: List<SourceShape>,
            counts: IntArray,
            demand: Demand,
        ): Boolean {
            for (subset in 1 until SUBSET_COUNT) {
                var capacity = 0L
                for (color in 0 until COLOR_COUNT) {
                    if (subset and (1 shl color) != 0) capacity += available[color]
                }
                for (mask in 1 until SUBSET_COUNT) {
                    if (flexible[mask] > 0 && mask and subset != 0) capacity += flexible[mask]
                }
                var gross = 0L
                var net = 0L
                for ((typeIndex, type) in types.withIndex()) {
                    gross += counts[typeIndex].toLong() * type.maxOutputsIn[subset]
                    net += counts[typeIndex].toLong() * type.maxNetOutputs
                }
                // Over all colours an activation adds at most its net output (outputs minus inner).
                capacity += if (subset == SUBSET_COUNT - 1) net else gross
                if (demand.subsetSums[subset] > capacity) return false
            }
            return true
        }

        /** Exact: can the outer demand be matched to fixed colours plus optional flexible units? */
        private fun matchable(available: IntArray, flexible: IntArray, demand: Demand): Boolean {
            if (flexible.all { it == 0 }) return hall(available, demand.subsetSums)
            charge()
            val demandMasks = demand.countsByMask.indices.filter { demand.countsByMask[it] > 0 }
            val supply = (0 until COLOR_COUNT).filter { available[it] > 0 }.map { 1 shl it to available[it] } +
                (1 until SUBSET_COUNT).filter { flexible[it] > 0 }.map { it to flexible[it] }
            // Nodes: 0 = source, 1..D demand groups, D+1..D+P supply groups, D+P+1 = sink.
            val nodeCount = demandMasks.size + supply.size + 2
            val sink = nodeCount - 1
            val capacity = Array(nodeCount) { IntArray(nodeCount) }
            demandMasks.forEachIndexed { d, mask ->
                capacity[0][1 + d] = demand.countsByMask[mask]
                supply.forEachIndexed { p, (supplyMask, _) ->
                    if (mask and supplyMask != 0) capacity[1 + d][1 + demandMasks.size + p] = demand.total
                }
            }
            supply.forEachIndexed { p, (_, amount) -> capacity[1 + demandMasks.size + p][sink] = amount }
            var flow = 0
            while (true) {
                charge()
                val parent = IntArray(nodeCount) { -1 }
                parent[0] = 0
                val queue = ArrayDeque(listOf(0))
                while (queue.isNotEmpty() && parent[sink] < 0) {
                    val node = queue.removeFirst()
                    for (next in 0 until nodeCount) {
                        if (parent[next] < 0 && capacity[node][next] > 0) {
                            parent[next] = node
                            queue.addLast(next)
                        }
                    }
                }
                if (parent[sink] < 0) break
                var bottleneck = Int.MAX_VALUE
                var node = sink
                while (node != 0) {
                    bottleneck = minOf(bottleneck, capacity[parent[node]][node])
                    node = parent[node]
                }
                node = sink
                while (node != 0) {
                    capacity[parent[node]][node] -= bottleneck
                    capacity[node][parent[node]] += bottleneck
                    node = parent[node]
                }
                flow += bottleneck
            }
            return flow == demand.total
        }

        /**
         * Every distinct way to pay [masks] from existing colours and flexible units, as the
         * resulting (colours, flexible) pair.
         */
        private fun payments(
            available: IntArray,
            flexible: IntArray,
            masks: List<Int>,
        ): List<Pair<IntArray, IntArray>> {
            val results = LinkedHashMap<Pair<List<Int>, List<Int>>, Pair<IntArray, IntArray>>()
            fun assign(index: Int, colors: IntArray, units: IntArray) {
                charge()
                if (index == masks.size) {
                    results.putIfAbsent(colors.toList() to units.toList(), colors.copyOf() to units.copyOf())
                    return
                }
                for (color in 0 until COLOR_COUNT) {
                    if (masks[index] and (1 shl color) != 0 && colors[color] > 0) {
                        colors[color]--
                        assign(index + 1, colors, units)
                        colors[color]++
                    }
                }
                for (mask in 1 until SUBSET_COUNT) {
                    if (units[mask] > 0 && masks[index] and mask != 0) {
                        units[mask]--
                        assign(index + 1, colors, units)
                        units[mask]++
                    }
                }
            }
            assign(0, available.copyOf(), flexible.copyOf())
            return results.values.toList()
        }
    }

    /** The remaining outer demand, grouped by accepted-colour mask. */
    private class Demand(masks: List<Int>) {
        val countsByMask: IntArray = IntArray(SUBSET_COUNT).also { counts -> masks.forEach { counts[it]++ } }
        val subsetSums: IntArray = subsetDemandSums(masks)
        val total: Int = masks.size
    }

    private data class SearchKey(
        val available: List<Int>,
        val flexible: List<Int>,
        val types: List<String>,
        val counts: List<Int>,
        val budget: Long?,
    )

    private class ActivationShape(
        val innerMasks: List<Int>,
        val outputs: List<Int>,
        val damage: Long,
    ) {
        val canonical: String = "$innerMasks>$outputs!$damage"
    }

    /** Colour-abstracted capability of one source (all of its published options). */
    private class SourceShape private constructor(
        val activations: List<ActivationShape>,
        val canonical: String,
        /** Outputs of the single free activation shape, when the source is eager. */
        val eagerOutputs: List<Int>?,
        /** Colour mask of the optional unit, when every activation is a free single output. */
        val flexibleMask: Int?,
    ) {
        /** For each colour subset, the most outputs one activation of this source puts in it. */
        val maxOutputsIn: IntArray = IntArray(SUBSET_COUNT) { subset ->
            activations.maxOf { activation -> activation.outputs.count { subset and (1 shl it) != 0 } }
        }

        /** The most resources one activation can add net of its inner cost (never negative). */
        val maxNetOutputs: Int = maxOf(0, activations.maxOf { it.outputs.size - it.innerMasks.size })

        override fun equals(other: Any?): Boolean = other is SourceShape && other.canonical == canonical

        override fun hashCode(): Int = canonical.hashCode()

        companion object {
            fun of(options: List<PaymentSourceActivationDomainV2>, budgetRelevant: Boolean): SourceShape {
                val activations = options.flatMap { option ->
                    option.productionChoices.map { production ->
                        ActivationShape(
                            innerMasks = option.atomicActivationManaCostUnits.map(::unitMask).sorted(),
                            outputs = outputColors(production)!!.map { it.ordinal }.sorted(),
                            damage = option.fixedSelfDamageAmount.toLong(),
                        )
                    }
                }.distinctBy { it.canonical }
                fun free(activation: ActivationShape) =
                    activation.innerMasks.isEmpty() && (!budgetRelevant || activation.damage == 0L)
                val eager = activations.singleOrNull()?.takeIf(::free)?.outputs
                val flexible = if (eager == null && activations.all { free(it) && it.outputs.size == 1 }) {
                    activations.fold(0) { mask, activation -> mask or (1 shl activation.outputs.single()) }
                } else {
                    null
                }
                return SourceShape(
                    activations = activations,
                    canonical = activations.map { it.canonical }.sorted().joinToString("|"),
                    eagerOutputs = eager,
                    flexibleMask = flexible,
                )
            }
        }
    }

    private const val COLOR_COUNT = 6
    private const val SUBSET_COUNT = 1 shl COLOR_COUNT

    /** For each colour subset S, the number of demand units whose accepted colours lie inside S. */
    private fun subsetDemandSums(masks: List<Int>): IntArray {
        val sums = IntArray(SUBSET_COUNT)
        masks.forEach { sums[it]++ }
        for (color in 0 until COLOR_COUNT) {
            for (subset in 0 until SUBSET_COUNT) {
                if (subset and (1 shl color) != 0) sums[subset] += sums[subset xor (1 shl color)]
            }
        }
        return sums
    }

    /** Hall's condition for unit demands against colour capacities. */
    private fun hall(available: IntArray, demandSums: IntArray): Boolean {
        for (subset in 1 until SUBSET_COUNT) {
            var capacity = 0L
            for (color in 0 until COLOR_COUNT) {
                if (subset and (1 shl color) != 0) capacity += available[color]
            }
            if (demandSums[subset] > capacity) return false
        }
        return true
    }

    private fun unitMask(unit: AtomicManaCostUnitV1): Int = when (unit.kind) {
        PaymentCostKindV1.GENERIC -> SUBSET_COUNT - 1
        PaymentCostKindV1.COLORLESS -> 1 shl PaymentManaColor.COLORLESS.ordinal
        PaymentCostKindV1.COLORED -> unit.allowedColors.fold(0) { mask, color -> mask or (1 shl color.ordinal) }
    }

    private fun accepts(unit: AtomicManaCostUnitV1, color: PaymentManaColor): Boolean =
        unitMask(unit) and (1 shl color.ordinal) != 0

    private fun bucketColor(key: InitialPoolBucketKeyV1): PaymentManaColor = when (key) {
        is InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> key.color
        is InitialPoolBucketKeyV1.CertifiedFloatingBucket -> key.key.poolColor
    }

    /** The ordered output units of a production choice, or null outside the reviewed slice. */
    private fun outputColors(choice: ProductionChoice): List<PaymentManaColor>? {
        if (choice.amount != 1 || choice.bonusChoice != null) return null
        val fixed = choice.fixedOutputs ?: return listOf(choice.producedColor)
        if (fixed.size < 2 ||
            fixed.map { it.index } != fixed.indices.toList() ||
            fixed.any { it.amount != 1 } ||
            choice.producedColor != fixed.first().color
        ) {
            return null
        }
        return fixed.map { it.color }
    }
}

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Test-only independent flat reference enumerator for PaymentConstructionGrammarV1
 * (docs/ml/c1-live-payment-choice-boundary-02a.md §6, TASK 2).
 *
 * Independence: this oracle shares NO code with the grammar. It does not call nextSteps, apply,
 * isTerminal, or materialize, and it does not reuse the grammar's ledger or viability search. It
 * GENERATES every candidate program by brute force — every ordered sequence of distinct published
 * options (sourceId uniqueness is deliberately NOT enforced by generation), every production and
 * cost-order combination, and every total function from atomic cost targets to the full resource
 * universe (all published pool buckets plus every output unit of every activation in the program,
 * earlier or later) — and then FILTERS each candidate through [violation], which re-derives the
 * ledger rules L1-L7 directly from the published DTO. It may be exponential; fixtures keep it small.
 */
internal object PaymentConstructionFlatReferenceEnumerator {

    /** Hard ceiling on generated candidates so an oversized fixture fails loudly, never silently. */
    const val MAX_GENERATED_CANDIDATES: Long = 50_000_000L

    data class Enumeration(
        val legalPlans: List<PaymentPlanV3>,
        val generatedCandidates: Long,
    )

    fun enumerate(domain: PaymentDomainV5, maxCandidates: Long = MAX_GENERATED_CANDIDATES): Enumeration {
        val options = domain.sourceActivationOptions
        val legal = LinkedHashMap<String, PaymentPlanV3>()
        var generated = 0L

        fun programs(prefix: List<Int>, emit: (List<Int>) -> Unit) {
            emit(prefix)
            for (optionIndex in options.indices) {
                if (optionIndex !in prefix) programs(prefix + optionIndex, emit)
            }
        }

        programs(emptyList()) { optionSequence ->
            val choiceAxes = optionSequence.map { optionIndex ->
                val option = options[optionIndex]
                option.productionChoices.flatMap { production ->
                    option.activationCostOrderOptions.map { order -> production to order }
                }
            }
            for (choices in cartesian(choiceAxes)) {
                val activations = optionSequence.mapIndexed { position, optionIndex ->
                    val option = options[optionIndex]
                    SourceActivationV2(
                        sourceId = option.sourceId,
                        manaAbilityKey = option.manaAbilityKey,
                        productionChoice = choices[position].first,
                        activationCostOrder = choices[position].second,
                    )
                }
                val targets = buildList<Pair<PaymentTargetV1, Int?>> {
                    optionSequence.forEachIndexed { position, optionIndex ->
                        options[optionIndex].atomicActivationManaCostUnits.forEach { unit ->
                            add(
                                PaymentTargetV1.ActivationCostUnit(position, unit.symbolIndex, unit.unitIndexWithinSymbol)
                                    to position
                            )
                        }
                    }
                    domain.outerAtomicCostUnits.forEach { unit ->
                        add(PaymentTargetV1.OuterCostUnit(unit.symbolIndex, unit.unitIndexWithinSymbol) to null)
                    }
                }
                val resources = buildList<ManaResourceRefV1> {
                    domain.initialPoolBuckets.forEach { add(ManaResourceRefV1.InitialPoolResource(it.key)) }
                    activations.forEachIndexed { activationIndex, activation ->
                        outputsOf(activation.productionChoice).indices.forEach { outputIndex ->
                            add(ManaResourceRefV1.ActivationOutputUnit(activationIndex, outputIndex))
                        }
                    }
                }
                val assignments = pow(resources.size.toLong(), targets.size)
                generated += assignments
                check(generated <= maxCandidates) {
                    "Reference fixture too large: $generated generated candidates"
                }
                for (assignment in functions(resources.size, targets.size)) {
                    val inner = activations.indices.map { mutableListOf<PaymentAllocationV1>() }
                    val outer = mutableListOf<PaymentAllocationV1>()
                    targets.forEachIndexed { targetIndex, (target, owner) ->
                        val allocation = PaymentAllocationV1(target, resources[assignment[targetIndex]])
                        if (owner == null) outer += allocation else inner[owner] += allocation
                    }
                    val plan = PaymentPlanV3(
                        activations = activations.mapIndexed { index, activation ->
                            activation.copy(activationCostAllocation = inner[index])
                        },
                        outerAllocation = outer,
                    )
                    if (violation(domain, plan) == null) legal.putIfAbsent(identity(plan), plan)
                }
            }
        }
        return Enumeration(legalPlans = legal.values.toList(), generatedCandidates = generated)
    }

    /**
     * The first ledger rule [plan] violates against [domain], or null when it is a complete legal
     * plan. Every rule is reconstructed from the published DTO alone.
     */
    fun violation(domain: PaymentDomainV5, plan: PaymentPlanV3): String? {
        // Domain conformity: every activation is a published option with a published choice.
        val optionOf = plan.activations.map { activation ->
            domain.sourceActivationOptions.firstOrNull {
                it.sourceId == activation.sourceId && it.manaAbilityKey == activation.manaAbilityKey
            } ?: return "unpublished source option"
        }
        plan.activations.forEachIndexed { index, activation ->
            if (activation.productionChoice !in optionOf[index].productionChoices) return "unpublished production"
            if (activation.activationCostOrder !in optionOf[index].activationCostOrderOptions) return "unpublished order"
        }
        // L1 at most one activation per sourceId.
        if (plan.activations.map { it.sourceId }.toSet().size != plan.activations.size) return "L1"

        // L3 every atomic target exactly once, and allocations sit in their owner's list.
        val expected = mutableMapOf<PaymentTargetV1, AtomicManaCostUnitV1>()
        plan.activations.forEachIndexed { index, _ ->
            optionOf[index].atomicActivationManaCostUnits.forEach { unit ->
                expected[PaymentTargetV1.ActivationCostUnit(index, unit.symbolIndex, unit.unitIndexWithinSymbol)] = unit
            }
        }
        domain.outerAtomicCostUnits.forEach { unit ->
            expected[PaymentTargetV1.OuterCostUnit(unit.symbolIndex, unit.unitIndexWithinSymbol)] = unit
        }
        val allocated = plan.activations.flatMap { it.activationCostAllocation } + plan.outerAllocation
        if (allocated.map { it.target }.toSet().size != allocated.size) return "L3 duplicate target"
        if (allocated.map { it.target }.toSet() != expected.keys) return "L3 incomplete targets"
        plan.activations.forEachIndexed { index, activation ->
            if (activation.activationCostAllocation.any {
                    (it.target as? PaymentTargetV1.ActivationCostUnit)?.activationIndex != index
                }
            ) return "L3 misplaced inner allocation"
        }
        if (plan.outerAllocation.any { it.target !is PaymentTargetV1.OuterCostUnit }) return "L3 misplaced outer allocation"

        val outputs = plan.activations.map { outputsOf(it.productionChoice) }
        val usedOutputs = mutableSetOf<ManaResourceRefV1.ActivationOutputUnit>()
        val bucketUse = mutableMapOf<InitialPoolBucketKeyV1, Int>()
        for (allocation in allocated) {
            val ownerIndex = (allocation.target as? PaymentTargetV1.ActivationCostUnit)?.activationIndex
            val color = when (val resource = allocation.resource) {
                is ManaResourceRefV1.InitialPoolResource -> {
                    val bucket = domain.initialPoolBuckets.firstOrNull { it.key == resource.bucketKey }
                        ?: return "unpublished bucket"
                    bucketUse[resource.bucketKey] = (bucketUse[resource.bucketKey] ?: 0) + 1
                    // L5 bucket capacity per exact key.
                    if (bucketUse.getValue(resource.bucketKey) > bucket.availableAmount) return "L5"
                    when (val key = resource.bucketKey) {
                        is InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> key.color
                        is InitialPoolBucketKeyV1.CertifiedFloatingBucket -> key.key.poolColor
                    }
                }

                is ManaResourceRefV1.ActivationOutputUnit -> {
                    val producer = outputs.getOrNull(resource.activationIndex) ?: return "unknown producer"
                    val color = producer.getOrNull(resource.outputIndex) ?: return "unknown output"
                    // L2 inner costs consume only strictly earlier outputs.
                    if (ownerIndex != null && resource.activationIndex >= ownerIndex) return "L2"
                    // L4 each output unit at most once.
                    if (!usedOutputs.add(resource)) return "L4"
                    color
                }
            }
            // L6 colour class.
            val unit = expected.getValue(allocation.target)
            val ok = when (unit.kind) {
                PaymentCostKindV1.GENERIC -> true
                PaymentCostKindV1.COLORLESS -> color == PaymentManaColor.COLORLESS
                PaymentCostKindV1.COLORED -> color in unit.allowedColors
            }
            if (!ok) return "L6"
        }
        // L7 certified self-damage within the published budget.
        val budget = domain.fixedSelfDamageBudget
        if (budget != null && optionOf.sumOf { it.fixedSelfDamageAmount } > budget) return "L7"
        return null
    }

    /**
     * The frozen conservative proof identity (§7): activations in program order with indices
     * preserved; only allocation-list order inside one activation and inside the outer list is
     * normalized.
     */
    fun identity(plan: PaymentPlanV3): String {
        fun allocations(list: List<PaymentAllocationV1>): JsonElement =
            JsonArray(list.map { A3SemanticJson.canonicalJson(json.encodeToJsonElement(it)) }.sorted().map(::JsonPrimitive))
        val element = buildJsonObject {
            put(
                "activations",
                JsonArray(plan.activations.map { activation ->
                    buildJsonObject {
                        put("sourceId", JsonPrimitive(activation.sourceId.value))
                        put("manaAbilityKey", JsonPrimitive(activation.manaAbilityKey))
                        put("productionChoice", json.encodeToJsonElement(activation.productionChoice))
                        put("activationCostOrder", json.encodeToJsonElement(activation.activationCostOrder))
                        put("activationCostAllocation", allocations(activation.activationCostAllocation))
                    }
                }),
            )
            put("outerAllocation", allocations(plan.outerAllocation))
        }
        return A3SemanticJson.canonicalJson(element)
    }

    private val json = Json { encodeDefaults = true }

    private fun outputsOf(choice: ProductionChoice): List<PaymentManaColor> =
        choice.fixedOutputs?.map { it.color } ?: listOf(choice.producedColor)

    private fun <T> cartesian(axes: List<List<T>>): Sequence<List<T>> = sequence {
        if (axes.isEmpty()) {
            yield(emptyList())
            return@sequence
        }
        for (head in axes.first()) {
            for (tail in cartesian(axes.drop(1))) yield(listOf(head) + tail)
        }
    }

    private fun functions(rangeSize: Int, domainSize: Int): Sequence<IntArray> = sequence {
        if (domainSize == 0) {
            yield(IntArray(0))
            return@sequence
        }
        if (rangeSize == 0) return@sequence
        val current = IntArray(domainSize)
        while (true) {
            yield(current.copyOf())
            var position = domainSize - 1
            while (position >= 0 && current[position] == rangeSize - 1) {
                current[position] = 0
                position--
            }
            if (position < 0) return@sequence
            current[position]++
        }
    }

    private fun pow(base: Long, exponent: Int): Long {
        var result = 1L
        repeat(exponent) { result = Math.multiplyExact(result, base) }
        return result
    }
}

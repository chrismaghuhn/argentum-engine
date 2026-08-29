package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.ActivationCostOrderV1
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.InitialPoolBucketV1
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.costs.manaCostOrNull

/** Exact accounting for one selected source after an explicit plan is validated. */
internal data class ExactPaymentSourceMaterialization(
    val source: ManaSource,
    val ability: ActivatedAbility?,
    val outputs: List<PaymentManaColor>,
    val consumedOutputIndexes: Set<Int>,
    /** Snapshot captured before the source activation; applied only after production succeeds. */
    val sourceSubtypes: Set<Subtype>,
)

/**
 * The single accounting authority for explicit PaymentPlanV1 materialization. The legacy
 * [ManaSolution] adapter is retained only for [ManaAbilitySideEffectExecutor], which needs the
 * selected source ability in order to execute non-mana side effects exactly once.
 */
internal data class ExactPaymentMaterialization(
    /** Floating pool after the submitted pool buckets have been consumed, before source production. */
    val poolAfterFloatingSpend: ManaPool,
    /** Validation preview including every selected source output not consumed by the plan. */
    val poolAfterSpend: ManaPool,
    val sourcePayments: List<ExactPaymentSourceMaterialization>,
    val manaSpent: ManaPool,
    val spentManaProvenance: SpentManaProvenance,
) {
    /**
     * Materialize the pool only after the selected source side effects have succeeded. Solver
     * snapshots are carried into this method, but never become authoritative before that seam.
     */
    fun poolAfterSuccessfulSourceProduction(playerId: EntityId): ManaPool {
        var pool = poolAfterFloatingSpend
        for (payment in sourcePayments) {
            for ((index, color) in payment.outputs.withIndex()) {
                if (index !in payment.consumedOutputIndexes) {
                    pool = pool.addTracked(
                        color = color,
                        sourceId = payment.source.entityId,
                        subtypes = payment.sourceSubtypes,
                        knownToPlayers = setOf(playerId),
                    )
                }
            }
        }
        return pool
    }

    fun asManaSolution(): ManaSolution {
        val ordered = sourcePayments.sortedBy { it.source.entityId.value }
        return ManaSolution(
            sources = ordered.map { it.source },
            // This map is an adapter for side-effect execution only. Cost/event accounting must
            // use [manaSpent] and the consumed output indexes above.
            manaProduced = ordered.associate { payment ->
                val color = payment.outputs.first().asEngineColor()
                payment.source.entityId to ManaProduction(
                    color = color,
                    amount = if (color == null) 0 else 1,
                    colorless = if (color == null) 1 else 0,
                    manaAbility = payment.ability,
                )
            },
            manaAbilityUses = ordered.associate { payment ->
                payment.source.entityId to ManaAbilityUse(
                    ability = payment.ability,
                    producedColor = payment.outputs.first().asEngineColor(),
                )
            },
        )
    }
}

/** Rules-owned, fully resolved activation facts handed to the future ordered-program executor. */
internal data class ValidatedPaymentActivationV3(
    val source: ManaSource,
    val ability: ActivatedAbility,
    val productionChoice: ProductionChoice,
    val outputs: List<PaymentManaColor>,
    val effectiveCost: AbilityCost,
    val activationCostUnits: List<AtomicManaCostUnitV1>,
    val activationCostOrder: ActivationCostOrderV1,
    val activationCostAllocation: List<PaymentAllocationV1>,
)

/** The immutable result of the V3 preflight, including the one-ledger accounting snapshot. */
internal data class ValidatedPaymentProgramV3(
    val outerCost: ManaCost,
    val initialPoolBuckets: List<InitialPoolBucketV1>,
    val activations: List<ValidatedPaymentActivationV3>,
    /** All inner and outer allocations in their submitted program order. */
    val allocations: List<PaymentAllocationV1>,
    val consumedInitialPool: Map<InitialPoolBucketKeyV1, Int>,
    val consumedActivationOutputs: Set<ManaResourceRefV1.ActivationOutputUnit>,
)

/** Exact result of validating a V1/V2/V3 plan; no solver choice is made after this boundary. */
sealed interface PaymentPlanValidation {
    @ConsistentCopyVisibility
    data class Accepted internal constructor(
        internal val materialization: ExactPaymentMaterialization,
        /** Compatibility adapter for ManaAbilitySideEffectExecutor; not payment accounting. */
        val solution: ManaSolution = materialization.asManaSolution(),
    ) : PaymentPlanValidation {
        val poolAfterSpend: ManaPool get() = materialization.poolAfterSpend
    }

    /**
     * V3 has a different materialization shape: activation outputs are ordered program resources,
     * not the legacy V1 source-to-one-mana adapter. Keeping a separate result preserves the V1/V2
     * execution contract while giving the future ordered executor the exact preflight facts.
     */
    @ConsistentCopyVisibility
    data class AcceptedV3 internal constructor(
        internal val program: ValidatedPaymentProgramV3,
    ) : PaymentPlanValidation

    data class Rejected(val reason: String) : PaymentPlanValidation
}

/**
 * The ordinary source shape that PaymentPlanV1 can represent without hiding another choice.
 * Advanced sources remain available to the legacy engine paths but are deliberately rejected here
 * until their secondary choices, restrictions, riders, and external production modifiers have
 * public fields. A Rules-owned fixed-output profile is the one additive multi-mana exception.
 */
fun ManaSource.supportsPaymentPlanV1(): Boolean {
    val productionShapeSupported = if (paymentManaProductionProfiles.isNotEmpty()) {
        paymentManaProductionProfiles.keys == paymentManaSideEffectCertificates.keys &&
            paymentManaProductionProfiles.values.all { it !is PaymentManaProductionProfile.Unsupported } &&
            paymentManaSideEffectCertificates.values.all { it.isSupported() } &&
            paymentProfileKeysAreComplete() &&
            sideEffectPainMetadataIsComplete()
    } else {
        manaAmount == 1 &&
            bonusManaPerTap == 0 &&
            bonusManaColorlessPerTap == 0 &&
            bonusManaColor == null &&
            !bonusManaIsAnyColor
    }
    val legacySideEffectShapeSupported = paymentManaProductionProfiles.isNotEmpty() ||
        (paymentManaSideEffectCertificates.isEmpty() && colorPainCost.isEmpty() && colorlessPainCost == 0)
    return !requiresSacrifice &&
        tapPermanentsSubCost == null &&
        productionShapeSupported &&
        restriction == null &&
        colorRestrictions.isEmpty() &&
        colorRiders.isEmpty() &&
        !hasContextSensitiveAbilities &&
        colorActivationManaCost.isEmpty() &&
        legacySideEffectShapeSupported &&
        colorsRequiringSacrifice.isEmpty() &&
        !hasUnrepresentedIntrinsicManaChoice() &&
        ordinaryTapManaAbilitiesOnly()
}

private fun PaymentManaSideEffectCertificate.isSupported(): Boolean = when (this) {
    PaymentManaSideEffectCertificate.NoSideEffect -> true
    is PaymentManaSideEffectCertificate.FixedSelfDamage -> amount > 0
    is PaymentManaSideEffectCertificate.Unsupported -> false
}

/**
 * Every candidate that can currently be activated for payment must have both halves of the exact
 * profile. A source may not publish a convenient subset while hiding another legal mana ability.
 */
private fun ManaSource.paymentProfileKeysAreComplete(): Boolean {
    val legalKeys = buildSet {
        for ((color, abilities) in manaAbilityOptionsForColor) {
            if (abilities.isEmpty()) add(ManaAbilityIdentity.intrinsic(color))
            else abilities.forEach { add(ManaAbilityIdentity.key(it)) }
        }
        if (manaAbilityOptionsForColorless.isEmpty()) {
            if (producesColorless) add(ManaAbilityIdentity.intrinsic(null))
        } else {
            manaAbilityOptionsForColorless.forEach { add(ManaAbilityIdentity.key(it)) }
        }
        intrinsicManaColors.forEach { add(ManaAbilityIdentity.intrinsic(it)) }
    }
    return legalKeys.isNotEmpty() && legalKeys == paymentManaProductionProfiles.keys
}

/**
 * [colorPainCost] is legacy aggregate metadata, not authority for side effects. It may only pass
 * when it exactly agrees with the certificate bound to each production profile key.
 */
private fun ManaSource.sideEffectPainMetadataIsComplete(): Boolean {
    val expectedColoredPain = mutableMapOf<com.wingedsheep.sdk.core.Color, Int>()
    var expectedColorlessPain = Int.MAX_VALUE

    for ((key, profile) in paymentManaProductionProfiles) {
        val certificate = paymentManaSideEffectCertificates[key] ?: return false
        val pain = when (certificate) {
            PaymentManaSideEffectCertificate.NoSideEffect -> 0
            is PaymentManaSideEffectCertificate.FixedSelfDamage -> certificate.amount
            is PaymentManaSideEffectCertificate.Unsupported -> return false
        }
        val colors = when (profile) {
            is PaymentManaProductionProfile.SelectableSingleOutput -> profile.allowedColors
            is PaymentManaProductionProfile.FixedOutputBundle -> profile.outputs.map { it.color }.toSet()
            is PaymentManaProductionProfile.Unsupported -> return false
        }
        for (color in colors) {
            val engineColor = color.asEngineColor()
            if (engineColor == null) {
                expectedColorlessPain = minOf(expectedColorlessPain, pain)
            } else {
                expectedColoredPain[engineColor] = minOf(expectedColoredPain[engineColor] ?: Int.MAX_VALUE, pain)
            }
        }
    }

    val expectedColoredPositive = expectedColoredPain
        .filterValues { it > 0 }
    val actualColoredPositive = colorPainCost.filterValues { it > 0 }
    val expectedColorless = if (expectedColorlessPain == Int.MAX_VALUE) 0 else expectedColorlessPain
    return actualColoredPositive == expectedColoredPositive && colorlessPainCost == expectedColorless
}

/**
 * A land with a basic subtype can have an intrinsic mana ability in addition to a granted/static
 * mana ability. The aggregate source currently carries the intrinsic production as seeded colors,
 * but its explicit ability lists cannot identify both choices. PaymentPlanV1 must therefore fail
 * closed instead of publishing or accepting only the granted ability.
 */
private fun ManaSource.hasUnrepresentedIntrinsicManaChoice(): Boolean =
    intrinsicManaColors.isNotEmpty() &&
        producesColors.flatMap(::manaAbilityOptionsFor)
            .plus(manaAbilityOptionsFor(null))
            .isNotEmpty()

private fun ManaSource.ordinaryTapManaAbilitiesOnly(): Boolean {
    val abilities = producesColors
        .flatMap(::manaAbilityOptionsFor)
        .plus(manaAbilityOptionsFor(null))
        .distinctBy { it.id.value }
    return abilities.isEmpty() || abilities.all {
        it.cost is AbilityCost.Tap && it.restrictions.isEmpty()
    }
}

private data class NormalizedPaymentPlan(
    val sourceActivations: List<SourceActivation>,
    val poolSpend: PoolSpend,
    val spendAllocation: NormalizedSpendAllocation,
    val contractName: String,
)

private data class NormalizedSpendAllocation(
    val costUnits: List<NormalizedCostUnitAllocation>,
    val x: List<NormalizedSpend>,
    val restricted: List<NormalizedSpend>,
    val riderBearingSourceIds: List<EntityId>,
)

private data class NormalizedCostUnitAllocation(
    val symbolIndex: Int,
    val spends: List<NormalizedSpend>,
)

private data class NormalizedSpend(
    val sourceId: EntityId?,
    val poolColor: PaymentManaColor?,
    val amount: Int,
    val restrictedBucketKey: String?,
    val sourceOutputIndex: Int?,
    val floatingSourceId: EntityId?,
    val floatingBucketKey: FloatingManaBucketKeyV1? = null,
)

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
    ): PaymentPlanValidation = validateInternal(
        state = state,
        playerId = playerId,
        cost = cost,
        plan = plan.toInternal(),
        spellContext = spellContext,
        excludeSources = excludeSources,
    )

    fun validateV2(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        plan: com.wingedsheep.engine.core.PaymentPlanV2,
        spellContext: SpellPaymentContext? = null,
        excludeSources: Set<EntityId> = emptySet(),
    ): PaymentPlanValidation {
        val (normalized, error) = plan.toInternal()
        if (normalized == null) {
            return PaymentPlanValidation.Rejected(error ?: "PaymentPlanV2 contains an invalid floating bucket reference")
        }
        return validateInternal(
            state = state,
            playerId = playerId,
            cost = cost,
            plan = normalized,
            spellContext = spellContext,
            excludeSources = excludeSources,
        )
    }

    /**
     * Validates the complete ordered V3 payment program without selecting or executing anything.
     *
     * Source discovery deliberately has no outer [SpellPaymentContext]: a source that cannot pay
     * the outer action may still be the only legal payer for an inner paid-mana activation. Any
     * spending restriction is subsequently rejected by the V5 qualification certificate instead
     * of being silently removed before the completeness boundary.
     */
    fun validateV3(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        plan: PaymentPlanV3,
        spellContext: SpellPaymentContext? = null,
        reservedOuterLifePayment: Int = 0,
        excludeSources: Set<EntityId> = emptySet(),
    ): PaymentPlanValidation {
        if (reservedOuterLifePayment < 0) {
            return PaymentPlanValidation.Rejected(
                "PaymentPlanV3 outer life reservation cannot be negative"
            )
        }
        val currentLife = state.lifeTotal(playerId)
        if (reservedOuterLifePayment > currentLife) {
            return PaymentPlanValidation.Rejected(
                "PaymentPlanV3 outer PayLife exceeds current life"
            )
        }
        val fixedSelfDamageBudget = currentLife - reservedOuterLifePayment
        val outerCost = cost.canonicalPaymentManaCost()
        if (!outerCost.isFixedOrdinaryManaCost()) {
            return PaymentPlanValidation.Rejected(
                "PaymentPlanV3 supports only ordinary fixed mana symbols"
            )
        }
        val outerUnits = outerCost.toV3AtomicManaCostUnits()
            ?: return PaymentPlanValidation.Rejected(
                "PaymentPlanV3 cannot atomize the outer mana cost"
            )

        val poolComponent = state.getEntity(playerId)?.get<ManaPoolComponent>() ?: ManaPoolComponent()
        val initialBuckets = poolComponent.toV3InitialPoolBuckets()
            ?: return PaymentPlanValidation.Rejected(
                "PaymentPlanV3 cannot use the current floating-mana provenance"
            )

        val discoveredSources = manaSolver.findAvailableManaSources(
            state = state,
            playerId = playerId,
            // See the method contract above: V3 must not drop an inner-only source here.
            spellContext = null,
            paymentOrderRequired = true,
        ).filter { it.entityId !in excludeSources }
        if (discoveredSources.any { !it.paymentManaExecutionStabilityCertified }) {
            return PaymentPlanValidation.Rejected(
                "PaymentPlanV3 has a source whose activation legality or cost is not stable across the ordered program"
            )
        }
        val sourcesById = discoveredSources.associateBy { it.entityId }
        if (sourcesById.size != discoveredSources.size) {
            return PaymentPlanValidation.Rejected(
                "PaymentPlanV3 source discovery returned duplicate source identities"
            )
        }

        val resolvedActivations = mutableListOf<ValidatedPaymentActivationV3>()
        val selectedSourceIds = mutableSetOf<EntityId>()
        var selectedFixedSelfDamage = 0
        for ((activationIndex, activation) in plan.activations.withIndex()) {
            if (!selectedSourceIds.add(activation.sourceId)) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 activates a source more than once"
                )
            }
            val source = sourcesById[activation.sourceId]
                ?: return PaymentPlanValidation.Rejected(
                    "Payment source is not currently available: ${activation.sourceId}"
                )
            if (!source.paymentManaAbilityOrderCertified) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 source ability order is not currently certified: ${activation.sourceId}"
                )
            }
            if (!source.paymentManaSpendingRestrictionsCertified) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 source has an unrepresented mana-spending restriction: ${activation.sourceId}"
                )
            }
            if (!source.paymentManaExecutionStabilityCertified) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 source execution stability is not currently certified: ${activation.sourceId}"
                )
            }
            if (source.paymentManaProductionProfiles.isEmpty() ||
                source.paymentManaProductionProfiles.keys != source.paymentManaSideEffectCertificates.keys ||
                !source.paymentProfileKeysAreComplete() ||
                source.paymentManaAbilityOrder.isEmpty() ||
                source.paymentManaAbilityOrder.distinct().size != source.paymentManaAbilityOrder.size ||
                source.paymentManaAbilityOrder.toSet() != source.paymentManaProductionProfiles.keys
            ) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 source production contract is incomplete: ${activation.sourceId}"
                )
            }

            val explicitAbilities = source.manaAbilityOptionsForColor.values
                .flatten()
                .plus(source.manaAbilityOptionsForColorless)
                // One runtime ability can be listed under several output colors. This removes
                // only that same in-memory ability; distinct structural options remain visible.
                .distinctBy { it.id.value }
            val abilitiesByKey = explicitAbilities.groupBy(ManaAbilityIdentity::key)
            if (abilitiesByKey.values.any { candidates -> candidates.size != 1 }) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 source has colliding mana ability identities: ${activation.sourceId}"
                )
            }

            val intrinsicAbilities = IntrinsicManaAbilities
                .forEntity(state, state.projectedState, source.entityId)
                .mapNotNull { ability ->
                    val symbol = ability.id.value.removePrefix("intrinsic_mana_").singleOrNull()
                        ?: return@mapNotNull null
                    val color = com.wingedsheep.sdk.core.Color.fromSymbol(symbol)
                        ?: return@mapNotNull null
                    ManaAbilityIdentity.intrinsic(color) to ability
                }
                .toMap()
            val ability = if (activation.manaAbilityKey.startsWith("intrinsic:")) {
                intrinsicAbilities[activation.manaAbilityKey]
            } else {
                abilitiesByKey[activation.manaAbilityKey]?.singleOrNull()
            } ?: return PaymentPlanValidation.Rejected(
                "Mana ability identity does not match the current source"
            )

            val profile = source.paymentManaProductionProfiles[activation.manaAbilityKey]
                ?: return PaymentPlanValidation.Rejected(
                    "Mana ability identity is not in the current source order"
                )
            val advertisedSideEffectCertificate =
                source.paymentManaSideEffectCertificates[activation.manaAbilityKey]
                    ?: return PaymentPlanValidation.Rejected(
                        "Mana ability side-effect certificate is missing"
                    )
            val currentSideEffectCertificate = PaymentManaSideEffectCertificateResolver.resolve(ability.effect)
            if (advertisedSideEffectCertificate != currentSideEffectCertificate ||
                !currentSideEffectCertificate.isSupportedByPaymentProgramV3()
            ) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 source has an unrepresented deterministic side effect"
                )
            }
            val fixedSelfDamageAmount = when (currentSideEffectCertificate) {
                PaymentManaSideEffectCertificate.NoSideEffect -> 0
                is PaymentManaSideEffectCertificate.FixedSelfDamage -> currentSideEffectCertificate.amount
                is PaymentManaSideEffectCertificate.Unsupported -> return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 source has an unrepresented deterministic side effect"
                )
            }
            val remainingFixedSelfDamageBudget = fixedSelfDamageBudget - selectedFixedSelfDamage
            if (fixedSelfDamageAmount > remainingFixedSelfDamageBudget) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 fixed self-damage exceeds life budget after outer PayLife reservation"
                )
            }
            selectedFixedSelfDamage += fixedSelfDamageAmount
            val outputs = when (profile) {
                is PaymentManaProductionProfile.SelectableSingleOutput -> {
                    if (activation.productionChoice.amount != 1 ||
                        activation.productionChoice.bonusChoice != null ||
                        activation.productionChoice.fixedOutputs != null
                    ) {
                        return PaymentPlanValidation.Rejected(
                            "PaymentPlanV3 single-output production choice is not canonical"
                        )
                    }
                    if (activation.productionChoice.producedColor !in profile.allowedColors) {
                        return PaymentPlanValidation.Rejected(
                            "Payment source cannot produce ${activation.productionChoice.producedColor.name}: ${activation.sourceId}"
                        )
                    }
                    listOf(activation.productionChoice.producedColor)
                }

                is PaymentManaProductionProfile.FixedOutputBundle -> {
                    val submitted = activation.productionChoice.fixedOutputs
                        ?: return PaymentPlanValidation.Rejected(
                            "PaymentPlanV3 fixed-output source requires canonical fixedOutputs"
                        )
                    val expected = profile.outputs.map { it.color }
                    if (submitted.size != expected.size ||
                        submitted.map { it.index } != expected.indices.toList() ||
                        submitted.any { it.amount != 1 } ||
                        submitted.map { it.color } != expected ||
                        activation.productionChoice.producedColor != expected.firstOrNull()
                    ) {
                        return PaymentPlanValidation.Rejected(
                            "PaymentPlanV3 fixedOutputs do not match current source production"
                        )
                    }
                    expected
                }

                is PaymentManaProductionProfile.Unsupported -> {
                    return PaymentPlanValidation.Rejected(
                        "Payment source production is unsupported: ${profile.reason}"
                    )
                }
            }

            if (!ability.isManaAbility) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 selected ability is not a mana ability"
                )
            }
            val effectiveCost = manaSolver.calculateEffectiveActivatedAbilityCost(
                state = state,
                sourceId = source.entityId,
                controllerId = playerId,
                ability = ability,
            )
            val costShape = effectiveCost.toV3ActivationCostShape()
                ?: return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 activation cost is outside the certified first slice"
                )
            if (activation.activationCostOrder != costShape.activationCostOrder) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 activation cost order does not match the current Rules cost"
                )
            }
            if (costShape.atomicManaCostUnits.isNotEmpty() &&
                !manaSolver.isPaidManaSourceTimingCertified(
                    PaidManaSourceTimingCandidate(
                        state = state,
                        controllerId = playerId,
                        sourceId = source.entityId,
                        manaAbilityKey = activation.manaAbilityKey,
                        ability = ability,
                        effectiveCost = effectiveCost,
                        productionProfile = profile,
                        spellContext = spellContext ?: SpellPaymentContext(),
                    )
                )
            ) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 paid-mana timing is not certified for source ${activation.sourceId}"
                )
            }
            if (!manaSolver.isPaymentProgramExecutionStabilityCertified(
                    PaymentProgramExecutionStabilityCandidate(
                        state = state,
                        controllerId = playerId,
                        sourceId = source.entityId,
                        manaAbilityKey = activation.manaAbilityKey,
                        ability = ability,
                        effectiveCost = effectiveCost,
                        productionProfile = profile,
                        sideEffectCertificate = currentSideEffectCertificate,
                        lifeMutationStabilityCertified = source.paymentManaExecutionStabilityCertified,
                    )
                )
            ) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 source execution stability is not certified for source ${activation.sourceId}"
                )
            }

            resolvedActivations += ValidatedPaymentActivationV3(
                source = source,
                ability = ability,
                productionChoice = activation.productionChoice,
                outputs = outputs,
                effectiveCost = effectiveCost,
                activationCostUnits = costShape.atomicManaCostUnits,
                activationCostOrder = costShape.activationCostOrder,
                activationCostAllocation = activation.activationCostAllocation,
            )
        }

        val expectedTargets = linkedMapOf<PaymentTargetV1, AtomicManaCostUnitV1>()
        for ((activationIndex, activation) in resolvedActivations.withIndex()) {
            for (unit in activation.activationCostUnits) {
                val target = PaymentTargetV1.ActivationCostUnit(
                    activationIndex = activationIndex,
                    symbolIndex = unit.symbolIndex,
                    unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                )
                if (expectedTargets.put(target, unit) != null) {
                    return PaymentPlanValidation.Rejected(
                        "PaymentPlanV3 generated duplicate activation cost targets"
                    )
                }
            }
        }
        for (unit in outerUnits) {
            val target = PaymentTargetV1.OuterCostUnit(
                symbolIndex = unit.symbolIndex,
                unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
            )
            if (expectedTargets.put(target, unit) != null) {
                return PaymentPlanValidation.Rejected(
                    "PaymentPlanV3 generated duplicate outer cost targets"
                )
            }
        }

        val ledger = PaymentResourceLedgerV3(
            initialPoolCapacities = initialBuckets,
            activations = resolvedActivations,
            expectedTargets = expectedTargets,
        )
        val allAllocations = mutableListOf<PaymentAllocationV1>()
        for ((activationIndex, activation) in resolvedActivations.withIndex()) {
            for (allocation in activation.activationCostAllocation) {
                val error = ledger.consume(
                    allocation = allocation,
                    currentActivationIndex = activationIndex,
                )
                if (error != null) return PaymentPlanValidation.Rejected(error)
                allAllocations += allocation
            }
        }
        for (allocation in plan.outerAllocation) {
            val error = ledger.consume(
                allocation = allocation,
                currentActivationIndex = null,
            )
            if (error != null) return PaymentPlanValidation.Rejected(error)
            allAllocations += allocation
        }
        ledger.finish()?.let { return PaymentPlanValidation.Rejected(it) }

        return PaymentPlanValidation.AcceptedV3(
            program = ValidatedPaymentProgramV3(
                outerCost = outerCost,
                initialPoolBuckets = initialBuckets.map { (key, amount) ->
                    InitialPoolBucketV1(key = key, availableAmount = amount)
                },
                activations = resolvedActivations,
                allocations = allAllocations,
                consumedInitialPool = ledger.consumedInitialPool,
                consumedActivationOutputs = ledger.consumedActivationOutputs,
            ),
        )
    }

    private fun validateInternal(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        plan: NormalizedPaymentPlan,
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
        val provenanceClassification = FloatingManaProvenanceClassification.classify(poolComponent)
        if (provenanceClassification is FloatingManaProvenanceClassification.Ambiguous) {
            return PaymentPlanValidation.Rejected(
                "PaymentPlanV1 cannot spend floating mana with hidden provenance"
            )
        }
        val certifiedFloatingMana = (provenanceClassification as?
            FloatingManaProvenanceClassification.CertifiedHomogeneous)?.candidate
        val certifiedHeterogeneousMana = (provenanceClassification as?
            FloatingManaProvenanceClassification.CertifiedHeterogeneous)?.candidate
        val certifiedJointMana = (provenanceClassification as?
            FloatingManaProvenanceClassification.CertifiedJoint)?.candidate
        val currentPool = poolComponent.toManaPool()

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
            validateCanonicalProductionChoice(activation.productionChoice)?.let {
                return PaymentPlanValidation.Rejected(it)
            }
            val source = sourcesById[activation.sourceId]
                ?: return PaymentPlanValidation.Rejected("Payment source is not currently available: ${activation.sourceId}")
            val color = activation.productionChoice.producedColor
            val engineColor = color.asEngineColor()
            val profile = source.paymentManaProductionProfiles[activation.manaAbilityKey]
                ?: if (source.paymentManaProductionProfiles.isEmpty()) {
                    PaymentManaProductionProfile.SelectableSingleOutput(
                        allowedColors = source.producesColors.map(PaymentManaColor::fromEngine).toSet() +
                            if (source.producesColorless) setOf(PaymentManaColor.COLORLESS) else emptySet()
                    )
                } else null
            if (profile is PaymentManaProductionProfile.Unsupported) {
                return PaymentPlanValidation.Rejected(
                    "Payment source production is unsupported: ${profile.reason}"
                )
            }
            if (profile == null || !source.supportsPaymentPlanV1()) {
                return PaymentPlanValidation.Rejected("Payment source shape is unsupported: ${activation.sourceId}")
            }
            val outputs = when (profile) {
                is PaymentManaProductionProfile.SelectableSingleOutput -> {
                    if (activation.productionChoice.fixedOutputs != null) {
                        return PaymentPlanValidation.Rejected(
                            "fixedOutputs is only valid for a fixed output bundle"
                        )
                    }
                    if (color !in profile.allowedColors) {
                        return PaymentPlanValidation.Rejected(
                            "Payment source cannot produce ${color.name}: ${activation.sourceId}"
                        )
                    }
                    listOf(color)
                }

                is PaymentManaProductionProfile.FixedOutputBundle -> {
                    val submitted = activation.productionChoice.fixedOutputs
                        ?: return PaymentPlanValidation.Rejected(
                            "Fixed-output source requires canonical fixedOutputs"
                        )
                    val expected = profile.outputs.map { it.color }
                    if (submitted.map { it.color } != expected) {
                        return PaymentPlanValidation.Rejected(
                            "fixedOutputs do not match the current source production"
                        )
                    }
                    submitted.map { it.color }
                }

                is PaymentManaProductionProfile.Unsupported -> error("handled above")
            }
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
            resolved[activation.sourceId] = ResolvedActivation(
                source = source,
                activation = activation,
                ability = resolvedAbility,
                profile = profile,
                outputs = outputs,
            )
        }

        val allocations = plan.spendAllocation.costUnits.associateBy { it.symbolIndex }
        if (allocations.size != plan.spendAllocation.costUnits.size ||
            allocations.keys != cost.symbols.indices.toSet()
        ) {
            return PaymentPlanValidation.Rejected("PaymentPlanV1 must allocate every cost symbol exactly once")
        }

        val poolAmounts = mutableMapOf<PaymentManaColor, Int>()
        val floatingSourceAmounts = mutableMapOf<EntityId, Int>()
        val floatingSourceColorAmounts = mutableMapOf<FloatingManaSourceColorKey, Int>()
        val floatingJointAmounts = mutableMapOf<FloatingManaBucketKeyV1, Int>()
        val sourceAmounts = mutableMapOf<EntityId, Int>()
        val sourceOutputAmounts = mutableMapOf<EntityId, MutableMap<Int, Int>>()
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
                if (spend.sourceId != null && (spend.poolColor != null || spend.floatingSourceId != null)) {
                    return PaymentPlanValidation.Rejected("A fresh source spend cannot carry floating provenance")
                }
                if (spend.floatingSourceId != null && spend.poolColor == null) {
                    return PaymentPlanValidation.Rejected("A floating provenance spend requires poolColor")
                }
                val color = when {
                    spend.sourceId != null && spend.poolColor == null && spend.floatingSourceId == null -> {
                        val selected = resolved[spend.sourceId]
                            ?: return PaymentPlanValidation.Rejected("Spend references an unselected source")
                        if (spend.amount != 1) {
                            return PaymentPlanValidation.Rejected(
                                "Each source output is exactly one mana in PaymentPlanV1"
                            )
                        }
                        val outputIndex = spend.sourceOutputIndex
                        val selectedColor = if (selected.profile is PaymentManaProductionProfile.FixedOutputBundle) {
                            outputIndex ?: return PaymentPlanValidation.Rejected(
                                "Bundle source spends require sourceOutputIndex"
                            )
                        } else {
                            if (outputIndex != null) {
                                return PaymentPlanValidation.Rejected(
                                    "Legacy single-output spends forbid sourceOutputIndex"
                                )
                            }
                            0
                        }
                        val outputColor = selected.outputs.getOrNull(selectedColor)
                            ?: return PaymentPlanValidation.Rejected(
                                "sourceOutputIndex is outside the selected production bundle"
                            )
                        val byOutput = sourceOutputAmounts.getOrPut(spend.sourceId) { mutableMapOf() }
                        val used = (byOutput[selectedColor] ?: 0) + spend.amount
                        if (used > 1) {
                            return PaymentPlanValidation.Rejected(
                                "A fixed production output cannot be spent more than once"
                            )
                        }
                        byOutput[selectedColor] = used
                        sourceAmounts[spend.sourceId] = (sourceAmounts[spend.sourceId] ?: 0) + spend.amount
                        outputColor
                    }
                    spend.sourceId == null && spend.floatingSourceId != null && spend.poolColor != null -> {
                        if (spend.sourceOutputIndex != null) {
                            return PaymentPlanValidation.Rejected(
                                "Floating provenance spends cannot carry sourceOutputIndex"
                            )
                        }
                        floatingSourceAmounts[spend.floatingSourceId] =
                            (floatingSourceAmounts[spend.floatingSourceId] ?: 0) + spend.amount
                        val sourceColorKey = FloatingManaSourceColorKey(
                            sourceId = spend.floatingSourceId,
                            poolColor = spend.poolColor,
                        )
                        floatingSourceColorAmounts[sourceColorKey] =
                            (floatingSourceColorAmounts[sourceColorKey] ?: 0) + spend.amount
                        if (spend.floatingBucketKey != null) {
                            if (certifiedJointMana == null ||
                                certifiedJointMana.buckets.none { it.key == spend.floatingBucketKey }
                            ) {
                                return PaymentPlanValidation.Rejected(
                                    "PaymentPlanV2 floating bucket key is not currently certified"
                                )
                            }
                            val key = spend.floatingBucketKey
                            floatingJointAmounts[key] =
                                (floatingJointAmounts[key] ?: 0) + spend.amount
                        } else if (certifiedJointMana != null) {
                            val matchingBuckets = certifiedJointMana.buckets
                                .map { it.key }
                                .filter { key ->
                                    key.sourceId == spend.floatingSourceId &&
                                        key.poolColor == spend.poolColor
                                }
                            if (matchingBuckets.size != 1) {
                                return PaymentPlanValidation.Rejected(
                                    if (plan.contractName == "PaymentPlanV2") {
                                        "PaymentPlanV2 floating bucket key is not currently certified"
                                    } else {
                                        "PaymentPlanV1 floating source reference does not uniquely identify a joint bucket"
                                    },
                                )
                            }
                            val key = matchingBuckets.single()
                            floatingJointAmounts[key] =
                                (floatingJointAmounts[key] ?: 0) + spend.amount
                        }
                        poolAmounts[spend.poolColor] = (poolAmounts[spend.poolColor] ?: 0) + spend.amount
                        spend.poolColor
                    }
                    spend.sourceId == null && spend.poolColor != null -> {
                        if (spend.sourceOutputIndex != null) {
                            return PaymentPlanValidation.Rejected(
                                "Pool spends cannot carry sourceOutputIndex"
                            )
                        }
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
            val spent = sourceAmounts[sourceId] ?: 0
            if (activation.profile is PaymentManaProductionProfile.FixedOutputBundle) {
                if (spent == 0) {
                    return PaymentPlanValidation.Rejected(
                        "A selected fixed-output source must spend at least one produced output"
                    )
                }
            } else if (spent != activation.activation.productionChoice.amount) {
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

        if (floatingSourceAmounts.isNotEmpty() &&
            certifiedFloatingMana == null &&
            certifiedHeterogeneousMana == null &&
            certifiedJointMana == null
        ) {
            return PaymentPlanValidation.Rejected(
                "PaymentPlanV1 floatingSourceId requires certified floating provenance"
            )
        }

        val certifiedPoolPayment = when {
            certifiedJointMana != null && plan.poolSpend.total() > 0 -> {
                for (color in PaymentManaColor.entries) {
                    val requested = plan.poolSpend.amount(color)
                    if (requested == 0) continue
                    val explicitlySelected = floatingJointAmounts.entries
                        .filter { it.key.poolColor == color }
                        .sumOf { it.value }
                    val unassigned = requested - explicitlySelected
                    if (unassigned < 0) {
                        return PaymentPlanValidation.Rejected(
                            "PaymentPlanV1 spends more floating mana than is available"
                        )
                    }
                    if (unassigned > 0) {
                        // A source-less legacy pool spend remains representable only when the
                        // requested color has exactly one joint bucket. This is a uniqueness
                        // proof, not an iteration-order choice; multiple subtype snapshots for
                        // the same color require an explicit V2 bucket key.
                        val matchingBuckets = certifiedJointMana.buckets
                            .map { it.key }
                            .filter { it.poolColor == color }
                        if (matchingBuckets.size != 1) {
                            return PaymentPlanValidation.Rejected(
                                "PaymentPlanV1 joint pool spend must identify every requested color"
                            )
                        }
                        val key = matchingBuckets.single()
                        val available = certifiedJointMana.buckets
                            .single { it.key == key }
                            .amount
                        val alreadySelected = floatingJointAmounts[key] ?: 0
                        if (alreadySelected + unassigned > available) {
                            return PaymentPlanValidation.Rejected(
                                "PaymentPlanV1 spends more floating mana than is available"
                            )
                        }
                        floatingJointAmounts[key] = alreadySelected + unassigned
                    }
                }
                currentPool.consumeCertifiedJoint(floatingJointAmounts)
                    ?: return PaymentPlanValidation.Rejected(
                        "PaymentPlanV1 spends more floating mana than is available"
                    )
            }

            certifiedHeterogeneousMana != null && plan.poolSpend.total() > 0 -> {
                for (color in PaymentManaColor.entries) {
                    val requested = plan.poolSpend.amount(color)
                    if (requested == 0) continue
                    val explicitlySelected = floatingSourceColorAmounts.entries
                        .filter { it.key.poolColor == color }
                        .sumOf { it.value }
                    if (explicitlySelected != requested) {
                        return PaymentPlanValidation.Rejected(
                            "PaymentPlanV1 heterogeneous pool spend must identify every requested color"
                        )
                    }
                }
                currentPool.consumeCertifiedHeterogeneous(
                    certifiedHeterogeneousMana,
                    floatingSourceColorAmounts,
                ) ?: return PaymentPlanValidation.Rejected(
                    "PaymentPlanV1 spends more floating mana than is available"
                )
            }

            certifiedFloatingMana != null && plan.poolSpend.total() > 0 -> {
                val poolColorAmount = plan.poolSpend.amount(certifiedFloatingMana.poolColor)
                val explicitFloatingAmount = floatingSourceAmounts.values.sum()
                val unassignedAmount = poolColorAmount - explicitFloatingAmount
                if (unassignedAmount < 0) {
                    return PaymentPlanValidation.Rejected("PaymentPlanV1 spends more floating mana than is available")
                }
                if (certifiedFloatingMana.sourceBuckets.size == 1) {
                    val onlySource = certifiedFloatingMana.sourceBuckets.single().sourceId
                    floatingSourceAmounts[onlySource] =
                        (floatingSourceAmounts[onlySource] ?: 0) + unassignedAmount
                } else if (unassignedAmount != 0) {
                    return PaymentPlanValidation.Rejected(
                        "PaymentPlanV1 certified multi-unit pool spend must identify every floating source"
                    )
                }
                currentPool.consumeCertifiedHomogeneous(certifiedFloatingMana, floatingSourceAmounts)
                    ?: return PaymentPlanValidation.Rejected(
                        "PaymentPlanV1 spends more floating mana than is available"
                    )
            }

            else -> null
        }

        var poolAfterSpend = certifiedPoolPayment?.first ?: currentPool
        if (certifiedPoolPayment == null) {
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
        }

        var whiteSpent = plan.poolSpend.white
        var blueSpent = plan.poolSpend.blue
        var blackSpent = plan.poolSpend.black
        var redSpent = plan.poolSpend.red
        var greenSpent = plan.poolSpend.green
        var colorlessSpent = plan.poolSpend.colorless
        val spentSubtypes = mutableMapOf<Subtype, Int>()
        val spentSourceIds = mutableSetOf<EntityId>()
        certifiedPoolPayment?.second?.let { provenance ->
            provenance.bySubtype.forEach { (subtype, amount) -> spentSubtypes[subtype] = amount }
            spentSourceIds += provenance.sourceIds
        }

        val poolAfterFloatingSpend = poolAfterSpend

        // Carry every fixed output through the validation preview using the production-time
        // snapshot already captured on the resolved source. This preview is not written to state:
        // actual source provenance is created only after the selected mana ability side effect
        // succeeds (see ExactPaymentMaterialization.poolAfterSuccessfulSourceProduction).
        val sourcePayments = resolved.values.map { activation ->
            val consumedIndexes = sourceOutputAmounts[activation.source.entityId]
                .orEmpty()
                .filterValues { it > 0 }
                .keys
            val subtypes = activation.source.sourceSubtypes
            for ((index, color) in activation.outputs.withIndex()) {
                if (index in consumedIndexes) {
                    spentSourceIds += activation.source.entityId
                    for (subtype in subtypes) {
                        spentSubtypes[subtype] = (spentSubtypes[subtype] ?: 0) + 1
                    }
                    when (color) {
                        PaymentManaColor.WHITE -> whiteSpent++
                        PaymentManaColor.BLUE -> blueSpent++
                        PaymentManaColor.BLACK -> blackSpent++
                        PaymentManaColor.RED -> redSpent++
                        PaymentManaColor.GREEN -> greenSpent++
                        PaymentManaColor.COLORLESS -> colorlessSpent++
                    }
                }
            }
            ExactPaymentSourceMaterialization(
                source = activation.source,
                ability = activation.ability,
                outputs = activation.outputs,
                consumedOutputIndexes = consumedIndexes,
                sourceSubtypes = subtypes,
            )
        }

        var poolAfterPreview = poolAfterFloatingSpend
        for (payment in sourcePayments) {
            for ((index, color) in payment.outputs.withIndex()) {
                if (index !in payment.consumedOutputIndexes) {
                    poolAfterPreview = poolAfterPreview.addTracked(
                        color = color,
                        sourceId = payment.source.entityId,
                        subtypes = payment.sourceSubtypes,
                    )
                }
            }
        }

        val materialization = ExactPaymentMaterialization(
            poolAfterSpend = poolAfterPreview,
            poolAfterFloatingSpend = poolAfterFloatingSpend,
            sourcePayments = sourcePayments.sortedBy { it.source.entityId.value },
            manaSpent = ManaPool(
                white = whiteSpent,
                blue = blueSpent,
                black = blackSpent,
                red = redSpent,
                green = greenSpent,
                colorless = colorlessSpent,
            ),
            spentManaProvenance = SpentManaProvenance(
                bySubtype = spentSubtypes,
                sourceIds = spentSourceIds,
            ),
        )

        // Array order is not a second payment choice. Materialize sources canonically so two
        // equivalent plans cannot change tap/event ordering merely by permuting the submitted list.
        return PaymentPlanValidation.Accepted(materialization)
    }

    private data class ResolvedActivation(
        val source: ManaSource,
        val activation: SourceActivation,
        val ability: ActivatedAbility?,
        val profile: PaymentManaProductionProfile,
        val outputs: List<PaymentManaColor>,
    )
}

/**
 * The sole V3 resource ledger. It validates inner allocations as each ordered node is reached and
 * validates outer allocations only after every node, so a resource can never be made available by
 * a self/forward reference. The ledger is local and mutable only during preflight; no GameState
 * component is changed by this class.
 */
private class PaymentResourceLedgerV3(
    private val initialPoolCapacities: Map<InitialPoolBucketKeyV1, Int>,
    private val activations: List<ValidatedPaymentActivationV3>,
    private val expectedTargets: Map<PaymentTargetV1, AtomicManaCostUnitV1>,
) {
    private val consumedInitial = mutableMapOf<InitialPoolBucketKeyV1, Int>()
    private val consumedOutputs = mutableSetOf<ManaResourceRefV1.ActivationOutputUnit>()
    private val filledTargets = mutableSetOf<PaymentTargetV1>()

    val consumedInitialPool: Map<InitialPoolBucketKeyV1, Int>
        get() = consumedInitial.toMap()

    val consumedActivationOutputs: Set<ManaResourceRefV1.ActivationOutputUnit>
        get() = consumedOutputs.toSet()

    /**
     * Consume exactly one referenced resource for exactly one published atomic target. A null
     * [currentActivationIndex] means the allocation belongs to the outer cost.
     */
    fun consume(
        allocation: PaymentAllocationV1,
        currentActivationIndex: Int?,
    ): String? {
        val target = allocation.target
        val expected = expectedTargets[target]
            ?: return "PaymentPlanV3 allocation references an unknown cost unit"
        if (target in filledTargets) {
            return "PaymentPlanV3 allocates a cost unit more than once"
        }
        when (target) {
            is PaymentTargetV1.ActivationCostUnit -> {
                if (currentActivationIndex != target.activationIndex) {
                    return "PaymentPlanV3 activation allocation targets the wrong activation node"
                }
            }

            is PaymentTargetV1.OuterCostUnit -> {
                if (currentActivationIndex != null) {
                    return "PaymentPlanV3 inner allocation cannot target the outer cost"
                }
            }
        }

        val color = when (val resource = allocation.resource) {
            is ManaResourceRefV1.InitialPoolResource -> {
                val capacity = initialPoolCapacities[resource.bucketKey]
                    ?: return "PaymentPlanV3 allocation references an unavailable initial-pool bucket"
                val consumed = consumedInitial[resource.bucketKey] ?: 0
                if (consumed >= capacity) {
                    return "PaymentPlanV3 initial-pool bucket capacity is exceeded"
                }
                resource.bucketKey.paymentColor()
                    ?: return "PaymentPlanV3 initial-pool bucket has no ordinary mana color"
            }

            is ManaResourceRefV1.ActivationOutputUnit -> {
                val producer = activations.getOrNull(resource.activationIndex)
                    ?: return "PaymentPlanV3 allocation references an unknown activation output"
                val lastAvailableActivation = currentActivationIndex ?: activations.size
                if (resource.activationIndex >= lastAvailableActivation) {
                    return "PaymentPlanV3 activation cost must reference an earlier activation output"
                }
                producer.outputs.getOrNull(resource.outputIndex)
                    ?: return "PaymentPlanV3 allocation references an unavailable activation output"
                if (resource in consumedOutputs) {
                    return "PaymentPlanV3 activation output cannot be spent more than once"
                }
                producer.outputs[resource.outputIndex]
            }
        }
        if (!expected.accepts(color)) {
            return "PaymentPlanV3 resource color does not satisfy its cost unit"
        }

        when (val resource = allocation.resource) {
            is ManaResourceRefV1.InitialPoolResource -> {
                consumedInitial[resource.bucketKey] = (consumedInitial[resource.bucketKey] ?: 0) + 1
            }

            is ManaResourceRefV1.ActivationOutputUnit -> {
                // The output was checked for availability and duplicate use above. Keep the
                // resource identity, not its color, so differently colored outputs never alias.
                consumedOutputs += resource
            }
        }
        filledTargets += target
        return null
    }

    fun finish(): String? {
        if (filledTargets != expectedTargets.keys) {
            val missing = expectedTargets.keys - filledTargets
            return "PaymentPlanV3 does not allocate every cost unit exactly once; missing=$missing"
        }
        return null
    }
}

private fun InitialPoolBucketKeyV1.paymentColor(): PaymentManaColor? = when (this) {
    is InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> color
    is InitialPoolBucketKeyV1.CertifiedFloatingBucket -> key.poolColor
}

private fun AtomicManaCostUnitV1.accepts(color: PaymentManaColor): Boolean = when (kind) {
    PaymentCostKindV1.COLORED -> color in allowedColors
    PaymentCostKindV1.COLORLESS -> color == PaymentManaColor.COLORLESS
    PaymentCostKindV1.GENERIC -> true
}

/** Build the exact fungible initial resources admitted by the V5 first slice. */
private fun ManaPoolComponent.toV3InitialPoolBuckets(): Map<InitialPoolBucketKeyV1, Int>? {
    return when (val classification = FloatingManaProvenanceClassification.classify(this)) {
        FloatingManaProvenanceClassification.NoTrackedProvenance -> buildMap {
            for (color in PaymentManaColor.entries) {
                val amount = when (color) {
                    PaymentManaColor.WHITE -> white
                    PaymentManaColor.BLUE -> blue
                    PaymentManaColor.BLACK -> black
                    PaymentManaColor.RED -> red
                    PaymentManaColor.GREEN -> green
                    PaymentManaColor.COLORLESS -> colorless
                }
                if (amount < 0) return null
                if (amount > 0) {
                    put(InitialPoolBucketKeyV1.UnrestrictedPoolBucket(color), amount)
                }
            }
        }

        is FloatingManaProvenanceClassification.CertifiedJoint -> {
            // V5 deliberately has no unstable multi-bucket ordering. The builder publishes only
            // one certified bucket, so the validator accepts exactly that same shape.
            val bucket = classification.candidate.buckets.singleOrNull() ?: return null
            if (bucket.amount <= 0) return null
            mapOf(
                InitialPoolBucketKeyV1.CertifiedFloatingBucket(bucket.key) to bucket.amount,
            )
        }

        is FloatingManaProvenanceClassification.CertifiedHomogeneous,
        is FloatingManaProvenanceClassification.CertifiedHeterogeneous,
        is FloatingManaProvenanceClassification.Ambiguous,
        -> null
    }
}

private data class V3ActivationCostShape(
    val atomicManaCostUnits: List<AtomicManaCostUnitV1>,
    val activationCostOrder: ActivationCostOrderV1,
)

/** Mirror the public V5 qualification shape using the current effective Rules cost. */
private fun AbilityCost.toV3ActivationCostShape(): V3ActivationCostShape? {
    val components = when (this) {
        AbilityCost.Tap -> listOf(this)
        is AbilityCost.Composite -> costs
        else -> return null
    }
    var manaCost: ManaCost? = null
    var tapCount = 0
    for (component in components) {
        when (component) {
            AbilityCost.Tap -> tapCount++
            is AbilityCost.Atom -> {
                val componentManaCost = component.manaCostOrNull ?: return null
                if (manaCost != null) return null
                manaCost = componentManaCost.canonicalPaymentManaCost()
            }

            else -> return null
        }
    }
    if (tapCount != 1) return null
    val ordinaryManaCost = manaCost ?: ManaCost.ZERO
    if (!ordinaryManaCost.isFixedOrdinaryManaCost()) return null
    val atomicUnits = ordinaryManaCost.toV3AtomicManaCostUnits() ?: return null
    val order = buildList {
        if (manaCost != null) add(ActivationCostComponentRefV1.ManaComponent)
        add(ActivationCostComponentRefV1.DeterministicNonManaComponent(0))
    }
    return V3ActivationCostShape(
        atomicManaCostUnits = atomicUnits,
        activationCostOrder = order,
    )
}

private fun ManaCost.toV3AtomicManaCostUnits(): List<AtomicManaCostUnitV1>? {
    val units = mutableListOf<AtomicManaCostUnitV1>()
    for ((symbolIndex, symbol) in symbols.withIndex()) {
        when (symbol) {
            is ManaSymbol.Colored -> units += AtomicManaCostUnitV1(
                symbolIndex = symbolIndex,
                unitIndexWithinSymbol = 0,
                kind = PaymentCostKindV1.COLORED,
                allowedColors = setOf(PaymentManaColor.fromEngine(symbol.color)),
            )

            is ManaSymbol.Colorless -> units += AtomicManaCostUnitV1(
                symbolIndex = symbolIndex,
                unitIndexWithinSymbol = 0,
                kind = PaymentCostKindV1.COLORLESS,
                allowedColors = setOf(PaymentManaColor.COLORLESS),
            )

            is ManaSymbol.Generic -> {
                if (symbol.amount < 0) return null
                repeat(symbol.amount) { unitIndex ->
                    units += AtomicManaCostUnitV1(
                        symbolIndex = symbolIndex,
                        unitIndexWithinSymbol = unitIndex,
                        kind = PaymentCostKindV1.GENERIC,
                    )
                }
            }

            else -> return null
        }
    }
    return units
}

private fun PaymentPlanV1.toInternal(): NormalizedPaymentPlan = NormalizedPaymentPlan(
    sourceActivations = sourceActivations,
    poolSpend = poolSpend,
    spendAllocation = NormalizedSpendAllocation(
        costUnits = spendAllocation.costUnits.map { allocation ->
            NormalizedCostUnitAllocation(
                symbolIndex = allocation.symbolIndex,
                spends = allocation.spends.map { it.toInternal() },
            )
        },
        x = spendAllocation.x.map { it.toInternal() },
        restricted = spendAllocation.restricted.map { it.toInternal() },
        riderBearingSourceIds = spendAllocation.riderBearingSourceIds,
    ),
    contractName = "PaymentPlanV1",
)

private fun com.wingedsheep.engine.core.ManaSpendReference.toInternal(): NormalizedSpend =
    NormalizedSpend(
        sourceId = sourceId,
        poolColor = poolColor,
        amount = amount,
        restrictedBucketKey = restrictedBucketKey,
        sourceOutputIndex = sourceOutputIndex,
        floatingSourceId = floatingSourceId,
    )

private fun com.wingedsheep.engine.core.PaymentPlanV2.toInternal(): Pair<NormalizedPaymentPlan?, String?> {
    fun normalize(reference: com.wingedsheep.engine.core.ManaSpendReferenceV2): Pair<NormalizedSpend?, String?> {
        if (reference.floatingSourceId == null && reference.floatingSourceSubtypes != null) {
            return null to "PaymentPlanV2 subtype snapshot is only valid for floating mana"
        }
        if (reference.floatingSourceId == null) {
            return NormalizedSpend(
                sourceId = reference.sourceId,
                poolColor = reference.poolColor,
                amount = reference.amount,
                restrictedBucketKey = reference.restrictedBucketKey,
                sourceOutputIndex = reference.sourceOutputIndex,
                floatingSourceId = null,
            ) to null
        }
        val color = reference.poolColor
            ?: return null to "PaymentPlanV2 floating bucket reference requires poolColor"
        val subtypeNames = reference.floatingSourceSubtypes
            ?: return null to "PaymentPlanV2 floating bucket reference requires floatingSourceSubtypes"
        if (subtypeNames.size != subtypeNames.toSet().size ||
            subtypeNames != subtypeNames.sorted()
        ) {
            return null to "PaymentPlanV2 floating subtype snapshot must be canonical"
        }
        return NormalizedSpend(
            sourceId = reference.sourceId,
            poolColor = color,
            amount = reference.amount,
            restrictedBucketKey = reference.restrictedBucketKey,
            sourceOutputIndex = reference.sourceOutputIndex,
            floatingSourceId = reference.floatingSourceId,
            floatingBucketKey = FloatingManaBucketKeyV1(
                sourceId = reference.floatingSourceId,
                poolColor = color,
                sourceSubtypes = subtypeNames.map { value -> Subtype(value) }.toSet(),
            ),
        ) to null
    }

    fun normalizeList(
        references: List<com.wingedsheep.engine.core.ManaSpendReferenceV2>,
    ): Pair<List<NormalizedSpend>?, String?> {
        val normalized = mutableListOf<NormalizedSpend>()
        for (reference in references) {
            val (spend, error) = normalize(reference)
            if (spend == null) return null to error
            normalized += spend
        }
        return normalized to null
    }

    val costUnits = mutableListOf<NormalizedCostUnitAllocation>()
    for (allocation in spendAllocation.costUnits) {
        val (spends, error) = normalizeList(allocation.spends)
        if (spends == null) return null to error
        costUnits += NormalizedCostUnitAllocation(allocation.symbolIndex, spends)
    }
    val (x, xError) = normalizeList(spendAllocation.x)
    if (x == null) return null to xError
    val (restricted, restrictedError) = normalizeList(spendAllocation.restricted)
    if (restricted == null) return null to restrictedError

    return NormalizedPaymentPlan(
        sourceActivations = sourceActivations,
        poolSpend = poolSpend,
        spendAllocation = NormalizedSpendAllocation(
            costUnits = costUnits,
            x = x,
            restricted = restricted,
            riderBearingSourceIds = spendAllocation.riderBearingSourceIds,
        ),
        contractName = "PaymentPlanV2",
    ) to null
}

private fun validateCanonicalProductionChoice(choice: ProductionChoice): String? {
    if (choice.amount != 1 || choice.bonusChoice != null) {
        return "PaymentPlanV1 supports one ordinary mana per source activation"
    }
    val outputs = choice.fixedOutputs ?: return null
    if (outputs.size < 2) {
        return "fixedOutputs must contain at least two outputs"
    }
    if (outputs.mapIndexed { index, output -> output.index == index }.any { !it }) {
        return "fixedOutputs indexes must be exactly 0..n-1 in list order"
    }
    if (outputs.any { it.amount != 1 }) {
        return "fixedOutputs amounts must be exactly one"
    }
    if (choice.producedColor != outputs.first().color) {
        return "producedColor must equal fixedOutputs[0].color"
    }
    return null
}

private fun PoolSpend.hasNegativeAmount(): Boolean =
    white < 0 || blue < 0 || black < 0 || red < 0 || green < 0 || colorless < 0 ||
        restricted.values.any { it < 0 }

private fun ManaSymbol.accepts(color: PaymentManaColor): Boolean = when (this) {
    is ManaSymbol.Colored -> color == PaymentManaColor.fromEngine(this.color)
    is ManaSymbol.Colorless -> color == PaymentManaColor.COLORLESS
    is ManaSymbol.Generic -> true
    else -> false
}

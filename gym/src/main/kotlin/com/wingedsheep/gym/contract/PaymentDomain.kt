package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.ActivationCostOrderV1
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.InitialPoolBucketV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.mechanics.combat.CombatObjectOrder
import com.wingedsheep.engine.mechanics.cost.ActivatedAbilityCostCalculator
import com.wingedsheep.engine.mechanics.mana.IntrinsicManaAbilities
import com.wingedsheep.engine.mechanics.mana.FloatingManaProvenanceClassification
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.ManaSource
import com.wingedsheep.engine.mechanics.mana.PaidManaSourceTimingCandidate
import com.wingedsheep.engine.mechanics.mana.PaidManaSourceTimingCertifier
import com.wingedsheep.engine.mechanics.mana.PaymentManaProductionProfile
import com.wingedsheep.engine.mechanics.mana.PaymentManaSideEffectCertificateResolver
import com.wingedsheep.engine.mechanics.mana.SpellPaymentContext
import com.wingedsheep.engine.mechanics.mana.canonicalPaymentManaCost
import com.wingedsheep.engine.mechanics.mana.isFixedOrdinaryManaCost
import com.wingedsheep.engine.mechanics.mana.isSupportedByPaymentProgramV3
import com.wingedsheep.engine.mechanics.mana.supportsPaymentPlanV1
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.costs.CostAtom
import kotlinx.serialization.Serializable

const val PAYMENT_DOMAIN_V2_VERSION: Int = 2
const val PAYMENT_DOMAIN_V3_VERSION: Int = 3
const val PAYMENT_DOMAIN_V4_VERSION: Int = 4
const val PAYMENT_DOMAIN_V5_VERSION: Int = 5
const val PAYMENT_DOMAIN_VERSION: Int = PAYMENT_DOMAIN_V4_VERSION

@Serializable
enum class PaymentCostKind {
    COLORED,
    COLORLESS,
    GENERIC,
}

@Serializable
data class PaymentCostUnitDomain(
    val symbolIndex: Int,
    val kind: PaymentCostKind,
    val amount: Int,
    val allowedColors: Set<PaymentManaColor> = emptySet(),
)

@Serializable
@Deprecated("Historical PaymentDomain V1 wire DTO; current observations use PaymentDomainV4")
data class PaymentPoolDomainV1(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
    val certifiedFloatingMana: CertifiedFloatingManaCandidateV1? = null,
)

@Serializable
data class CertifiedFloatingManaCandidateV1(
    val poolColor: PaymentManaColor,
    val sourceId: EntityId,
    val sourceSubtypes: List<String>,
)

@Serializable
data class CertifiedFloatingManaSourceBucketDomainV2(
    val sourceId: EntityId,
    val amount: Int,
)

/**
 * The one canonical public representation of certified floating provenance in PaymentDomain V2.
 * The subtype set is common to every unit; source buckets only partition the units by source.
 */
@Serializable
data class CertifiedHomogeneousFloatingManaDomainV2(
    val poolColor: PaymentManaColor,
    val sourceSubtypes: List<String>,
    val sourceBuckets: List<CertifiedFloatingManaSourceBucketDomainV2>,
)

@Serializable
data class PaymentPoolDomainV2(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
    val certifiedFloatingMana: CertifiedHomogeneousFloatingManaDomainV2? = null,
)

/** One exact mana-ability identity and its explicit production choices. */
@Serializable
data class PaymentSourceActivationDomain(
    val sourceId: com.wingedsheep.sdk.model.EntityId,
    val sourceName: String,
    val manaAbilityKey: String,
    val productionChoices: List<com.wingedsheep.engine.core.ProductionChoice>,
)

/**
 * Complete action-level payment domain for the supported ordinary fixed-cost slice.
 *
 * This is deliberately separate from the pending [ManaSourcesDomain]. It contains no automatic
 * payment suggestion: a controller must submit a [com.wingedsheep.engine.core.PaymentPlanV1]
 * whose source, production, pool, and allocation choices are all explicit.
 */
@Serializable
@Deprecated("Historical PaymentDomain V1 wire DTO; current observations use PaymentDomainV4")
data class PaymentDomainV1(
    val version: Int = 1,
    val requiredCost: String,
    val costUnits: List<PaymentCostUnitDomain>,
    val currentPool: PaymentPoolDomainV1,
    val sourceActivations: List<PaymentSourceActivationDomain>,
)

/**
 * Historical V2 action-level payment domain for the ordinary fixed-cost slice.
 *
 * `certifiedFloatingMana` is the only public authority for historical floating source buckets.
 * Future source activations remain a separate origin class and retain their existing domain shape.
 */
@Serializable
data class PaymentDomainV2(
    val version: Int = PAYMENT_DOMAIN_V2_VERSION,
    val requiredCost: String,
    val costUnits: List<PaymentCostUnitDomain>,
    val currentPool: PaymentPoolDomainV2,
    val sourceActivations: List<PaymentSourceActivationDomain>,
) {
    init {
        require(version == PAYMENT_DOMAIN_V2_VERSION) {
            "Unsupported PaymentDomainV2 version: $version"
        }
    }
}

/** One exact source/color partition of a certified heterogeneous floating-mana pool. */
@Serializable
data class CertifiedFloatingManaSourceColorBucketDomainV3(
    val sourceId: EntityId,
    val poolColor: PaymentManaColor,
    val amount: Int,
)

/**
 * The V3 heterogeneous representation publishes the Rules-owned source/color buckets directly.
 * No source/color relationship may be reconstructed from the pool totals or source buckets.
 */
@Serializable
data class CertifiedHeterogeneousFloatingManaDomainV3(
    val sourceColorBuckets: List<CertifiedFloatingManaSourceColorBucketDomainV3>,
    val sourceSubtypes: List<String>,
)

/**
 * Current public payment-pool shape. The two certified representations are an explicit one-of:
 * a homogeneous pool uses [certifiedFloatingMana], while a genuinely multi-color pool uses
 * [certifiedHeterogeneousFloatingMana].
 */
@Serializable
data class PaymentPoolDomainV3(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
    val certifiedFloatingMana: CertifiedHomogeneousFloatingManaDomainV2? = null,
    val certifiedHeterogeneousFloatingMana: CertifiedHeterogeneousFloatingManaDomainV3? = null,
) {
    init {
        require(certifiedFloatingMana == null || certifiedHeterogeneousFloatingMana == null) {
            "PaymentPoolDomainV3 cannot publish both homogeneous and heterogeneous floating mana"
        }
    }
}

/**
 * Historical action-level payment domain. Current observations use [PaymentDomainV4]; V3 remains
 * decodable only under its own historical wire contract.
 */
@Serializable
data class PaymentDomainV3(
    val version: Int = PAYMENT_DOMAIN_V3_VERSION,
    val requiredCost: String,
    val costUnits: List<PaymentCostUnitDomain>,
    val currentPool: PaymentPoolDomainV3,
    val sourceActivations: List<PaymentSourceActivationDomain>,
) {
    init {
        require(version == PAYMENT_DOMAIN_V3_VERSION) {
            "Unsupported PaymentDomainV3 version: $version"
        }
    }
}

/** One complete public source/color/production-subtype bucket in PaymentDomain V4. */
@Serializable
data class CertifiedFloatingManaBucketDomainV4(
    val sourceId: EntityId,
    val poolColor: PaymentManaColor,
    val sourceSubtypes: List<String>,
    val amount: Int,
)

/** Current public pool shape: one canonical bucket list for homogeneous and heterogeneous pools. */
@Serializable
data class PaymentPoolDomainV4(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
    val certifiedFloatingBuckets: List<CertifiedFloatingManaBucketDomainV4> = emptyList(),
)

/** Current action-level payment domain for exact joint floating-mana choices. */
@Serializable
data class PaymentDomainV4(
    val version: Int = PAYMENT_DOMAIN_V4_VERSION,
    val requiredCost: String,
    val costUnits: List<PaymentCostUnitDomain>,
    val currentPool: PaymentPoolDomainV4,
    val sourceActivations: List<PaymentSourceActivationDomain>,
) {
    init {
        require(version == PAYMENT_DOMAIN_V4_VERSION) {
            "Unsupported PaymentDomainV4 version: $version"
        }
        require(currentPool.certifiedFloatingBuckets.all { it.amount > 0 }) {
            "PaymentDomainV4 bucket amounts must be positive"
        }
        require(currentPool.certifiedFloatingBuckets.map { it.key() }.toSet().size ==
            currentPool.certifiedFloatingBuckets.size
        ) {
            "PaymentDomainV4 cannot publish duplicate floating bucket keys"
        }
        require(currentPool.certifiedFloatingBuckets.all { bucket ->
            bucket.sourceSubtypes == bucket.sourceSubtypes.distinct().sorted()
        }) {
            "PaymentDomainV4 subtype snapshots must be canonical"
        }
    }

    private fun CertifiedFloatingManaBucketDomainV4.key(): String = buildString {
        append(sourceId.value)
        append('|')
        append(poolColor.name)
        append('|')
        append(sourceSubtypes.joinToString(","))
    }
}

/** The first qualified paid-mana-source shape published by PaymentDomain V5. */
@Serializable
enum class PaymentActivationSupportKindV1 {
    @kotlinx.serialization.SerialName("FixedManaAndTapSelf")
    FIXED_MANA_AND_TAP_SELF;

    companion object {
        /** Readable alias matching the public contract terminology. */
        val FixedManaAndTapSelf: PaymentActivationSupportKindV1 = FIXED_MANA_AND_TAP_SELF
    }
}

/** Deterministic non-mana cost components currently expressible in V5. */
@Serializable
enum class PaymentDeterministicNonManaCostKindV1 {
    @kotlinx.serialization.SerialName("TapSelf")
    TAP_SELF;

    companion object {
        /** Readable alias matching the Rules cost terminology. */
        val TapSelf: PaymentDeterministicNonManaCostKindV1 = TAP_SELF
    }
}

/**
 * Public capability entry for one complete source/ability option in PaymentDomain V5.
 *
 * This is a capability description only. It contains no selected resources or allocations and
 * never asks a policy to infer a missing activation-cost payment.
 */
@Serializable
data class PaymentSourceActivationDomainV2(
    val sourceId: EntityId,
    val sourceName: String,
    val manaAbilityKey: String,
    val productionChoices: List<ProductionChoice>,
    val atomicActivationManaCostUnits: List<AtomicManaCostUnitV1>,
    val activationSupportKind: PaymentActivationSupportKindV1,
    val deterministicNonManaCosts: List<PaymentDeterministicNonManaCostKindV1>,
    val activationCostOrderOptions: List<ActivationCostOrderV1>,
) {
    init {
        require(productionChoices.isNotEmpty()) {
            "PaymentSourceActivationDomainV2 must publish at least one production choice"
        }
        require(atomicActivationManaCostUnits.map {
            it.symbolIndex to it.unitIndexWithinSymbol
        }.toSet().size == atomicActivationManaCostUnits.size) {
            "PaymentSourceActivationDomainV2 cannot publish duplicate activation cost units"
        }
        require(deterministicNonManaCosts.distinct().size == deterministicNonManaCosts.size) {
            "PaymentSourceActivationDomainV2 cannot publish duplicate deterministic cost components"
        }
        require(activationCostOrderOptions.isNotEmpty()) {
            "PaymentSourceActivationDomainV2 must publish at least one cost order"
        }
        val requiredComponents = buildList<ActivationCostComponentRefV1> {
            if (atomicActivationManaCostUnits.isNotEmpty()) {
                add(ActivationCostComponentRefV1.ManaComponent)
            }
            deterministicNonManaCosts.indices.forEach { index ->
                add(ActivationCostComponentRefV1.DeterministicNonManaComponent(index))
            }
        }.toSet()
        require(activationCostOrderOptions.all { order ->
            order.distinct().size == order.size &&
                order.size == requiredComponents.size &&
                order.toSet() == requiredComponents &&
                order.all { component ->
                    component == ActivationCostComponentRefV1.ManaComponent ||
                        (component as? ActivationCostComponentRefV1.DeterministicNonManaComponent)
                            ?.index in deterministicNonManaCosts.indices
                }
        }) {
            "PaymentSourceActivationDomainV2 contains an invalid activation cost order"
        }
    }
}

/** Current complete action-level public payment domain for the ordered V3 program. */
@Serializable
data class PaymentDomainV5(
    val version: Int = PAYMENT_DOMAIN_V5_VERSION,
    val requiredCost: String,
    val outerAtomicCostUnits: List<AtomicManaCostUnitV1>,
    val initialPoolBuckets: List<InitialPoolBucketV1>,
    val sourceActivationOptions: List<PaymentSourceActivationDomainV2>,
) {
    init {
        require(version == PAYMENT_DOMAIN_V5_VERSION) {
            "Unsupported PaymentDomainV5 version: $version"
        }
        require(initialPoolBuckets.all { it.availableAmount > 0 }) {
            "PaymentDomainV5 initial-pool bucket amounts must be positive"
        }
        require(initialPoolBuckets.map { it.key }.toSet().size == initialPoolBuckets.size) {
            "PaymentDomainV5 cannot publish duplicate initial-pool buckets"
        }
        require(outerAtomicCostUnits.map {
            it.symbolIndex to it.unitIndexWithinSymbol
        }.toSet().size == outerAtomicCostUnits.size) {
            "PaymentDomainV5 cannot publish duplicate outer cost units"
        }
        require(sourceActivationOptions.map { it.sourceId to it.manaAbilityKey }.toSet().size ==
            sourceActivationOptions.size
        ) {
            "PaymentDomainV5 cannot publish duplicate source/ability options"
        }
    }
}

/**
 * Builds the public action-level domain from the existing engine mana-source discovery. The caller
 * must pass the same ability payment context and source exclusion that the authoritative ability
 * handler will use. This class never solves or suggests a payment; it only publishes exact
 * source/ability/color candidates and fails closed for unsupported pool/source shapes.
 */
class PaymentDomainBuilder(
    private val manaSolver: ManaSolver,
    private val visibility: Visibility,
    private val activatedAbilityCostCalculator: ActivatedAbilityCostCalculator? = null,
    private val paidManaSourceTimingCertifier: PaidManaSourceTimingCertifier,
) {
    fun build(
        state: GameState,
        playerId: EntityId,
        requiredCost: String,
        spellContext: SpellPaymentContext,
        excludeSources: Set<EntityId> = emptySet(),
    ): PaymentDomainV4? {
        val cost = runCatching { ManaCost.parse(requiredCost) }
            .getOrNull()
            ?.canonicalPaymentManaCost()
            ?: return null
        val costUnits = cost.symbols.mapIndexed { index, symbol -> symbol.toDomain(index) ?: return null }

        val pool = state.getEntity(playerId)?.get<ManaPoolComponent>() ?: ManaPoolComponent()
        // Restricted mana has no stable public bucket identity in V4. Do not publish a partial
        // domain that would force the engine to choose a restricted bucket during submission.
        if (pool.restrictedMana.isNotEmpty()) return null
        var certifiedFloatingBuckets = emptyList<CertifiedFloatingManaBucketDomainV4>()
        when (val classification =
            FloatingManaProvenanceClassification.classify(pool)) {
            FloatingManaProvenanceClassification.NoTrackedProvenance -> Unit
            is FloatingManaProvenanceClassification.Ambiguous -> return null
            is FloatingManaProvenanceClassification.CertifiedHomogeneous -> {
                // A legacy aggregate/source-color proof is not a production-time joint proof.
                // V4 never reconstructs subtype snapshots from those projections.
                return null
            }
            is FloatingManaProvenanceClassification.CertifiedHeterogeneous -> {
                return null
            }
            is FloatingManaProvenanceClassification.CertifiedJoint -> {
                if (playerId !in pool.manaProvenanceKnownTo) return null
                if (classification.candidate.buckets.any {
                        !isPerspectiveSafeSource(state, playerId, it.key.sourceId)
                    }
                ) return null
                certifiedFloatingBuckets = classification.candidate.buckets
                    .sortedWith(
                        compareBy(
                            { it.key.sourceId.value },
                            { it.key.poolColor.ordinal },
                            { it.key.sourceSubtypes.sortedBy { subtype -> subtype.value }.joinToString(",") },
                        ),
                    )
                    .map { bucket ->
                        CertifiedFloatingManaBucketDomainV4(
                            sourceId = bucket.key.sourceId,
                            poolColor = bucket.key.poolColor,
                            sourceSubtypes = bucket.key.sourceSubtypes
                                .map { subtype -> subtype.value }
                                .sorted(),
                            amount = bucket.amount,
                        )
                    }
            }
        }

        val sourceActivations = buildList {
            for (source in manaSolver.findAvailableManaSources(state, playerId, spellContext)
                .filter { it.entityId !in excludeSources }
                .sortedBy { it.entityId.value }
            ) {
                if (!source.supportsPaymentPlanV1()) return null
                addAll(source.toDomain() ?: return null)
            }
        }

        return PaymentDomainV4(
            requiredCost = requiredCost,
            costUnits = costUnits,
            currentPool = PaymentPoolDomainV4(
                white = pool.white,
                blue = pool.blue,
                black = pool.black,
                red = pool.red,
                green = pool.green,
                colorless = pool.colorless,
                certifiedFloatingBuckets = certifiedFloatingBuckets,
            ),
            sourceActivations = sourceActivations,
        )
    }

    /**
     * Builds the complete V5 ordered-program capability domain.
     *
     * V5 is intentionally a separate builder path. The historical V4 source predicate rejects
     * paid mana abilities and its DTO remains unchanged; this path qualifies only the reviewed
     * fixed-mana-plus-TapSelf support kind and still fails closed for every other discovered legal
     * source or ability option.
     */
    fun buildV5(
        state: GameState,
        playerId: EntityId,
        requiredCost: String,
        spellContext: SpellPaymentContext,
        excludeSources: Set<EntityId> = emptySet(),
    ): PaymentDomainV5? {
        val cost = runCatching { ManaCost.parse(requiredCost) }
            .getOrNull()
            ?.canonicalPaymentManaCost()
            ?: return null
        if (!cost.isFixedOrdinaryManaCost()) return null
        val outerAtomicCostUnits = cost.toAtomicDomain() ?: return null

        val pool = state.getEntity(playerId)?.get<ManaPoolComponent>() ?: ManaPoolComponent()
        val initialPoolBuckets = pool.toV5InitialPoolBuckets(state, playerId) ?: return null

        // Inventory every currently usable mana ability before applying the outer action's
        // spending context. A paid mana-source activation has its own ability context, so a
        // source restricted to abilities may be legal for a Signet's {1} while illegal for the
        // outer spell. V5 cannot represent that distinction yet; toV5Domain therefore rejects
        // the complete source after discovery instead of letting this source disappear.
        val discovered = manaSolver.findAvailableManaSources(
            state = state,
            playerId = playerId,
            spellContext = null,
            paymentOrderRequired = true,
        )
            .filter { it.entityId !in excludeSources }
        val orderedIds = CombatObjectOrder.order(state, discovered.map { it.entityId }) ?: return null
        val sourcesById = discovered.associateBy { it.entityId }
        val sourceActivationOptions = buildList {
            for (sourceId in orderedIds) {
                val source = sourcesById[sourceId] ?: return null
                if (!isPerspectiveSafeSource(state, playerId, sourceId)) return null
                val sourceDomain = source.toV5Domain(
                    state = state,
                    playerId = playerId,
                    spellContext = spellContext,
                    costCalculator = activatedAbilityCostCalculator ?: return null,
                    timingCertifier = paidManaSourceTimingCertifier,
                )
                if (sourceDomain == null) return null
                addAll(sourceDomain)
            }
        }

        return PaymentDomainV5(
            requiredCost = cost.toString(),
            outerAtomicCostUnits = outerAtomicCostUnits,
            initialPoolBuckets = initialPoolBuckets,
            sourceActivationOptions = sourceActivationOptions,
        )
    }

    private fun isPerspectiveSafeSource(
        state: GameState,
        playerId: EntityId,
        sourceId: EntityId,
    ): Boolean = visibility.isEntityIdentityVisibleTo(state, sourceId, playerId)

    private fun ManaPoolComponent.toV5InitialPoolBuckets(
        state: GameState,
        playerId: EntityId,
    ): List<InitialPoolBucketV1>? {
        if (restrictedMana.isNotEmpty()) return null
        return when (val classification = FloatingManaProvenanceClassification.classify(this)) {
            FloatingManaProvenanceClassification.NoTrackedProvenance -> buildList {
                val amounts = listOf(
                    PaymentManaColor.WHITE to white,
                    PaymentManaColor.BLUE to blue,
                    PaymentManaColor.BLACK to black,
                    PaymentManaColor.RED to red,
                    PaymentManaColor.GREEN to green,
                    PaymentManaColor.COLORLESS to colorless,
                )
                amounts.filter { it.second > 0 }.forEach { (color, amount) ->
                    add(
                        InitialPoolBucketV1(
                            key = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(color),
                            availableAmount = amount,
                        )
                    )
                }
            }

            is FloatingManaProvenanceClassification.CertifiedJoint -> {
                if (playerId !in manaProvenanceKnownTo) return null
                if (classification.candidate.buckets.any {
                        !isPerspectiveSafeSource(state, playerId, it.key.sourceId)
                    }
                ) return null
                // V4 owns the historical multi-bucket ordering. V5 must not promote that
                // EntityId-based order into a new public contract without a stable Rules-owned
                // provenance order. A single fungible bucket has no ordering choice; multiple
                // buckets remain unsupported until that Rules metadata exists.
                if (classification.candidate.buckets.size > 1) return null
                classification.candidate.buckets
                    .filter { it.amount > 0 }
                    .map {
                        InitialPoolBucketV1(
                            key = InitialPoolBucketKeyV1.CertifiedFloatingBucket(it.key),
                            availableAmount = it.amount,
                        )
                    }
            }

            is FloatingManaProvenanceClassification.CertifiedHomogeneous,
            is FloatingManaProvenanceClassification.CertifiedHeterogeneous,
            is FloatingManaProvenanceClassification.Ambiguous -> null
        }
    }

    private fun ManaCost.toAtomicDomain(): List<AtomicManaCostUnitV1>? = buildList {
        for ((symbolIndex, symbol) in symbols.withIndex()) {
            when (symbol) {
                is ManaSymbol.Colored -> add(
                    AtomicManaCostUnitV1(
                        symbolIndex = symbolIndex,
                        unitIndexWithinSymbol = 0,
                        kind = PaymentCostKindV1.COLORED,
                        allowedColors = setOf(PaymentManaColor.fromEngine(symbol.color)),
                    )
                )

                is ManaSymbol.Colorless -> add(
                    AtomicManaCostUnitV1(
                        symbolIndex = symbolIndex,
                        unitIndexWithinSymbol = 0,
                        kind = PaymentCostKindV1.COLORLESS,
                        allowedColors = setOf(PaymentManaColor.COLORLESS),
                    )
                )

                is ManaSymbol.Generic -> {
                    if (symbol.amount < 0) return null
                    repeat(symbol.amount) { unitIndex ->
                        add(
                            AtomicManaCostUnitV1(
                                symbolIndex = symbolIndex,
                                unitIndexWithinSymbol = unitIndex,
                                kind = PaymentCostKindV1.GENERIC,
                            )
                        )
                    }
                }

                else -> return null
            }
        }
    }

    private data class V5ActivationCostShape(
        val manaCost: ManaCost,
        val atomicManaCostUnits: List<AtomicManaCostUnitV1>,
        val activationCostOrder: ActivationCostOrderV1,
    )

    private fun AbilityCost.toV5ActivationCostShape(): V5ActivationCostShape? {
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
                is AbilityCost.Atom -> when (val atom = component.atom) {
                    is CostAtom.Mana -> {
                        if (manaCost != null) return null
                        manaCost = atom.cost.canonicalPaymentManaCost()
                    }

                    else -> return null
                }

                else -> return null
            }
        }
        if (tapCount != 1) return null
        val ordinaryManaCost = manaCost ?: ManaCost.ZERO
        if (!ordinaryManaCost.isFixedOrdinaryManaCost()) return null
        val atomicUnits = ordinaryManaCost.toAtomicDomain() ?: return null
        val order = buildList {
            if (manaCost != null) add(ActivationCostComponentRefV1.ManaComponent)
            add(ActivationCostComponentRefV1.DeterministicNonManaComponent(0))
        }
        return V5ActivationCostShape(ordinaryManaCost, atomicUnits, order)
    }

    private fun ManaSource.toV5Domain(
        state: GameState,
        playerId: EntityId,
        spellContext: SpellPaymentContext,
        costCalculator: ActivatedAbilityCostCalculator,
        timingCertifier: PaidManaSourceTimingCertifier,
    ): List<PaymentSourceActivationDomainV2>? {
        if (!paymentManaAbilityOrderCertified ||
            !paymentManaSpendingRestrictionsCertified ||
            !paymentManaExecutionStabilityCertified ||
            paymentManaProductionProfiles.isEmpty() ||
            paymentManaAbilityOrder.isEmpty()
        ) {
            return null
        }
        if (paymentManaProductionProfiles.keys != paymentManaSideEffectCertificates.keys) {
            return null
        }
        if (paymentManaAbilityOrder.distinct().size != paymentManaAbilityOrder.size ||
            paymentManaAbilityOrder.toSet() != paymentManaProductionProfiles.keys
        ) {
            return null
        }

        // One ActivatedAbility appears in more than one output-color map for fixed bundles. The
        // runtime ID is used only to deduplicate that same in-memory object; it is never exposed
        // or used to order distinct options.
        val explicitAbilities = manaAbilityOptionsForColor.values
            .flatten()
            .plus(manaAbilityOptionsForColorless)
            .distinctBy { it.id.value }
        val abilitiesByKey = explicitAbilities.groupBy(ManaAbilityIdentity::key)
        if (abilitiesByKey.values.any { candidates -> candidates.size != 1 }) {
            return null
        }

        // Basic-land abilities are Rules-synthesized identities. Their serialized structural
        // payload is intentionally not the public identity, so resolve those keys through the
        // same intrinsic identity helper used by source discovery and V1/V2 validation. There is
        // deliberately no intrinsic:null resolution here: the legacy blank-land colorless
        // fallback is not a Rules-certified ability and V5 must fail closed. Explicit abilities
        // continue to use the structural key path below.
        val intrinsicAbilities = IntrinsicManaAbilities
            .forEntity(state, state.projectedState, entityId)
            .associateBy { ability ->
                val symbol = ability.id.value.removePrefix("intrinsic_mana_").singleOrNull()
                    ?: return null
                ManaAbilityIdentity.intrinsic(Color.fromSymbol(symbol))
            }
            .toMutableMap()

        return paymentManaAbilityOrder.map { manaAbilityKey ->
            val profile = paymentManaProductionProfiles[manaAbilityKey] ?: return null
            val ability = if (manaAbilityKey.startsWith("intrinsic:")) {
                intrinsicAbilities[manaAbilityKey] ?: return null
            } else {
                abilitiesByKey[manaAbilityKey]?.singleOrNull() ?: return null
            }
            val advertisedSideEffectCertificate =
                paymentManaSideEffectCertificates[manaAbilityKey] ?: return null
            val currentSideEffectCertificate = PaymentManaSideEffectCertificateResolver.resolve(ability.effect)
            if (advertisedSideEffectCertificate != currentSideEffectCertificate ||
                !currentSideEffectCertificate.isSupportedByPaymentProgramV3()
            ) {
                return null
            }
            val effectiveCost = costCalculator.calculate(
                state = state,
                sourceId = entityId,
                controllerId = playerId,
                ability = ability,
            )
            val shape = effectiveCost.toV5ActivationCostShape() ?: return null
            if (shape.atomicManaCostUnits.isNotEmpty() &&
                !timingCertifier.certify(
                    PaidManaSourceTimingCandidate(
                        state = state,
                        controllerId = playerId,
                        sourceId = entityId,
                        manaAbilityKey = manaAbilityKey,
                        ability = ability,
                        effectiveCost = effectiveCost,
                        productionProfile = profile,
                        spellContext = spellContext,
                    )
                )
            ) return null
            val productionChoices = when (profile) {
                is PaymentManaProductionProfile.SelectableSingleOutput ->
                    profile.allowedColors.sortedBy(PaymentManaColor::ordinal).map {
                        ProductionChoice(producedColor = it)
                    }

                is PaymentManaProductionProfile.FixedOutputBundle -> {
                    if (profile.outputs.size < 2) return null
                    val outputs = profile.outputs.mapIndexed { index, output ->
                        FixedManaOutput(index = index, color = output.color, amount = 1)
                    }
                    listOf(
                        ProductionChoice(
                            producedColor = outputs.first().color,
                            fixedOutputs = outputs,
                        )
                    )
                }

                is PaymentManaProductionProfile.Unsupported -> return null
            }
            if (productionChoices.isEmpty()) return null
            PaymentSourceActivationDomainV2(
                sourceId = entityId,
                sourceName = name,
                manaAbilityKey = manaAbilityKey,
                productionChoices = productionChoices,
                atomicActivationManaCostUnits = shape.atomicManaCostUnits,
                activationSupportKind = PaymentActivationSupportKindV1.FIXED_MANA_AND_TAP_SELF,
                deterministicNonManaCosts = listOf(
                    PaymentDeterministicNonManaCostKindV1.TAP_SELF,
                ),
                activationCostOrderOptions = listOf(shape.activationCostOrder),
            )
        }
    }

    private fun ManaSymbol.toDomain(index: Int): PaymentCostUnitDomain? = when (this) {
        is ManaSymbol.Colored -> PaymentCostUnitDomain(
            symbolIndex = index,
            kind = PaymentCostKind.COLORED,
            amount = 1,
            allowedColors = setOf(PaymentManaColor.fromEngine(color)),
        )
        is ManaSymbol.Colorless -> PaymentCostUnitDomain(
            symbolIndex = index,
            kind = PaymentCostKind.COLORLESS,
            amount = 1,
            allowedColors = setOf(PaymentManaColor.COLORLESS),
        )
        is ManaSymbol.Generic -> PaymentCostUnitDomain(
            symbolIndex = index,
            kind = PaymentCostKind.GENERIC,
            amount = amount,
        )
        // The first slice intentionally does not hide hybrid, Phyrexian, twobrid, or X choices.
        else -> null
    }

    private fun ManaSource.toDomain(): List<PaymentSourceActivationDomain>? {
        if (!supportsPaymentPlanV1()) return null

        if (paymentManaProductionProfiles.isNotEmpty()) {
            if (paymentManaProductionProfiles.values.any { profile ->
                    when (profile) {
                        is PaymentManaProductionProfile.Unsupported -> true
                        is PaymentManaProductionProfile.SelectableSingleOutput -> profile.allowedColors.isEmpty()
                        is PaymentManaProductionProfile.FixedOutputBundle -> profile.outputs.size < 2
                    }
                }) return null

            val domains = paymentManaProductionProfiles.entries
                .sortedBy { it.key }
                .map { (manaAbilityKey, profile) ->
                    val productionChoices = when (profile) {
                        is PaymentManaProductionProfile.SelectableSingleOutput ->
                            profile.allowedColors.sortedBy(PaymentManaColor::ordinal).map {
                                ProductionChoice(producedColor = it)
                            }

                        is PaymentManaProductionProfile.FixedOutputBundle -> {
                            val outputs = profile.outputs.mapIndexed { index, output ->
                                FixedManaOutput(
                                    index = index,
                                    color = output.color,
                                    amount = 1,
                                )
                            }
                            listOf(
                                ProductionChoice(
                                    producedColor = outputs.first().color,
                                    fixedOutputs = outputs,
                                )
                            )
                        }

                        is PaymentManaProductionProfile.Unsupported ->
                            emptyList()
                    }
                    PaymentSourceActivationDomain(
                        sourceId = entityId,
                        sourceName = name,
                        manaAbilityKey = manaAbilityKey,
                        productionChoices = productionChoices,
                    )
                }
                .filter { it.productionChoices.isNotEmpty() }
            return domains.takeIf { it.isNotEmpty() }
        }

        // Compatibility fallback for ManaSource instances constructed outside the current source
        // discovery path. Discovered sources always carry a Rules-owned profile above.
        if (
            requiresSacrifice ||
            tapPermanentsSubCost != null ||
            manaAmount != 1 ||
            bonusManaPerTap != 0 ||
            bonusManaColorlessPerTap != 0 ||
            bonusManaColor != null ||
            bonusManaIsAnyColor ||
            restriction != null ||
            colorRestrictions.isNotEmpty() ||
            colorRiders.isNotEmpty() ||
            hasContextSensitiveAbilities ||
            colorActivationManaCost.isNotEmpty() ||
            colorPainCost.isNotEmpty() ||
            colorlessPainCost != 0 ||
            colorsRequiringSacrifice.isNotEmpty()
        ) return null

        val choicesByAbility = linkedMapOf<String, MutableSet<PaymentManaColor>>()
        for (color in Color.entries) {
            if (color !in producesColors) continue
            val abilities = manaAbilityOptionsFor(color)
            if (abilities.isEmpty()) {
                choicesByAbility.getOrPut(ManaAbilityIdentity.intrinsic(color)) { linkedSetOf() }
                    .add(PaymentManaColor.fromEngine(color))
            } else {
                abilities.forEach { ability ->
                    choicesByAbility.getOrPut(ManaAbilityIdentity.key(ability)) { linkedSetOf() }
                        .add(PaymentManaColor.fromEngine(color))
                }
            }
        }
        if (producesColorless) {
            val abilities = manaAbilityOptionsFor(null)
            if (abilities.isEmpty()) {
                choicesByAbility.getOrPut(ManaAbilityIdentity.intrinsic(null)) { linkedSetOf() }
                    .add(PaymentManaColor.COLORLESS)
            } else {
                abilities.forEach { ability ->
                    choicesByAbility.getOrPut(ManaAbilityIdentity.key(ability)) { linkedSetOf() }
                        .add(PaymentManaColor.COLORLESS)
                }
            }
        }
        if (choicesByAbility.isEmpty()) return null

        return choicesByAbility.entries.sortedBy { it.key }.map { (manaAbilityKey, colors) ->
            PaymentSourceActivationDomain(
                sourceId = entityId,
                sourceName = name,
                manaAbilityKey = manaAbilityKey,
                productionChoices = colors.sortedBy(PaymentManaColor::ordinal).map {
                    ProductionChoice(producedColor = it)
                },
            )
        }
    }
}

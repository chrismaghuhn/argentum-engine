package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.mechanics.mana.FloatingManaProvenanceClassification
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.ManaSource
import com.wingedsheep.engine.mechanics.mana.PaymentManaProductionProfile
import com.wingedsheep.engine.mechanics.mana.SpellPaymentContext
import com.wingedsheep.engine.mechanics.mana.supportsPaymentPlanV1
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

const val PAYMENT_DOMAIN_V2_VERSION: Int = 2
const val PAYMENT_DOMAIN_VERSION: Int = 3

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
@Deprecated("Historical PaymentDomain V1 wire DTO; current observations use PaymentDomainV3")
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
@Deprecated("Historical PaymentDomain V1 wire DTO; current observations use PaymentDomainV3")
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
 * Current action-level payment domain. Version 3 is required because the existing repository does
 * not contain a proven fail-closed V2 client decoder path before domain interpretation.
 */
@Serializable
data class PaymentDomainV3(
    val version: Int = PAYMENT_DOMAIN_VERSION,
    val requiredCost: String,
    val costUnits: List<PaymentCostUnitDomain>,
    val currentPool: PaymentPoolDomainV3,
    val sourceActivations: List<PaymentSourceActivationDomain>,
) {
    init {
        require(version == PAYMENT_DOMAIN_VERSION) {
            "Unsupported PaymentDomainV3 version: $version"
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
) {
    fun build(
        state: GameState,
        playerId: EntityId,
        requiredCost: String,
        spellContext: SpellPaymentContext,
        excludeSources: Set<EntityId> = emptySet(),
    ): PaymentDomainV3? {
        val cost = runCatching { ManaCost.parse(requiredCost) }.getOrNull() ?: return null
        val costUnits = cost.symbols.mapIndexed { index, symbol -> symbol.toDomain(index) ?: return null }

        val pool = state.getEntity(playerId)?.get<ManaPoolComponent>() ?: ManaPoolComponent()
        // Restricted mana has no stable public bucket identity in V3. Do not publish a partial
        // domain that would force the engine to choose a restricted bucket during submission.
        if (pool.restrictedMana.isNotEmpty()) return null
        var certifiedFloatingMana: CertifiedHomogeneousFloatingManaDomainV2? = null
        var certifiedHeterogeneousFloatingMana: CertifiedHeterogeneousFloatingManaDomainV3? = null
        when (val classification =
            FloatingManaProvenanceClassification.classify(pool)) {
            FloatingManaProvenanceClassification.NoTrackedProvenance -> Unit
            is FloatingManaProvenanceClassification.Ambiguous -> return null
            is FloatingManaProvenanceClassification.CertifiedHomogeneous -> {
                val candidate = classification.candidate
                if (candidate.sourceBuckets.any {
                        !isPerspectiveSafeSource(state, playerId, it.sourceId)
                    }
                ) return null
                certifiedFloatingMana = CertifiedHomogeneousFloatingManaDomainV2(
                    poolColor = candidate.poolColor,
                    sourceSubtypes = candidate.sourceSubtypes.map { it.value }.sorted(),
                    sourceBuckets = candidate.sourceBuckets
                        .sortedBy { it.sourceId.value }
                        .map { bucket ->
                            CertifiedFloatingManaSourceBucketDomainV2(
                                sourceId = bucket.sourceId,
                                amount = bucket.amount,
                            )
                        },
                )
            }
            is FloatingManaProvenanceClassification.CertifiedHeterogeneous -> {
                val candidate = classification.candidate
                if (candidate.sourceColorBuckets.any {
                        !isPerspectiveSafeSource(state, playerId, it.sourceId)
                    }
                ) return null
                certifiedHeterogeneousFloatingMana = CertifiedHeterogeneousFloatingManaDomainV3(
                    sourceColorBuckets = candidate.sourceColorBuckets
                        .sortedWith(compareBy({ it.sourceId.value }, { it.poolColor.ordinal }))
                        .map { bucket ->
                            CertifiedFloatingManaSourceColorBucketDomainV3(
                                sourceId = bucket.sourceId,
                                poolColor = bucket.poolColor,
                                amount = bucket.amount,
                            )
                        },
                    sourceSubtypes = candidate.sourceSubtypes.map { it.value }.sorted(),
                )
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

        return PaymentDomainV3(
            requiredCost = requiredCost,
            costUnits = costUnits,
            currentPool = PaymentPoolDomainV3(
                white = pool.white,
                blue = pool.blue,
                black = pool.black,
                red = pool.red,
                green = pool.green,
                colorless = pool.colorless,
                certifiedFloatingMana = certifiedFloatingMana,
                certifiedHeterogeneousFloatingMana = certifiedHeterogeneousFloatingMana,
            ),
            sourceActivations = sourceActivations,
        )
    }

    private fun isPerspectiveSafeSource(
        state: GameState,
        playerId: EntityId,
        sourceId: EntityId,
    ): Boolean = visibility.isEntityIdentityVisibleTo(state, sourceId, playerId)

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

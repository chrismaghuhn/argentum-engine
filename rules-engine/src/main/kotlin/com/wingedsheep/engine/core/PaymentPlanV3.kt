package com.wingedsheep.engine.core

import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The atomic kind of a fixed ordinary mana-cost unit in the V3 payment contract.
 *
 * V1/V2 payment plans intentionally keep their historical symbol/amount representation. V3
 * expands a symbol such as `{2}` into two separately addressable generic units so that one global
 * ledger can account for every inner and outer allocation without inventing an ordering choice.
 */
@Serializable
enum class PaymentCostKindV1 {
    COLORED,
    COLORLESS,
    GENERIC,
}

/** One atom of a fixed ordinary mana cost. */
@Serializable
data class AtomicManaCostUnitV1(
    val symbolIndex: Int,
    val unitIndexWithinSymbol: Int,
    val kind: PaymentCostKindV1,
    val allowedColors: Set<PaymentManaColor> = emptySet(),
) {
    init {
        require(symbolIndex >= 0) { "Atomic mana cost symbolIndex must be non-negative" }
        require(unitIndexWithinSymbol >= 0) {
            "Atomic mana cost unitIndexWithinSymbol must be non-negative"
        }
        when (kind) {
            PaymentCostKindV1.COLORED -> require(allowedColors.isNotEmpty()) {
                "A colored atomic mana unit must publish at least one allowed color"
            }

            PaymentCostKindV1.COLORLESS -> require(
                allowedColors == setOf(PaymentManaColor.COLORLESS)
            ) {
                "A colorless atomic mana unit must allow only COLORLESS"
            }

            PaymentCostKindV1.GENERIC -> require(allowedColors.isEmpty()) {
                "A generic atomic mana unit cannot publish color restrictions"
            }
        }
    }
}

/**
 * Rules-owned identity of a fungible initial-pool bucket exposed to an explicit V3 plan.
 *
 * Unrestricted pool mana is fungible within its color bucket. Certified floating mana reuses the
 * complete Rules-issued bucket key, including its source and subtype snapshot; a caller cannot
 * manufacture a partial provenance identity.
 */
@Serializable
sealed interface InitialPoolBucketKeyV1 {
    @Serializable
    @SerialName("UnrestrictedPoolBucket")
    data class UnrestrictedPoolBucket(
        val color: PaymentManaColor,
    ) : InitialPoolBucketKeyV1

    @Serializable
    @SerialName("CertifiedFloatingBucket")
    data class CertifiedFloatingBucket(
        val key: FloatingManaBucketKeyV1,
    ) : InitialPoolBucketKeyV1
}

/** Capacity of one published fungible initial-pool bucket. */
@Serializable
data class InitialPoolBucketV1(
    val key: InitialPoolBucketKeyV1,
    val availableAmount: Int,
)

/** A legal non-mana component in the selected activation-cost order. */
@Serializable
sealed interface ActivationCostComponentRefV1 {
    /** The ordinary mana portion of the activation cost. */
    @Serializable
    @SerialName("ManaComponent")
    data object ManaComponent : ActivationCostComponentRefV1

    /** A deterministic component whose position is explicit in the submitted program. */
    @Serializable
    @SerialName("DeterministicNonManaComponent")
    data class DeterministicNonManaComponent(
        val index: Int,
    ) : ActivationCostComponentRefV1
}

/** Ordered cost components for one source activation. */
typealias ActivationCostOrderV1 = List<ActivationCostComponentRefV1>

/** A resource that can be consumed by exactly one atomic payment target. */
@Serializable
sealed interface ManaResourceRefV1 {
    /** A fungible unit drawn from a published initial-pool bucket. */
    @Serializable
    @SerialName("InitialPoolResource")
    data class InitialPoolResource(
        val bucketKey: InitialPoolBucketKeyV1,
    ) : ManaResourceRefV1

    /** One indexed unit of one earlier activation's fixed output bundle. */
    @Serializable
    @SerialName("ActivationOutputUnit")
    data class ActivationOutputUnit(
        val activationIndex: Int,
        val outputIndex: Int,
    ) : ManaResourceRefV1
}

/** One atomic activation-cost or outer-cost target. */
@Serializable
sealed interface PaymentTargetV1 {
    /** One unit of the ordinary mana cost of an activation node. */
    @Serializable
    @SerialName("ActivationCostUnit")
    data class ActivationCostUnit(
        val activationIndex: Int,
        val symbolIndex: Int,
        val unitIndexWithinSymbol: Int,
    ) : PaymentTargetV1

    /** One unit of the action's outer mana cost. */
    @Serializable
    @SerialName("OuterCostUnit")
    data class OuterCostUnit(
        val symbolIndex: Int,
        val unitIndexWithinSymbol: Int,
    ) : PaymentTargetV1
}

/** One explicit one-resource-to-one-atomic-target ledger entry. */
@Serializable
data class PaymentAllocationV1(
    val target: PaymentTargetV1,
    val resource: ManaResourceRefV1,
)

/**
 * One ordered mana-source activation in a V3 payment program.
 *
 * The node's identity is its position in [PaymentPlanV3.activations]. There is deliberately no
 * arbitrary activation ID: output references use that list position, which makes forward
 * references and cycles structurally rejectable.
 */
@Serializable
data class SourceActivationV2(
    val sourceId: EntityId,
    val manaAbilityKey: String,
    val productionChoice: ProductionChoice,
    val activationCostOrder: ActivationCostOrderV1 = emptyList(),
    val activationCostAllocation: List<PaymentAllocationV1> = emptyList(),
)

/**
 * Complete externally selected ordered payment program for the V5 public domain.
 *
 * The activation-cost allocations and [outerAllocation] share one logical Rules ledger. The
 * validator, rather than this DTO, proves capacities, provenance, ordering, and source freshness.
 */
@Serializable
data class PaymentPlanV3(
    val activations: List<SourceActivationV2> = emptyList(),
    val outerAllocation: List<PaymentAllocationV1> = emptyList(),
)

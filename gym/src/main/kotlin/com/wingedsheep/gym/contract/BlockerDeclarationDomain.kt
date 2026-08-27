package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Version of the public blocker-declaration choice domain. */
const val BLOCKER_DECLARATION_DOMAIN_VERSION: Int = 1

/**
 * Complete public projection of one Rules-owned DeclareBlockers certificate.
 *
 * The candidate lists and relation maps are already in producer-owned canonical order. The
 * `requirements` list is a multiset: duplicate entries are intentionally retained because CR
 * 509.1c counts resolved requirement instances, not distinct entity relations. Blocking costs are
 * not part of this DTO; a valid assignment may be followed by a separate cost-payment decision.
 */
@Serializable
data class BlockerDeclarationDomainV1(
    val version: Int = BLOCKER_DECLARATION_DOMAIN_VERSION,
    val blockerOrder: List<EntityId>,
    val attackerOrder: List<EntityId>,
    val blockerToAttackers: Map<EntityId, List<EntityId>>,
    val maxAttackersByBlocker: Map<EntityId, Int>,
    val minBlockersByAttacker: Map<EntityId, Int>,
    val maxBlockersByAttacker: Map<EntityId, Int>,
    val globalMaxBlockers: Int?,
    val coBlockerRequirements: Map<EntityId, List<BlockCoBlockerRequirementV1>>,
    val requirements: List<BlockRequirementV1>,
    val minimumSatisfiedRequirementCount: Int,
    val canDeclareZeroBlockers: Boolean,
) {
    init {
        require(version == BLOCKER_DECLARATION_DOMAIN_VERSION) {
            "Unsupported blocker declaration domain version: $version"
        }
    }
}

@Serializable
data class BlockCoBlockerRequirementV1(
    val eligibleCoBlockers: List<EntityId>,
)

/** Resolved 509.1c requirement instances. Equal instances are valid and must not be deduplicated. */
@Serializable
sealed interface BlockRequirementV1 {
    @Serializable
    @SerialName("block-specific")
    data class BlockSpecific(
        val blockerId: EntityId,
        val attackerId: EntityId,
    ) : BlockRequirementV1

    @Serializable
    @SerialName("block-one-of")
    data class BlockOneOf(
        val blockerId: EntityId,
        val attackerIds: List<EntityId>,
    ) : BlockRequirementV1

    @Serializable
    @SerialName("attacker-must-be-blocked-if-able")
    data class AttackerMustBeBlockedIfAble(
        val attackerId: EntityId,
    ) : BlockRequirementV1

    @Serializable
    @SerialName("attacker-must-be-blocked-by-all")
    data class AttackerMustBeBlockedByAll(
        val attackerId: EntityId,
    ) : BlockRequirementV1

    @Serializable
    @SerialName("blocker-must-block-if-able")
    data class BlockerMustBlockIfAble(
        val blockerId: EntityId,
    ) : BlockRequirementV1
}

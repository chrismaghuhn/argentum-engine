package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/** Version of the public attack-declaration choice domain. */
const val ATTACK_DECLARATION_DOMAIN_VERSION: Int = 1

/** Current public attack-declaration domain version. V1 remains historical codec material. */
const val ATTACK_DECLARATION_DOMAIN_V2_VERSION: Int = 2

@Serializable
data class AttackDeclarationDomainV1(
    val version: Int = ATTACK_DECLARATION_DOMAIN_VERSION,
    val attackerToDefenders: Map<EntityId, List<EntityId>>,
    val mandatoryAttackers: List<EntityId>,
    val canDeclareZeroAttackers: Boolean,
    val maxAttackers: Int?,
    val coAttackerRequirements: Map<EntityId, List<AttackCoAttackerRequirementV1>>,
    val bandConstraints: AttackBandConstraintsV1,
) {
    init {
        require(version == ATTACK_DECLARATION_DOMAIN_VERSION) {
            "Unsupported attack declaration domain version: $version"
        }
    }
}

/**
 * Current public attack-declaration domain. [attackerOrder] is the Rules-owned candidate
 * sequence; all attacker-related lists and the per-attacker defender lists retain that producer
 * order. V1 is intentionally not used on the live strict-observation path.
 */
@Serializable
data class AttackDeclarationDomainV2(
    val version: Int = ATTACK_DECLARATION_DOMAIN_V2_VERSION,
    val attackerOrder: List<EntityId>,
    val attackerToDefenders: Map<EntityId, List<EntityId>>,
    val mandatoryAttackers: List<EntityId>,
    val canDeclareZeroAttackers: Boolean,
    val maxAttackers: Int?,
    val coAttackerRequirements: Map<EntityId, List<AttackCoAttackerRequirementV1>>,
    val bandConstraints: AttackBandConstraintsV1,
) {
    init {
        require(version == ATTACK_DECLARATION_DOMAIN_V2_VERSION) {
            "Unsupported attack declaration domain version: $version"
        }
    }
}

@Serializable
data class AttackCoAttackerRequirementV1(
    val anyOf: List<EntityId>,
)

@Serializable
data class AttackBandConstraintsV1(
    val bandingAttackersByDefender: Map<EntityId, List<EntityId>>,
    val nonBandingAttackersByDefender: Map<EntityId, List<EntityId>>,
)

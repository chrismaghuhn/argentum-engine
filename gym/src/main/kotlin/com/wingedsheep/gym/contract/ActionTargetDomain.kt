package com.wingedsheep.gym.contract

import kotlinx.serialization.Serializable

/** Independent version for the fixed action-level target contract. */
const val ACTION_TARGET_DOMAIN_VERSION: Int = 1

/** Composition forms are intentionally bounded; V1 publishes only a fixed ordered list. */
@Serializable
enum class ActionTargetComposition {
    FIXED,
}

/**
 * The public target domain for one executable action.
 *
 * V1 carries the engine-issued requirement list and keeps the existing flat
 * [com.wingedsheep.engine.core.GameAction] target payload. Grouped, prefix, and modal-branch
 * payloads are future contracts and must not be represented by this type.
 */
@Serializable
data class ActionTargetDomainV1(
    val version: Int = ACTION_TARGET_DOMAIN_VERSION,
    val composition: ActionTargetComposition = ActionTargetComposition.FIXED,
    val requirements: List<TargetRequirementDomain> = emptyList(),
) {
    init {
        require(version == ACTION_TARGET_DOMAIN_VERSION) {
            "Unsupported action target domain version: $version"
        }
        require(composition == ActionTargetComposition.FIXED) {
            "Unsupported action target domain composition: $composition"
        }
    }
}

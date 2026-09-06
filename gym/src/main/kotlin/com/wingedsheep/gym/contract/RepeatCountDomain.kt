package com.wingedsheep.gym.contract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable

/** Version of the public repeat-count choice domain. */
const val REPEAT_COUNT_DOMAIN_V1_VERSION: Int = 1

/**
 * Complete public domain for an activated ability's repeat count.
 *
 * The Rules legal-action producer owns the upper bound. V1 deliberately fixes the lower bound
 * at one: a repeatable activation is represented as one or more activations, while zero is not a
 * repeat-count choice.
 */
@Serializable
data class RepeatCountDomainV1(
    val version: Int = REPEAT_COUNT_DOMAIN_V1_VERSION,
    val minCount: Int = 1,
    val maxCount: Int,
) {
    init {
        require(version == REPEAT_COUNT_DOMAIN_V1_VERSION) {
            "Unsupported repeat-count domain version: $version"
        }
        require(minCount == 1) {
            "Repeat-count domain must start at one"
        }
        require(maxCount >= minCount) {
            "Repeat-count domain has an invalid range"
        }
    }
}

/** Decode a stored repeat domain with its exact, closed nested JSON shape. */
internal fun decodeRepeatCountDomain(
    value: JsonElement,
    label: String = "repeat-count domain",
): RepeatCountDomainV1 {
    val objectValue = value as? JsonObject
        ?: throw IllegalArgumentException("Malformed $label")
    require(objectValue.keys == setOf("version", "minCount", "maxCount")) {
        "Malformed $label"
    }
    return A3SemanticJson.decodeStrict(
        RepeatCountDomainV1.serializer(),
        objectValue,
        label,
    )
}

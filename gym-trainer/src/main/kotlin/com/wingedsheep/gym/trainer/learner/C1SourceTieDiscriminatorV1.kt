package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.contract.A3SemanticJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal data class C1ProjectedCandidateForTie(
    val sourceBindingOrdinal: Int,
    val featureView: JsonElement,
)

/**
 * Produces deterministic tie keys only from the already admitted source-owned feature view.
 *
 * Equal feature keys deliberately produce no discriminator so Selection V2 can use PolicyTieRng.
 */
internal object C1SourceTieDiscriminatorV1 {
    private val forbiddenKeys = setOf(
        "actionId",
        "decisionId",
        "sourceEntityId",
        "targetEntityIds",
        "validSacrificeTargets",
        "entityId",
        "rowIndex",
        "sourceBindingOrdinal",
        "allocationOrder",
        "batchSlot",
    )

    fun produce(candidates: List<C1ProjectedCandidateForTie>): Map<Int, JsonElement> {
        require(candidates.map { it.sourceBindingOrdinal }.distinct().size == candidates.size) {
            "Source binding ordinals must be unique"
        }
        val keys = candidates.associate { candidate ->
            require(candidate.sourceBindingOrdinal >= 0) {
                "Source binding ordinal must not be negative"
            }
            rejectForbiddenKeys(candidate.featureView)
            candidate.sourceBindingOrdinal to A3SemanticJson.canonicalJson(candidate.featureView)
        }
        val uniqueKeys = keys.values.groupingBy { it }.eachCount()
        return keys
            .filterValues { key -> uniqueKeys[key] == 1 }
            .mapValues { (_, key) -> JsonPrimitive(key) }
    }

    private fun rejectForbiddenKeys(value: JsonElement) {
        when (value) {
            is kotlinx.serialization.json.JsonObject -> {
                require(value.keys.none(forbiddenKeys::contains)) {
                    "Tie discriminator contains a forbidden identity/order field"
                }
                value.values.forEach(::rejectForbiddenKeys)
            }

            is kotlinx.serialization.json.JsonArray -> value.forEach(::rejectForbiddenKeys)
            else -> Unit
        }
    }
}

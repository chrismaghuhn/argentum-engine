package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.contract.C1ModelFacingProjectionV1
import kotlinx.serialization.json.JsonElement

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
    fun produce(candidates: List<C1ProjectedCandidateForTie>): Map<Int, JsonElement> =
        C1ModelFacingProjectionV1.produceTieDiscriminators(
            candidates.map { it.sourceBindingOrdinal to it.featureView },
        )
}

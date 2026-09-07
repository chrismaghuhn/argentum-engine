package com.wingedsheep.gym.history

import com.wingedsheep.sdk.model.EntityId

const val PERSPECTIVE_ALIAS_REGISTRY_V1_VERSION: Int = 1

const val PERSPECTIVE_ALIAS_REGISTRY_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-history-c-perspective-alias-registry@v1"

/** Compact semantic value; its ordinal has meaning only inside one registry namespace. */
@JvmInline
internal value class PerspectiveSemanticAlias(
    val ordinal: Long,
) {
    init {
        require(ordinal >= 0L) { "Perspective aliases require a non-negative ordinal" }
    }

    fun canonical(): String = "o$ordinal"
}

/** Current alias binding, including only the identity disclosure learned for this incarnation. */
internal data class PerspectiveAliasBinding(
    val alias: PerspectiveSemanticAlias,
    val knownCardDefinitionId: String? = null,
)

/** Immutable registry namespace for one semantic episode and one perspective. */
internal data class PerspectiveAliasRegistryV1(
    val version: Int = PERSPECTIVE_ALIAS_REGISTRY_V1_VERSION,
    val schemaIdentity: String = PERSPECTIVE_ALIAS_REGISTRY_V1_SCHEMA_IDENTITY,
    val semanticEpisodeId: String,
    val perspectivePlayerId: EntityId,
    val nextAliasOrdinal: Long = 0L,
    val activeBindings: Map<HistoryCObjectWitness, PerspectiveAliasBinding> = emptyMap(),
    val retiredAliases: Set<PerspectiveSemanticAlias> = emptySet(),
)

internal data class PerspectiveAliasAssignment(
    val candidateIndex: Int,
    val alias: PerspectiveSemanticAlias,
    val identityDisclosure: HistoryCIdentityDisclosure,
    val cardDefinitionId: String?,
)

internal sealed interface PerspectiveAliasAllocationResult {
    data class Accepted(
        val registry: PerspectiveAliasRegistryV1,
        val assignments: List<PerspectiveAliasAssignment>,
    ) : PerspectiveAliasAllocationResult

    data class Rejected(
        val failure: HistoryCFailure,
    ) : PerspectiveAliasAllocationResult
}

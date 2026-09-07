package com.wingedsheep.gym.history

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.charset.StandardCharsets

const val HISTORY_C_SNAPSHOT_V1_VERSION: Int = 1

const val HISTORY_C_SNAPSHOT_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-history-c-lifecycle-snapshot@v1"

@Serializable
internal data class HistoryCSnapshotBindingV1(
    val entityId: String,
    val objectIdentityStamp: Long,
    val aliasOrdinal: Long,
    val knownCardDefinitionId: String? = null,
)

@Serializable
internal data class HistoryCSnapshotRegistryV1(
    val version: Int,
    val schemaIdentity: String,
    val semanticEpisodeId: String,
    val perspectivePlayerId: String,
    val nextAliasOrdinal: Long,
    val activeBindings: List<HistoryCSnapshotBindingV1>,
    val retiredAliasOrdinals: List<Long>,
)

@Serializable
internal data class HistoryCSnapshotEnvelopeV1(
    val version: Int,
    val schemaIdentity: String,
    val semanticEpisodeId: String,
    val stepCount: Int,
    val projectionGeneration: Long,
    val playerIds: List<String>,
    val registries: List<HistoryCSnapshotRegistryV1>,
    val integritySha256: String,
)

internal sealed interface HistoryCSnapshotDecodeResult {
    data class Accepted(val state: HistoryCLifecycleStateV1) : HistoryCSnapshotDecodeResult

    data class Rejected(val failure: HistoryCFailure) : HistoryCSnapshotDecodeResult
}

/** Deterministic, privileged snapshot codec for History-C lifecycle state. */
internal object HistoryCSnapshotCodecV1 {
    private val json = A3SemanticJson.strictJson

    fun encode(
        state: HistoryCLifecycleStateV1,
        stepCount: Int,
        projectionGeneration: Long = stepCount.toLong(),
    ): ByteArray {
        require(stepCount >= 0) { "History-C snapshot stepCount must not be negative" }
        require(projectionGeneration >= 0L) {
            "History-C snapshot projectionGeneration must not be negative"
        }
        val envelope = HistoryCSnapshotEnvelopeV1(
            version = HISTORY_C_SNAPSHOT_V1_VERSION,
            schemaIdentity = HISTORY_C_SNAPSHOT_V1_SCHEMA_IDENTITY,
            semanticEpisodeId = state.semanticEpisodeId,
            stepCount = stepCount,
            projectionGeneration = projectionGeneration,
            playerIds = state.playerIds.map { it.value },
            registries = state.playerIds
                .map { state.registries.getValue(it) }
                .sortedBy { it.perspectivePlayerId.value }
                .map(::encodeRegistry),
            integritySha256 = "",
        )
        return encodeEnvelope(envelope)
    }

    internal fun encodeEnvelope(envelope: HistoryCSnapshotEnvelopeV1): ByteArray {
        val withIntegrity = envelope.copy(integritySha256 = integrity(envelope.copy(integritySha256 = "")))
        return canonical(withIntegrity).toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(
        encoded: ByteArray,
        expectedPlayerIds: List<EntityId>,
        expectedStepCount: Int,
        expectedProjectionGeneration: Long = expectedStepCount.toLong(),
    ): HistoryCSnapshotDecodeResult {
        if (expectedStepCount < 0 || expectedProjectionGeneration < 0L) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
        }
        val envelope = try {
            json.decodeFromString<HistoryCSnapshotEnvelopeV1>(
                encoded.toString(StandardCharsets.UTF_8),
            )
        } catch (_: Exception) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
        }

        if (envelope.version != HISTORY_C_SNAPSHOT_V1_VERSION) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_VERSION)
        }
        if (envelope.schemaIdentity != HISTORY_C_SNAPSHOT_V1_SCHEMA_IDENTITY) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_SCHEMA_IDENTITY)
        }
        if (envelope.integritySha256 != integrity(envelope.copy(integritySha256 = ""))) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_INTEGRITY)
        }
        if (envelope.semanticEpisodeId.isBlank()) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
        }
        if (envelope.stepCount != expectedStepCount) {
            return rejected(HistoryCFailureCode.HISTORY_C_SNAPSHOT_STEP_MISMATCH)
        }
        if (envelope.projectionGeneration != expectedProjectionGeneration) {
            return rejected(HistoryCFailureCode.HISTORY_C_SNAPSHOT_PROJECTION_MISMATCH)
        }
        if (envelope.playerIds != expectedPlayerIds.map { it.value } ||
            envelope.playerIds.distinct().size != envelope.playerIds.size
        ) {
            return rejected(HistoryCFailureCode.HISTORY_C_SNAPSHOT_PERSPECTIVE_MISMATCH)
        }

        val expectedPerspectiveOrder = envelope.registries.sortedBy { it.perspectivePlayerId }
        if (envelope.registries != expectedPerspectiveOrder) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
        }

        val registries = linkedMapOf<EntityId, PerspectiveAliasRegistryV1>()
        for (snapshotRegistry in envelope.registries) {
            if (snapshotRegistry.version != PERSPECTIVE_ALIAS_REGISTRY_V1_VERSION ||
                snapshotRegistry.schemaIdentity != PERSPECTIVE_ALIAS_REGISTRY_V1_SCHEMA_IDENTITY
            ) {
                return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
            }
            if (snapshotRegistry.semanticEpisodeId != envelope.semanticEpisodeId) {
                return rejected(HistoryCFailureCode.HISTORY_C_SNAPSHOT_EPISODE_MISMATCH)
            }
            val perspective = EntityId(snapshotRegistry.perspectivePlayerId)
            if (perspective !in expectedPlayerIds || registries.containsKey(perspective)) {
                return rejected(HistoryCFailureCode.HISTORY_C_SNAPSHOT_PERSPECTIVE_MISMATCH)
            }
            val activeBindings = linkedMapOf<HistoryCObjectWitness, PerspectiveAliasBinding>()
            for (binding in snapshotRegistry.activeBindings) {
                val witness = try {
                    HistoryCObjectWitness(EntityId(binding.entityId), binding.objectIdentityStamp)
                } catch (_: Exception) {
                    return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
                }
                if (activeBindings.containsKey(witness) || binding.knownCardDefinitionId.isNullOrBlank() &&
                    binding.knownCardDefinitionId != null
                ) {
                    return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
                }
                val alias = try {
                    PerspectiveSemanticAlias(binding.aliasOrdinal)
                } catch (_: Exception) {
                    return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
                }
                activeBindings[witness] = PerspectiveAliasBinding(
                    alias = alias,
                    knownCardDefinitionId = binding.knownCardDefinitionId,
                )
            }
            if (snapshotRegistry.activeBindings != snapshotRegistry.activeBindings
                    .sortedWith(compareBy({ it.entityId }, { it.objectIdentityStamp })) ||
                snapshotRegistry.retiredAliasOrdinals != snapshotRegistry.retiredAliasOrdinals.sorted() ||
                snapshotRegistry.retiredAliasOrdinals.distinct().size !=
                    snapshotRegistry.retiredAliasOrdinals.size
            ) {
                return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
            }
            val retiredAliases = try {
                snapshotRegistry.retiredAliasOrdinals.map(::PerspectiveSemanticAlias).toSet()
            } catch (_: Exception) {
                return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
            }
            val registry = try {
                PerspectiveAliasRegistryV1(
                    version = snapshotRegistry.version,
                    schemaIdentity = snapshotRegistry.schemaIdentity,
                    semanticEpisodeId = snapshotRegistry.semanticEpisodeId,
                    perspectivePlayerId = perspective,
                    nextAliasOrdinal = snapshotRegistry.nextAliasOrdinal,
                    activeBindings = activeBindings,
                    retiredAliases = retiredAliases,
                )
            } catch (_: Exception) {
                return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
            }
            if (PerspectiveAliasAllocator.validateRegistryState(registry) != null) {
                return rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
            }
            registries[perspective] = registry
        }
        if (registries.keys != expectedPlayerIds.toSet()) {
            return rejected(HistoryCFailureCode.HISTORY_C_SNAPSHOT_PERSPECTIVE_MISMATCH)
        }

        return try {
            HistoryCSnapshotDecodeResult.Accepted(
                HistoryCLifecycleStateV1(
                    semanticEpisodeId = envelope.semanticEpisodeId,
                    playerIds = expectedPlayerIds,
                    registries = expectedPlayerIds.associateWith { registries.getValue(it) },
                ),
            )
        } catch (_: Exception) {
            rejected(HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE)
        }
    }

    private fun encodeRegistry(
        registry: PerspectiveAliasRegistryV1,
    ): HistoryCSnapshotRegistryV1 = HistoryCSnapshotRegistryV1(
        version = registry.version,
        schemaIdentity = registry.schemaIdentity,
        semanticEpisodeId = registry.semanticEpisodeId,
        perspectivePlayerId = registry.perspectivePlayerId.value,
        nextAliasOrdinal = registry.nextAliasOrdinal,
        activeBindings = registry.activeBindings.entries
            .sortedWith(compareBy({ it.key.entityId.value }, { it.key.objectIdentityStamp }))
            .map { (witness, binding) ->
                HistoryCSnapshotBindingV1(
                    entityId = witness.entityId.value,
                    objectIdentityStamp = witness.objectIdentityStamp,
                    aliasOrdinal = binding.alias.ordinal,
                    knownCardDefinitionId = binding.knownCardDefinitionId,
                )
            },
        retiredAliasOrdinals = registry.retiredAliases.map { it.ordinal }.sorted(),
    )

    private fun integrity(envelope: HistoryCSnapshotEnvelopeV1): String =
        A3SemanticJson.sha256(canonical(envelope).toByteArray(StandardCharsets.UTF_8))

    private fun canonical(envelope: HistoryCSnapshotEnvelopeV1): String =
        A3SemanticJson.canonicalJson(json.encodeToJsonElement(envelope))

    private fun rejected(code: HistoryCFailureCode): HistoryCSnapshotDecodeResult.Rejected =
        HistoryCSnapshotDecodeResult.Rejected(HistoryCFailure(code))
}

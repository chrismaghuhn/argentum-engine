package com.wingedsheep.gym.history

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.PerspectiveHistoryV1
import com.wingedsheep.sdk.model.EntityId
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

const val PERSPECTIVE_HISTORY_SNAPSHOT_V1_VERSION: Int = 1

const val PERSPECTIVE_HISTORY_SNAPSHOT_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-perspective-history-snapshot@v1"

@Serializable
internal data class PerspectiveHistorySnapshotEnvelopeV1(
    val version: Int,
    val schemaIdentity: String,
    val semanticEpisodeId: String,
    val stepCount: Int,
    val projectionGeneration: Long,
    val playerIds: List<String>,
    val histories: List<PerspectiveHistoryV1>,
    val integritySha256: String,
)

internal sealed interface PerspectiveHistorySnapshotDecodeResult {
    data class Accepted(val state: PerspectiveHistoryStateV1) :
        PerspectiveHistorySnapshotDecodeResult

    data class Rejected(val failure: HistoryCFailure) : PerspectiveHistorySnapshotDecodeResult
}

internal object PerspectiveHistorySnapshotCodecV1 {
    private val json = A3SemanticJson.strictJson

    fun encode(
        state: PerspectiveHistoryStateV1,
        stepCount: Int,
        projectionGeneration: Long,
    ): ByteArray {
        require(stepCount >= 0)
        require(projectionGeneration >= 0L)
        return encodeEnvelope(
            PerspectiveHistorySnapshotEnvelopeV1(
                version = PERSPECTIVE_HISTORY_SNAPSHOT_V1_VERSION,
                schemaIdentity = PERSPECTIVE_HISTORY_SNAPSHOT_V1_SCHEMA_IDENTITY,
                semanticEpisodeId = state.semanticEpisodeId,
                stepCount = stepCount,
                projectionGeneration = projectionGeneration,
                playerIds = state.playerIds.map { it.value },
                histories = state.playerIds
                    .map { state.histories.getValue(it) }
                    .sortedBy { it.perspectivePlayerId.value },
                integritySha256 = "",
            ),
        )
    }

    internal fun encodeEnvelope(envelope: PerspectiveHistorySnapshotEnvelopeV1): ByteArray {
        val withIntegrity = envelope.copy(
            integritySha256 = integrity(envelope.copy(integritySha256 = "")),
        )
        return canonical(withIntegrity).toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(
        encoded: ByteArray,
        expectedPlayerIds: List<EntityId>,
        expectedStepCount: Int,
        expectedProjectionGeneration: Long,
    ): PerspectiveHistorySnapshotDecodeResult {
        val envelope = try {
            json.decodeFromString<PerspectiveHistorySnapshotEnvelopeV1>(
                encoded.toString(StandardCharsets.UTF_8),
            )
        } catch (_: Exception) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_STATE)
        }
        if (envelope.version != PERSPECTIVE_HISTORY_SNAPSHOT_V1_VERSION) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_VERSION)
        }
        if (envelope.schemaIdentity != PERSPECTIVE_HISTORY_SNAPSHOT_V1_SCHEMA_IDENTITY) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_SCHEMA_IDENTITY)
        }
        if (envelope.integritySha256 != integrity(envelope.copy(integritySha256 = ""))) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_INTEGRITY)
        }
        if (envelope.semanticEpisodeId.isBlank() ||
            envelope.stepCount != expectedStepCount ||
            envelope.projectionGeneration != expectedProjectionGeneration
        ) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_STATE)
        }
        if (envelope.playerIds != expectedPlayerIds.map { it.value } ||
            envelope.playerIds.distinct().size != envelope.playerIds.size
        ) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_PERSPECTIVE)
        }
        if (envelope.histories != envelope.histories.sortedBy { it.perspectivePlayerId.value }) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_STATE)
        }
        val histories = envelope.histories.associateBy { it.perspectivePlayerId }
        if (histories.size != envelope.histories.size || histories.keys != expectedPlayerIds.toSet()) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_PERSPECTIVE)
        }
        if (histories.values.any {
                it.semanticEpisodeId != envelope.semanticEpisodeId ||
                    it.perspectivePlayerId !in expectedPlayerIds
            }
        ) {
            return rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_STATE)
        }
        return try {
            PerspectiveHistorySnapshotDecodeResult.Accepted(
                PerspectiveHistoryStateV1(
                    semanticEpisodeId = envelope.semanticEpisodeId,
                    playerIds = expectedPlayerIds,
                    histories = expectedPlayerIds.associateWith { histories.getValue(it) },
                ),
            )
        } catch (_: Exception) {
            rejected(HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_STATE)
        }
    }

    private fun integrity(envelope: PerspectiveHistorySnapshotEnvelopeV1): String =
        A3SemanticJson.sha256(canonical(envelope).toByteArray(StandardCharsets.UTF_8))

    private fun canonical(envelope: PerspectiveHistorySnapshotEnvelopeV1): String =
        A3SemanticJson.canonicalJson(json.encodeToJsonElement(envelope))

    private fun rejected(code: HistoryCFailureCode): PerspectiveHistorySnapshotDecodeResult.Rejected =
        PerspectiveHistorySnapshotDecodeResult.Rejected(HistoryCFailure(code))
}

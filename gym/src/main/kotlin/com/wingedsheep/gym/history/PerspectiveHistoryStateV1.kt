package com.wingedsheep.gym.history

import com.wingedsheep.gym.contract.PerspectiveEventBatchV1
import com.wingedsheep.gym.contract.PerspectiveHistoryEntryV1
import com.wingedsheep.gym.contract.PerspectiveHistoryIdentityDisclosureV1
import com.wingedsheep.gym.contract.PerspectiveHistoryReferenceRoleV1
import com.wingedsheep.gym.contract.PerspectiveHistoryReferenceV1
import com.wingedsheep.gym.contract.PerspectiveHistoryV1
import com.wingedsheep.sdk.model.EntityId

internal data class PerspectiveHistoryStateV1(
    val semanticEpisodeId: String,
    val playerIds: List<EntityId>,
    val histories: Map<EntityId, PerspectiveHistoryV1>,
) {
    init {
        require(semanticEpisodeId.isNotBlank())
        require(playerIds.isNotEmpty() && playerIds.distinct().size == playerIds.size)
        require(histories.keys == playerIds.toSet())
        require(histories.all { (perspective, history) ->
            history.semanticEpisodeId == semanticEpisodeId &&
                history.perspectivePlayerId == perspective
        })
    }

    fun withHistory(history: PerspectiveHistoryV1): PerspectiveHistoryStateV1 {
        require(history.semanticEpisodeId == semanticEpisodeId)
        require(history.perspectivePlayerId in playerIds)
        return copy(histories = histories + (history.perspectivePlayerId to history))
    }

    companion object {
        fun start(
            semanticEpisodeId: String,
            playerIds: List<EntityId>,
        ): PerspectiveHistoryStateV1 {
            require(semanticEpisodeId.isNotBlank())
            require(playerIds.isNotEmpty() && playerIds.distinct().size == playerIds.size)
            return PerspectiveHistoryStateV1(
                semanticEpisodeId = semanticEpisodeId,
                playerIds = playerIds.toList(),
                histories = playerIds.associateWith { perspective ->
                    PerspectiveHistoryV1(
                        semanticEpisodeId = semanticEpisodeId,
                        perspectivePlayerId = perspective,
                    )
                },
            )
        }
    }
}

internal object PerspectiveHistoryComposerV1 {
    fun append(
        state: PerspectiveHistoryStateV1,
        eventBatch: PerspectiveEventBatchV1,
        evidence: HistoryCReferenceEvidenceV1,
        projection: PerspectiveReferenceProjectionV1,
    ): PerspectiveHistoryStateV1 {
        require(eventBatch.perspectivePlayerId in state.playerIds)
        require(eventBatch.perspectivePlayerId == evidence.perspectivePlayerId)

        val referencesByEventOrdinal = projection.referenceOccurrences
            .map { assignment ->
                val candidate = evidence.candidates.getOrNull(assignment.candidateIndex)
                    ?: error("History-C assignment references an unknown candidate")
                val role = when (candidate.slot.role) {
                    HistoryCReferenceSlotRole.EVENT_SUBJECT ->
                        PerspectiveHistoryReferenceRoleV1.EVENT_SUBJECT
                    HistoryCReferenceSlotRole.MOVED_OBJECT ->
                        PerspectiveHistoryReferenceRoleV1.MOVED_OBJECT
                    HistoryCReferenceSlotRole.SOURCE -> PerspectiveHistoryReferenceRoleV1.SOURCE
                    HistoryCReferenceSlotRole.TARGET -> PerspectiveHistoryReferenceRoleV1.TARGET
                }
                candidate.slot.eventOrdinal to PerspectiveHistoryReferenceV1(
                    semanticRole = role,
                    semanticAlias = assignment.alias.canonical(),
                    identityDisclosure = when (assignment.identityDisclosure) {
                        HistoryCIdentityDisclosure.OPAQUE ->
                            PerspectiveHistoryIdentityDisclosureV1.OPAQUE
                        HistoryCIdentityDisclosure.DEFINITION_KNOWN ->
                            PerspectiveHistoryIdentityDisclosureV1.DEFINITION_KNOWN
                    },
                    cardDefinitionId = assignment.cardDefinitionId,
                )
            }
            .groupBy({ it.first }, { it.second })

        val existing = state.histories.getValue(eventBatch.perspectivePlayerId)
        val appended = eventBatch.entries.mapIndexed { index, event ->
            PerspectiveHistoryEntryV1(
                perspectiveHistoryOrdinal = existing.entries.size.toLong() + index,
                eventFamily = event.eventFamily,
                semanticPayload = event.semanticPayload,
                references = referencesByEventOrdinal[event.perspectiveEventOrdinal].orEmpty(),
            )
        }
        return state.withHistory(existing.copy(entries = existing.entries + appended))
    }
}

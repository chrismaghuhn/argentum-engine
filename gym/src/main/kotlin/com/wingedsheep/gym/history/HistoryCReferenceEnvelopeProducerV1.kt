package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.LookedAtCardsEvent
import com.wingedsheep.engine.core.TurnedFaceDownEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventClassification
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface HistoryCReferenceEnvelopeProducerResult {
    data class Accepted(val envelope: HistoryCReferenceEnvelopeV1) :
        HistoryCReferenceEnvelopeProducerResult

    data class Rejected(val failure: HistoryCFailure) : HistoryCReferenceEnvelopeProducerResult
}

/**
 * Generic internal producer for the raw event families whose A/C authority is already frozen.
 * It never scans the world for matches: every candidate is derived from the exact committed raw
 * event and its before/after witness stamps.
 */
internal object HistoryCReferenceEnvelopeProducerV1 {
    fun produce(
        transition: CommittedRulesTransition,
        projection: PerspectiveEventProjectionResult,
    ): HistoryCReferenceEnvelopeProducerResult {
        if (!projection.isComplete) {
            return rejected(HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE)
        }

        val rawEvents = emittedRawEvents(transition.events, projection.classifications)
        val candidates = mutableListOf<HistoryCReferenceCandidateV1>()
        for ((eventOrdinal, rawEvent) in rawEvents.withIndex()) {
            when (rawEvent) {
                is CardsRevealedEvent -> {
                    for (roleOrdinal in rawEvent.cardIds.indices) {
                        val candidate = knownCandidate(
                            transition = transition,
                            eventOrdinal = eventOrdinal,
                            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                            roleOrdinal = roleOrdinal,
                            entityId = rawEvent.cardIds[roleOrdinal],
                        ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
                        candidates += candidate
                    }
                }

                is HandLookedAtEvent -> {
                    if (rawEvent.viewingPlayerId != projection.batch.perspectivePlayerId) {
                        return rejected(HistoryCFailureCode.PERSPECTIVE_MISMATCH)
                    }
                    for (roleOrdinal in rawEvent.cardIds.indices) {
                        val candidate = knownCandidate(
                            transition = transition,
                            eventOrdinal = eventOrdinal,
                            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                            roleOrdinal = roleOrdinal,
                            entityId = rawEvent.cardIds[roleOrdinal],
                        ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
                        candidates += candidate
                    }
                }

                is LookedAtCardsEvent -> {
                    if (rawEvent.playerId != projection.batch.perspectivePlayerId) {
                        return rejected(HistoryCFailureCode.PERSPECTIVE_MISMATCH)
                    }
                    for (roleOrdinal in rawEvent.cardIds.indices) {
                        val candidate = knownCandidate(
                            transition = transition,
                            eventOrdinal = eventOrdinal,
                            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                            roleOrdinal = roleOrdinal,
                            entityId = rawEvent.cardIds[roleOrdinal],
                        ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
                        candidates += candidate
                    }
                }

                is ZoneChangeEvent -> {
                    val candidate = zoneChangeCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        event = rawEvent,
                    ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
                    candidates += candidate
                }

                is TurnedFaceDownEvent -> {
                    val after = witness(transition.afterState, rawEvent.entityId)
                        ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
                    candidates += opaqueCandidate(
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        afterWitness = after,
                    )
                }

                else -> Unit
            }
        }
        return HistoryCReferenceEnvelopeProducerResult.Accepted(
            HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = projection.batch.perspectivePlayerId,
                candidates = candidates,
            ),
        )
    }

    private fun knownCandidate(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        role: HistoryCReferenceSlotRole,
        roleOrdinal: Int,
        entityId: EntityId,
    ): HistoryCReferenceCandidateV1? {
        val after = witness(transition.afterState, entityId)
        val before = witness(transition.beforeState, entityId)
        val definition = cardDefinition(transition.afterState, after)
            ?: cardDefinition(transition.beforeState, before)
            ?: return null
        return HistoryCReferenceCandidateV1(
            slot = HistoryCReferenceSlot(eventOrdinal, role, roleOrdinal),
            referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            beforeWitness = before,
            afterWitness = after,
            identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
            cardDefinitionId = definition,
            orderProof = HistoryCOrderProof(
                authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
                rank = roleOrdinal,
            ),
            semanticDescriptor = descriptor(),
        )
    }

    private fun zoneChangeCandidate(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        event: ZoneChangeEvent,
    ): HistoryCReferenceCandidateV1? {
        val before = witness(transition.beforeState, event.entityId)
        val after = witness(transition.afterState, event.entityId)
        if (before == null && after == null) return null
        val referenceKind = if (event.fromZone == Zone.STACK || event.toZone == Zone.STACK) {
            HistoryCReferenceKind.STACK_OBJECT
        } else {
            HistoryCReferenceKind.CARD_OR_RULES_OBJECT
        }
        return HistoryCReferenceCandidateV1(
            slot = HistoryCReferenceSlot(
                eventOrdinal = eventOrdinal,
                role = HistoryCReferenceSlotRole.MOVED_OBJECT,
            ),
            referenceKind = referenceKind,
            beforeWitness = before,
            afterWitness = after,
            identityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
            orderProof = HistoryCOrderProof(
                authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
                rank = 0,
            ),
            semanticDescriptor = descriptor(),
        )
    }

    private fun opaqueCandidate(
        eventOrdinal: Int,
        role: HistoryCReferenceSlotRole,
        afterWitness: HistoryCObjectWitness,
    ): HistoryCReferenceCandidateV1 = HistoryCReferenceCandidateV1(
        slot = HistoryCReferenceSlot(eventOrdinal, role),
        referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        afterWitness = afterWitness,
        identityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
        orderProof = HistoryCOrderProof(
            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
            rank = 0,
        ),
        semanticDescriptor = descriptor(),
    )

    private fun emittedRawEvents(
        events: List<GameEvent>,
        classifications: List<PerspectiveEventClassification>,
    ): List<GameEvent> {
        val result = mutableListOf<GameEvent>()
        for ((rawIndex, classification) in classifications.withIndex()) {
            if (classification.disposition == PerspectiveEventDisposition.EMITTED) {
                result += events.getOrNull(rawIndex)
                    ?: error("History-C producer classification exceeds committed raw events")
            }
        }
        return result
    }

    private fun witness(state: GameState, entityId: EntityId): HistoryCObjectWitness? =
        if (state.hasEntity(entityId)) {
            state.objectIdentityStamps[entityId]?.let { stamp ->
                HistoryCObjectWitness(entityId, stamp)
            }
        } else {
            null
        }

    private fun cardDefinition(
        state: GameState,
        witness: HistoryCObjectWitness?,
    ): String? = witness?.let { state.getEntity(it.entityId)?.get<CardComponent>()?.cardDefinitionId }

    private fun descriptor() = buildJsonObject { put("type", "object_reference") }

    private fun rejected(code: HistoryCFailureCode): HistoryCReferenceEnvelopeProducerResult.Rejected =
        HistoryCReferenceEnvelopeProducerResult.Rejected(HistoryCFailure(code))
}

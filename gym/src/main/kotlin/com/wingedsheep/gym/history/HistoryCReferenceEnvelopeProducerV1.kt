package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AbilityActivatedEvent
import com.wingedsheep.engine.core.AbilityResolvedEvent
import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.core.BecomesTargetEvent
import com.wingedsheep.engine.core.BlockersDeclaredEvent
import com.wingedsheep.engine.core.CardCycledEvent
import com.wingedsheep.engine.core.CardRevealedFromDrawEvent
import com.wingedsheep.engine.core.CardsDiscardedEvent
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.CommitCrimeEvent
import com.wingedsheep.engine.core.CountersAddedEvent
import com.wingedsheep.engine.core.CountersRemovedEvent
import com.wingedsheep.engine.core.CreatureDestroyedEvent
import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageAssignedEvent
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.HandRevealedEvent
import com.wingedsheep.engine.core.KeywordGrantedEvent
import com.wingedsheep.engine.core.LandPlayedEvent
import com.wingedsheep.engine.core.LandTappedForManaEvent
import com.wingedsheep.engine.core.LookedAtCardsEvent
import com.wingedsheep.engine.core.ManaAddedEvent
import com.wingedsheep.engine.core.ResolvedEvent
import com.wingedsheep.engine.core.PermanentAttachedEvent
import com.wingedsheep.engine.core.PermanentUnattachedEvent
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.core.SpellCopiedEvent
import com.wingedsheep.engine.core.StatsModifiedEvent
import com.wingedsheep.engine.core.TurnFaceUpEvent
import com.wingedsheep.engine.core.TappedEvent
import com.wingedsheep.engine.core.TurnedFaceDownEvent
import com.wingedsheep.engine.core.TransformedEvent
import com.wingedsheep.engine.core.TargetsChosenEvent
import com.wingedsheep.engine.core.UntappedEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventClassification
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
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
 * Generic internal producer for raw event families whose A/C authority is frozen.
 *
 * The matrix below is deliberate: a family whose A payload is object-bearing must either produce
 * exact event-bound candidates or reject with typed missing-authority evidence. A scalar-only A
 * family produces no candidates by design. This prevents a new object-bearing family from being
 * silently accepted as a reference-free History-D entry.
 */
internal object HistoryCReferenceEnvelopeProducerV1 {
    private val referenceBearingFamilies = setOf(
        PerspectiveEventFamily.SPELL_CAST,
        PerspectiveEventFamily.ABILITY_ACTIVATED,
        PerspectiveEventFamily.ABILITY_TRIGGERED,
        PerspectiveEventFamily.ABILITY_RESOLVED,
        PerspectiveEventFamily.LAND_PLAYED,
        PerspectiveEventFamily.ZONE_CHANGED,
        PerspectiveEventFamily.TAPPED,
        PerspectiveEventFamily.UNTAPPED,
        PerspectiveEventFamily.PERMANENT_ATTACHED,
        PerspectiveEventFamily.PERMANENT_UNATTACHED,
        PerspectiveEventFamily.LAND_TAPPED_FOR_MANA,
        PerspectiveEventFamily.COUNTERS_ADDED,
        PerspectiveEventFamily.COUNTERS_REMOVED,
        PerspectiveEventFamily.STATS_MODIFIED,
        PerspectiveEventFamily.KEYWORD_GRANTED,
        PerspectiveEventFamily.TARGETS_CHOSEN,
        PerspectiveEventFamily.BECAME_TARGET,
        PerspectiveEventFamily.COMMIT_CRIME,
        PerspectiveEventFamily.CREATURE_DESTROYED,
        PerspectiveEventFamily.DAMAGE_TO_OBJECT,
        PerspectiveEventFamily.CARD_REVEALED_FROM_DRAW,
        PerspectiveEventFamily.CARDS_DISCARDED,
        PerspectiveEventFamily.CARD_CYCLED,
        PerspectiveEventFamily.CARDS_DRAWN,
        PerspectiveEventFamily.PUBLIC_HAND_REVEALED,
        PerspectiveEventFamily.PUBLIC_CARDS_REVEALED,
        PerspectiveEventFamily.PRIVATE_HAND_LOOKED_AT,
        PerspectiveEventFamily.PRIVATE_CARDS_LOOKED_AT,
        PerspectiveEventFamily.ATTACKERS_DECLARED,
        PerspectiveEventFamily.BLOCKERS_DECLARED,
        PerspectiveEventFamily.DAMAGE_ASSIGNED,
        PerspectiveEventFamily.TURNED_FACE_UP,
        PerspectiveEventFamily.TURNED_FACE_DOWN,
        PerspectiveEventFamily.TRANSFORMED,
        PerspectiveEventFamily.SPELL_COPIED,
        PerspectiveEventFamily.RESOLVED,
    )

    fun produce(
        transition: CommittedRulesTransition,
        projection: PerspectiveEventProjectionResult,
    ): HistoryCReferenceEnvelopeProducerResult {
        if (!projection.isComplete) {
            return rejected(HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE)
        }

        val rawEvents = emittedRawEvents(transition.events, projection.classifications)
        val candidates = mutableListOf<HistoryCReferenceCandidateV1>()
        val relations = mutableListOf<HistoryCReferenceRelationEvidenceV1>()
        for ((eventOrdinal, rawEvent) in rawEvents.withIndex()) {
            val family = projection.batch.entries[eventOrdinal].eventFamily
            val eventCandidates = when (rawEvent) {
                is AbilityActivatedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.SOURCE,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.sourceId,
                        endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
                    ),
                )

                is AbilityTriggeredEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.SOURCE,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.sourceId,
                    ),
                )

                is CommitCrimeEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.SOURCE,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.sourceEntityId,
                        referenceKind = HistoryCReferenceKind.STACK_OBJECT,
                        endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
                    ),
                )

                is AbilityResolvedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.SOURCE,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.sourceId,
                    ),
                )

                is AttackersDeclaredEvent -> attackerCandidates(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    event = rawEvent,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is BlockersDeclaredEvent -> blockerCandidates(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    event = rawEvent,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is CardRevealedFromDrawEvent -> required(
                    knownCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        entityId = rawEvent.cardEntityId,
                    ),
                )

                is CardsDiscardedEvent -> requiredCollection(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    entityIds = rawEvent.cardIds,
                    role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is CardCycledEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.cardId,
                        endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
                    ),
                )

                is CardsDrawnEvent -> requiredCollection(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    entityIds = rawEvent.cardIds,
                    role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is CardsRevealedEvent -> requiredKnownCollection(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    entityIds = rawEvent.cardIds,
                    role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is CountersAddedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.entityId,
                    ),
                )

                is CountersRemovedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.entityId,
                    ),
                )

                is StatsModifiedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.targetId,
                    ),
                )

                is KeywordGrantedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.targetId,
                    ),
                )

                is CreatureDestroyedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.entityId,
                        endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
                    ),
                )

                is DamageDealtEvent -> if (rawEvent.targetIsPlayer) {
                    emptyList()
                } else {
                    damageDealtCandidates(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        event = rawEvent,
                    ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
                }

                is DamageAssignedEvent -> damageCandidates(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    event = rawEvent,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is HandLookedAtEvent -> {
                    if (rawEvent.viewingPlayerId != projection.batch.perspectivePlayerId) {
                        return rejected(HistoryCFailureCode.PERSPECTIVE_MISMATCH)
                    }
                    requiredKnownCollection(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        entityIds = rawEvent.cardIds,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                    ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
                }

                is HandRevealedEvent -> requiredKnownCollection(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    entityIds = rawEvent.cardIds,
                    role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is LandPlayedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.MOVED_OBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.cardId,
                    ),
                )

                is LandTappedForManaEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.landId,
                    ),
                )

                is LookedAtCardsEvent -> {
                    if (rawEvent.playerId != projection.batch.perspectivePlayerId) {
                        return rejected(HistoryCFailureCode.PERSPECTIVE_MISMATCH)
                    }
                    requiredKnownCollection(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        entityIds = rawEvent.cardIds,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                    ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
                }

                // ManaAddedEvent is scalar-only in the A contract. Its optional sourceId is
                // operational provenance, not a model-facing object reference authority.
                is ManaAddedEvent -> emptyList()

                is PermanentAttachedEvent -> pairedCandidates(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    first = ReferenceSpec(
                        entityId = rawEvent.attachmentId,
                        role = HistoryCReferenceSlotRole.SOURCE,
                        roleOrdinal = 0,
                    ),
                    second = ReferenceSpec(
                        entityId = rawEvent.attachedToId,
                        role = HistoryCReferenceSlotRole.TARGET,
                        roleOrdinal = 0,
                    ),
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is PermanentUnattachedEvent -> pairedCandidates(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    first = ReferenceSpec(
                        entityId = rawEvent.attachmentId,
                        role = HistoryCReferenceSlotRole.SOURCE,
                        roleOrdinal = 0,
                    ),
                    second = ReferenceSpec(
                        entityId = rawEvent.attachedToId,
                        role = HistoryCReferenceSlotRole.TARGET,
                        roleOrdinal = 0,
                    ),
                    endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is TargetsChosenEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.stackObjectId,
                        referenceKind = HistoryCReferenceKind.STACK_OBJECT,
                    ),
                )

                is BecomesTargetEvent -> targetCandidates(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    event = rawEvent,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is ResolvedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.entityId,
                        referenceKind = HistoryCReferenceKind.STACK_OBJECT,
                        endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
                    ),
                )

                is SpellCastEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.spellEntityId,
                        referenceKind = HistoryCReferenceKind.STACK_OBJECT,
                    ),
                )

                is SpellCopiedEvent -> spellCopyCandidates(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    event = rawEvent,
                ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)

                is TappedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.entityId,
                    ),
                )

                is TurnFaceUpEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.entityId,
                    ),
                )

                is TurnedFaceDownEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.entityId,
                    ),
                )

                is UntappedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.entityId,
                    ),
                )

                is TransformedEvent -> required(
                    opaqueCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        entityId = rawEvent.entityId,
                    ),
                )

                is ZoneChangeEvent -> required(
                    zoneChangeCandidate(
                        transition = transition,
                        eventOrdinal = eventOrdinal,
                        event = rawEvent,
                    ),
                )

                else -> emptyList()
            }
            if (eventCandidates.isEmpty() &&
                family in referenceBearingFamilies &&
                hasReferenceBearingObject(rawEvent)
            ) {
                return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
            }
            val candidateOffset = candidates.size
            candidates += eventCandidates
            when (rawEvent) {
                is AttackersDeclaredEvent -> {
                    val attackRelations = attackerRelations(
                        eventOrdinal = eventOrdinal,
                        event = rawEvent,
                        candidates = eventCandidates,
                        candidateOffset = candidateOffset,
                        perspectivePlayerId = projection.batch.perspectivePlayerId,
                        transition = transition,
                    ) ?: return rejected(HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA)
                    relations += attackRelations
                }

                is BlockersDeclaredEvent -> relations += blockerRelations(
                    eventOrdinal = eventOrdinal,
                    event = rawEvent,
                    candidates = eventCandidates,
                    candidateOffset = candidateOffset,
                )

                is DamageAssignedEvent -> relations += damageRelations(
                    eventOrdinal = eventOrdinal,
                    event = rawEvent,
                    candidates = eventCandidates,
                    candidateOffset = candidateOffset,
                )

                else -> Unit
            }
        }
        return HistoryCReferenceEnvelopeProducerResult.Accepted(
            HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = projection.batch.perspectivePlayerId,
                candidates = candidates,
                relations = relations,
            ),
        )
    }

    private fun required(candidate: HistoryCReferenceCandidateV1?): List<HistoryCReferenceCandidateV1> =
        listOfNotNull(candidate)

    private fun requiredCollection(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        entityIds: List<EntityId>,
        role: HistoryCReferenceSlotRole,
    ): List<HistoryCReferenceCandidateV1>? {
        val result = mutableListOf<HistoryCReferenceCandidateV1>()
        entityIds.forEachIndexed { roleOrdinal, entityId ->
            result += opaqueCandidate(
                transition = transition,
                eventOrdinal = eventOrdinal,
                role = role,
                roleOrdinal = roleOrdinal,
                rank = roleOrdinal,
                entityId = entityId,
            ) ?: return null
        }
        return result
    }

    private fun requiredKnownCollection(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        entityIds: List<EntityId>,
        role: HistoryCReferenceSlotRole,
    ): List<HistoryCReferenceCandidateV1>? {
        val result = mutableListOf<HistoryCReferenceCandidateV1>()
        entityIds.forEachIndexed { roleOrdinal, entityId ->
            result += knownCandidate(
                transition = transition,
                eventOrdinal = eventOrdinal,
                role = role,
                roleOrdinal = roleOrdinal,
                entityId = entityId,
            ) ?: return null
        }
        return result
    }

    private fun attackerCandidates(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        event: AttackersDeclaredEvent,
    ): List<HistoryCReferenceCandidateV1>? {
        val result = requiredCollection(
            transition = transition,
            eventOrdinal = eventOrdinal,
            entityIds = event.attackers,
            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
        )?.toMutableList() ?: return null
        val defenderIds = event.declaredAttacks
            .asSequence()
            .map { it.defenderId }
            .filter { hasCardOrRulesWitness(transition, it) }
            .distinct()
            .toList()
        defenderIds.forEachIndexed { roleOrdinal, defenderId ->
            result += opaqueCandidate(
                transition = transition,
                eventOrdinal = eventOrdinal,
                role = HistoryCReferenceSlotRole.TARGET,
                roleOrdinal = roleOrdinal,
                rank = event.attackers.size + roleOrdinal,
                entityId = defenderId,
            ) ?: return null
        }
        return result
    }

    private fun blockerCandidates(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        event: BlockersDeclaredEvent,
    ): List<HistoryCReferenceCandidateV1>? {
        val result = mutableListOf<HistoryCReferenceCandidateV1>()
        var rank = 0
        var blockerOrdinal = 0
        var targetOrdinal = 0
        event.blockers.forEach { (blockerId, attackerIds) ->
            result += opaqueCandidate(
                transition = transition,
                eventOrdinal = eventOrdinal,
                role = HistoryCReferenceSlotRole.SOURCE,
                roleOrdinal = blockerOrdinal++,
                rank = rank++,
                entityId = blockerId,
            ) ?: return null
            attackerIds.forEach { attackerId ->
                result += opaqueCandidate(
                    transition = transition,
                    eventOrdinal = eventOrdinal,
                    role = HistoryCReferenceSlotRole.TARGET,
                    roleOrdinal = targetOrdinal++,
                    rank = rank++,
                    entityId = attackerId,
                ) ?: return null
            }
        }
        return result
    }

    private fun damageCandidates(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        event: DamageAssignedEvent,
    ): List<HistoryCReferenceCandidateV1>? {
        val result = mutableListOf<HistoryCReferenceCandidateV1>()
        var rank = 0
        result += opaqueCandidate(
            transition = transition,
            eventOrdinal = eventOrdinal,
            role = HistoryCReferenceSlotRole.SOURCE,
            roleOrdinal = 0,
            rank = rank++,
            entityId = event.attackerId,
            endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
        ) ?: return null
        event.assignments.keys.forEachIndexed { targetOrdinal, targetId ->
            if (!hasCardOrRulesWitness(transition, targetId)) return@forEachIndexed
            result += opaqueCandidate(
                transition = transition,
                eventOrdinal = eventOrdinal,
                role = HistoryCReferenceSlotRole.TARGET,
                roleOrdinal = targetOrdinal,
                rank = rank++,
                entityId = targetId,
                endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
            ) ?: return null
        }
        return result
    }

    private fun damageDealtCandidates(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        event: DamageDealtEvent,
    ): List<HistoryCReferenceCandidateV1>? {
        val result = mutableListOf<HistoryCReferenceCandidateV1>()
        var rank = 0
        event.sourceId?.let { sourceId ->
            result += opaqueCandidate(
                transition = transition,
                eventOrdinal = eventOrdinal,
                role = HistoryCReferenceSlotRole.SOURCE,
                roleOrdinal = 0,
                rank = rank++,
                entityId = sourceId,
                endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
            ) ?: return null
        }
        result += opaqueCandidate(
            transition = transition,
            eventOrdinal = eventOrdinal,
            role = HistoryCReferenceSlotRole.TARGET,
            roleOrdinal = 0,
            rank = rank,
            entityId = event.targetId,
            endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
        ) ?: return null
        return result
    }

    private fun pairedCandidates(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        first: ReferenceSpec,
        second: ReferenceSpec,
        endpointAuthority: HistoryCReferenceEndpointAuthority =
            HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
    ): List<HistoryCReferenceCandidateV1>? = listOf(first, second)
        .mapIndexed { rank, spec ->
            opaqueCandidate(
                transition = transition,
                eventOrdinal = eventOrdinal,
                role = spec.role,
                roleOrdinal = spec.roleOrdinal,
                rank = rank,
                entityId = spec.entityId,
                referenceKind = spec.referenceKind,
                endpointAuthority = endpointAuthority,
            ) ?: return null
        }

    private fun targetCandidates(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        event: BecomesTargetEvent,
    ): List<HistoryCReferenceCandidateV1>? {
        val result = mutableListOf<HistoryCReferenceCandidateV1>()
        var rank = 0
        result += opaqueCandidate(
            transition = transition,
            eventOrdinal = eventOrdinal,
            role = HistoryCReferenceSlotRole.SOURCE,
            roleOrdinal = 0,
            rank = rank++,
            entityId = event.sourceEntityId,
        ) ?: return null
        if (!event.targetIsPlayer) {
            result += opaqueCandidate(
                transition = transition,
                eventOrdinal = eventOrdinal,
                role = HistoryCReferenceSlotRole.TARGET,
                roleOrdinal = 0,
                rank = rank,
                entityId = event.targetEntityId,
            ) ?: return null
        }
        return result
    }

    private fun blockerRelations(
        eventOrdinal: Int,
        event: BlockersDeclaredEvent,
        candidates: List<HistoryCReferenceCandidateV1>,
        candidateOffset: Int,
    ): List<HistoryCReferenceRelationEvidenceV1> = buildList {
        event.blockers.forEach { (blockerId, attackerIds) ->
            val source = candidates.indexOfFirst { candidate ->
                candidate.slot.role == HistoryCReferenceSlotRole.SOURCE &&
                    candidateEntityId(candidate) == blockerId
            }
            if (source < 0) return@forEach
            attackerIds.forEach { attackerId ->
                val target = candidates.indexOfFirst { candidate ->
                    candidate.slot.role == HistoryCReferenceSlotRole.TARGET &&
                        candidateEntityId(candidate) == attackerId
                }
                if (target >= 0) {
                    add(
                        HistoryCReferenceRelationEvidenceV1(
                            eventOrdinal = eventOrdinal,
                            kind = HistoryCReferenceRelationKindV1.BLOCKS,
                            sourceCandidateIndex = candidateOffset + source,
                            targetCandidateIndex = candidateOffset + target,
                        ),
                    )
                }
            }
        }
    }

    private fun attackerRelations(
        eventOrdinal: Int,
        event: AttackersDeclaredEvent,
        candidates: List<HistoryCReferenceCandidateV1>,
        candidateOffset: Int,
        perspectivePlayerId: EntityId,
        transition: CommittedRulesTransition,
    ): List<HistoryCReferenceRelationEvidenceV1>? = buildList {
        for (declaredAttack in event.declaredAttacks) {
            val source = candidates.indexOfFirst { candidate ->
                candidate.slot.role == HistoryCReferenceSlotRole.EVENT_SUBJECT &&
                    candidateEntityId(candidate) == declaredAttack.attackerId
            }
            if (source < 0) return null

            if (hasCardOrRulesWitness(transition, declaredAttack.defenderId)) {
                val target = candidates.indexOfFirst { candidate ->
                    candidate.slot.role == HistoryCReferenceSlotRole.TARGET &&
                        candidateEntityId(candidate) == declaredAttack.defenderId
                }
                if (target < 0) return null
                add(
                    HistoryCReferenceRelationEvidenceV1(
                        eventOrdinal = eventOrdinal,
                        kind = HistoryCReferenceRelationKindV1.ATTACKS_DEFENDER,
                        sourceCandidateIndex = candidateOffset + source,
                        targetCandidateIndex = candidateOffset + target,
                    ),
                )
            } else {
                val defendingPlayerId = declaredAttack.defendingPlayerId ?: return null
                add(
                    HistoryCReferenceRelationEvidenceV1(
                        eventOrdinal = eventOrdinal,
                        kind = HistoryCReferenceRelationKindV1.ATTACKS_DEFENDER,
                        sourceCandidateIndex = candidateOffset + source,
                        targetPlayerRole = perspectivePlayerRole(
                            defendingPlayerId,
                            perspectivePlayerId,
                        ),
                    ),
                )
            }
        }
    }

    private fun perspectivePlayerRole(playerId: EntityId, perspectivePlayerId: EntityId): String =
        if (playerId == perspectivePlayerId) "SELF" else "OTHER"

    private fun damageRelations(
        eventOrdinal: Int,
        event: DamageAssignedEvent,
        candidates: List<HistoryCReferenceCandidateV1>,
        candidateOffset: Int,
    ): List<HistoryCReferenceRelationEvidenceV1> {
        val source = candidates.indexOfFirst { candidate ->
            candidate.slot.role == HistoryCReferenceSlotRole.SOURCE &&
                candidateEntityId(candidate) == event.attackerId
        }
        if (source < 0) return emptyList()
        return event.assignments.mapNotNull { (targetId, amount) ->
            val target = candidates.indexOfFirst { candidate ->
                candidate.slot.role == HistoryCReferenceSlotRole.TARGET &&
                    candidateEntityId(candidate) == targetId
            }
            if (target < 0) return@mapNotNull null
            HistoryCReferenceRelationEvidenceV1(
                eventOrdinal = eventOrdinal,
                kind = HistoryCReferenceRelationKindV1.DAMAGE_ASSIGNED,
                sourceCandidateIndex = candidateOffset + source,
                targetCandidateIndex = candidateOffset + target,
                amount = amount,
            )
        }
    }

    private fun candidateEntityId(candidate: HistoryCReferenceCandidateV1): EntityId? =
        candidate.afterWitness?.entityId ?: candidate.beforeWitness?.entityId

    private fun spellCopyCandidates(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        event: SpellCopiedEvent,
    ): List<HistoryCReferenceCandidateV1>? {
        val result = mutableListOf<HistoryCReferenceCandidateV1>()
        result += opaqueCandidate(
            transition = transition,
            eventOrdinal = eventOrdinal,
            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            roleOrdinal = 0,
            rank = 0,
            entityId = event.copyEntityId,
            referenceKind = HistoryCReferenceKind.STACK_OBJECT,
        ) ?: return null
        event.originalSpellId?.let { originalSpellId ->
            result += opaqueCandidate(
                transition = transition,
                eventOrdinal = eventOrdinal,
                role = HistoryCReferenceSlotRole.SOURCE,
                roleOrdinal = 0,
                rank = 1,
                entityId = originalSpellId,
                referenceKind = HistoryCReferenceKind.STACK_OBJECT,
            ) ?: return null
        }
        return result
    }

    private fun knownCandidate(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        role: HistoryCReferenceSlotRole,
        roleOrdinal: Int,
        entityId: EntityId,
    ): HistoryCReferenceCandidateV1? {
        val after = witness(transition.afterState, entityId)
        val definition = cardDefinition(transition.afterState, after) ?: return null
        return HistoryCReferenceCandidateV1(
            slot = HistoryCReferenceSlot(eventOrdinal, role, roleOrdinal),
            referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            afterWitness = after,
            identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
            cardDefinitionId = definition,
            orderProof = HistoryCOrderProof(
                authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
                rank = roleOrdinal,
            ),
            semanticDescriptor = descriptor(),
            endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
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
            endpointAuthority = HistoryCReferenceEndpointAuthority.ZONE_TRANSITION_PAIR,
        )
    }

    private fun opaqueCandidate(
        transition: CommittedRulesTransition,
        eventOrdinal: Int,
        role: HistoryCReferenceSlotRole,
        roleOrdinal: Int,
        rank: Int,
        entityId: EntityId,
        referenceKind: HistoryCReferenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        endpointAuthority: HistoryCReferenceEndpointAuthority =
            HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
    ): HistoryCReferenceCandidateV1? {
        val after = witness(transition.afterState, entityId)
        val before = witness(transition.beforeState, entityId)
        val selected = when (endpointAuthority) {
            HistoryCReferenceEndpointAuthority.BEFORE_OBJECT -> {
                if (before == null) return null
                before to null
            }

            HistoryCReferenceEndpointAuthority.AFTER_OBJECT -> {
                if (after == null) return null
                null to after
            }

            HistoryCReferenceEndpointAuthority.SAME_INCARNATION -> {
                if (before != null && after != null && before != after) return null
                if (after != null) null to after else before to null
            }

            HistoryCReferenceEndpointAuthority.ZONE_TRANSITION_PAIR -> {
                if (before == null && after == null) return null
                before to after
            }

            HistoryCReferenceEndpointAuthority.UNSPECIFIED -> {
                if (after != null) null to after else before to null
            }
        }
        if (selected.first == null && selected.second == null) return null
        return HistoryCReferenceCandidateV1(
            slot = HistoryCReferenceSlot(eventOrdinal, role, roleOrdinal),
            referenceKind = referenceKind,
            beforeWitness = selected.first,
            afterWitness = selected.second,
            identityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
            orderProof = HistoryCOrderProof(
                authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
                rank = rank,
            ),
            semanticDescriptor = descriptor(),
            endpointAuthority = endpointAuthority,
        )
    }

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

    private fun hasCardOrRulesWitness(
        transition: CommittedRulesTransition,
        entityId: EntityId,
    ): Boolean = listOf(transition.beforeState, transition.afterState).any { state ->
        witness(state, entityId) != null &&
            state.getEntity(entityId)?.get<CardComponent>() != null
    }

    private fun descriptor() = buildJsonObject { put("type", "object_reference") }

    private fun hasReferenceBearingObject(event: GameEvent): Boolean = when (event) {
        is AbilityResolvedEvent,
        is AbilityActivatedEvent,
        is AbilityTriggeredEvent,
        is BecomesTargetEvent,
        is BlockersDeclaredEvent,
        is CardCycledEvent,
        is CommitCrimeEvent,
        is CardRevealedFromDrawEvent,
        is CardsDiscardedEvent,
        is CardsDrawnEvent,
        is CardsRevealedEvent,
        is DamageDealtEvent,
        is DamageAssignedEvent,
        is CountersAddedEvent,
        is CountersRemovedEvent,
        is StatsModifiedEvent,
        is KeywordGrantedEvent,
        is CreatureDestroyedEvent,
        is HandLookedAtEvent,
        is HandRevealedEvent,
        is LandPlayedEvent,
        is LandTappedForManaEvent,
        is LookedAtCardsEvent,
        is PermanentAttachedEvent,
        is PermanentUnattachedEvent,
        is ResolvedEvent,
        is SpellCastEvent,
        is SpellCopiedEvent,
        is TargetsChosenEvent,
        is TurnFaceUpEvent,
        is TurnedFaceDownEvent,
        is TappedEvent,
        is TransformedEvent,
        is UntappedEvent,
        is ZoneChangeEvent,
        -> true

        is AttackersDeclaredEvent -> event.attackers.isNotEmpty() || event.declaredAttacks.isNotEmpty()

        is ManaAddedEvent -> false

        else -> false
    }

    private data class ReferenceSpec(
        val entityId: EntityId,
        val role: HistoryCReferenceSlotRole,
        val roleOrdinal: Int,
        val referenceKind: HistoryCReferenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
    )

    private fun rejected(code: HistoryCFailureCode): HistoryCReferenceEnvelopeProducerResult.Rejected =
        HistoryCReferenceEnvelopeProducerResult.Rejected(HistoryCFailure(code))
}

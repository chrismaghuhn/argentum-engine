package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AbilityActivatedEvent
import com.wingedsheep.engine.core.AbilityResolvedEvent
import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.core.BecomesTargetEvent
import com.wingedsheep.engine.core.BlockersDeclaredEvent
import com.wingedsheep.engine.core.CardRevealedFromDrawEvent
import com.wingedsheep.engine.core.CardsDiscardedEvent
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageAssignedEvent
import com.wingedsheep.engine.core.CountersAddedEvent
import com.wingedsheep.engine.core.CountersRemovedEvent
import com.wingedsheep.engine.core.CreatureDestroyedEvent
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.HandRevealedEvent
import com.wingedsheep.engine.core.LandPlayedEvent
import com.wingedsheep.engine.core.LandTappedForManaEvent
import com.wingedsheep.engine.core.LookedAtCardsEvent
import com.wingedsheep.engine.core.ManaAddedEvent
import com.wingedsheep.engine.core.PermanentAttachedEvent
import com.wingedsheep.engine.core.PermanentUnattachedEvent
import com.wingedsheep.engine.core.ResolvedEvent
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.core.SpellCopiedEvent
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
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.PerspectiveEventBatchV1
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Internal version of the typed History-C reference-candidate envelope. */
const val HISTORY_C_REFERENCE_EVIDENCE_V1_VERSION: Int = 1

/** Stable identity of the internal History-C evidence contract. */
const val HISTORY_C_REFERENCE_EVIDENCE_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-history-c-reference-evidence@v1"

/** Exact current Rules object witness. It never crosses a model-facing seam. */
internal data class HistoryCObjectWitness(
    val entityId: EntityId,
    val objectIdentityStamp: Long,
) {
    init {
        require(entityId.value.isNotBlank()) { "History-C object witness requires an entity" }
        require(objectIdentityStamp > 0L) {
            "History-C object witness requires a positive object-incarnation stamp"
        }
    }
}

/** Reference families that HISTC-A can carry without allocating a semantic alias. */
internal enum class HistoryCReferenceKind {
    CARD_OR_RULES_OBJECT,
    STACK_OBJECT,
}

/** Printed identity disclosure is independent from object addressability. */
internal enum class HistoryCIdentityDisclosure {
    OPAQUE,
    DEFINITION_KNOWN,
}

/** Typed semantic slot; runtime/source coordinates remain internal to the envelope. */
internal enum class HistoryCReferenceSlotRole {
    EVENT_SUBJECT,
    SOURCE,
    TARGET,
    MOVED_OBJECT,
}

internal data class HistoryCReferenceSlot(
    val eventOrdinal: Int,
    val role: HistoryCReferenceSlotRole,
    val roleOrdinal: Int = 0,
)

/** Public/producer order authorities allowed to feed a future allocator. */
internal enum class HistoryCOrderAuthority {
    EXPLICIT_PRODUCER_ORDER,
    PUBLIC_SEMANTIC_ORDER,
    EXPLICIT_PLAYER_ORDER,
}

internal data class HistoryCOrderProof(
    val authority: HistoryCOrderAuthority,
    val rank: Int,
)

/** One typed candidate, still before any perspective alias allocation. */
internal data class HistoryCReferenceCandidateV1(
    val slot: HistoryCReferenceSlot,
    val referenceKind: HistoryCReferenceKind,
    val beforeWitness: HistoryCObjectWitness? = null,
    val afterWitness: HistoryCObjectWitness? = null,
    val identityDisclosure: HistoryCIdentityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
    val cardDefinitionId: String? = null,
    val orderProof: HistoryCOrderProof,
    val semanticDescriptor: JsonObject,
)

/** Internal envelope supplied by a committed producer; it has no alias field. */
internal data class HistoryCReferenceEnvelopeV1(
    val version: Int = HISTORY_C_REFERENCE_EVIDENCE_V1_VERSION,
    val schemaIdentity: String = HISTORY_C_REFERENCE_EVIDENCE_V1_SCHEMA_IDENTITY,
    val perspectivePlayerId: EntityId,
    val candidates: List<HistoryCReferenceCandidateV1>,
)

/** Validated A+B-free evidence output; HISTC-B owns lifetime/allocation later. */
internal data class HistoryCReferenceEvidenceV1(
    val perspectivePlayerId: EntityId,
    val eventBatch: PerspectiveEventBatchV1,
    val candidates: List<HistoryCReferenceCandidateV1>,
)

/**
 * Validates the first History-C seam without creating aliases.
 *
 * The implementation accepts only an already committed A projection and witnesses that match the
 * exact before/after state held by that committed transition. The returned evidence is internal;
 * HISTC-B will own any future alias registry.
 */
internal object HistoryCReferenceAuthority {
    private val supportedReferenceKinds = setOf(
        HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        HistoryCReferenceKind.STACK_OBJECT,
    )

    private val forbiddenRuntimeKeys = setOf(
        "entityId",
        "entityIds",
        "sourceId",
        "sourceIds",
        "targetId",
        "targetIds",
        "cardId",
        "cardIds",
        "ownerId",
        "controllerId",
        "playerId",
        "objectIdentityStamp",
        "objectIdentityStamps",
    )

    fun validate(
        transition: CommittedRulesTransition,
        projection: PerspectiveEventProjectionResult,
        envelope: HistoryCReferenceEnvelopeV1,
    ): HistoryCReferenceAuthorityResult {
        when {
            envelope.version != HISTORY_C_REFERENCE_EVIDENCE_V1_VERSION ->
                return rejected(HistoryCFailureCode.UNKNOWN_REFERENCE_SCHEMA_VERSION)

            envelope.schemaIdentity != HISTORY_C_REFERENCE_EVIDENCE_V1_SCHEMA_IDENTITY ->
                return rejected(HistoryCFailureCode.UNKNOWN_REFERENCE_SCHEMA_IDENTITY)

            envelope.perspectivePlayerId != projection.batch.perspectivePlayerId ->
                return rejected(HistoryCFailureCode.PERSPECTIVE_MISMATCH)

            !projection.isComplete ->
                return rejected(HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE)
        }

        var previousOrder: CandidateOrder? = null
        for (candidate in envelope.candidates) {
            val failure = validateCandidate(
                transition = transition,
                projection = projection,
                candidate = candidate,
                previousOrder = previousOrder,
            )
            if (failure != null) return HistoryCReferenceAuthorityResult.Rejected(failure)
            previousOrder = CandidateOrder.from(candidate)
        }

        return HistoryCReferenceAuthorityResult.Accepted(
            HistoryCReferenceEvidenceV1(
                perspectivePlayerId = envelope.perspectivePlayerId,
                eventBatch = projection.batch,
                candidates = envelope.candidates.toList(),
            ),
        )
    }

    private fun validateCandidate(
        transition: CommittedRulesTransition,
        projection: PerspectiveEventProjectionResult,
        candidate: HistoryCReferenceCandidateV1,
        previousOrder: CandidateOrder?,
    ): HistoryCFailure? {
        if (candidate.referenceKind !in supportedReferenceKinds) {
            return HistoryCFailure(HistoryCFailureCode.UNSUPPORTED_REFERENCE_KIND)
        }

        val eventCount = projection.batch.entries.size
        if (candidate.slot.eventOrdinal !in 0 until eventCount || candidate.slot.roleOrdinal < 0) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_REFERENCE_SLOT)
        }

        if (candidate.beforeWitness == null && candidate.afterWitness == null) {
            return HistoryCFailure(HistoryCFailureCode.MISSING_EVENT_TIME_WITNESS)
        }

        val rawEvent = rawEventForProjectedOrdinal(transition, projection, candidate.slot.eventOrdinal)
            ?: return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_UNSUPPORTED)
        validateCandidateAgainstRawEvent(
            transition = transition,
            event = rawEvent,
            candidate = candidate,
            perspectivePlayerId = projection.batch.perspectivePlayerId,
        )?.let { return it }

        if (candidate.beforeWitness != null &&
            !containsWitness(transition.beforeState, candidate.beforeWitness)
        ) {
            return HistoryCFailure(HistoryCFailureCode.STALE_BEFORE_WITNESS)
        }

        if (candidate.afterWitness != null &&
            !containsWitness(transition.afterState, candidate.afterWitness)
        ) {
            return HistoryCFailure(HistoryCFailureCode.STALE_AFTER_WITNESS)
        }

        if (candidate.orderProof.rank < 0) {
            return HistoryCFailure(HistoryCFailureCode.MISSING_ORDER_AUTHORITY)
        }

        val order = CandidateOrder.from(candidate)
        if (previousOrder != null && order < previousOrder) {
            return HistoryCFailure(HistoryCFailureCode.MISSING_ORDER_AUTHORITY)
        }

        if (candidate.identityDisclosure == HistoryCIdentityDisclosure.OPAQUE &&
            candidate.cardDefinitionId != null
        ) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_IDENTITY_DISCLOSURE)
        }
        if (candidate.identityDisclosure == HistoryCIdentityDisclosure.DEFINITION_KNOWN &&
            candidate.cardDefinitionId.isNullOrBlank()
        ) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_IDENTITY_DISCLOSURE)
        }

        return validateSemanticDescriptor(candidate.semanticDescriptor)
    }

    /** Map A's projected ordinal back to the exact raw event without exposing that coordinate. */
    private fun rawEventForProjectedOrdinal(
        transition: CommittedRulesTransition,
        projection: PerspectiveEventProjectionResult,
        projectedOrdinal: Int,
    ): GameEvent? {
        var emittedOrdinal = 0
        for ((rawIndex, classification) in projection.classifications.withIndex()) {
            if (classification.disposition != PerspectiveEventDisposition.EMITTED) continue
            if (emittedOrdinal == projectedOrdinal) return transition.events.getOrNull(rawIndex)
            emittedOrdinal++
        }
        return null
    }

    private fun validateCandidateAgainstRawEvent(
        transition: CommittedRulesTransition,
        event: GameEvent,
        candidate: HistoryCReferenceCandidateV1,
        perspectivePlayerId: EntityId,
    ): HistoryCFailure? = when (event) {
        is AbilityActivatedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.sourceId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.SOURCE,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is AbilityTriggeredEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.sourceId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.SOURCE,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is AbilityResolvedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.sourceId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.SOURCE,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is AttackersDeclaredEvent -> validateCollectionCandidate(
            transition = transition,
            expected = event.attackers.mapIndexed { index, entityId ->
                ExpectedReference(
                    entityId = entityId,
                    role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                    roleOrdinal = index,
                    rank = index,
                )
            },
            candidate = candidate,
        )

        is BlockersDeclaredEvent -> validateCollectionCandidate(
            transition = transition,
            expected = blockerReferences(event),
            candidate = candidate,
        )

        is BecomesTargetEvent -> validateCollectionCandidate(
            transition = transition,
            expected = buildList {
                add(
                    ExpectedReference(
                        entityId = event.sourceEntityId,
                        role = HistoryCReferenceSlotRole.SOURCE,
                        roleOrdinal = 0,
                        rank = 0,
                    ),
                )
                if (!event.targetIsPlayer) {
                    add(
                        ExpectedReference(
                            entityId = event.targetEntityId,
                            role = HistoryCReferenceSlotRole.TARGET,
                            roleOrdinal = 0,
                            rank = 1,
                        ),
                    )
                }
            },
            candidate = candidate,
        )

        is CardRevealedFromDrawEvent -> validateCollectionCandidate(
            transition = transition,
            expected = listOf(
                ExpectedReference(
                    entityId = event.cardEntityId,
                    role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                    roleOrdinal = 0,
                    rank = 0,
                ),
            ),
            candidate = candidate,
        )

        is CardsDiscardedEvent -> validateCollectionCandidate(
            transition = transition,
            expected = event.cardIds.mapIndexed { index, entityId ->
                ExpectedReference(
                    entityId = entityId,
                    role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                    roleOrdinal = index,
                    rank = index,
                )
            },
            candidate = candidate,
        )

        is CardsDrawnEvent -> validateCollectionCandidate(
            transition = transition,
            expected = event.cardIds.mapIndexed { index, entityId ->
                ExpectedReference(
                    entityId = entityId,
                    role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                    roleOrdinal = index,
                    rank = index,
                )
            },
            candidate = candidate,
        )

        is DamageDealtEvent -> if (event.targetIsPlayer) {
            HistoryCFailure(HistoryCFailureCode.RAW_EVENT_ORDER_AUTHORITY_MISMATCH)
        } else {
            validateCollectionCandidate(
                transition = transition,
                expected = buildList {
                    var rank = 0
                    event.sourceId?.let { sourceId ->
                        add(
                            ExpectedReference(
                                entityId = sourceId,
                                role = HistoryCReferenceSlotRole.SOURCE,
                                roleOrdinal = 0,
                                rank = rank++,
                            ),
                        )
                    }
                    add(
                        ExpectedReference(
                            entityId = event.targetId,
                            role = HistoryCReferenceSlotRole.TARGET,
                            roleOrdinal = 0,
                            rank = rank,
                        ),
                    )
                },
                candidate = candidate,
            )
        }

        is CardsRevealedEvent -> validateCardsRevealedCandidate(transition, event, candidate)

        is CountersAddedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is CountersRemovedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is CreatureDestroyedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )
        is HandLookedAtEvent -> validateSingleObjectLookCandidate(
            transition = transition,
            perspectivePlayerId = perspectivePlayerId,
            viewingPlayerId = event.viewingPlayerId,
            cardIds = event.cardIds,
            candidate = candidate,
        )

        is LookedAtCardsEvent -> validateSingleObjectLookCandidate(
            transition = transition,
            perspectivePlayerId = perspectivePlayerId,
            viewingPlayerId = event.playerId,
            cardIds = event.cardIds,
            candidate = candidate,
        )

        is HandRevealedEvent -> validateCollectionCandidate(
            transition = transition,
            expected = event.cardIds.mapIndexed { index, entityId ->
                ExpectedReference(
                    entityId = entityId,
                    role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                    roleOrdinal = index,
                    rank = index,
                )
            },
            candidate = candidate,
        )

        is LandPlayedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.cardId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.MOVED_OBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is LandTappedForManaEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.landId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is ManaAddedEvent -> event.sourceId?.let { sourceId ->
            validateSingleObjectCandidate(
                transition = transition,
                eventEntityId = sourceId,
                eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
                eventRole = HistoryCReferenceSlotRole.SOURCE,
                candidate = candidate,
                identityMustBeOpaque = false,
            )
        } ?: HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_UNSUPPORTED)

        is PermanentAttachedEvent -> validateCollectionCandidate(
            transition = transition,
            expected = listOf(
                ExpectedReference(
                    entityId = event.attachmentId,
                    role = HistoryCReferenceSlotRole.SOURCE,
                    roleOrdinal = 0,
                    rank = 0,
                ),
                ExpectedReference(
                    entityId = event.attachedToId,
                    role = HistoryCReferenceSlotRole.TARGET,
                    roleOrdinal = 0,
                    rank = 1,
                ),
            ),
            candidate = candidate,
        )

        is PermanentUnattachedEvent -> validateCollectionCandidate(
            transition = transition,
            expected = listOf(
                ExpectedReference(
                    entityId = event.attachmentId,
                    role = HistoryCReferenceSlotRole.SOURCE,
                    roleOrdinal = 0,
                    rank = 0,
                ),
                ExpectedReference(
                    entityId = event.attachedToId,
                    role = HistoryCReferenceSlotRole.TARGET,
                    roleOrdinal = 0,
                    rank = 1,
                ),
            ),
            candidate = candidate,
        )

        is TargetsChosenEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.stackObjectId,
            eventKind = HistoryCReferenceKind.STACK_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is TurnedFaceDownEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = true,
        )

        is ResolvedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = HistoryCReferenceKind.STACK_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is SpellCastEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.spellEntityId,
            eventKind = HistoryCReferenceKind.STACK_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is SpellCopiedEvent -> validateCollectionCandidate(
            transition = transition,
            expected = buildList {
                add(
                    ExpectedReference(
                        entityId = event.copyEntityId,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        roleOrdinal = 0,
                        rank = 0,
                        referenceKind = HistoryCReferenceKind.STACK_OBJECT,
                    ),
                )
                event.originalSpellId?.let { originalSpellId ->
                    add(
                        ExpectedReference(
                            entityId = originalSpellId,
                            role = HistoryCReferenceSlotRole.SOURCE,
                            roleOrdinal = 0,
                            rank = 1,
                            referenceKind = HistoryCReferenceKind.STACK_OBJECT,
                        ),
                    )
                }
            },
            candidate = candidate,
        )

        is TappedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is TurnFaceUpEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is UntappedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is TransformedEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            eventRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            candidate = candidate,
            identityMustBeOpaque = false,
        )

        is DamageAssignedEvent -> validateCollectionCandidate(
            transition = transition,
            expected = buildList {
                add(
                    ExpectedReference(
                        entityId = event.attackerId,
                        role = HistoryCReferenceSlotRole.SOURCE,
                        roleOrdinal = 0,
                        rank = 0,
                    ),
                )
                var targetRank = 1
                event.assignments.keys.forEachIndexed { targetOrdinal, targetId ->
                    if (hasCardOrRulesWitness(transition, targetId)) {
                        add(
                            ExpectedReference(
                                entityId = targetId,
                                role = HistoryCReferenceSlotRole.TARGET,
                                roleOrdinal = targetOrdinal,
                                rank = targetRank++,
                            ),
                        )
                    }
                }
            },
            candidate = candidate,
        )

        is ZoneChangeEvent -> validateSingleObjectCandidate(
            transition = transition,
            eventEntityId = event.entityId,
            eventKind = if (event.fromZone == com.wingedsheep.sdk.core.Zone.STACK ||
                event.toZone == com.wingedsheep.sdk.core.Zone.STACK
            ) {
                HistoryCReferenceKind.STACK_OBJECT
            } else {
                HistoryCReferenceKind.CARD_OR_RULES_OBJECT
            },
            eventRole = HistoryCReferenceSlotRole.MOVED_OBJECT,
            candidate = candidate,
            identityMustBeOpaque = true,
        )

        else -> HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_UNSUPPORTED)
    }

    private fun validateCollectionCandidate(
        transition: CommittedRulesTransition,
        expected: List<ExpectedReference>,
        candidate: HistoryCReferenceCandidateV1,
    ): HistoryCFailure? {
        if (candidate.orderProof.authority != HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_ORDER_AUTHORITY_MISMATCH)
        }
        val expectedReference = expected.firstOrNull { reference ->
            reference.role == candidate.slot.role &&
                reference.roleOrdinal == candidate.slot.roleOrdinal &&
                reference.rank == candidate.orderProof.rank
        } ?: return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH)
        if (candidate.referenceKind != expectedReference.referenceKind ||
            !candidateWitnessesEntity(candidate, expectedReference.entityId)
        ) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH)
        }
        return validateDefinitionAgainstWitnessState(transition, candidate)
    }

    private fun blockerReferences(event: BlockersDeclaredEvent): List<ExpectedReference> = buildList {
        var rank = 0
        var blockerOrdinal = 0
        var targetOrdinal = 0
        event.blockers.forEach { (blockerId, attackerIds) ->
            add(
                ExpectedReference(
                    entityId = blockerId,
                    role = HistoryCReferenceSlotRole.SOURCE,
                    roleOrdinal = blockerOrdinal++,
                    rank = rank++,
                ),
            )
            attackerIds.forEach { attackerId ->
                add(
                    ExpectedReference(
                        entityId = attackerId,
                        role = HistoryCReferenceSlotRole.TARGET,
                        roleOrdinal = targetOrdinal++,
                        rank = rank++,
                    ),
                )
            }
        }
    }

    private fun validateSingleObjectLookCandidate(
        transition: CommittedRulesTransition,
        perspectivePlayerId: EntityId,
        viewingPlayerId: EntityId,
        cardIds: List<EntityId>,
        candidate: HistoryCReferenceCandidateV1,
    ): HistoryCFailure? {
        if (viewingPlayerId != perspectivePlayerId || cardIds.isEmpty()) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_UNSUPPORTED)
        }
        if (candidate.referenceKind != HistoryCReferenceKind.CARD_OR_RULES_OBJECT ||
            candidate.slot.role != HistoryCReferenceSlotRole.EVENT_SUBJECT ||
            candidate.slot.roleOrdinal !in cardIds.indices ||
            candidate.orderProof.authority != HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER ||
            candidate.orderProof.rank != candidate.slot.roleOrdinal
        ) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH)
        }
        if (!candidateWitnessesEntity(candidate, cardIds[candidate.slot.roleOrdinal])) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH)
        }
        return validateDefinitionAgainstWitnessState(transition, candidate)
    }

    private fun validateCardsRevealedCandidate(
        transition: CommittedRulesTransition,
        event: CardsRevealedEvent,
        candidate: HistoryCReferenceCandidateV1,
    ): HistoryCFailure? {
        if (candidate.orderProof.authority != HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_ORDER_AUTHORITY_MISMATCH)
        }
        if (candidate.referenceKind != HistoryCReferenceKind.CARD_OR_RULES_OBJECT ||
            candidate.slot.role !in setOf(
                HistoryCReferenceSlotRole.EVENT_SUBJECT,
                HistoryCReferenceSlotRole.MOVED_OBJECT,
            ) ||
            candidate.slot.roleOrdinal !in event.cardIds.indices ||
            candidate.orderProof.authority != HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER ||
            candidate.orderProof.rank != candidate.slot.roleOrdinal
        ) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH)
        }

        val expectedEntityId = event.cardIds[candidate.slot.roleOrdinal]
        if (!candidateWitnessesEntity(candidate, expectedEntityId)) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH)
        }
        return validateDefinitionAgainstWitnessState(transition, candidate)
    }

    private fun validateSingleObjectCandidate(
        transition: CommittedRulesTransition,
        eventEntityId: EntityId,
        eventKind: HistoryCReferenceKind,
        eventRole: HistoryCReferenceSlotRole,
        candidate: HistoryCReferenceCandidateV1,
        identityMustBeOpaque: Boolean,
    ): HistoryCFailure? {
        if (candidate.orderProof.authority != HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_ORDER_AUTHORITY_MISMATCH)
        }
        if (candidate.referenceKind != eventKind ||
            candidate.slot.role != eventRole ||
            candidate.slot.roleOrdinal != 0 ||
            candidate.orderProof.authority != HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER ||
            candidate.orderProof.rank != 0 ||
            (identityMustBeOpaque &&
                candidate.identityDisclosure != HistoryCIdentityDisclosure.OPAQUE)
        ) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH)
        }
        if (!candidateWitnessesEntity(candidate, eventEntityId)) {
            return HistoryCFailure(HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH)
        }
        return validateDefinitionAgainstWitnessState(transition, candidate)
    }

    private fun candidateWitnessesEntity(
        candidate: HistoryCReferenceCandidateV1,
        expectedEntityId: EntityId,
    ): Boolean = listOfNotNull(candidate.beforeWitness, candidate.afterWitness)
        .all { it.entityId == expectedEntityId }

    private fun validateDefinitionAgainstWitnessState(
        transition: CommittedRulesTransition,
        candidate: HistoryCReferenceCandidateV1,
    ): HistoryCFailure? {
        val expectedDefinition = candidate.cardDefinitionId ?: return null
        val witness = candidate.afterWitness ?: candidate.beforeWitness
            ?: return HistoryCFailure(HistoryCFailureCode.MISSING_EVENT_TIME_WITNESS)
        val state = if (candidate.afterWitness != null &&
            containsWitness(transition.afterState, witness)
        ) {
            transition.afterState
        } else {
            transition.beforeState
        }
        val actualDefinition = state.getEntity(witness.entityId)?.get<CardComponent>()?.cardDefinitionId
        return if (actualDefinition == expectedDefinition) {
            null
        } else {
            HistoryCFailure(HistoryCFailureCode.RAW_EVENT_DEFINITION_MISMATCH)
        }
    }

    private fun containsWitness(state: GameState, witness: HistoryCObjectWitness): Boolean =
        state.hasEntity(witness.entityId) &&
            state.objectIdentityStamps[witness.entityId] == witness.objectIdentityStamp

    private fun hasCardOrRulesWitness(
        transition: CommittedRulesTransition,
        entityId: EntityId,
    ): Boolean = listOf(transition.beforeState, transition.afterState).any { state ->
        val stamp = state.objectIdentityStamps[entityId] ?: return@any false
        containsWitness(state, HistoryCObjectWitness(entityId, stamp)) &&
            state.getEntity(entityId)?.get<CardComponent>() != null
    }

    private fun validateSemanticDescriptor(descriptor: JsonObject): HistoryCFailure? {
        try {
            A3SemanticJson.requireSemanticObject(descriptor, "History-C semantic descriptor")
            A3SemanticJson.requireNoOpaqueTriggerHandles(descriptor, "History-C semantic descriptor")
        } catch (_: IllegalArgumentException) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_SEMANTIC_DESCRIPTOR)
        }

        if (containsRuntimeIdentityKey(descriptor)) {
            return HistoryCFailure(HistoryCFailureCode.RAW_RUNTIME_ID_AT_SEMANTIC_SEAM)
        }
        return null
    }

    private fun containsRuntimeIdentityKey(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.entries.any { (key, value) ->
            key in forbiddenRuntimeKeys ||
                key.endsWith("Id", ignoreCase = true) ||
                key.endsWith("Ids", ignoreCase = true) ||
                key.contains("ordinal", ignoreCase = true) ||
                key.contains("coordinate", ignoreCase = true) ||
                containsRuntimeIdentityKey(value)
        }

        is JsonArray -> element.any(::containsRuntimeIdentityKey)
        else -> false
    }

    private data class ExpectedReference(
        val entityId: EntityId,
        val role: HistoryCReferenceSlotRole,
        val roleOrdinal: Int,
        val rank: Int,
        val referenceKind: HistoryCReferenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
    )

    private fun rejected(code: HistoryCFailureCode): HistoryCReferenceAuthorityResult.Rejected =
        HistoryCReferenceAuthorityResult.Rejected(HistoryCFailure(code))

    private data class CandidateOrder(
        val eventOrdinal: Int,
        val rank: Int,
        val role: Int,
        val roleOrdinal: Int,
    ) : Comparable<CandidateOrder> {
        override fun compareTo(other: CandidateOrder): Int = compareValuesBy(
            this,
            other,
            CandidateOrder::eventOrdinal,
            CandidateOrder::rank,
            CandidateOrder::role,
            CandidateOrder::roleOrdinal,
        )

        companion object {
            fun from(candidate: HistoryCReferenceCandidateV1): CandidateOrder = CandidateOrder(
                eventOrdinal = candidate.slot.eventOrdinal,
                rank = candidate.orderProof.rank,
                role = candidate.slot.role.ordinal,
                roleOrdinal = candidate.slot.roleOrdinal,
            )
        }
    }
}

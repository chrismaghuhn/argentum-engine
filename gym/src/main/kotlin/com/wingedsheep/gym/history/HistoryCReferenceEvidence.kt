package com.wingedsheep.gym.history

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.PerspectiveEventBatchV1
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

    private fun containsWitness(state: GameState, witness: HistoryCObjectWitness): Boolean =
        state.hasEntity(witness.entityId) &&
            state.objectIdentityStamps[witness.entityId] == witness.objectIdentityStamp

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

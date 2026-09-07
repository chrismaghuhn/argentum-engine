package com.wingedsheep.gym.history

import com.wingedsheep.engine.mechanics.KnownInformationLedger
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.KnownInformationFactKind
import com.wingedsheep.engine.state.components.player.KnownInformationFactV1
import com.wingedsheep.engine.state.components.player.KnownInformationLedgerComponentV1
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/** Pure result of one committed, perspective-local History-C projection. */
internal data class PerspectiveReferenceProjectionV1(
    val nextRegistry: PerspectiveAliasRegistryV1,
    val referenceOccurrences: List<PerspectiveAliasAssignment>,
    val incarnationRelations: List<PerspectiveIncarnationRelationV1>,
)

internal sealed interface PerspectiveReferenceProjectionResult {
    data class Accepted(
        val projection: PerspectiveReferenceProjectionV1,
    ) : PerspectiveReferenceProjectionResult

    data class Rejected(
        val failure: HistoryCFailure,
    ) : PerspectiveReferenceProjectionResult
}

/**
 * Deep internal seam joining committed History-A evidence, event-time visibility, the merged
 * Rules-owned History-B ledger, and the immutable HISTC-B alias registry.
 *
 * The projector has no mutable state and emits no cross-incarnation relation until a typed
 * producer-owned relation witness is available. A/B evidence is accepted input, not a public
 * caller-facing authority path; the committed-source adapter is responsible for obtaining it.
 */
internal class PerspectiveReferenceProjectorV1(
    cardRegistry: CardRegistry,
) {
    private val visibility = Visibility(cardRegistry)

    fun project(
        semanticEpisodeId: String,
        perspectivePlayerId: EntityId,
        transition: CommittedRulesTransition,
        evidence: HistoryCReferenceEvidenceV1,
        registry: PerspectiveAliasRegistryV1,
    ): PerspectiveReferenceProjectionResult {
        if (semanticEpisodeId.isBlank()) {
            return rejected(HistoryCFailureCode.EPISODE_MISMATCH)
        }
        if (transition.sourceStepCount <= 0) {
            return rejected(HistoryCFailureCode.UNCOMMITTED_TRANSITION)
        }
        if (perspectivePlayerId != evidence.perspectivePlayerId ||
            perspectivePlayerId != evidence.eventBatch.perspectivePlayerId
        ) {
            return rejected(HistoryCFailureCode.PERSPECTIVE_MISMATCH)
        }

        val registryCheck = when (
            val check = PerspectiveAliasAllocator.allocate(
                registry = registry,
                semanticEpisodeId = semanticEpisodeId,
                evidence = evidence.copy(candidates = emptyList()),
            )
        ) {
            is PerspectiveAliasAllocationResult.Rejected ->
                return PerspectiveReferenceProjectionResult.Rejected(check.failure)

            is PerspectiveAliasAllocationResult.Accepted -> check
        }

        val beforeEndpoints = mutableListOf<Endpoint>()
        val afterEndpoints = mutableListOf<Endpoint>()
        for ((candidateIndex, candidate) in evidence.candidates.withIndex()) {
            val endpoints = endpointsFor(candidateIndex, candidate, transition)
            for (endpoint in endpoints) {
                when (val decision = authorize(endpoint, perspectivePlayerId)) {
                    is EndpointDecision.Reference -> if (endpoint.isAfter) {
                        afterEndpoints += decision.endpoint
                    } else {
                        beforeEndpoints += decision.endpoint
                    }

                    EndpointDecision.Omit -> Unit
                    is EndpointDecision.Reject -> {
                        return PerspectiveReferenceProjectionResult.Rejected(decision.failure)
                    }
                }
            }
        }

        var nextRegistry = registryCheck.registry
        val occurrences = mutableListOf<PerspectiveAliasAssignment>()

        when (val before = allocateEndpoints(nextRegistry, beforeEndpoints, evidence, semanticEpisodeId)) {
            is EndpointAllocation.Accepted -> {
                nextRegistry = before.registry
                occurrences += before.assignments
            }

            is EndpointAllocation.Rejected -> return PerspectiveReferenceProjectionResult.Rejected(
                before.failure,
            )
        }

        when (val after = allocateEndpoints(nextRegistry, afterEndpoints, evidence, semanticEpisodeId)) {
            is EndpointAllocation.Accepted -> {
                nextRegistry = after.registry
                occurrences += after.assignments
            }

            is EndpointAllocation.Rejected -> return PerspectiveReferenceProjectionResult.Rejected(
                after.failure,
            )
        }

        nextRegistry = reconcile(nextRegistry, transition.afterState, perspectivePlayerId)

        return PerspectiveReferenceProjectionResult.Accepted(
            PerspectiveReferenceProjectionV1(
                nextRegistry = nextRegistry,
                referenceOccurrences = occurrences,
                // No accepted A+B relation witness currently exists. Alias allocation and
                // retirement remain useful without asserting a cross-incarnation relationship.
                incarnationRelations = emptyList(),
            ),
        )
    }

    private fun endpointsFor(
        candidateIndex: Int,
        candidate: HistoryCReferenceCandidateV1,
        transition: CommittedRulesTransition,
    ): List<Endpoint> {
        val before = candidate.beforeWitness
        val after = candidate.afterWitness
        return when {
            before != null && after != null && before != after -> listOf(
                Endpoint(
                    originalCandidateIndex = candidateIndex,
                    candidate = candidate.copy(beforeWitness = before, afterWitness = null),
                    witness = before,
                    state = transition.beforeState,
                    isAfter = false,
                ),
                Endpoint(
                    originalCandidateIndex = candidateIndex,
                    candidate = candidate.copy(beforeWitness = null, afterWitness = after),
                    witness = after,
                    state = transition.afterState,
                    isAfter = true,
                ),
            )

            after != null -> listOf(
                Endpoint(
                    originalCandidateIndex = candidateIndex,
                    candidate = candidate.copy(beforeWitness = null, afterWitness = after),
                    witness = after,
                    state = transition.afterState,
                    isAfter = true,
                ),
            )

            before != null -> listOf(
                Endpoint(
                    originalCandidateIndex = candidateIndex,
                    candidate = candidate.copy(beforeWitness = before, afterWitness = null),
                    witness = before,
                    state = transition.beforeState,
                    isAfter = false,
                ),
            )

            else -> emptyList()
        }
    }

    private fun authorize(
        endpoint: Endpoint,
        perspectivePlayerId: EntityId,
    ): EndpointDecision {
        if (!containsWitness(endpoint.state, endpoint.witness)) {
            return EndpointDecision.Reject(
                HistoryCFailure(
                    if (endpoint.isAfter) {
                        HistoryCFailureCode.STALE_AFTER_WITNESS
                    } else {
                        HistoryCFailureCode.STALE_BEFORE_WITNESS
                    },
                ),
            )
        }

        if (!visibility.isEntityReferenceAddressableTo(
                endpoint.state,
                endpoint.witness.entityId,
                perspectivePlayerId,
            )
        ) {
            return EndpointDecision.Omit
        }

        val location = locate(endpoint.state, endpoint.witness.entityId)
        val ledger = KnownInformationLedger.forPlayer(endpoint.state, perspectivePlayerId)
        val exactFacts = ledger.activeFacts.filter { fact ->
            fact.subjectEntityId == endpoint.witness.entityId &&
                fact.objectIdentityStamp == endpoint.witness.objectIdentityStamp
        }
        val identityFact = exactFacts.firstOrNull { it.factKind == KnownInformationFactKind.IDENTITY }
        val zoneFact = exactFacts.firstOrNull { it.factKind == KnownInformationFactKind.ZONE_MEMBERSHIP }
        val identityVisible = visibility.isEntityIdentityVisibleTo(
            endpoint.state,
            endpoint.witness.entityId,
            perspectivePlayerId,
        )

        if (location != null && location.zone in setOf(Zone.HAND, Zone.LIBRARY)) {
            val zoneVisible = visibility.isZoneVisibleTo(
                endpoint.state,
                ZoneKey(checkNotNull(location.ownerId), location.zone),
                perspectivePlayerId,
            )
            if (!zoneVisible && (identityFact == null || zoneFact?.knownZone != location.zone)) {
                return EndpointDecision.Reject(
                    HistoryCFailure(HistoryCFailureCode.MISSING_HISTORY_B_CONTINUITY_EVIDENCE),
                )
            }
        }

        val identityAuthorized = identityVisible || identityFact != null
        if (endpoint.candidate.identityDisclosure == HistoryCIdentityDisclosure.DEFINITION_KNOWN &&
            !identityAuthorized
        ) {
            return EndpointDecision.Reject(
                HistoryCFailure(HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH),
            )
        }

        val actualDefinition = endpoint.state.getEntity(endpoint.witness.entityId)
            ?.get<CardComponent>()
            ?.cardDefinitionId
        val claimedDefinition = endpoint.candidate.cardDefinitionId
        if (claimedDefinition != null && actualDefinition != claimedDefinition) {
            return EndpointDecision.Reject(
                HistoryCFailure(HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH),
            )
        }

        val effectiveDisclosure = if (identityAuthorized) {
            HistoryCIdentityDisclosure.DEFINITION_KNOWN
        } else {
            HistoryCIdentityDisclosure.OPAQUE
        }
        val effectiveDefinition = if (effectiveDisclosure == HistoryCIdentityDisclosure.DEFINITION_KNOWN) {
            actualDefinition ?: return EndpointDecision.Reject(
                HistoryCFailure(HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH),
            )
        } else {
            null
        }

        return EndpointDecision.Reference(
            endpoint.copy(
                candidate = endpoint.candidate.copy(
                    identityDisclosure = effectiveDisclosure,
                    cardDefinitionId = effectiveDefinition,
                ),
            ),
        )
    }

    private fun allocateEndpoints(
        registry: PerspectiveAliasRegistryV1,
        endpoints: List<Endpoint>,
        evidence: HistoryCReferenceEvidenceV1,
        semanticEpisodeId: String,
    ): EndpointAllocation {
        if (endpoints.isEmpty()) return EndpointAllocation.Accepted(registry, emptyList())

        return when (
            val allocation = PerspectiveAliasAllocator.allocate(
                registry = registry,
                semanticEpisodeId = semanticEpisodeId,
                evidence = evidence.copy(candidates = endpoints.map { it.candidate }),
            )
        ) {
            is PerspectiveAliasAllocationResult.Rejected -> EndpointAllocation.Rejected(
                allocation.failure,
            )

            is PerspectiveAliasAllocationResult.Accepted -> EndpointAllocation.Accepted(
                registry = allocation.registry,
                assignments = allocation.assignments.mapIndexed { localIndex, assignment ->
                    assignment.copy(candidateIndex = endpoints[localIndex].originalCandidateIndex)
                },
            )
        }
    }

    private fun reconcile(
        registry: PerspectiveAliasRegistryV1,
        afterState: GameState,
        perspectivePlayerId: EntityId,
    ): PerspectiveAliasRegistryV1 {
        var reconciled = registry
        for (witness in registry.activeBindings.keys) {
            val stillAddressable = containsWitness(afterState, witness) &&
                visibility.isEntityReferenceAddressableTo(
                    afterState,
                    witness.entityId,
                    perspectivePlayerId,
                )
            if (!stillAddressable) {
                reconciled = PerspectiveAliasAllocator.retire(reconciled, witness)
            }
        }
        return reconciled
    }

    private fun containsWitness(state: GameState, witness: HistoryCObjectWitness): Boolean =
        state.hasEntity(witness.entityId) &&
            state.objectIdentityStamps[witness.entityId] == witness.objectIdentityStamp

    private fun locate(state: GameState, entityId: EntityId): Location? {
        val zoneEntry = state.zones.entries
            .sortedWith(compareBy({ it.key.ownerId.value }, { it.key.zoneType.ordinal }))
            .firstOrNull { (_, ids) -> entityId in ids }
        if (zoneEntry != null) {
            return Location(zoneEntry.key.ownerId, zoneEntry.key.zoneType)
        }
        return if (entityId in state.stack) Location(null, Zone.STACK) else null
    }

    private fun rejected(code: HistoryCFailureCode): PerspectiveReferenceProjectionResult.Rejected =
        PerspectiveReferenceProjectionResult.Rejected(HistoryCFailure(code))

    private data class Location(
        val ownerId: EntityId?,
        val zone: Zone,
    )

    private data class Endpoint(
        val originalCandidateIndex: Int,
        val candidate: HistoryCReferenceCandidateV1,
        val witness: HistoryCObjectWitness,
        val state: GameState,
        val isAfter: Boolean,
    )

    private sealed interface EndpointDecision {
        data class Reference(val endpoint: Endpoint) : EndpointDecision
        data class Reject(val failure: HistoryCFailure) : EndpointDecision
        data object Omit : EndpointDecision
    }

    private sealed interface EndpointAllocation {
        data class Accepted(
            val registry: PerspectiveAliasRegistryV1,
            val assignments: List<PerspectiveAliasAssignment>,
        ) : EndpointAllocation

        data class Rejected(val failure: HistoryCFailure) : EndpointAllocation
    }
}

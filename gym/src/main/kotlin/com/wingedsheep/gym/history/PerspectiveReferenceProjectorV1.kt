package com.wingedsheep.gym.history

import com.wingedsheep.engine.mechanics.KnownInformationLedger
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.AbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.state.components.player.KnownInformationFactKind
import com.wingedsheep.engine.state.components.player.KnownInformationFactV1
import com.wingedsheep.engine.state.components.player.KnownInformationLedgerComponentV1
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/** Pure result of one committed, perspective-local History-C projection. */
internal data class PerspectiveReferenceProjectionV1(
    val nextRegistry: PerspectiveAliasRegistryV1,
    val referenceOccurrences: List<PerspectiveAliasAssignment>,
    val incarnationRelations: List<PerspectiveIncarnationRelationV1>,
    val semanticRelations: List<PerspectiveSemanticRelationAssignment> = emptyList(),
)

internal data class PerspectiveSemanticRelationAssignment(
    val eventOrdinal: Int,
    val kind: HistoryCReferenceRelationKindV1,
    val sourceAlias: PerspectiveSemanticAlias,
    val targetAlias: PerspectiveSemanticAlias? = null,
    val targetPlayerRole: String? = null,
    val amount: Int? = null,
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

        val orderedCandidates = when (val ordering = orderCandidates(evidence)) {
            is CandidateOrdering.Accepted -> ordering.candidates
            is CandidateOrdering.Rejected ->
                return PerspectiveReferenceProjectionResult.Rejected(ordering.failure)
        }
        val authorizedEndpoints = mutableListOf<Endpoint>()
        for (indexedCandidate in orderedCandidates) {
            val candidateIndex = indexedCandidate.originalCandidateIndex
            val candidate = indexedCandidate.candidate
            val endpoints = endpointsFor(candidateIndex, candidate, transition)
            for (endpoint in endpoints) {
                when (val decision = authorize(endpoint, perspectivePlayerId)) {
                    is EndpointDecision.Reference -> authorizedEndpoints += decision.endpoint

                    EndpointDecision.Omit -> Unit
                    is EndpointDecision.Reject -> {
                        return PerspectiveReferenceProjectionResult.Rejected(decision.failure)
                    }
                }
            }
        }

        val orderedEndpoints = canonicalizeUnorderedCollections(
            endpoints = authorizedEndpoints,
            evidence = evidence,
        )

        validateEndpointGroups(
            registry = registryCheck.registry,
            endpoints = orderedEndpoints,
            evidence = evidence,
            semanticEpisodeId = semanticEpisodeId,
        )?.let { return PerspectiveReferenceProjectionResult.Rejected(it) }

        var nextRegistry = registryCheck.registry
        val occurrences = mutableListOf<PerspectiveAliasAssignment>()

        // Preserve A's first-reference order exactly. A before/after pair is adjacent in this
        // sequence; C must not introduce a global old-endpoint phase followed by a new-endpoint
        // phase. Symmetry is validated separately per event/endpoint phase below so sequential
        // single-endpoint allocation cannot weaken HISTC-B's duplicate policy.
        for (endpoint in orderedEndpoints) {
            when (val allocation = allocateEndpoints(
                nextRegistry,
                listOf(endpoint),
                evidence,
                semanticEpisodeId,
            )) {
                is EndpointAllocation.Accepted -> {
                    nextRegistry = allocation.registry
                    occurrences += allocation.assignments
                }

                is EndpointAllocation.Rejected -> return PerspectiveReferenceProjectionResult.Rejected(
                    allocation.failure,
                )
            }
        }

        nextRegistry = reconcile(nextRegistry, transition.afterState, perspectivePlayerId)
        val assignmentsByCandidate = occurrences.associateBy { it.candidateIndex }
        val semanticRelations = evidence.relations.mapNotNull { relation ->
            val source = assignmentsByCandidate[relation.sourceCandidateIndex]
                ?: return@mapNotNull null
            val target = relation.targetCandidateIndex?.let(assignmentsByCandidate::get)
                ?: if (relation.targetPlayerRole != null) null else return@mapNotNull null
            PerspectiveSemanticRelationAssignment(
                eventOrdinal = relation.eventOrdinal,
                kind = relation.kind,
                sourceAlias = source.alias,
                targetAlias = target?.alias,
                targetPlayerRole = relation.targetPlayerRole,
                amount = relation.amount,
            )
        }

        return PerspectiveReferenceProjectionResult.Accepted(
            PerspectiveReferenceProjectionV1(
                nextRegistry = nextRegistry,
                referenceOccurrences = occurrences,
                // These are within-event semantic facts, not cross-incarnation identity links.
                // The latter remains deliberately empty until independently typed producer
                // authority exists.
                incarnationRelations = emptyList(),
                semanticRelations = semanticRelations,
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

    private fun orderCandidates(
        evidence: HistoryCReferenceEvidenceV1,
    ): CandidateOrdering {
        val ordered = mutableListOf<IndexedCandidate>()
        val groups = evidence.candidates.indices.groupBy { evidence.candidates[it].slot.eventOrdinal }
        for (group in groups.values) {
            val candidates = group.map { index ->
                IndexedCandidate(index, evidence.candidates[index])
            }
            if (candidates.size <= 1) {
                ordered += candidates
                continue
            }

            if (isPrivateLook(evidence, group.first()) ||
                isPublicReveal(evidence, group.first())
            ) {
                // Neither private hand-look order nor public reveal cardIds order is a safe
                // semantic tie-breaker. The producer may provide a known printed identity; use
                // that only, and keep same-definition distinct witnesses fail-closed in B.
                if (candidates.any {
                        it.candidate.identityDisclosure != HistoryCIdentityDisclosure.DEFINITION_KNOWN ||
                            it.candidate.cardDefinitionId.isNullOrBlank()
                    }
                ) {
                    return CandidateOrdering.Rejected(
                        HistoryCFailure(HistoryCFailureCode.UNORDERED_SYMMETRY),
                    )
                }
                ordered += candidates.sortedWith(
                    compareBy<IndexedCandidate>({ it.candidate.cardDefinitionId })
                        .thenBy { it.candidate.slot.role },
                )
                continue
            }
            ordered += candidates
        }
        return CandidateOrdering.Accepted(ordered)
    }

    /**
     * Canonicalize event-local collections only after Visibility/History-B has authorized each
     * endpoint. Raw list/map order is not a semantic tie-breaker for these families. A same-key
     * distinct-witness group is still rejected by B's symmetry validation before allocation.
     */
    private fun canonicalizeUnorderedCollections(
        endpoints: List<Endpoint>,
        evidence: HistoryCReferenceEvidenceV1,
    ): List<Endpoint> {
        val result = mutableListOf<Endpoint>()
        var index = 0
        while (index < endpoints.size) {
            val first = endpoints[index]
            val key = EndpointGroupKey(first.candidate.slot.eventOrdinal, first.isAfter)
            val group = mutableListOf<Endpoint>()
            while (index < endpoints.size) {
                val endpoint = endpoints[index]
                if (EndpointGroupKey(endpoint.candidate.slot.eventOrdinal, endpoint.isAfter) != key) {
                    break
                }
                group += endpoint
                index++
            }
            val eventFamily = evidence.eventBatch.entries
                .getOrNull(key.eventOrdinal)
                ?.eventFamily
            if (group.size > 1 && eventFamily in unorderedCollectionFamilies) {
                result += group.sortedWith(
                    compareBy<Endpoint>(
                        { it.candidate.identityDisclosure.ordinal },
                        { it.candidate.cardDefinitionId ?: "" },
                        { it.candidate.referenceKind.ordinal },
                        { it.candidate.slot.role.ordinal },
                    ),
                )
            } else {
                result += group
            }
        }
        return result
    }

    private val unorderedCollectionFamilies = setOf(
        PerspectiveEventFamily.CARDS_DRAWN,
        PerspectiveEventFamily.CARDS_DISCARDED,
        PerspectiveEventFamily.PRIVATE_HAND_LOOKED_AT,
        PerspectiveEventFamily.PRIVATE_CARDS_LOOKED_AT,
        PerspectiveEventFamily.PUBLIC_HAND_REVEALED,
        PerspectiveEventFamily.PUBLIC_CARDS_REVEALED,
        PerspectiveEventFamily.ATTACKERS_DECLARED,
        PerspectiveEventFamily.BLOCKERS_DECLARED,
        PerspectiveEventFamily.DAMAGE_ASSIGNED,
    )

    private fun isPrivateLook(
        evidence: HistoryCReferenceEvidenceV1,
        eventOrdinal: Int,
    ): Boolean = evidence.eventBatch.entries.getOrNull(eventOrdinal)?.eventFamily in setOf(
        PerspectiveEventFamily.PRIVATE_HAND_LOOKED_AT,
        PerspectiveEventFamily.PRIVATE_CARDS_LOOKED_AT,
    )

    private fun isPublicReveal(
        evidence: HistoryCReferenceEvidenceV1,
        eventOrdinal: Int,
    ): Boolean = evidence.eventBatch.entries.getOrNull(eventOrdinal)?.eventFamily in setOf(
        PerspectiveEventFamily.PUBLIC_HAND_REVEALED,
        PerspectiveEventFamily.PUBLIC_CARDS_REVEALED,
    )

    private fun authorize(
        endpoint: Endpoint,
        perspectivePlayerId: EntityId,
    ): EndpointDecision {
        if (!containsWitness(endpoint.state, endpoint.witness)) {
            if (endpoint.candidate.witnessProvenance == HistoryCReferenceWitnessProvenance.EVENT_OWNED &&
                endpoint.candidate.identityDisclosure == HistoryCIdentityDisclosure.OPAQUE &&
                endpoint.candidate.cardDefinitionId == null
            ) {
                return EndpointDecision.Reference(
                    endpoint.copy(
                        candidate = endpoint.candidate.copy(
                            identityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
                            cardDefinitionId = null,
                        ),
                    ),
                )
            }
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
        val actualDefinition = endpoint.state.getEntity(endpoint.witness.entityId)
            ?.get<CardComponent>()
            ?.cardDefinitionId
        // Stack addressability is public, but an ability/trigger stack object has no card identity.
        // Keep that Rules object opaque rather than treating public object visibility as a
        // CardDefinition authority. Card-like objects outside the stack remain fail-closed below.
        val isPublicRulesStackObject = endpoint.state.stack.contains(endpoint.witness.entityId) &&
            actualDefinition == null &&
            isAuthoritativeNonCardRulesStackObject(endpoint.state, endpoint.witness.entityId)

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

        val identityAuthorized = !isPublicRulesStackObject &&
            (identityVisible || identityFact != null)
        if (endpoint.candidate.identityDisclosure == HistoryCIdentityDisclosure.DEFINITION_KNOWN &&
            !identityAuthorized
        ) {
            return EndpointDecision.Reject(
                HistoryCFailure(HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH),
            )
        }

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

    /**
     * Preserve B's symmetry policy without letting a before/after pair look like two unordered
     * candidates. The validation registry is disposable; only the ordered single-endpoint pass
     * below can mutate the returned registry.
     */
    private fun validateEndpointGroups(
        registry: PerspectiveAliasRegistryV1,
        endpoints: List<Endpoint>,
        evidence: HistoryCReferenceEvidenceV1,
        semanticEpisodeId: String,
    ): HistoryCFailure? {
        var validationRegistry = registry
        val groups = endpoints.groupBy { EndpointGroupKey(it.candidate.slot.eventOrdinal, it.isAfter) }
        for (group in groups.values) {
            if (group.size <= 1) continue
            when (
                val allocation = PerspectiveAliasAllocator.allocate(
                    registry = validationRegistry,
                    semanticEpisodeId = semanticEpisodeId,
                    evidence = evidence.copy(candidates = group.map { it.candidate }),
                )
            ) {
                is PerspectiveAliasAllocationResult.Rejected -> return allocation.failure
                is PerspectiveAliasAllocationResult.Accepted -> validationRegistry = allocation.registry
            }
        }
        return null
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

    private fun isAuthoritativeNonCardRulesStackObject(
        state: GameState,
        entityId: EntityId,
    ): Boolean {
        val entity = state.getEntity(entityId) ?: return false
        if (entity.has<SpellOnStackComponent>()) return false
        val nonCardMarkerCount =
            (if (entity.has<TriggeredAbilityOnStackComponent>()) 1 else 0) +
                (if (entity.has<ActivatedAbilityOnStackComponent>()) 1 else 0) +
                (if (entity.has<AbilityOnStackComponent>()) 1 else 0)
        return nonCardMarkerCount == 1
    }

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

    private data class IndexedCandidate(
        val originalCandidateIndex: Int,
        val candidate: HistoryCReferenceCandidateV1,
    )

    private data class EndpointGroupKey(
        val eventOrdinal: Int,
        val isAfter: Boolean,
    )

    private sealed interface EndpointDecision {
        data class Reference(val endpoint: Endpoint) : EndpointDecision
        data class Reject(val failure: HistoryCFailure) : EndpointDecision
        data object Omit : EndpointDecision
    }

    private sealed interface CandidateOrdering {
        data class Accepted(val candidates: List<IndexedCandidate>) : CandidateOrdering
        data class Rejected(val failure: HistoryCFailure) : CandidateOrdering
    }

    private sealed interface EndpointAllocation {
        data class Accepted(
            val registry: PerspectiveAliasRegistryV1,
            val assignments: List<PerspectiveAliasAssignment>,
        ) : EndpointAllocation

        data class Rejected(val failure: HistoryCFailure) : EndpointAllocation
    }
}

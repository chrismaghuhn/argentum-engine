package com.wingedsheep.gym.history

import com.wingedsheep.gym.contract.A3SemanticJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class HistoryCPublicDistinctionKind {
    PRODUCER_PUBLIC_SLOT,
    PUBLIC_SEMANTIC_ROLE,
    PUBLIC_POSITION,
    FACE_DOWN_PHYSICAL_DISTINCTION,
}

/** Typed proof that otherwise symmetric candidates are publicly distinguishable to this perspective. */
internal data class HistoryCPublicDistinctionProofV1(
    val candidateIndex: Int,
    val kind: HistoryCPublicDistinctionKind,
    val rank: Int,
)

/** Pure immutable allocation and lifecycle operations for HISTC-B. */
internal object PerspectiveAliasAllocator {
    private val publicDistinctionDescriptorKeys = setOf("publicPosition", "publicRole")

    fun allocate(
        registry: PerspectiveAliasRegistryV1,
        semanticEpisodeId: String,
        evidence: HistoryCReferenceEvidenceV1,
        publicDistinctions: List<HistoryCPublicDistinctionProofV1> = emptyList(),
    ): PerspectiveAliasAllocationResult {
        validateRegistry(registry)?.let { return PerspectiveAliasAllocationResult.Rejected(it) }
        if (semanticEpisodeId.isBlank() ||
            registry.semanticEpisodeId.isBlank() ||
            semanticEpisodeId != registry.semanticEpisodeId
        ) {
            return rejected(HistoryCFailureCode.EPISODE_MISMATCH)
        }
        if (registry.perspectivePlayerId != evidence.perspectivePlayerId) {
            return rejected(HistoryCFailureCode.PERSPECTIVE_MISMATCH)
        }

        val witnesses = mutableListOf<HistoryCObjectWitness>()
        for (candidate in evidence.candidates) {
            val before = candidate.beforeWitness
            val after = candidate.afterWitness
            if (before != null && after != null && before != after) {
                return rejected(HistoryCFailureCode.CROSS_INCARNATION_REFERENCE_UNSUPPORTED)
            }
            witnesses += after ?: before
                ?: return rejected(HistoryCFailureCode.MISSING_EVENT_TIME_WITNESS)
        }

        val proofByCandidate = validatePublicDistinctions(
            candidates = evidence.candidates,
            proofs = publicDistinctions,
        ) ?: return rejected(HistoryCFailureCode.INVALID_PUBLIC_DISTINCTION)

        validateSymmetry(evidence, witnesses, proofByCandidate)?.let {
            return PerspectiveAliasAllocationResult.Rejected(it)
        }

        var nextRegistry = registry
        val assignments = mutableListOf<PerspectiveAliasAssignment>()
        evidence.candidates.indices.forEach { candidateIndex ->
            val candidate = evidence.candidates[candidateIndex]
            val witness = witnesses[candidateIndex]
            val existing = nextRegistry.activeBindings[witness]
            if (existing != null) {
                val updated = mergeIdentity(existing, candidate)
                    ?: return rejected(HistoryCFailureCode.IDENTITY_CONTRADICTION)
                if (updated != existing) {
                    nextRegistry = nextRegistry.copy(
                        activeBindings = nextRegistry.activeBindings + (witness to updated),
                    )
                }
                assignments += assignment(candidateIndex, existing.alias, candidate)
                return@forEach
            }

            nextRegistry = retireOtherIncarnations(nextRegistry, witness)
            val ordinal = nextRegistry.nextAliasOrdinal
            if (ordinal == Long.MAX_VALUE) {
                return PerspectiveAliasAllocationResult.Rejected(
                    HistoryCFailure(HistoryCFailureCode.ALIAS_SPACE_EXHAUSTED),
                )
            }
            val alias = PerspectiveSemanticAlias(ordinal)
            val binding = PerspectiveAliasBinding(
                alias = alias,
                knownCardDefinitionId = candidate.cardDefinitionId,
            )
            nextRegistry = nextRegistry.copy(
                nextAliasOrdinal = ordinal + 1L,
                activeBindings = nextRegistry.activeBindings + (witness to binding),
            )
            assignments += assignment(candidateIndex, alias, candidate)
        }

        return PerspectiveAliasAllocationResult.Accepted(nextRegistry, assignments)
    }

    fun retire(
        registry: PerspectiveAliasRegistryV1,
        witness: HistoryCObjectWitness,
    ): PerspectiveAliasRegistryV1 {
        val binding = registry.activeBindings[witness] ?: return registry
        return registry.copy(
            activeBindings = registry.activeBindings - witness,
            retiredAliases = registry.retiredAliases + binding.alias,
        )
    }

    private fun validateRegistry(registry: PerspectiveAliasRegistryV1): HistoryCFailure? {
        if (registry.version != PERSPECTIVE_ALIAS_REGISTRY_V1_VERSION) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_REGISTRY_VERSION)
        }
        if (registry.schemaIdentity != PERSPECTIVE_ALIAS_REGISTRY_V1_SCHEMA_IDENTITY) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_REGISTRY_SCHEMA_IDENTITY)
        }
        if (registry.nextAliasOrdinal < 0L) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_REGISTRY_STATE)
        }
        val aliases = registry.activeBindings.values.map { it.alias }
        if (aliases.distinct().size != aliases.size || aliases.any { it in registry.retiredAliases }) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_REGISTRY_STATE)
        }
        if (aliases.any { it.ordinal >= registry.nextAliasOrdinal }) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_REGISTRY_STATE)
        }
        if ((aliases + registry.retiredAliases).any { it.ordinal >= registry.nextAliasOrdinal }) {
            return HistoryCFailure(HistoryCFailureCode.INVALID_REGISTRY_STATE)
        }
        return null
    }

    private fun validatePublicDistinctions(
        candidates: List<HistoryCReferenceCandidateV1>,
        proofs: List<HistoryCPublicDistinctionProofV1>,
    ): Map<Int, HistoryCPublicDistinctionProofV1>? {
        val byCandidate = linkedMapOf<Int, HistoryCPublicDistinctionProofV1>()
        for (proof in proofs) {
            if (proof.candidateIndex !in candidates.indices ||
                proof.rank < 0 ||
                byCandidate.put(proof.candidateIndex, proof) != null ||
                !isAuthoritativelyVerified(proof, candidates[proof.candidateIndex])
            ) return null
        }
        return byCandidate
    }

    private fun isAuthoritativelyVerified(
        proof: HistoryCPublicDistinctionProofV1,
        candidate: HistoryCReferenceCandidateV1,
    ): Boolean = when (proof.kind) {
        // A producer's order is useful for event binding, but it does not prove that two
        // otherwise symmetric objects are publicly distinguishable to this perspective.
        HistoryCPublicDistinctionKind.PRODUCER_PUBLIC_SLOT,
        HistoryCPublicDistinctionKind.FACE_DOWN_PHYSICAL_DISTINCTION -> false

        HistoryCPublicDistinctionKind.PUBLIC_POSITION ->
            publicPosition(candidate) == proof.rank

        HistoryCPublicDistinctionKind.PUBLIC_SEMANTIC_ROLE ->
            publicRole(candidate) != null
    }

    private fun validateSymmetry(
        evidence: HistoryCReferenceEvidenceV1,
        witnesses: List<HistoryCObjectWitness>,
        proofs: Map<Int, HistoryCPublicDistinctionProofV1>,
    ): HistoryCFailure? {
        val groups = evidence.candidates.indices.groupBy { index ->
            val candidate = evidence.candidates[index]
            SymmetryKey(
                referenceKind = candidate.referenceKind,
                identityDisclosure = candidate.identityDisclosure,
                cardDefinitionId = candidate.cardDefinitionId,
                semanticDescriptor = canonicalSymmetryDescriptor(candidate.semanticDescriptor),
            )
        }
        for (indices in groups.values) {
            val distinctWitnesses = indices.map { witnesses[it] }.distinct()
            if (distinctWitnesses.size <= 1) continue
            val groupProofs = indices.map { proofs[it] ?: return HistoryCFailure(HistoryCFailureCode.UNORDERED_SYMMETRY) }
            val ranks = groupProofs.map { it.rank }
            if (groupProofs.map { it.kind }.distinct().size != 1 ||
                ranks.distinct().size != ranks.size ||
                ranks != ranks.sorted()
            ) {
                return HistoryCFailure(HistoryCFailureCode.INVALID_PUBLIC_DISTINCTION)
            }
            when (groupProofs.first().kind) {
                HistoryCPublicDistinctionKind.PUBLIC_POSITION -> {
                    val positions = indices.map { publicPosition(evidence.candidates[it]) }
                    if (positions.any { it == null } || positions.distinct().size != positions.size) {
                        return HistoryCFailure(HistoryCFailureCode.INVALID_PUBLIC_DISTINCTION)
                    }
                }

                HistoryCPublicDistinctionKind.PUBLIC_SEMANTIC_ROLE -> {
                    val roles = indices.map { publicRole(evidence.candidates[it]) }
                    if (roles.any { it == null } || roles.distinct().size != roles.size) {
                        return HistoryCFailure(HistoryCFailureCode.INVALID_PUBLIC_DISTINCTION)
                    }
                }

                HistoryCPublicDistinctionKind.PRODUCER_PUBLIC_SLOT,
                HistoryCPublicDistinctionKind.FACE_DOWN_PHYSICAL_DISTINCTION ->
                    return HistoryCFailure(HistoryCFailureCode.INVALID_PUBLIC_DISTINCTION)
            }
        }
        return null
    }

    private fun publicPosition(candidate: HistoryCReferenceCandidateV1): Int? {
        val value = candidate.semanticDescriptor["publicPosition"] as? JsonPrimitive ?: return null
        if (value.isString) return null
        return value.content.toIntOrNull()
    }

    private fun publicRole(candidate: HistoryCReferenceCandidateV1): String? {
        val value = candidate.semanticDescriptor["publicRole"] as? JsonPrimitive ?: return null
        if (!value.isString || value.content.isBlank()) return null
        return value.content
    }

    private fun canonicalSymmetryDescriptor(descriptor: JsonObject): String =
        A3SemanticJson.canonicalJson(
            JsonObject(descriptor.filterKeys { it !in publicDistinctionDescriptorKeys }),
        )

    private fun mergeIdentity(
        existing: PerspectiveAliasBinding,
        candidate: HistoryCReferenceCandidateV1,
    ): PerspectiveAliasBinding? {
        val candidateDefinition = candidate.cardDefinitionId ?: return existing
        val existingDefinition = existing.knownCardDefinitionId
        if (existingDefinition != null && existingDefinition != candidateDefinition) return null
        return existing.copy(knownCardDefinitionId = candidateDefinition)
    }

    private fun retireOtherIncarnations(
        registry: PerspectiveAliasRegistryV1,
        witness: HistoryCObjectWitness,
    ): PerspectiveAliasRegistryV1 {
        val stale = registry.activeBindings.filterKeys {
            it.entityId == witness.entityId && it.objectIdentityStamp != witness.objectIdentityStamp
        }
        if (stale.isEmpty()) return registry
        return registry.copy(
            activeBindings = registry.activeBindings - stale.keys,
            retiredAliases = registry.retiredAliases + stale.values.map { it.alias },
        )
    }

    private fun assignment(
        candidateIndex: Int,
        alias: PerspectiveSemanticAlias,
        candidate: HistoryCReferenceCandidateV1,
    ): PerspectiveAliasAssignment = PerspectiveAliasAssignment(
        candidateIndex = candidateIndex,
        alias = alias,
        identityDisclosure = candidate.identityDisclosure,
        cardDefinitionId = candidate.cardDefinitionId,
    )

    private fun rejected(code: HistoryCFailureCode): PerspectiveAliasAllocationResult.Rejected =
        PerspectiveAliasAllocationResult.Rejected(HistoryCFailure(code))

    private data class SymmetryKey(
        val referenceKind: HistoryCReferenceKind,
        val identityDisclosure: HistoryCIdentityDisclosure,
        val cardDefinitionId: String?,
        val semanticDescriptor: String,
    )
}

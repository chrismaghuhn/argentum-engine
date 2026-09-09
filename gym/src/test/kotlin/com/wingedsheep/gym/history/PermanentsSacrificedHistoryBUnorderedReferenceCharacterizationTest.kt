package com.wingedsheep.gym.history

import com.wingedsheep.gym.contract.PerspectiveEventBatchV1
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Test-only characterization of HISTC-B allocation for the unordered sacrifice collection. */
class PermanentsSacrificedHistoryBUnorderedReferenceCharacterizationTest : FunSpec({
    val perspective = EntityId("sacrifice-perspective")
    val firstWitness = HistoryCObjectWitness(EntityId("first-sacrificed-object"), 101L)
    val secondWitness = HistoryCObjectWitness(EntityId("second-sacrificed-object"), 102L)
    val episode = "permanents-sacrificed-history-b-characterization"

    fun candidate(
        witness: HistoryCObjectWitness,
        roleOrdinal: Int,
        rank: Int,
    ) = HistoryCReferenceCandidateV1(
        slot = HistoryCReferenceSlot(
            eventOrdinal = 0,
            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            roleOrdinal = roleOrdinal,
        ),
        referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        beforeWitness = witness,
        identityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
        orderProof = HistoryCOrderProof(
            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
            rank = rank,
        ),
        semanticDescriptor = buildJsonObject { put("type", "object_reference") },
        endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
        witnessProvenance = HistoryCReferenceWitnessProvenance.TRANSITION_STATE,
    )

    fun evidence(vararg candidates: HistoryCReferenceCandidateV1) = HistoryCReferenceEvidenceV1(
        perspectivePlayerId = perspective,
        eventBatch = PerspectiveEventBatchV1(
            perspectivePlayerId = perspective,
            entries = emptyList(),
        ),
        candidates = candidates.toList(),
    )

    fun registry() = PerspectiveAliasRegistryV1(
        semanticEpisodeId = episode,
        perspectivePlayerId = perspective,
    )

    test("a single opaque sacrifice candidate is allocatable by History-B") {
        val result = PerspectiveAliasAllocator.allocate(
            registry = registry(),
            semanticEpisodeId = episode,
            evidence = evidence(candidate(firstWitness, roleOrdinal = 0, rank = 0)),
        ).shouldBeInstanceOf<PerspectiveAliasAllocationResult.Accepted>()

        result.assignments.size shouldBe 1
        result.assignments.single().candidateIndex shouldBe 0
        result.assignments.single().alias.canonical() shouldBe "o0"
        result.registry.nextAliasOrdinal shouldBe 1L
        result.registry.activeBindings.keys.single() shouldBe firstWitness
    }

    test("two symmetric opaque sacrifice candidates fail closed as unordered symmetry") {
        val initial = registry()
        val result = PerspectiveAliasAllocator.allocate(
            registry = initial,
            semanticEpisodeId = episode,
            evidence = evidence(
                // These structural ranks intentionally model the raw collection positions. The
                // producer-order proof is not an independent public distinction for an unordered
                // SelectCardsDecision and therefore must not authorize alias allocation.
                candidate(firstWitness, roleOrdinal = 0, rank = 0),
                candidate(secondWitness, roleOrdinal = 1, rank = 1),
            ),
        ).shouldBeInstanceOf<PerspectiveAliasAllocationResult.Rejected>()

        result.failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
        initial.nextAliasOrdinal shouldBe 0L
        initial.activeBindings shouldBe emptyMap()

        println(
            "PERMANENTS_SACRIFICED_HISTORY_B_CHARACTERIZATION " +
                "singleCandidate=ACCEPTED " +
                "multiCandidate=UNORDERED_SYMMETRY " +
                "candidateOrderProof=EXPLICIT_PRODUCER_ORDER " +
                "decisionOrderSemanticAuthority=NONE " +
                "allocationMutationOnReject=NONE",
        )
    }
})

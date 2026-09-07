package com.wingedsheep.gym.history

import com.wingedsheep.gym.contract.PerspectiveEventBatchV1
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PerspectiveAliasRegistryTest : FunSpec({

    val p1 = EntityId.of("p1")
    val p2 = EntityId.of("p2")

    fun witness(id: String, stamp: Long = 1L): HistoryCObjectWitness =
        HistoryCObjectWitness(EntityId.of(id), stamp)

    fun candidate(
        objectWitness: HistoryCObjectWitness,
        identityDisclosure: HistoryCIdentityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
        cardDefinitionId: String? = null,
        eventOrdinal: Int = 0,
        roleOrdinal: Int = 0,
        descriptor: String = "opaque",
        publicPosition: Int? = null,
        publicRole: String? = null,
    ): HistoryCReferenceCandidateV1 = HistoryCReferenceCandidateV1(
        slot = HistoryCReferenceSlot(
            eventOrdinal = eventOrdinal,
            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            roleOrdinal = roleOrdinal,
        ),
        referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        afterWitness = objectWitness,
        identityDisclosure = identityDisclosure,
        cardDefinitionId = cardDefinitionId,
        orderProof = HistoryCOrderProof(
            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
            rank = roleOrdinal,
        ),
        semanticDescriptor = buildJsonObject {
            put("type", "object_reference")
            put("visibility", descriptor)
            publicPosition?.let { put("publicPosition", it) }
            publicRole?.let { put("publicRole", it) }
        },
    )

    fun evidence(
        perspective: EntityId = p1,
        candidates: List<HistoryCReferenceCandidateV1>,
    ): HistoryCReferenceEvidenceV1 = HistoryCReferenceEvidenceV1(
        perspectivePlayerId = perspective,
        eventBatch = PerspectiveEventBatchV1(perspectivePlayerId = perspective, entries = emptyList()),
        candidates = candidates,
    )

    fun registry(
        episode: String = "episode-0",
        perspective: EntityId = p1,
    ): PerspectiveAliasRegistryV1 = PerspectiveAliasRegistryV1(
        semanticEpisodeId = episode,
        perspectivePlayerId = perspective,
    )

    fun accepted(result: PerspectiveAliasAllocationResult): PerspectiveAliasAllocationResult.Accepted =
        result.shouldBeInstanceOf<PerspectiveAliasAllocationResult.Accepted>()

    fun rejected(result: PerspectiveAliasAllocationResult): PerspectiveAliasAllocationResult.Rejected =
        result.shouldBeInstanceOf<PerspectiveAliasAllocationResult.Rejected>()

    fun publicProof(
        candidateIndex: Int,
        rank: Int,
        kind: HistoryCPublicDistinctionKind = HistoryCPublicDistinctionKind.PUBLIC_POSITION,
    ) = HistoryCPublicDistinctionProofV1(
        candidateIndex = candidateIndex,
        kind = kind,
        rank = rank,
    )

    fun allocate(
        registry: PerspectiveAliasRegistryV1,
        evidence: HistoryCReferenceEvidenceV1,
        publicDistinctions: List<HistoryCPublicDistinctionProofV1> = emptyList(),
        semanticEpisodeId: String = registry.semanticEpisodeId,
    ): PerspectiveAliasAllocationResult = PerspectiveAliasAllocator.allocate(
        registry = registry,
        semanticEpisodeId = semanticEpisodeId,
        evidence = evidence,
        publicDistinctions = publicDistinctions,
    )

    test("HISTC-03 hidden hand mutation does not alter the perspective-local alias") {
        val first = accepted(
            allocate(
                registry(),
                evidence(candidates = listOf(candidate(witness("hidden-a")))),
            ),
        )
        val second = accepted(
            allocate(
                registry(),
                evidence(candidates = listOf(candidate(witness("hidden-b")))),
            ),
        )

        first.assignments.single().alias.canonical() shouldBe "o0"
        second.assignments.single().alias.canonical() shouldBe "o0"
    }

    test("HISTC-04 hidden event insertion cannot consume an alias ordinal") {
        val visible = evidence(
            candidates = listOf(
                candidate(witness("visible-a"), descriptor = "first"),
                candidate(witness("visible-b", 2L), descriptor = "second", roleOrdinal = 1),
            ),
        )
        val first = accepted(allocate(registry(), visible))
        val second = accepted(allocate(registry(), visible))

        first.assignments.map { it.alias.canonical() } shouldBe listOf("o0", "o1")
        second.assignments.map { it.alias.canonical() } shouldBe listOf("o0", "o1")
    }

    test("HISTC-07 opaque face-down evidence allocates without definition identity") {
        val result = accepted(
            allocate(
                registry(),
                evidence(candidates = listOf(candidate(witness("face-down")))),
            ),
        )

        result.assignments.single().alias.canonical() shouldBe "o0"
        result.assignments.single().cardDefinitionId shouldBe null
    }

    test("HISTC-08 identity disclosure upgrades the same incarnation alias") {
        val opaque = accepted(
            allocate(
                registry(),
                evidence(candidates = listOf(candidate(witness("face-down")))),
            ),
        )
        val known = accepted(
            allocate(
                opaque.registry,
                evidence(
                    candidates = listOf(
                        candidate(
                            witness("face-down"),
                            identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                            cardDefinitionId = "mtn",
                        ),
                    ),
                ),
            ),
        )

        opaque.assignments.single().alias.canonical() shouldBe "o0"
        known.assignments.single().alias.canonical() shouldBe "o0"
        known.registry.activeBindings[witness("face-down")]?.knownCardDefinitionId shouldBe "mtn"
    }

    test("HISTC-09 a new incarnation retires the old alias and allocates a fresh one") {
        val first = accepted(
            allocate(
                registry(),
                evidence(candidates = listOf(candidate(witness("e17", 44L)))),
            ),
        )
        val second = accepted(
            allocate(
                first.registry,
                evidence(candidates = listOf(candidate(witness("e17", 45L)))),
            ),
        )

        second.assignments.single().alias.canonical() shouldBe "o1"
        second.registry.activeBindings.keys shouldBe setOf(witness("e17", 45L))
        second.registry.retiredAliases.map { it.canonical() } shouldContain "o0"
    }

    test("HISTC-15 retired token aliases are never reused") {
        val first = accepted(
            allocate(
                registry(),
                evidence(candidates = listOf(candidate(witness("token", 1L)))),
            ),
        )
        val retired = PerspectiveAliasAllocator.retire(first.registry, witness("token", 1L))
        val second = accepted(
            allocate(
                retired,
                evidence(candidates = listOf(candidate(witness("token", 1L)))),
            ),
        )

        second.assignments.single().alias.canonical() shouldBe "o1"
        second.registry.retiredAliases.map { it.canonical() } shouldContain "o0"
    }

    test("HISTC-16 perspective namespaces are isolated") {
        val p1Result = accepted(
            allocate(
                registry(perspective = p1),
                evidence(perspective = p1, candidates = listOf(candidate(witness("public")))),
            ),
        )
        val p2Result = accepted(
            allocate(
                registry(perspective = p2),
                evidence(perspective = p2, candidates = listOf(candidate(witness("public")))),
            ),
        )

        p1Result.assignments.single().alias.canonical() shouldBe "o0"
        p2Result.assignments.single().alias.canonical() shouldBe "o0"
    }

    test("HISTC-21 indistinguishable distinct witnesses fail closed") {
        val result = rejected(
            allocate(
                registry(),
                evidence(
                    candidates = listOf(
                        candidate(witness("same-a"), roleOrdinal = 0),
                        candidate(witness("same-b", 2L), roleOrdinal = 1),
                    ),
                ),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
        registry().nextAliasOrdinal shouldBe 0L
    }

    test("HISTC-22 public distinction authority is deferred to a typed future source") {
        val candidates = listOf(
            candidate(witness("runtime-a"), roleOrdinal = 0, publicPosition = 0),
            candidate(witness("runtime-b", 2L), roleOrdinal = 1, publicPosition = 1),
        )
        val result = rejected(
            allocate(
                registry(),
                evidence(candidates = candidates),
                publicDistinctions = listOf(publicProof(0, 0), publicProof(1, 1)),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
    }

    test("HISTC-23 later identity disclosure does not mutate the earlier occurrence") {
        val first = accepted(
            allocate(
                registry(),
                evidence(candidates = listOf(candidate(witness("opaque")))),
            ),
        )
        val firstOccurrence = first.assignments.single()
        val second = accepted(
            allocate(
                first.registry,
                evidence(
                    candidates = listOf(
                        candidate(
                            witness("opaque"),
                            identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                            cardDefinitionId = "mtn",
                        ),
                    ),
                ),
            ),
        )

        firstOccurrence.identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE
        firstOccurrence.cardDefinitionId shouldBe null
        second.assignments.single().alias.canonical() shouldBe "o0"
    }

    test("HISTC-B-REVIEW-01 rejected batch does not consume an ordinal") {
        val initial = registry()
        val result = rejected(
            allocate(
                initial,
                evidence(
                    candidates = listOf(
                        candidate(witness("first"), roleOrdinal = 0),
                        candidate(witness("ambiguous", 2L), roleOrdinal = 1),
                    ),
                ),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
        initial.nextAliasOrdinal shouldBe 0L
        initial.activeBindings shouldBe emptyMap()
    }

    test("HISTC-B-REVIEW-02 retired same witness receives a new alias") {
        val first = accepted(
            allocate(
                registry(),
                evidence(candidates = listOf(candidate(witness("same", 7L)))),
            ),
        )
        val second = accepted(
            allocate(
                PerspectiveAliasAllocator.retire(first.registry, witness("same", 7L)),
                evidence(candidates = listOf(candidate(witness("same", 7L)))),
            ),
        )

        second.assignments.single().alias.canonical() shouldBe "o1"
        second.registry.retiredAliases.map { it.canonical() } shouldContain "o0"
    }

    test("HISTC-B-REVIEW-03 definition contradiction fails closed") {
        val first = accepted(
            allocate(
                registry(),
                evidence(
                    candidates = listOf(
                        candidate(
                            witness("known"),
                            identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                            cardDefinitionId = "mtn",
                        ),
                    ),
                ),
            ),
        )
        val result = rejected(
            allocate(
                first.registry,
                evidence(
                    candidates = listOf(
                        candidate(
                            witness("known"),
                            identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                            cardDefinitionId = "other",
                        ),
                    ),
                ),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.IDENTITY_CONTRADICTION
        first.registry.activeBindings[witness("known")]?.knownCardDefinitionId shouldBe "mtn"
    }

    test("HISTC-B-REVIEW-04 episode namespaces do not share ordinals") {
        val first = accepted(
            allocate(
                registry(episode = "episode-a"),
                evidence(perspective = p1, candidates = listOf(candidate(witness("public")))),
            ),
        )
        val second = accepted(
            allocate(
                registry(episode = "episode-b"),
                evidence(perspective = p1, candidates = listOf(candidate(witness("public")))),
            ),
        )

        first.assignments.single().alias.canonical() shouldBe "o0"
        second.assignments.single().alias.canonical() shouldBe "o0"
    }

    test("HISTC-B-REVIEW-10 allocation rejects a mismatched requested episode") {
        val initial = registry(episode = "episode-a")
        val result = rejected(
            allocate(
                initial,
                evidence(candidates = listOf(candidate(witness("episode-mismatch")))),
                semanticEpisodeId = "episode-b",
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.EPISODE_MISMATCH
        initial.nextAliasOrdinal shouldBe 0L
        initial.activeBindings shouldBe emptyMap()
    }

    test("HISTC-B-REVIEW-05 runtime EntityId variation preserves the alias sequence") {
        val first = accepted(
            allocate(
                registry(),
                evidence(
                    candidates = listOf(
                        candidate(witness("runtime-a"), roleOrdinal = 0, descriptor = "first"),
                        candidate(witness("runtime-b", 2L), roleOrdinal = 1, descriptor = "second"),
                    ),
                ),
            ),
        )
        val second = accepted(
            allocate(
                registry(),
                evidence(
                    candidates = listOf(
                        candidate(witness("other-runtime-a"), roleOrdinal = 0, descriptor = "first"),
                        candidate(witness("other-runtime-b", 2L), roleOrdinal = 1, descriptor = "second"),
                    ),
                ),
            ),
        )

        first.assignments.map { it.alias.canonical() } shouldBe listOf("o0", "o1")
        second.assignments.map { it.alias.canonical() } shouldBe listOf("o0", "o1")
    }

    test("HISTC-B-REVIEW-06 unknown registry versions fail closed") {
        val result = rejected(
            allocate(
                registry().copy(version = PERSPECTIVE_ALIAS_REGISTRY_V1_VERSION + 1),
                evidence(candidates = listOf(candidate(witness("versioned")))),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.INVALID_REGISTRY_VERSION
    }

    test("HISTC-B-REVIEW-07 blank episode identity fails closed") {
        val result = rejected(
            allocate(
                registry(episode = ""),
                evidence(candidates = listOf(candidate(witness("episode")))),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.EPISODE_MISMATCH
    }

    test("HISTC-B-REVIEW-08 alias ordinal exhaustion is atomic") {
        val initial = registry().copy(nextAliasOrdinal = Long.MAX_VALUE)
        val result = rejected(
            allocate(
                initial,
                evidence(candidates = listOf(candidate(witness("overflow")))),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.ALIAS_SPACE_EXHAUSTED
        initial.nextAliasOrdinal shouldBe Long.MAX_VALUE
        initial.activeBindings shouldBe emptyMap()
    }

    test("HISTC-B-REVIEW-09_MALFORMED_RETIRED_ALIAS_CANNOT_BE_REUSED") {
        val initial = registry().copy(
            nextAliasOrdinal = 0L,
            retiredAliases = setOf(PerspectiveSemanticAlias(0L)),
        )
        val result = rejected(
            allocate(
                initial,
                evidence(candidates = listOf(candidate(witness("tombstoned")))),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.INVALID_REGISTRY_STATE
        initial.nextAliasOrdinal shouldBe 0L
        initial.activeBindings shouldBe emptyMap()
    }

    test("HISTC-B-REVIEW-11 producer order is not a public distinction") {
        val initial = registry()
        val result = rejected(
            allocate(
                initial,
                evidence(
                    candidates = listOf(
                        candidate(witness("symmetric-a"), roleOrdinal = 0),
                        candidate(witness("symmetric-b", 2L), roleOrdinal = 1),
                    ),
                ),
                publicDistinctions = listOf(
                    publicProof(0, 0, HistoryCPublicDistinctionKind.PRODUCER_PUBLIC_SLOT),
                    publicProof(1, 1, HistoryCPublicDistinctionKind.PRODUCER_PUBLIC_SLOT),
                ),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
        initial.nextAliasOrdinal shouldBe 0L
        initial.activeBindings shouldBe emptyMap()
    }

    test("HISTC-B-REVIEW-12 forged public position fails closed") {
        val initial = registry()
        val result = rejected(
            allocate(
                initial,
                evidence(
                    candidates = listOf(
                        candidate(witness("position-a"), publicPosition = 0),
                        candidate(witness("position-b", 2L), publicPosition = 1),
                    ),
                ),
                publicDistinctions = listOf(publicProof(0, 0), publicProof(1, 1)),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
        initial.nextAliasOrdinal shouldBe 0L
        initial.activeBindings shouldBe emptyMap()
    }

    test("HISTC-B-REVIEW-13 forged public role fails closed") {
        val initial = registry()
        val result = rejected(
            allocate(
                initial,
                evidence(
                    candidates = listOf(
                        candidate(witness("role-a"), publicRole = "role-x"),
                        candidate(witness("role-b", 2L), publicRole = "role-y"),
                    ),
                ),
                publicDistinctions = listOf(
                    publicProof(0, 0, HistoryCPublicDistinctionKind.PUBLIC_SEMANTIC_ROLE),
                    publicProof(1, 1, HistoryCPublicDistinctionKind.PUBLIC_SEMANTIC_ROLE),
                ),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
        initial.nextAliasOrdinal shouldBe 0L
        initial.activeBindings shouldBe emptyMap()
    }
})

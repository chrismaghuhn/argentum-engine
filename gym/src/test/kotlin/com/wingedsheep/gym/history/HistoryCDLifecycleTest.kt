package com.wingedsheep.gym.history

import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.gym.contract.PerspectiveEventBatchV1
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

class HistoryCDLifecycleTest : FunSpec({

    val p1 = EntityId("p1")
    val p2 = EntityId("p2")

    fun candidate(
        witness: HistoryCObjectWitness,
        definition: String? = null,
        disclosure: HistoryCIdentityDisclosure =
            if (definition == null) HistoryCIdentityDisclosure.OPAQUE
            else HistoryCIdentityDisclosure.DEFINITION_KNOWN,
        roleOrdinal: Int = 0,
    ) = HistoryCReferenceCandidateV1(
        slot = HistoryCReferenceSlot(
            eventOrdinal = 0,
            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            roleOrdinal = roleOrdinal,
        ),
        referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        afterWitness = witness,
        identityDisclosure = disclosure,
        cardDefinitionId = definition,
        orderProof = HistoryCOrderProof(
            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
            rank = roleOrdinal,
        ),
        semanticDescriptor = buildJsonObject { put("type", "object_reference") },
    )

    fun evidence(vararg candidates: HistoryCReferenceCandidateV1) = HistoryCReferenceEvidenceV1(
        perspectivePlayerId = p1,
        eventBatch = PerspectiveEventBatchV1(perspectivePlayerId = p1, entries = emptyList()),
        candidates = candidates.toList(),
    )

    fun allocate(
        state: HistoryCLifecycleStateV1,
        evidence: HistoryCReferenceEvidenceV1,
    ): Pair<HistoryCLifecycleStateV1, List<PerspectiveAliasAssignment>> {
        val result = PerspectiveAliasAllocator.allocate(
            registry = state.registries.getValue(p1),
            semanticEpisodeId = state.semanticEpisodeId,
            evidence = evidence,
        ).shouldBeInstanceOf<PerspectiveAliasAllocationResult.Accepted>()
        return state.withRegistry(result.registry) to result.assignments
    }

    test("HISTC-D-01 snapshot round-trip preserves non-trivial perspective registries") {
        val witness = HistoryCObjectWitness(EntityId("card-a"), 7L)
        val registry = PerspectiveAliasRegistryV1(
            semanticEpisodeId = "episode-a",
            perspectivePlayerId = p1,
            nextAliasOrdinal = 2L,
            activeBindings = mapOf(
                witness to PerspectiveAliasBinding(
                    alias = PerspectiveSemanticAlias(1L),
                    knownCardDefinitionId = "mtn",
                ),
            ),
            retiredAliases = setOf(PerspectiveSemanticAlias(0L)),
        )
        val state = HistoryCLifecycleStateV1.start(
            semanticEpisodeId = "episode-a",
            playerIds = listOf(p1, p2),
        ).withRegistry(registry)

        val encoded = HistoryCSnapshotCodecV1.encode(state, stepCount = 4)
        val restored = HistoryCSnapshotCodecV1.decode(
            encoded = encoded,
            expectedPlayerIds = listOf(p1, p2),
            expectedStepCount = 4,
        ).shouldBeInstanceOf<HistoryCSnapshotDecodeResult.Accepted>().state

        restored shouldBe state
        restored.registries.keys shouldContain p1
        restored.registries[p1] shouldBe registry
    }

    test("HISTC-D-16 unknown version, schema, integrity, and registry state fail closed") {
        val state = HistoryCLifecycleStateV1.start("episode-a", listOf(p1, p2))
        val encoded = HistoryCSnapshotCodecV1.encode(state, stepCount = 0)
        val envelope = Json.decodeFromString<HistoryCSnapshotEnvelopeV1>(
            encoded.toString(StandardCharsets.UTF_8),
        )

        fun decode(candidate: HistoryCSnapshotEnvelopeV1): HistoryCFailureCode =
            HistoryCSnapshotCodecV1.decode(
                encoded = HistoryCSnapshotCodecV1.encodeEnvelope(candidate),
                expectedPlayerIds = listOf(p1, p2),
                expectedStepCount = 0,
            ).shouldBeInstanceOf<HistoryCSnapshotDecodeResult.Rejected>().failure.code

        decode(envelope.copy(version = 99)) shouldBe
            HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_VERSION
        decode(envelope.copy(schemaIdentity = "future-schema")) shouldBe
            HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_SCHEMA_IDENTITY
        HistoryCSnapshotCodecV1.decode(
            encoded = envelope.copy(integritySha256 = "0".repeat(64))
                .let { Json.encodeToString(it).toByteArray(StandardCharsets.UTF_8) },
            expectedPlayerIds = listOf(p1, p2),
            expectedStepCount = 0,
        ).shouldBeInstanceOf<HistoryCSnapshotDecodeResult.Rejected>().failure.code shouldBe
            HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_INTEGRITY

        val invalidRegistry = envelope.copy(
            registries = envelope.registries.map { registry ->
                if (registry.perspectivePlayerId == p1.value) {
                    registry.copy(
                        nextAliasOrdinal = 0L,
                        activeBindings = listOf(
                            HistoryCSnapshotBindingV1(
                                entityId = "card-a",
                                objectIdentityStamp = 1L,
                                aliasOrdinal = 0L,
                            ),
                        ),
                    )
                } else {
                    registry
                }
            },
        )
        decode(invalidRegistry) shouldBe HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE
    }

    test("HISTC-D-09/D-10 tombstones and identity upgrades survive restore") {
        val opaqueWitness = HistoryCObjectWitness(EntityId("card-a"), 1L)
        val state = HistoryCLifecycleStateV1.start("episode-a", listOf(p1, p2)).withRegistry(
            PerspectiveAliasRegistryV1(
                semanticEpisodeId = "episode-a",
                perspectivePlayerId = p1,
                nextAliasOrdinal = 1L,
                activeBindings = mapOf(
                    opaqueWitness to PerspectiveAliasBinding(PerspectiveSemanticAlias(0L)),
                ),
            ),
        )
        val restored = HistoryCSnapshotCodecV1.decode(
            HistoryCSnapshotCodecV1.encode(state, stepCount = 2),
            expectedPlayerIds = listOf(p1, p2),
            expectedStepCount = 2,
        ).shouldBeInstanceOf<HistoryCSnapshotDecodeResult.Accepted>().state

        val (upgraded, upgradeAssignments) = allocate(
            restored,
            evidence(
                candidate(
                    witness = opaqueWitness,
                    definition = "mtn",
                ),
            ),
        )
        upgradeAssignments.single().alias.canonical() shouldBe "o0"
        upgraded.registries.getValue(p1).activeBindings.getValue(opaqueWitness)
            .knownCardDefinitionId shouldBe "mtn"

        val replacement = HistoryCObjectWitness(EntityId("card-a"), 2L)
        val (reincarnated, replacementAssignments) = allocate(
            upgraded,
            evidence(candidate(replacement, definition = "mtn")),
        )
        replacementAssignments.single().alias.canonical() shouldBe "o1"
        reincarnated.registries.getValue(p1).retiredAliases
            .map { it.canonical() } shouldBe listOf("o0")
    }

    test("HISTC-D-13 same-definition private-look symmetry remains fail-closed after restore") {
        val first = HistoryCObjectWitness(EntityId("card-a"), 1L)
        val second = HistoryCObjectWitness(EntityId("card-b"), 1L)
        val state = HistoryCLifecycleStateV1.start("episode-a", listOf(p1, p2))
        val restored = HistoryCSnapshotCodecV1.decode(
            HistoryCSnapshotCodecV1.encode(state, stepCount = 0),
            expectedPlayerIds = listOf(p1, p2),
            expectedStepCount = 0,
        ).shouldBeInstanceOf<HistoryCSnapshotDecodeResult.Accepted>().state

        val rejection = PerspectiveAliasAllocator.allocate(
            registry = restored.registries.getValue(p1),
            semanticEpisodeId = restored.semanticEpisodeId,
            evidence = evidence(
                candidate(first, definition = "mtn", roleOrdinal = 0),
                candidate(second, definition = "mtn", roleOrdinal = 1),
            ),
        ).shouldBeInstanceOf<PerspectiveAliasAllocationResult.Rejected>()
        rejection.failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
        restored.registries.getValue(p1).nextAliasOrdinal shouldBe 0L
    }

    test("HISTC-D-12 multi-object known identities retain the same restored registry result") {
        val first = HistoryCObjectWitness(EntityId("card-a"), 1L)
        val second = HistoryCObjectWitness(EntityId("card-b"), 1L)
        val initial = HistoryCLifecycleStateV1.start("episode-a", listOf(p1, p2))
        val input = evidence(
            candidate(first, definition = "mtn", roleOrdinal = 0),
            candidate(second, definition = "forest", roleOrdinal = 1),
        )
        val uninterrupted = allocate(initial, input).first
        val restored = HistoryCSnapshotCodecV1.decode(
            HistoryCSnapshotCodecV1.encode(initial, stepCount = 0),
            expectedPlayerIds = listOf(p1, p2),
            expectedStepCount = 0,
        ).shouldBeInstanceOf<HistoryCSnapshotDecodeResult.Accepted>().state
        val resumed = allocate(restored, input).first

        resumed shouldBe uninterrupted
        resumed.registries.getValue(p1).nextAliasOrdinal shouldBe 2L
    }

    test("HISTC-D-12 opaque multi-object sets remain symmetry-rejected after restore") {
        val first = HistoryCObjectWitness(EntityId("card-a"), 1L)
        val second = HistoryCObjectWitness(EntityId("card-b"), 1L)
        val state = HistoryCLifecycleStateV1.start("episode-a", listOf(p1, p2))
        val restored = HistoryCSnapshotCodecV1.decode(
            HistoryCSnapshotCodecV1.encode(state, stepCount = 0),
            expectedPlayerIds = listOf(p1, p2),
            expectedStepCount = 0,
        ).shouldBeInstanceOf<HistoryCSnapshotDecodeResult.Accepted>().state

        val rejection = PerspectiveAliasAllocator.allocate(
            registry = restored.registries.getValue(p1),
            semanticEpisodeId = restored.semanticEpisodeId,
            evidence = evidence(
                candidate(first, roleOrdinal = 0),
                candidate(second, roleOrdinal = 1),
            ),
        ).shouldBeInstanceOf<PerspectiveAliasAllocationResult.Rejected>()
        rejection.failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
    }

    test("HISTC-D-14/D-15 replay suffix after a checkpoint matches uninterrupted allocation") {
        val first = HistoryCObjectWitness(EntityId("card-a"), 1L)
        val second = HistoryCObjectWitness(EntityId("card-a"), 2L)
        val third = HistoryCObjectWitness(EntityId("card-b"), 1L)
        val initial = HistoryCLifecycleStateV1.start("episode-a", listOf(p1, p2))
        val prefix = allocate(initial, evidence(candidate(first))).first
        val checkpoint = HistoryCSnapshotCodecV1.decode(
            HistoryCSnapshotCodecV1.encode(prefix, stepCount = 1),
            expectedPlayerIds = listOf(p1, p2),
            expectedStepCount = 1,
        ).shouldBeInstanceOf<HistoryCSnapshotDecodeResult.Accepted>().state

        val suffix = listOf(
            evidence(candidate(first, definition = "mtn")),
            evidence(candidate(second, definition = "mtn")),
            evidence(candidate(third, definition = "forest")),
        )
        val uninterrupted = suffix.fold(prefix) { state, next -> allocate(state, next).first }
        val resumed = suffix.fold(checkpoint) { state, next -> allocate(state, next).first }

        resumed shouldBe uninterrupted
        resumed.registries.getValue(p1).nextAliasOrdinal shouldBe 3L
        resumed.registries.getValue(p1).retiredAliases.map { it.canonical() } shouldBe listOf("o0")
    }
})

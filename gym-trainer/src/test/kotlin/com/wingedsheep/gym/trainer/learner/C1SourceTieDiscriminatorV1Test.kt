package com.wingedsheep.gym.trainer.learner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class C1SourceTieDiscriminatorV1Test : FunSpec({
    test("produces distinct invariant keys for distinct admitted semantic features") {
        val candidates = listOf(
            C1ProjectedCandidateForTie(
                sourceBindingOrdinal = 0,
                featureView = buildJsonObject { put("kind", "PassPriority") },
            ),
            C1ProjectedCandidateForTie(
                sourceBindingOrdinal = 1,
                featureView = buildJsonObject { put("kind", "PlayLand") },
            ),
        )

        val result = C1SourceTieDiscriminatorV1.produce(candidates)

        result.keys shouldBe setOf(0, 1)
        result[0] shouldNotBe result[1]
    }

    test("symmetric candidates receive no discriminator") {
        val feature = buildJsonObject { put("kind", "PassPriority") }
        val result = C1SourceTieDiscriminatorV1.produce(
            listOf(
                C1ProjectedCandidateForTie(0, feature),
                C1ProjectedCandidateForTie(1, feature),
            ),
        )

        result shouldBe emptyMap()
    }

    test("rejects raw identity and physical-order fields") {
        shouldThrow<IllegalArgumentException> {
            C1SourceTieDiscriminatorV1.produce(
                listOf(
                    C1ProjectedCandidateForTie(
                        0,
                        buildJsonObject { put("sourceEntityId", "entity-raw") },
                    ),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            C1SourceTieDiscriminatorV1.produce(
                listOf(
                    C1ProjectedCandidateForTie(
                        0,
                        buildJsonObject { put("rowIndex", 0) },
                    ),
                ),
            )
        }
    }

    test("is invariant under physical candidate permutation") {
        val first = C1ProjectedCandidateForTie(
            0,
            buildJsonObject { put("kind", "PassPriority") },
        )
        val second = C1ProjectedCandidateForTie(
            1,
            buildJsonObject { put("kind", "PlayLand") },
        )

        C1SourceTieDiscriminatorV1.produce(listOf(first, second)) shouldBe
            C1SourceTieDiscriminatorV1.produce(listOf(second, first))
    }
})

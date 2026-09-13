package com.wingedsheep.gym.trainer.learner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class C1DatasetSplitV1Test : FunSpec({
    test("uses the accepted split KATs") {
        C1DatasetSplitV1.bucket("0".repeat(64)) shouldBe 75
        C1DatasetSplitV1.assign("0".repeat(64)) shouldBe C1DatasetPartition.TRAIN

        C1DatasetSplitV1.bucket("09".repeat(32)) shouldBe 88
        C1DatasetSplitV1.assign("09".repeat(32)) shouldBe C1DatasetPartition.VALIDATION

        C1DatasetSplitV1.bucket("1c".repeat(32)) shouldBe 91
        C1DatasetSplitV1.assign("1c".repeat(32)) shouldBe C1DatasetPartition.TEST
    }

    test("uses semanticEpisodeId only") {
        val id = "0".repeat(64)

        C1DatasetSplitV1.assign(id) shouldBe C1DatasetSplitV1.assign(id)
        C1DatasetSplitV1.assign(id) shouldBe C1DatasetPartition.TRAIN
    }

    test("rejects malformed semantic episode identities") {
        shouldThrow<IllegalArgumentException> {
            C1DatasetSplitV1.bucket("not-a-sha")
        }
        shouldThrow<IllegalArgumentException> {
            C1DatasetSplitV1.bucket("A".repeat(64))
        }
        shouldThrow<IllegalArgumentException> {
            C1DatasetSplitV1.bucket("0".repeat(63))
        }
    }
})

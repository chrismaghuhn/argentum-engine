package com.wingedsheep.gym.contract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SchemaHashContractTest : FunSpec({

    test("current Gym schema identifies the upstream-synced observation contract") {
        SchemaHash.CURRENT shouldBe "argentum-gym-contract@v1.27-upstream-sync-05"
        // Neither parent contract: the fork's last version and upstream's last version both
        // describe a shape this merge changed.
        SchemaHash.CURRENT shouldNotBe "argentum-gym-contract@v1.26-repeat-count-domain"
        SchemaHash.CURRENT shouldNotBe "argentum-gym-contract@v1.6-multi-seat-observation"
    }
})

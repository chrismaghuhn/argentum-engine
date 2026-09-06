package com.wingedsheep.gym.contract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SchemaHashContractTest : FunSpec({

    test("current Gym schema identifies the repeat-count observation contract") {
        SchemaHash.CURRENT shouldBe "argentum-gym-contract@v1.26-repeat-count-domain"
        SchemaHash.CURRENT shouldNotBe "argentum-gym-contract@v1.25-target-payment-domain"
    }
})

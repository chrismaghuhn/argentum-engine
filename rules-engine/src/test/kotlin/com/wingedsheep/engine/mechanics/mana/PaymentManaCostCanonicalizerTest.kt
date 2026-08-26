package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.sdk.core.ManaCost
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PaymentManaCostCanonicalizerTest : FunSpec({

    test("canonicalizes only an ordinary zero mana payment representation") {
        ManaCost.parse("{0}").canonicalPaymentManaCost() shouldBe ManaCost.ZERO
        ManaCost.ZERO.canonicalPaymentManaCost() shouldBe ManaCost.ZERO
        ManaCost.parse("{1}").canonicalPaymentManaCost() shouldBe ManaCost.parse("{1}")
        ManaCost.parse("{G}").canonicalPaymentManaCost() shouldBe ManaCost.parse("{G}")
        ManaCost.parse("{X}").canonicalPaymentManaCost() shouldBe ManaCost.parse("{X}")
        ManaCost.parse("{0}{0}").canonicalPaymentManaCost() shouldBe ManaCost.parse("{0}{0}")
    }
})

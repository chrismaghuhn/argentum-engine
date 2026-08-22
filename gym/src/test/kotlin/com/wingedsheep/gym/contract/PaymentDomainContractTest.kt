package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PaymentDomainContractTest : FunSpec({

    test("certified floating candidate round-trips without changing PaymentPlanV1") {
        val domain = PaymentDomainV1(
            requiredCost = "{1}{B}",
            costUnits = listOf(
                PaymentCostUnitDomain(
                    symbolIndex = 0,
                    kind = PaymentCostKind.GENERIC,
                    amount = 1,
                ),
                PaymentCostUnitDomain(
                    symbolIndex = 1,
                    kind = PaymentCostKind.COLORED,
                    amount = 1,
                    allowedColors = setOf(PaymentManaColor.BLACK),
                ),
            ),
            currentPool = PaymentPoolDomain(
                green = 1,
                certifiedFloatingMana = CertifiedFloatingManaCandidateV1(
                    poolColor = PaymentManaColor.GREEN,
                    sourceId = EntityId("e108"),
                    sourceSubtypes = listOf("Forest"),
                ),
            ),
            sourceActivations = emptyList(),
        )
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        val encoded = json.encodeToString(domain)
        json.decodeFromString<PaymentDomainV1>(encoded) shouldBe domain
        PAYMENT_DOMAIN_VERSION shouldBe 1
    }
})

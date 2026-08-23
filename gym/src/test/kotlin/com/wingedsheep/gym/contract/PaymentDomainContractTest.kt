package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PaymentDomainContractTest : FunSpec({

    test("the current payment domain version is V2 for homogeneous source buckets") {
        PAYMENT_DOMAIN_VERSION shouldBe 2
    }

    test("certified homogeneous floating buckets round-trip in PaymentDomain V2") {
        val domain = PaymentDomainV2(
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
            currentPool = PaymentPoolDomainV2(
                green = 2,
                certifiedFloatingMana = CertifiedHomogeneousFloatingManaDomainV2(
                    poolColor = PaymentManaColor.GREEN,
                    sourceSubtypes = listOf("Forest"),
                    sourceBuckets = listOf(
                        CertifiedFloatingManaSourceBucketDomainV2(EntityId("e108"), 1),
                        CertifiedFloatingManaSourceBucketDomainV2(EntityId("e117"), 1),
                    ),
                ),
            ),
            sourceActivations = emptyList(),
        )
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        val encoded = json.encodeToString(domain)
        json.decodeFromString<PaymentDomainV2>(encoded) shouldBe domain
        domain.version shouldBe 2
    }

    test("PaymentDomain V1 remains decodable as a historical singular-candidate fixture") {
        val domain = PaymentDomainV1(
            requiredCost = "{1}{B}",
            costUnits = listOf(
                PaymentCostUnitDomain(0, PaymentCostKind.GENERIC, 1),
                PaymentCostUnitDomain(
                    symbolIndex = 1,
                    kind = PaymentCostKind.COLORED,
                    amount = 1,
                    allowedColors = setOf(PaymentManaColor.BLACK),
                ),
            ),
            currentPool = PaymentPoolDomainV1(
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
        domain.version shouldBe 1
    }
})

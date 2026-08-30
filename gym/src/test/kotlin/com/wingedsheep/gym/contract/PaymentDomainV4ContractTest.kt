package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** RED/GREEN contract coverage for the single-list PaymentDomainV4 shape. */
class PaymentDomainV4ContractTest : FunSpec({

    test("current Gym schema identifies V5 while V4 remains historical") {
        SchemaHash.CURRENT shouldBe "argentum-gym-contract@v1.24-mana-color-domain"
        PAYMENT_DOMAIN_VERSION shouldBe PAYMENT_DOMAIN_V5_VERSION
        PAYMENT_DOMAIN_V4_VERSION shouldBe 4
    }

    test("PaymentDomainV4 round-trips canonical mixed joint buckets") {
        val domain = PaymentDomainV4(
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
            currentPool = PaymentPoolDomainV4(
                black = 1,
                green = 2,
                certifiedFloatingBuckets = listOf(
                    CertifiedFloatingManaBucketDomainV4(
                        sourceId = EntityId("e1"),
                        poolColor = PaymentManaColor.BLACK,
                        sourceSubtypes = emptyList(),
                        amount = 1,
                    ),
                    CertifiedFloatingManaBucketDomainV4(
                        sourceId = EntityId("e2"),
                        poolColor = PaymentManaColor.GREEN,
                        sourceSubtypes = listOf("Forest"),
                        amount = 2,
                    ),
                ),
            ),
            sourceActivations = emptyList(),
        )
        val json = Json { encodeDefaults = true; explicitNulls = false }

        json.decodeFromString<PaymentDomainV4>(json.encodeToString(domain)) shouldBe domain
        domain.version shouldBe PAYMENT_DOMAIN_V4_VERSION
    }

    test("PaymentDomainV4 rejects duplicate semantic bucket rows") {
        shouldThrow<IllegalArgumentException> {
            PaymentDomainV4(
                requiredCost = "{G}",
                costUnits = listOf(
                    PaymentCostUnitDomain(
                        0,
                        PaymentCostKind.COLORED,
                        1,
                        setOf(PaymentManaColor.GREEN),
                    ),
                ),
                currentPool = PaymentPoolDomainV4(
                    green = 2,
                    certifiedFloatingBuckets = listOf(
                        CertifiedFloatingManaBucketDomainV4(
                            EntityId("e1"), PaymentManaColor.GREEN, emptyList(), 1,
                        ),
                        CertifiedFloatingManaBucketDomainV4(
                            EntityId("e1"), PaymentManaColor.GREEN, emptyList(), 1,
                        ),
                    ),
                ),
                sourceActivations = emptyList(),
            )
        }
    }

    test("historical V3 decoding fails closed for a V4 version") {
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val stale = PaymentDomainV3(
            requiredCost = "{G}",
            costUnits = listOf(
                PaymentCostUnitDomain(
                    0,
                    PaymentCostKind.COLORED,
                    1,
                    setOf(PaymentManaColor.GREEN),
                ),
            ),
            currentPool = PaymentPoolDomainV3(),
            sourceActivations = emptyList(),
        )
        val element = json.parseToJsonElement(json.encodeToString(stale)).jsonObject
        val v4Element = buildJsonObject {
            element.forEach { (key, value) -> put(key, value) }
            put("version", JsonPrimitive(4))
        }

        shouldThrow<IllegalArgumentException> {
            json.decodeFromJsonElement(PaymentDomainV3.serializer(), v4Element)
        }
    }
})

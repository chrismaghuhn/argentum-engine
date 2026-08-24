package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

class PaymentDomainContractTest : FunSpec({

    test("the current payment domain version is V3") {
        PAYMENT_DOMAIN_VERSION shouldBe 3
        PAYMENT_DOMAIN_V2_VERSION shouldBe 2
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

    test("certified heterogeneous source-color buckets round-trip in PaymentDomain V3") {
        val domain = PaymentDomainV3(
            requiredCost = "{B}{G}{2}",
            costUnits = listOf(
                PaymentCostUnitDomain(
                    symbolIndex = 0,
                    kind = PaymentCostKind.COLORED,
                    amount = 1,
                    allowedColors = setOf(PaymentManaColor.BLACK),
                ),
                PaymentCostUnitDomain(
                    symbolIndex = 1,
                    kind = PaymentCostKind.COLORED,
                    amount = 1,
                    allowedColors = setOf(PaymentManaColor.GREEN),
                ),
                PaymentCostUnitDomain(2, PaymentCostKind.GENERIC, 2),
            ),
            currentPool = PaymentPoolDomainV3(
                black = 1,
                green = 3,
                certifiedHeterogeneousFloatingMana = CertifiedHeterogeneousFloatingManaDomainV3(
                    sourceColorBuckets = listOf(
                        CertifiedFloatingManaSourceColorBucketDomainV3(
                            EntityId("e108"), PaymentManaColor.BLACK, 1,
                        ),
                        CertifiedFloatingManaSourceColorBucketDomainV3(
                            EntityId("e117"), PaymentManaColor.GREEN, 1,
                        ),
                        CertifiedFloatingManaSourceColorBucketDomainV3(
                            EntityId("e136"), PaymentManaColor.GREEN, 2,
                        ),
                    ),
                    sourceSubtypes = emptyList(),
                ),
            ),
            sourceActivations = emptyList(),
        )
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        val encoded = json.encodeToString(domain)
        json.decodeFromString<PaymentDomainV3>(encoded) shouldBe domain
        domain.version shouldBe 3
        domain.currentPool.certifiedFloatingMana shouldBe null
        domain.currentPool.certifiedHeterogeneousFloatingMana!!.sourceColorBuckets.size shouldBe 3
    }

    test("PaymentDomainV3 rejects a stale V2 version before interpretation") {
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }
        val encoded = json.encodeToString(
            PaymentDomainV3(
                requiredCost = "{G}",
                costUnits = listOf(
                    PaymentCostUnitDomain(
                        symbolIndex = 0,
                        kind = PaymentCostKind.COLORED,
                        amount = 1,
                        allowedColors = setOf(PaymentManaColor.GREEN),
                    ),
                ),
                currentPool = PaymentPoolDomainV3(),
                sourceActivations = emptyList(),
            ),
        )
        val stale = JsonObject(
            json.parseToJsonElement(encoded).jsonObject + ("version" to JsonPrimitive(2)),
        )

        shouldThrow<IllegalArgumentException> {
            json.decodeFromJsonElement(PaymentDomainV3.serializer(), stale)
        }
    }

    test("historical PaymentDomainV2 decoder rejects the current V3 version") {
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }
        val current = PaymentDomainV3(
            requiredCost = "{G}",
            costUnits = listOf(
                PaymentCostUnitDomain(
                    symbolIndex = 0,
                    kind = PaymentCostKind.COLORED,
                    amount = 1,
                    allowedColors = setOf(PaymentManaColor.GREEN),
                ),
            ),
            currentPool = PaymentPoolDomainV3(
                green = 1,
                certifiedHeterogeneousFloatingMana = CertifiedHeterogeneousFloatingManaDomainV3(
                    sourceColorBuckets = listOf(
                        CertifiedFloatingManaSourceColorBucketDomainV3(
                            EntityId("e117"), PaymentManaColor.GREEN, 1,
                        ),
                    ),
                    sourceSubtypes = emptyList(),
                ),
            ),
            sourceActivations = emptyList(),
        )

        shouldThrow<IllegalArgumentException> {
            json.decodeFromString<PaymentDomainV2>(json.encodeToString(current))
        }
    }

    test("PaymentPoolDomainV3 rejects simultaneous homogeneous and heterogeneous certification") {
        shouldThrow<IllegalArgumentException> {
            PaymentPoolDomainV3(
                green = 1,
                certifiedFloatingMana = CertifiedHomogeneousFloatingManaDomainV2(
                    poolColor = PaymentManaColor.GREEN,
                    sourceSubtypes = emptyList(),
                    sourceBuckets = listOf(
                        CertifiedFloatingManaSourceBucketDomainV2(EntityId("e108"), 1),
                    ),
                ),
                certifiedHeterogeneousFloatingMana = CertifiedHeterogeneousFloatingManaDomainV3(
                    sourceColorBuckets = listOf(
                        CertifiedFloatingManaSourceColorBucketDomainV3(
                            EntityId("e108"), PaymentManaColor.GREEN, 1,
                        ),
                    ),
                    sourceSubtypes = emptyList(),
                ),
            )
        }
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

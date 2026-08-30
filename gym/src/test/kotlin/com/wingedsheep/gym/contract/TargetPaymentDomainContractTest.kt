package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class TargetPaymentDomainContractTest : FunSpec({

    fun paymentDomain(requiredCost: String): PaymentDomainV5 {
        val parsedCost = ManaCost.parse(requiredCost)
        val atomicUnits = parsedCost.toAtomicDomain()
            ?: error("fixture cost is outside the V5 ordinary-mana slice")
        return PaymentDomainV5(
            requiredCost = requiredCost,
            outerAtomicCostUnits = atomicUnits,
            initialPoolBuckets = emptyList(),
            sourceActivationOptions = emptyList(),
        )
    }

    test("publishes one non-null V5 domain per target in producer order") {
        val domain = TargetPaymentDomainV1(
            targetBindings = listOf(
                TargetPaymentBindingV1(
                    target = EntityId("target-a"),
                    affordable = true,
                    paymentDomain = paymentDomain("{0}"),
                ),
                TargetPaymentBindingV1(
                    target = EntityId("target-b"),
                    affordable = false,
                    paymentDomain = paymentDomain("{2}"),
                ),
            ),
        )

        domain.targetBindings.map { it.target } shouldBe
            listOf(EntityId("target-a"), EntityId("target-b"))
        domain.targetBindings.map { it.paymentDomain.requiredCost } shouldBe listOf("{0}", "{2}")
    }

    test("rejects an unsupported target payment domain version") {
        shouldThrow<IllegalArgumentException> {
            TargetPaymentDomainV1(
                version = 2,
                targetBindings = listOf(
                    TargetPaymentBindingV1(
                        target = EntityId("target"),
                        affordable = true,
                        paymentDomain = paymentDomain("{0}"),
                    ),
                ),
            )
        }
    }

    test("rejects an empty target payment domain") {
        shouldThrow<IllegalArgumentException> {
            TargetPaymentDomainV1(targetBindings = emptyList())
        }
    }

    test("rejects duplicate target bindings") {
        val binding = TargetPaymentBindingV1(
            target = EntityId("duplicate"),
            affordable = true,
            paymentDomain = paymentDomain("{0}"),
        )

        shouldThrow<IllegalArgumentException> {
            TargetPaymentDomainV1(targetBindings = listOf(binding, binding))
        }
    }

    test("LegalActionView carries and serializes the target payment relation") {
        val domain = TargetPaymentDomainV1(
            targetBindings = listOf(
                TargetPaymentBindingV1(
                    target = EntityId("target"),
                    affordable = true,
                    paymentDomain = paymentDomain("{2}"),
                ),
            ),
        )
        val view = LegalActionView(
            actionId = 7,
            kind = "ActivateAbility",
            description = "Target-dependent payment",
            affordable = true,
            targetPaymentDomain = domain,
        )

        view.targetPaymentDomain shouldBe domain

        val jsonFormat = Json { encodeDefaults = true }
        val json = jsonFormat.encodeToString(view)
        jsonFormat.decodeFromString<LegalActionView>(json) shouldBe view
    }
})

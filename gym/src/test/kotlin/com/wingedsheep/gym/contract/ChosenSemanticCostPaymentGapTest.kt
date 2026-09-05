package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RED characterization for the first A9 generation boundary: the public policy can choose a
 * real `costPayment`, but the durable chosen-action contract currently has no validator for it.
 *
 * The test intentionally asserts today's rejection so the ordinary suite remains green. The
 * desired post-fix behavior is that the same real action is admitted as a chosen semantic input.
 */
class ChosenSemanticCostPaymentGapTest : FunSpec({

    test("public costPayment cannot become a durable chosen action") {
        val player = EntityId("player")
        val source = EntityId("source")
        val sacrificed = EntityId("sacrificed")
        val action = ActivateAbility(
            playerId = player,
            sourceId = source,
            abilityId = AbilityId("activated"),
            costPayment = AdditionalCostPayment(
                sacrificedPermanents = listOf(sacrificed),
            ),
        )
        val view = LegalActionView(
            actionId = 0,
            kind = "ActivateAbility",
            description = "activated ability with public cost payment",
            affordable = true,
            sourceEntityId = source,
            validSacrificeTargets = listOf(sacrificed),
            sacrificeCount = 1,
            sacrificeMinCount = 1,
            sacrificeMaxCount = 1,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("costPayment"),
            actionSemantics = buildJsonObject {
                put("type", "ActivateAbility")
                put("abilityKey", buildJsonObject {
                    put("origin", "a9-cost-payment-gap")
                    put("ordinal", 0)
                })
            },
        )
        val candidate = ObservationCanonicalizer.semanticActionFingerprint(view)
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )

        val failure = shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.fromRecordedAction(domain, candidate, action)
        }
        failure.message shouldBe
            "Chosen action payload has no complete stored-domain validator for: costPayment"
    }
})

package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RED characterization for the next A9 boundary: CastSpell's public additional-cost choice is
 * not yet represented by the durable chosen-action validator.
 */
class ChosenSemanticAdditionalCostPaymentGapTest : FunSpec({

    test("public additionalCostPayment cannot become a durable chosen action") {
        val player = EntityId("player")
        val card = EntityId("spell")
        val sacrifice = EntityId("sacrifice")
        val candidate = publicCandidate(sacrifice)
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )
        val action = CastSpell(
            playerId = player,
            cardId = card,
            additionalCostPayment = AdditionalCostPayment(
                sacrificedPermanents = listOf(sacrifice),
            ),
        )

        val failure = shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.fromRecordedAction(domain, candidate, action)
        }

        failure.message shouldBe
            "Chosen action payload has no complete stored-domain validator for: additionalCostPayment"
    }
})

private fun publicCandidate(sacrifice: EntityId): JsonObject =
    ObservationCanonicalizer.semanticActionFingerprint(
        LegalActionView(
            actionId = 0,
            kind = "CastSpell",
            description = "spell with a public sacrifice cost",
            affordable = true,
            validSacrificeTargets = listOf(sacrifice),
            sacrificeCount = 1,
            sacrificeMinCount = 1,
            sacrificeMaxCount = 1,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("additionalCostPayment"),
            actionSemantics = buildJsonObject { put("type", "CastSpell") },
        ),
    )

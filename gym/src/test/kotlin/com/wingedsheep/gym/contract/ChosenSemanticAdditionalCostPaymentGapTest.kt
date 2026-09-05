package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.DistributedCounterRemoval
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * RED-to-GREEN contract coverage for CastSpell's public `additionalCostPayment` input.
 *
 * The field is intentionally separate from ActivateAbility's `costPayment` contract. These tests
 * reuse the same public sacrifice-domain semantics while keeping the action-specific payload key.
 */
class ChosenSemanticAdditionalCostPaymentGapTest : FunSpec({

    val player = EntityId("player")
    val card = EntityId("spell")
    val firstSacrifice = EntityId("sacrifice-a")
    val secondSacrifice = EntityId("sacrifice-b")

    fun candidate(
        sacrificeTargets: List<EntityId> = listOf(firstSacrifice),
        sacrificeCount: Int = 1,
        sacrificeMinCount: Int = 1,
        sacrificeMaxCount: Int = 1,
    ): JsonObject {
        val view = LegalActionView(
            actionId = 0,
            kind = "CastSpell",
            description = "presentation-only spell with a public sacrifice cost",
            affordable = true,
            validSacrificeTargets = sacrificeTargets,
            sacrificeCount = sacrificeCount,
            sacrificeMinCount = sacrificeMinCount,
            sacrificeMaxCount = sacrificeMaxCount,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("additionalCostPayment"),
            actionSemantics = buildJsonObject { put("type", "CastSpell") },
        )
        return ObservationCanonicalizer.semanticActionFingerprint(view)
    }

    fun domain(candidate: JsonObject): CompleteLegalDomainV1 = CompleteLegalDomainV1(
        kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
        candidates = listOf(candidate),
    )

    fun action(payment: AdditionalCostPayment): CastSpell = CastSpell(
        playerId = player,
        cardId = card,
        additionalCostPayment = payment,
    )

    fun encodedPayment(payment: AdditionalCostPayment): JsonElement =
        A3SemanticJson.strictJson.encodeToJsonElement(
            AdditionalCostPayment.serializer(),
            payment,
        )

    fun recordedChosen(
        candidate: JsonObject,
        payment: AdditionalCostPayment,
    ): ChosenSemanticActionV1 = ChosenSemanticActionV1.fromRecordedAction(
        domain(candidate),
        candidate,
        action(payment),
    )

    fun assertRejected(
        candidate: JsonObject,
        payment: AdditionalCostPayment,
    ) {
        shouldThrow<IllegalArgumentException> {
            recordedChosen(candidate, payment)
        }
    }

    test("public sacrifice additionalCostPayment becomes a durable chosen action") {
        val storedCandidate = candidate()
        val payment = AdditionalCostPayment(
            sacrificedPermanents = listOf(firstSacrifice),
        )

        val chosen = recordedChosen(storedCandidate, payment)

        chosen.candidate shouldBe storedCandidate
        chosen.choicePayload shouldBe buildJsonObject {
            put("additionalCostPayment", encodedPayment(payment))
        }
    }

    test("a sacrifice outside the stored public domain is rejected") {
        assertRejected(
            candidate(sacrificeTargets = listOf(firstSacrifice)),
            AdditionalCostPayment(sacrificedPermanents = listOf(secondSacrifice)),
        )
    }

    test("duplicate sacrificed permanents are rejected") {
        assertRejected(
            candidate(
                sacrificeTargets = listOf(firstSacrifice, secondSacrifice),
                sacrificeMinCount = 1,
                sacrificeMaxCount = 2,
            ),
            AdditionalCostPayment(sacrificedPermanents = listOf(firstSacrifice, firstSacrifice)),
        )
    }

    test("too few sacrifices are rejected") {
        assertRejected(
            candidate(),
            AdditionalCostPayment(),
        )
    }

    test("too many sacrifices are rejected") {
        assertRejected(
            candidate(
                sacrificeTargets = listOf(firstSacrifice, secondSacrifice),
                sacrificeMinCount = 1,
                sacrificeMaxCount = 1,
            ),
            AdditionalCostPayment(
                sacrificedPermanents = listOf(firstSacrifice, secondSacrifice),
            ),
        )
    }

    test("malformed stored sacrifice domains are rejected") {
        shouldThrow<IllegalArgumentException> {
            domain(
                candidate(
                    sacrificeTargets = listOf(firstSacrifice, firstSacrifice),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            domain(
                candidate(
                    sacrificeMinCount = 2,
                    sacrificeMaxCount = 1,
                ),
            )
        }
    }

    test("malformed additionalCostPayment JSON is rejected") {
        val storedCandidate = candidate()
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(
                domain(storedCandidate),
                storedCandidate,
                buildJsonObject {
                    put("additionalCostPayment", JsonPrimitive("not-an-object"))
                },
            )
        }
    }

    test("unknown nested additionalCostPayment fields are rejected") {
        val storedCandidate = candidate()
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(
                domain(storedCandidate),
                storedCandidate,
                buildJsonObject {
                    put("additionalCostPayment", buildJsonObject {
                        put("bargainSacrifice", JsonPrimitive(firstSacrifice.value))
                    })
                },
            )
        }
    }

    test("unsupported non-default additional-cost channels remain fail-closed") {
        val storedCandidate = candidate()
        listOf(
            AdditionalCostPayment(discardedCards = listOf(firstSacrifice)),
            AdditionalCostPayment(exiledCards = listOf(firstSacrifice)),
            AdditionalCostPayment(lifePaid = 1),
            AdditionalCostPayment(variableCostPermanents = listOf(firstSacrifice)),
            AdditionalCostPayment(beheldCards = listOf(firstSacrifice)),
            AdditionalCostPayment(tappedPermanents = listOf(firstSacrifice)),
            AdditionalCostPayment(bouncedPermanents = listOf(firstSacrifice)),
            AdditionalCostPayment(blightTargets = listOf(firstSacrifice)),
            AdditionalCostPayment(blightAmount = 1),
            AdditionalCostPayment(payXLifeAmount = 1),
            AdditionalCostPayment(
                distributedCounterRemovals = listOf(
                    DistributedCounterRemoval(
                        entityId = firstSacrifice,
                        counterType = "+1/+1",
                        count = 1,
                    ),
                ),
            ),
        ).forEach { payment -> assertRejected(storedCandidate, payment) }
    }

    test("canonical no-op additional-cost channels remain accepted") {
        val storedCandidate = candidate()
        val payment = AdditionalCostPayment(
            discardedCards = emptyList(),
            exiledCards = emptyList(),
            lifePaid = 0,
            variableCostPermanents = emptyList(),
            beheldCards = emptyList(),
            tappedPermanents = emptyList(),
            bouncedPermanents = emptyList(),
            blightTargets = emptyList(),
            blightAmount = 0,
            payXLifeAmount = 0,
            distributedCounterRemovals = emptyList(),
            sacrificedPermanents = listOf(firstSacrifice),
        )

        recordedChosen(storedCandidate, payment).choicePayload["additionalCostPayment"] shouldBe
            encodedPayment(payment)
    }
})

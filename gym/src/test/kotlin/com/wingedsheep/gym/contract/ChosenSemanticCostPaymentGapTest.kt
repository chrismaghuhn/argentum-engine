package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.DistributedCounterRemoval
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * RED-to-GREEN contract coverage for the public `costPayment` chosen-input seam.
 *
 * The first test is the original A9 characterization, now expressing the desired behavior. The
 * negative cases ensure adding the field to the validator cannot become an unchecked allow-list.
 */
class ChosenSemanticCostPaymentGapTest : FunSpec({

    val player = EntityId("player")
    val source = EntityId("source")
    val firstSacrifice = EntityId("sacrifice-a")
    val secondSacrifice = EntityId("sacrifice-b")

    fun cost(type: String): JsonObject = buildJsonObject { put("type", type) }

    fun sacrificeCost(count: Int = 1): JsonObject = buildJsonObject {
        put("type", "CostAtomWrapper")
        put("atom", buildJsonObject {
            put("type", "AtomSacrifice")
            put("count", count)
        })
    }

    fun compositeCost(vararg costs: JsonObject): JsonObject = buildJsonObject {
        put("type", "CostComposite")
        put("costs", buildJsonArray { costs.forEach(::add) })
    }

    fun candidate(
        sacrificeTargets: List<EntityId> = listOf(firstSacrifice),
        sacrificeCount: Int = 1,
        sacrificeMinCount: Int = 1,
        sacrificeMaxCount: Int = 1,
        abilityCost: JsonObject = sacrificeCost(),
    ): JsonObject {
        val view = LegalActionView(
            actionId = 0,
            kind = "ActivateAbility",
            description = "presentation-only activated ability",
            affordable = true,
            sourceEntityId = source,
            validSacrificeTargets = sacrificeTargets,
            sacrificeCount = sacrificeCount,
            sacrificeMinCount = sacrificeMinCount,
            sacrificeMaxCount = sacrificeMaxCount,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("costPayment"),
            actionSemantics = buildJsonObject {
                put("type", "ActivateAbility")
                put("abilityKey", buildJsonObject {
                    put("ability", buildJsonObject { put("cost", abilityCost) })
                })
            },
        )
        return ObservationCanonicalizer.semanticActionFingerprint(view)
    }

    fun domain(candidate: JsonObject): CompleteLegalDomainV1 = CompleteLegalDomainV1(
        kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
        candidates = listOf(candidate),
    )

    fun action(payment: AdditionalCostPayment): ActivateAbility = ActivateAbility(
        playerId = player,
        sourceId = source,
        abilityId = AbilityId("activated"),
        costPayment = payment,
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

    test("public sacrifice costPayment becomes a durable chosen action") {
        val storedCandidate = candidate()
        val payment = AdditionalCostPayment(
            sacrificedPermanents = listOf(firstSacrifice),
        )

        val chosen = recordedChosen(storedCandidate, payment)

        chosen.candidate shouldBe storedCandidate
        chosen.choicePayload shouldBe buildJsonObject {
            put("costPayment", encodedPayment(payment))
        }
    }

    test("source-bound tap and sacrifice costPayment retain both public semantic legs") {
        val storedCandidate = candidate(
            sacrificeTargets = listOf(source),
            abilityCost = compositeCost(cost("CostTap"), cost("CostSacrificeSelf")),
        )
        val payment = AdditionalCostPayment(
            tappedPermanents = listOf(source),
            sacrificedPermanents = listOf(source),
        )

        recordedChosen(storedCandidate, payment).choicePayload shouldBe buildJsonObject {
            put("costPayment", encodedPayment(payment))
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

    test("fixed sacrifice count from public action semantics is enforced") {
        assertRejected(
            candidate(
                sacrificeTargets = listOf(firstSacrifice, secondSacrifice),
                sacrificeCount = 2,
                sacrificeMinCount = 1,
                sacrificeMaxCount = 2,
                abilityCost = sacrificeCost(count = 2),
            ),
            AdditionalCostPayment(sacrificedPermanents = listOf(firstSacrifice)),
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

    test("malformed costPayment JSON is rejected") {
        val storedCandidate = candidate()
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(
                domain(storedCandidate),
                storedCandidate,
                buildJsonObject { put("costPayment", JsonPrimitive("not-an-object")) },
            )
        }
    }

    test("unknown nested costPayment fields are rejected") {
        val storedCandidate = candidate()
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(
                domain(storedCandidate),
                storedCandidate,
                buildJsonObject {
                    put("costPayment", buildJsonObject {
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
            AdditionalCostPayment(tappedPermanents = listOf(source)),
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
            bouncedPermanents = emptyList(),
            blightTargets = emptyList(),
            blightAmount = 0,
            payXLifeAmount = 0,
            distributedCounterRemovals = emptyList(),
            sacrificedPermanents = listOf(firstSacrifice),
        )

        recordedChosen(storedCandidate, payment).choicePayload["costPayment"] shouldBe
            encodedPayment(payment)
    }

    test("costPayment validation has no runtime authority dependency") {
        var root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        var sourcePath: Path? = null
        while (sourcePath == null) {
            val candidatePath = root.resolve(
                "gym/src/main/kotlin/com/wingedsheep/gym/contract/ChosenSemanticInput.kt",
            )
            if (Files.exists(candidatePath)) {
                sourcePath = candidatePath
            } else {
                root = root.parent ?: error("Could not locate ChosenSemanticInput.kt")
            }
        }
        val validatorSource = checkNotNull(sourcePath).toFile().readText()
            .substringAfter("internal object StoredActionPayloadValidator")
        listOf(
            "GameState",
            "CardRegistry",
            "ManaSolver",
            "ObservationBuilder",
            "ActionRegistry",
        ).forEach { forbidden ->
            check(forbidden !in validatorSource) {
                "StoredActionPayloadValidator gained a runtime authority dependency: $forbidden"
            }
        }
    }
})

package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** RED → GREEN contract coverage for the public repeat-count choice. */
class ChosenSemanticRepeatCountGapTest : FunSpec({
    test("the public minimum repeat count is accepted from the stored domain") {
        val candidate = repeatCandidate(RepeatCountDomainV1(maxCount = 2))

        val chosen = ChosenSemanticActionV1.fromRecordedAction(
            domain = actionDomain(candidate),
            candidate = candidate,
            action = recordedAction(repeatCount = 1),
        )

        chosen.choicePayload["repeatCount"] shouldBe JsonPrimitive(1)
    }

    test("the public maximum repeat count is accepted from the stored domain") {
        val candidate = repeatCandidate(RepeatCountDomainV1(maxCount = 2))

        val chosen = ChosenSemanticActionV1.fromRecordedAction(
            domain = actionDomain(candidate),
            candidate = candidate,
            action = recordedAction(repeatCount = 2),
        )

        chosen.choicePayload["repeatCount"] shouldBe JsonPrimitive(2)
    }

    test("zero, negative, and above-maximum repeat counts are rejected") {
        val candidate = repeatCandidate(RepeatCountDomainV1(maxCount = 2))

        listOf(0, -1, 3).forEach { value ->
            shouldThrow<IllegalArgumentException> {
                ChosenSemanticActionV1.from(
                    domain = actionDomain(candidate),
                    candidate = candidate,
                    choicePayload = repeatPayload(JsonPrimitive(value)),
                )
            }
        }
    }

    test("a malformed repeat-count value is rejected") {
        val candidate = repeatCandidate(RepeatCountDomainV1(maxCount = 2))

        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.from(
                domain = actionDomain(candidate),
                candidate = candidate,
                choicePayload = repeatPayload(JsonPrimitive("2")),
            )
        }
    }

    test("a repeat choice without a stored public domain fails closed") {
        val candidate = repeatCandidate(domain = null)

        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.fromRecordedAction(
                domain = actionDomain(candidate),
                candidate = candidate,
                action = recordedAction(repeatCount = 1),
            )
        }
    }

    test("a malformed stored repeat domain fails closed") {
        val candidate = replaceJsonValue(
            repeatCandidate(RepeatCountDomainV1(maxCount = 2)),
            "repeatCountDomain",
            buildJsonObject {
                put("version", REPEAT_COUNT_DOMAIN_V1_VERSION)
                put("minCount", 0)
                put("maxCount", 2)
            },
        )

        shouldThrow<IllegalArgumentException> {
            actionDomain(candidate)
        }
    }

    test("a repeat domain with an unknown nested field fails closed") {
        val candidate = replaceJsonValue(
            repeatCandidate(RepeatCountDomainV1(maxCount = 2)),
            "repeatCountDomain",
            buildJsonObject {
                put("version", REPEAT_COUNT_DOMAIN_V1_VERSION)
                put("minCount", 1)
                put("maxCount", 2)
                put("futureChoice", true)
            },
        )

        shouldThrow<IllegalArgumentException> {
            actionDomain(candidate)
        }
    }

    test("the candidate fingerprint and digest bind the repeat domain maximum") {
        val lower = repeatCandidate(RepeatCountDomainV1(maxCount = 2))
        val higher = repeatCandidate(RepeatCountDomainV1(maxCount = 3))

        lower shouldNotBe higher
        CandidateDomainDigestV1.from(actionDomain(lower)).value shouldNotBe
            CandidateDomainDigestV1.from(actionDomain(higher)).value
    }

    test("repeatCount is required only when Rules publishes a real choice") {
        val action = LegalAction(
            action = recordedAction(repeatCount = 1),
            actionType = "ActivateAbility",
            description = "repeatable activation",
            maxRepeatableActivations = 1,
        )

        ActionPayloadRequirements.requiredPayloadFields(action) shouldBe emptyList()
        ActionPayloadRequirements.requiredPayloadFields(action.copy(maxRepeatableActivations = 2)) shouldBe
            listOf("repeatCount")
    }
})

private val repeatPlayer = EntityId("repeat-player")
private val repeatSource = EntityId("repeat-source")
private val repeatAbility = AbilityId("repeatable-ability")

private fun repeatCandidate(domain: RepeatCountDomainV1?): JsonObject =
    ObservationCanonicalizer.semanticActionFingerprint(
        LegalActionView(
            actionId = 0,
            kind = "ActivateAbility",
            description = "repeatable activated ability",
            affordable = true,
            sourceEntityId = repeatSource,
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("repeatCount"),
            repeatCountDomain = domain,
            actionSemantics = buildJsonObject {
                put("type", "ActivateAbility")
                put("abilityKey", buildJsonObject {
                    put("ability", buildJsonObject {})
                })
                // This is a template hint, not the public repeat-count authority. The tests
                // deliberately choose the domain maximum while the hint remains one.
                put("repeatCount", 1)
            },
        ),
    )

private fun actionDomain(candidate: JsonObject): CompleteLegalDomainV1 = CompleteLegalDomainV1(
    kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
    candidates = listOf(candidate),
)

private fun recordedAction(repeatCount: Int): ActivateAbility = ActivateAbility(
    playerId = repeatPlayer,
    sourceId = repeatSource,
    abilityId = repeatAbility,
    repeatCount = repeatCount,
)

private fun repeatPayload(value: JsonElement): JsonObject = buildJsonObject {
    put("repeatCount", value)
}

private fun replaceJsonValue(
    objectValue: JsonObject,
    key: String,
    value: JsonElement,
): JsonObject = buildJsonObject {
    objectValue.forEach { (existingKey, existingValue) ->
        put(existingKey, if (existingKey == key) value else existingValue)
    }
}

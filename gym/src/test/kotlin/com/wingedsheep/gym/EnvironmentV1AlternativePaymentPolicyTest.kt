package com.wingedsheep.gym

import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.paymentPlanV3FromPublic
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

class EnvironmentV1AlternativePaymentPolicyTest : FunSpec({
    test("accepts only candidate-bound equip alternative payments from action semantics") {
        ("alternativePayment" in EXTERNAL_POLICY_SUPPORTED_REQUIRED_PAYLOAD_FIELDS) shouldBe false

        listOf("NORMAL", "FREE_FIRST_EQUIP").forEach { equipPayment ->
            val alternativePayment = buildJsonObject {
                put("equipPayment", equipPayment)
            }
            val choice = DeterministicExternalPolicy().choose(
                observation = observationFor(equipAction(alternativePayment)),
                policyState = DeterministicPolicyState(policySeed = 1L),
            ).shouldBeInstanceOf<SemanticChoice.Action>()

            choice.payload?.get("alternativePayment") shouldBe alternativePayment
            Json.decodeFromJsonElement(
                PaymentStrategy.serializer(),
                choice.payload?.get("paymentStrategy")!!,
            ) shouldBe PaymentStrategy.ExplicitV3(paymentPlan = PaymentPlanV3())
        }
    }

    test("keeps resource, invalid, and missing alternative payments fail-closed") {
        val resourceAlternativePayment = buildJsonObject {
            put("equipPayment", "NORMAL")
            put("harmonizeCreature", "creature")
        }
        val resourceChoice = DeterministicExternalPolicy().choose(
            observation = observationFor(equipAction(resourceAlternativePayment)),
            policyState = DeterministicPolicyState(policySeed = 1L),
        ).shouldBeInstanceOf<SemanticChoice.Gap>()
        resourceChoice.code shouldBe "A5_DECISION_GAP"

        val invalidAlternativePayment = buildJsonObject {
            put("equipPayment", "FUTURE_EQUIP_MODE")
        }
        val invalidChoice = DeterministicExternalPolicy().choose(
            observation = observationFor(equipAction(invalidAlternativePayment)),
            policyState = DeterministicPolicyState(policySeed = 1L),
        ).shouldBeInstanceOf<SemanticChoice.Gap>()
        invalidChoice.code shouldBe "A5_DECISION_GAP"

        val missingSemanticAction = equipAction(
            alternativePayment = buildJsonObject { put("equipPayment", "NORMAL") },
        ).copy(
            actionSemantics = buildJsonObject { put("type", "ActivateAbility") },
        )
        val missingChoice = DeterministicExternalPolicy().choose(
            observation = observationFor(missingSemanticAction),
            policyState = DeterministicPolicyState(policySeed = 1L),
        ).shouldBeInstanceOf<SemanticChoice.Gap>()
        missingChoice.code shouldBe "A5_DECISION_GAP"
    }
})

private fun equipAction(alternativePayment: JsonObject): LegalActionView = LegalActionView(
    actionId = 1,
    kind = "ActivateAbility",
    description = "Equip {0}",
    affordable = true,
    sourceEntityId = EntityId("equipment"),
    manaCost = "{0}",
    paymentDomain = PaymentDomainV5(
        requiredCost = "{0}",
        outerAtomicCostUnits = emptyList(),
        initialPoolBuckets = emptyList(),
        sourceActivationOptions = emptyList(),
    ),
    requiresStructuredAction = true,
    requiredPayloadFields = listOf("paymentStrategy", "alternativePayment"),
    actionSemantics = buildJsonObject {
        put("type", "ActivateAbility")
        put("alternativePayment", alternativePayment)
    },
)

private fun observationFor(action: LegalActionView): TrainingObservation {
    paymentPlanV3FromPublic(action.paymentDomain!!) shouldBe PaymentPlanV3()
    val player = EntityId("player-0")
    return TrainingObservation(
        schemaHash = "test-schema",
        perspectivePlayerId = player,
        agentToAct = player,
        turnNumber = 1,
        phase = Phase.PRECOMBAT_MAIN,
        step = Step.PRECOMBAT_MAIN,
        activePlayerId = player,
        priorityPlayerId = player,
        players = emptyList(),
        zones = emptyList(),
        stack = emptyList(),
        pendingDecision = null,
        legalActions = listOf(action),
        terminated = false,
        truncated = false,
        winnerId = null,
        stateDigest = "digest",
    )
}

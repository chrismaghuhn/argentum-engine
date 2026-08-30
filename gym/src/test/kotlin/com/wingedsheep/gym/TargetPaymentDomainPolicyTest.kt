package com.wingedsheep.gym

import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.gym.contract.ActionTargetDomainV1
import com.wingedsheep.gym.contract.EntityFeatures
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ManaPoolView
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.gym.contract.PlayerView
import com.wingedsheep.gym.contract.TargetPaymentBindingV1
import com.wingedsheep.gym.contract.TargetPaymentDomainV1
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.contract.ZoneView
import com.wingedsheep.gym.contract.toAtomicDomain
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class TargetPaymentDomainPolicyTest : FunSpec({

    val player = EntityId("player")
    val affordableTarget = EntityId("target-affordable")
    val unaffordableTarget = EntityId("target-unaffordable")

    fun paymentDomain(requiredCost: String): PaymentDomainV5 {
        val cost = ManaCost.parse(requiredCost)
        return PaymentDomainV5(
            requiredCost = requiredCost,
            outerAtomicCostUnits = cost.toAtomicDomain()
                ?: error("Fixture cost is outside the ordinary V5 slice"),
            initialPoolBuckets = emptyList(),
            sourceActivationOptions = emptyList(),
        )
    }

    fun targetDomain(candidates: List<EntityId>) = ActionTargetDomainV1(
        requirements = listOf(
            TargetRequirementDomain(
                index = 0,
                description = "target permanent",
                minTargets = 1,
                maxTargets = 1,
                candidates = candidates,
                targetZone = "BATTLEFIELD",
                mustDifferFromEarlier = false,
                sameController = false,
                sameOwner = false,
                sameCreatureType = false,
                sameCardType = false,
                totalManaValueAtMost = null,
                differentNames = false,
                xConstrainsManaValue = false,
                xConstrainsManaValueExactly = false,
                xConstrainsPower = false,
                xConstrainsCount = false,
            ),
        ),
    )

    fun targetPaymentDomain(bindings: List<TargetPaymentBindingV1>) =
        TargetPaymentDomainV1(targetBindings = bindings)

    fun action(
        candidates: List<EntityId> = listOf(affordableTarget, unaffordableTarget),
        bindings: List<TargetPaymentBindingV1> = listOf(
            TargetPaymentBindingV1(
                target = affordableTarget,
                affordable = true,
                paymentDomain = paymentDomain("{0}"),
            ),
            TargetPaymentBindingV1(
                target = unaffordableTarget,
                affordable = false,
                paymentDomain = paymentDomain("{2}"),
            ),
        ),
        parentManaCost: String? = "{99}",
        parentPaymentDomain: PaymentDomainV5? = paymentDomain("{99}"),
    ) = LegalActionView(
        actionId = 7,
        kind = "ActivateAbility",
        description = "Target-bound payment action",
        affordable = true,
        targetEntityIds = candidates,
        targetDomain = targetDomain(candidates),
        manaCost = parentManaCost,
        paymentDomain = parentPaymentDomain,
        requiresStructuredAction = true,
        requiredPayloadFields = listOf("targets", "paymentStrategy"),
        actionSemantics = buildJsonObject {
            put("type", "ActivateAbility")
            put("manaCost", "{99}")
            put("cardName", "not an authority")
        },
        targetPaymentDomain = targetPaymentDomain(bindings),
    )

    fun observation(action: LegalActionView) = TrainingObservation(
        schemaHash = "test-schema",
        perspectivePlayerId = player,
        agentToAct = player,
        turnNumber = 1,
        phase = Phase.PRECOMBAT_MAIN,
        step = Step.PRECOMBAT_MAIN,
        activePlayerId = player,
        priorityPlayerId = player,
        players = listOf(
            PlayerView(
                id = player,
                name = "Player",
                lifeTotal = 20,
                handSize = 0,
                librarySize = 0,
                graveyardSize = 0,
                exileSize = 0,
                manaPool = ManaPoolView(),
                isPerspective = true,
                isActive = true,
                hasPriority = true,
                hasLost = false,
            ),
        ),
        zones = listOf(
            ZoneView(
                ownerId = player,
                zoneType = Zone.BATTLEFIELD,
                hidden = false,
                size = 2,
                cards = listOf(
                    permanent(affordableTarget),
                    permanent(unaffordableTarget),
                ),
            ),
        ),
        stack = emptyList(),
        pendingDecision = null,
        legalActions = listOf(action),
        terminated = false,
        truncated = false,
        winnerId = null,
        stateDigest = "digest",
    )

    fun policyChoice(action: LegalActionView): SemanticChoice = DeterministicExternalPolicy().choose(
        observation(action),
        DeterministicPolicyState(policySeed = 1L),
    )

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    test("selects only the affordable published binding and its V5 plan") {
        val action = action()
        val choice = policyChoice(action).shouldBeInstanceOf<SemanticChoice.Action>()
        val payload = choice.payload ?: error("Target-payment choice omitted its payload")

        payload["targets"] shouldBe JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "Permanent")
                    put("entityId", affordableTarget.value)
                },
            ),
        )
        actionJson.decodeFromJsonElement(
            PaymentStrategy.serializer(),
            payload["paymentStrategy"] ?: error("Target-payment choice omitted paymentStrategy"),
        ) shouldBe PaymentStrategy.ExplicitV3(paymentPlan = PaymentPlanV3())
    }

    test("fails closed when a target binding is missing from the published candidate set") {
        val choice = policyChoice(
            action(
                bindings = listOf(
                    TargetPaymentBindingV1(
                        target = affordableTarget,
                        affordable = true,
                        paymentDomain = paymentDomain("{0}"),
                    ),
                ),
            ),
        ).shouldBeInstanceOf<SemanticChoice.Gap>()

        choice.family shouldBe "TARGET_PAYMENT"
        choice.code shouldBe "PAYMENT_DOMAIN_UNSUPPORTED"
    }

    test("fails closed when the public target candidate list contains a duplicate") {
        val choice = policyChoice(
            action(candidates = listOf(affordableTarget, affordableTarget)),
        ).shouldBeInstanceOf<SemanticChoice.Gap>()

        choice.family shouldBe "TARGET_PAYMENT"
        choice.code shouldBe "PAYMENT_DOMAIN_UNSUPPORTED"
    }

    test("fails closed when target bindings are not in the published candidate order") {
        val choice = policyChoice(
            action(
                bindings = listOf(
                    TargetPaymentBindingV1(
                        target = unaffordableTarget,
                        affordable = false,
                        paymentDomain = paymentDomain("{2}"),
                    ),
                    TargetPaymentBindingV1(
                        target = affordableTarget,
                        affordable = true,
                        paymentDomain = paymentDomain("{0}"),
                    ),
                ),
            ),
        ).shouldBeInstanceOf<SemanticChoice.Gap>()

        choice.family shouldBe "TARGET_PAYMENT"
        choice.code shouldBe "PAYMENT_DOMAIN_UNSUPPORTED"
    }

    test("fails closed when the target-payment relation is missing") {
        val missingRelation = action().copy(targetPaymentDomain = null)
        val choice = policyChoice(missingRelation).shouldBeInstanceOf<SemanticChoice.Gap>()

        choice.code shouldBe "PAYMENT_DOMAIN_UNSUPPORTED"
    }
})

private fun permanent(entityId: EntityId) = EntityFeatures(
    entityId = entityId,
    cardDefinitionId = "test-permanent",
    name = "Test Permanent",
    zone = Zone.BATTLEFIELD,
    ownerId = EntityId("player"),
    controllerId = EntityId("player"),
    types = setOf("CREATURE"),
    subtypes = emptySet(),
    colors = emptySet(),
    keywords = emptySet(),
    manaCost = "",
    manaValue = 0,
    power = 1,
    toughness = 1,
)

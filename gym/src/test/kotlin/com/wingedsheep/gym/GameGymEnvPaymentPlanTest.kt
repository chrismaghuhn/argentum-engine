package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.CostUnitAllocation
import com.wingedsheep.engine.core.ManaSpendReference
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.SpendAllocation
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** End-to-end Gym materialization tests for the public PaymentPlanV1 boundary. */
class GameGymEnvPaymentPlanTest : FunSpec({

    val anyColorSource = card("Gym Payment Any Color Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddManaOfChoice()
            manaAbility = true
        }
    }

    val payableAbilitySource = card("Gym Payment Payable Ability Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Mana("{1}{B}")
            effect = Effects.GainLife(1)
        }
    }

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(anyColorSource)
        register(payableAbilitySource)
    }

    fun prepared(): Pair<GameGymEnv, LegalActionView> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            payableAbilitySource.name to 1,
                            anyColorSource.name to 2,
                            "Mountain" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 81231L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().firstOrNull { it.action is com.wingedsheep.engine.core.PassPriority }
                ?: error("Expected priority while preparing payment-plan Gym state")
            environment.step(pass.action)
            state = environment.state
        }

        val hand = ZoneKey(player, Zone.HAND)
        val library = ZoneKey(player, Zone.LIBRARY)
        for (id in state.getZone(hand).toList()) {
            state = state.moveToZone(id, hand, library)
        }
        fun moveNamed(name: String, zone: Zone) {
            val id = state.getZone(library).firstOrNull { candidate ->
                state.getEntity(candidate)?.get<CardComponent>()?.name == name
            } ?: error("Could not find '$name' in prepared library")
            state = state.moveToZone(id, library, ZoneKey(player, zone))
        }
        moveNamed(payableAbilitySource.name, Zone.BATTLEFIELD)
        moveNamed(anyColorSource.name, Zone.BATTLEFIELD)
        moveNamed(anyColorSource.name, Zone.BATTLEFIELD)
        environment.restore(state, environment.playerIds, environment.stepCount)

        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        val view = gym.observe().observation.legalActions.firstOrNull { action ->
            action.kind == "ActivateAbility" && action.manaCost == "{1}{B}"
        } ?: error("Expected payable ActivateAbility: ${gym.observe().observation.legalActions}")
        return gym to view
    }

    fun paymentPayload(view: LegalActionView): JsonObject {
        val domain = view.paymentDomain ?: error("expected action-level payment domain")
        domain.sourceActivations.shouldHaveSize(2)
        val black = domain.sourceActivations[0]
        val generic = domain.sourceActivations[1]
        val plan = PaymentPlanV1(
            sourceActivations = listOf(
                SourceActivation(
                    sourceId = black.sourceId,
                    manaAbilityKey = black.manaAbilityKey,
                    productionChoice = black.productionChoices.first { it.producedColor == PaymentManaColor.BLACK },
                ),
                SourceActivation(
                    sourceId = generic.sourceId,
                    manaAbilityKey = generic.manaAbilityKey,
                    productionChoice = generic.productionChoices.first { it.producedColor == PaymentManaColor.GREEN },
                ),
            ),
            poolSpend = PoolSpend(),
            spendAllocation = SpendAllocation(
                costUnits = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = generic.sourceId))),
                    CostUnitAllocation(1, listOf(ManaSpendReference(sourceId = black.sourceId))),
                ),
            ),
        )
        val strategy = actionJson.encodeToJsonElement(
            PaymentStrategy.serializer(),
            PaymentStrategy.Explicit(paymentPlan = plan),
        )
        return buildJsonObject {
            view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put("paymentStrategy", strategy)
        }
    }

    test("Gym accepts a complete PaymentPlanV1 at the trusted action boundary") {
        val (gym, view) = prepared()

        gym.step(view.actionId, paymentPayload(view))
    }

    test("Gym rejects AutoPay and legacy runtime source lists at the trusted boundary") {
        val (gym, view) = prepared()
        val before = gym.observe().observation.stateDigest
        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId, view.actionSemantics!!)
        }
        gym.observe().observation.stateDigest shouldBe before

        val firstSource = view.paymentDomain!!.sourceActivations.first().sourceId
        val legacy = buildJsonObject {
            view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.Explicit(manaAbilitiesToActivate = listOf(firstSource)),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId, legacy)
        }
        gym.observe().observation.stateDigest shouldBe before
    }

    test("payment domain semantics survive Gym fork and snapshot restore") {
        val (gym, view) = prepared()
        val original = gym.observe().observation

        val fork = gym.fork() as GameGymEnv
        fork.observe().observation.stateDigest shouldBe original.stateDigest

        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        gym.step(view.actionId, paymentPayload(view))
        gym.restore(codec, handle)

        val restored = gym.observe().observation
        restored.stateDigest shouldBe original.stateDigest
        restored.legalActions.any { it.paymentDomain == view.paymentDomain } shouldBe true
    }
})

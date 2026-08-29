package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.mtg.sets.definitions.gtc.cards.BorosCharm
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import com.wingedsheep.engine.state.components.player.RestrictedManaEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** End-to-end Gym materialization tests for the public PaymentPlanV3 boundary. */
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

    val ordinarySpell = card("Gym Payment Ordinary Spell") {
        manaCost = "{1}{B}"
        typeLine = "Sorcery"
        spell {
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
        register(ordinarySpell)
        register(BorosCharm)
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

    fun preparedCast(): Pair<GameGymEnv, LegalActionView> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            ordinarySpell.name to 1,
                            anyColorSource.name to 2,
                            "Mountain" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 81232L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().firstOrNull { it.action is com.wingedsheep.engine.core.PassPriority }
                ?: error("Expected priority while preparing CastSpell payment-plan Gym state")
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
            } ?: error("Could not find '$name' in CastSpell payment-plan library")
            state = state.moveToZone(id, library, ZoneKey(player, zone))
        }
        moveNamed(ordinarySpell.name, Zone.HAND)
        moveNamed(anyColorSource.name, Zone.BATTLEFIELD)
        moveNamed(anyColorSource.name, Zone.BATTLEFIELD)
        environment.restore(state, environment.playerIds, environment.stepCount)

        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        val view = gym.observe().observation.legalActions.firstOrNull { action ->
            action.kind == "CastSpell" && action.manaCost == "{1}{B}"
        } ?: error("Expected ordinary payable CastSpell: ${gym.observe().observation.legalActions}")
        return gym to view
    }

    fun paymentPayload(view: LegalActionView): JsonObject {
        val domain = view.paymentDomain ?: error("expected action-level payment domain")
        val plan = paymentPlanV3FromPublic(domain)
            ?: error("expected a complete public PaymentDomainV5 plan")
        val strategy = actionJson.encodeToJsonElement(
            PaymentStrategy.serializer(),
            PaymentStrategy.ExplicitV3(paymentPlan = plan),
        )
        return buildJsonObject {
            view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put("paymentStrategy", strategy)
        }
    }

    fun borosCharmPaymentPayload(view: LegalActionView): JsonObject {
        val domain = view.paymentDomain ?: error("expected Boros Charm payment domain")
        val plan = paymentPlanV3FromPublic(domain)
            ?: error("expected a complete public PaymentDomainV5 plan")
        val strategy = actionJson.encodeToJsonElement(
            PaymentStrategy.serializer(),
            PaymentStrategy.ExplicitV3(paymentPlan = plan),
        )
        return buildJsonObject {
            view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put("paymentStrategy", strategy)
        }
    }

    fun preparedCastSpellMode(): Pair<GameGymEnv, LegalActionView> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            BorosCharm.name to 1,
                            anyColorSource.name to 2,
                            "Mountain" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 81233L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().firstOrNull { it.action is com.wingedsheep.engine.core.PassPriority }
                ?: error("Expected priority while preparing modal CastSpell payment-plan Gym state")
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
            } ?: error("Could not find '$name' in modal CastSpell payment-plan library")
            state = state.moveToZone(id, library, ZoneKey(player, zone))
        }
        moveNamed(BorosCharm.name, Zone.HAND)
        moveNamed(anyColorSource.name, Zone.BATTLEFIELD)
        moveNamed(anyColorSource.name, Zone.BATTLEFIELD)
        environment.restore(state, environment.playerIds, environment.stepCount)

        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        val view = gym.observe().observation.legalActions.firstOrNull { action ->
            action.kind == "CastSpellMode" && action.manaCost == "{R}{W}" &&
                action.actionSemantics?.get("chosenModes")?.jsonArray?.singleOrNull()
                    ?.jsonPrimitive?.content == "1"
        } ?: error("Expected Boros Charm CastSpellMode: ${gym.observe().observation.legalActions}")
        return gym to view
    }

    fun strategyPayload(view: LegalActionView, strategy: PaymentStrategy): JsonObject = buildJsonObject {
        view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
        put("paymentStrategy", actionJson.encodeToJsonElement(PaymentStrategy.serializer(), strategy))
    }

    test("Gym accepts a complete PaymentPlanV3 at the trusted action boundary") {
        val (gym, view) = prepared()

        gym.step(view.actionId, paymentPayload(view))
    }

    test("ordinary CastSpell publishes and accepts the complete PaymentPlanV3 domain") {
        val (gym, view) = preparedCast()

        view.kind shouldBe "CastSpell"
        view.manaCost shouldBe "{1}{B}"
        view.requiredPayloadFields shouldBe listOf("paymentStrategy")
        view.paymentDomain?.requiredCost shouldBe "{1}{B}"
        view.paymentDomain?.costUnits?.map { it.symbolIndex } shouldBe listOf(0, 1)

        gym.step(view.actionId, paymentPayload(view))
    }

    test("CastSpellMode publishes and accepts the complete PaymentPlanV3 domain") {
        val (gym, view) = preparedCastSpellMode()

        view.kind shouldBe "CastSpellMode"
        view.manaCost shouldBe "{R}{W}"
        view.requiredPayloadFields.contains("paymentStrategy") shouldBe true
        view.actionSemantics!!["chosenModes"]!!.jsonArray
            .map { it.jsonPrimitive.content } shouldBe listOf("1")
        view.paymentDomain?.requiredCost shouldBe "{R}{W}"

        gym.step(view.actionId, borosCharmPaymentPayload(view))
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

    test("Gym rejects AutoPay, FromPool, and legacy Explicit for CastSpell") {
        val (gym, view) = preparedCast()
        val before = gym.observe().observation.stateDigest
        val beforeStepCount = gym.environment.stepCount
        val sourceId = view.paymentDomain!!.sourceActivations.first().sourceId

        listOf(
            PaymentStrategy.AutoPay,
            PaymentStrategy.FromPool,
            PaymentStrategy.Explicit(manaAbilitiesToActivate = listOf(sourceId)),
        ).forEach { strategy ->
            shouldThrow<IllegalArgumentException> {
                gym.step(view.actionId, strategyPayload(view, strategy))
            }
            gym.observe().observation.stateDigest shouldBe before
            gym.environment.stepCount shouldBe beforeStepCount
        }

        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId)
        }
        gym.environment.stepCount shouldBe beforeStepCount
    }

    test("trusted Gym rejects AutoPay, FromPool, and legacy Explicit for CastSpellMode") {
        val (gym, view) = preparedCastSpellMode()
        val before = gym.observe().observation.stateDigest
        val beforeStepCount = gym.environment.stepCount
        val sourceId = view.paymentDomain!!.sourceActivations.first().sourceId

        listOf(
            PaymentStrategy.AutoPay,
            PaymentStrategy.FromPool,
            PaymentStrategy.Explicit(manaAbilitiesToActivate = listOf(sourceId)),
        ).forEach { strategy ->
            shouldThrow<IllegalArgumentException> {
                gym.step(view.actionId, strategyPayload(view, strategy))
            }
            gym.observe().observation.stateDigest shouldBe before
            gym.environment.stepCount shouldBe beforeStepCount
        }
    }

    test("trusted Gym fails closed when a payable CastSpell domain becomes unsupported") {
        val (gym, view) = preparedCast()
        gym.observe()
        val beforeStepCount = gym.environment.stepCount
        val sourceIds = view.paymentDomain!!.sourceActivations.map { it.sourceId }
        val player = gym.environment.playerIds.first()
        val unsupportedState = gym.environment.state.updateEntity(player) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            container.with(
                pool.copy(
                    restrictedMana = listOf(
                        RestrictedManaEntry(Color.BLACK, ManaRestriction.AnySpend),
                    ),
                ),
            )
        }
        val expectedState = unsupportedState
        gym.environment.restore(
            state = unsupportedState,
            playerIds = gym.environment.playerIds,
            stepCount = gym.environment.stepCount,
        )

        val failure = shouldThrow<UnsupportedPathFailure> {
            gym.step(view.actionId, strategyPayload(view, PaymentStrategy.AutoPay))
        }
        failure.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        gym.environment.stepCount shouldBe beforeStepCount
        sourceIds.forEach { sourceId ->
            gym.environment.state.getEntity(sourceId)?.has<TappedComponent>() shouldBe false
        }

        val legacyFailure = shouldThrow<UnsupportedPathFailure> {
            gym.step(view.actionId, strategyPayload(view, PaymentStrategy.Explicit()))
        }
        legacyFailure.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        shouldThrow<UnsupportedPathFailure> {
            gym.step(view.actionId)
        }.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        gym.environment.stepCount shouldBe beforeStepCount
        gym.environment.state shouldBe expectedState
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

    test("CastSpell payment domain survives Gym fork and snapshot restore") {
        val (gym, view) = preparedCast()
        val original = gym.observe().observation

        val fork = gym.fork() as GameGymEnv
        fork.observe().observation.stateDigest shouldBe original.stateDigest
        fork.observe().observation.legalActions.any { it.paymentDomain == view.paymentDomain } shouldBe true

        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        gym.step(view.actionId, paymentPayload(view))
        gym.restore(codec, handle)

        val restored = gym.observe().observation
        restored.stateDigest shouldBe original.stateDigest
        restored.legalActions.any { it.paymentDomain == view.paymentDomain } shouldBe true
    }

    test("CastSpellMode payment domain survives Gym fork and snapshot restore") {
        val (gym, view) = preparedCastSpellMode()
        val original = gym.observe().observation

        val fork = gym.fork() as GameGymEnv
        val forkObservation = fork.observe().observation
        forkObservation.stateDigest shouldBe original.stateDigest
        forkObservation.schemaHash shouldBe original.schemaHash
        forkObservation.legalActions.any {
            it.kind == "CastSpellMode" &&
                it.actionSemantics?.get("chosenModes")?.jsonArray?.map { mode ->
                    mode.jsonPrimitive.content
                } == listOf("1") &&
                it.paymentDomain == view.paymentDomain
        } shouldBe true

        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        gym.step(view.actionId, borosCharmPaymentPayload(view))
        gym.restore(codec, handle)

        val restored = gym.observe().observation
        restored.stateDigest shouldBe original.stateDigest
        restored.schemaHash shouldBe original.schemaHash
        restored.legalActions.any {
            it.kind == "CastSpellMode" &&
                it.actionSemantics?.get("chosenModes")?.jsonArray?.map { mode ->
                    mode.jsonPrimitive.content
                } == listOf("1") &&
                it.paymentDomain == view.paymentDomain
        } shouldBe true
    }
})

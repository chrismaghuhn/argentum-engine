package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.dka.cards.FaithlessLooting
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.KeywordAbility
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class GameGymEnvAlternativeCastPaymentTest : FunSpec({

    val flashbackWithLifeCost = FaithlessLooting.copy(
        name = "Flashback Life Cost Test",
        oracleText = "Draw two cards, then discard two cards. Flashback {2}{R}, Pay 2 life.",
        keywordAbilities = listOf(
            KeywordAbility.flashback("{2}{R}", Costs.additional.PayLife(2)),
        ),
    )

    data class Fixture(
        val environment: GameEnvironment,
        val registry: com.wingedsheep.engine.registry.CardRegistry,
        val playerId: EntityId,
        val cardId: EntityId,
    )

    fun prepared(
        mountainCount: Int,
        cardZone: Zone = Zone.GRAVEYARD,
        cardDefinition: CardDefinition = FaithlessLooting,
    ): Fixture {
        val registry = com.wingedsheep.engine.registry.CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(cardDefinition)
        }
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            cardDefinition.name to 1,
                            "Mountain" to 8,
                            "Forest" to 4,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 106503L + mountainCount,
            ),
        )

        val playerId = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        val hand = ZoneKey(playerId, Zone.HAND)
        val library = ZoneKey(playerId, Zone.LIBRARY)
        state.getZone(hand).forEach { id ->
            state = state.moveToZone(id, hand, library)
        }

        fun moveNamed(name: String, zone: Zone): EntityId {
            val id = state.getZone(library).firstOrNull { candidate ->
                state.getEntity(candidate)?.get<CardComponent>()?.name == name
            } ?: error("Could not find $name in the prepared library")
            state = state.moveToZone(id, library, ZoneKey(playerId, zone))
            return id
        }

        val cardId = moveNamed(cardDefinition.name, cardZone)
        repeat(mountainCount) { moveNamed("Mountain", Zone.BATTLEFIELD) }
        environment.restore(state, environment.playerIds, environment.stepCount)

        return Fixture(environment, registry, playerId, cardId)
    }

    fun flashbackAction(fixture: Fixture) = fixture.environment.legalActions().single {
        it.actionType == "CastWithFlashback" &&
            (it.action as? CastSpell)?.cardId == fixture.cardId
    }

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun paymentPayload(view: com.wingedsheep.gym.contract.LegalActionView, plan: PaymentPlanV3) =
        buildJsonObject {
            view.actionSemantics?.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(paymentPlan = plan),
                ),
            )
        }

    test("fixed alternative mana cost publishes a complete V5 domain") {
        val fixture = prepared(mountainCount = 3)
        val action = flashbackAction(fixture)
        val result = ObservationBuilder(cardRegistry = fixture.registry).build(
            state = fixture.environment.state,
            perspectivePlayerId = fixture.playerId,
            legalActions = listOf(action),
        )
        val view = result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single()

        action.manaCostString shouldBe "{2}{R}"
        action.affordable shouldBe true
        result.diagnostics shouldBe emptyList()
        view.paymentDomain shouldNotBe null
        view.paymentDomain!!.requiredCost shouldBe "{2}{R}"
    }

    test("unaffordable fixed alternative cost never falls back to printed cost") {
        val fixture = prepared(mountainCount = 1)
        val action = flashbackAction(fixture)
        val builder = ObservationBuilder(cardRegistry = fixture.registry)
        val domain = builder.paymentDomainV5For(fixture.environment.state, action)
        val result = builder.build(
            state = fixture.environment.state,
            perspectivePlayerId = fixture.playerId,
            legalActions = listOf(action),
        )
        val view = result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single()

        action.affordable shouldBe false
        domain shouldNotBe null
        domain!!.requiredCost shouldBe "{2}{R}"
        paymentPlanV3FromPublic(domain) shouldBe null
        view.affordable shouldBe false
        view.paymentDomain shouldBe null
        result.diagnostics shouldBe emptyList()
    }

    test("applicable alternative additional cost keeps V5 and ExplicitV3 fail-closed") {
        val fixture = prepared(mountainCount = 3, cardDefinition = flashbackWithLifeCost)
        val action = flashbackAction(fixture)
        val builder = ObservationBuilder(cardRegistry = fixture.registry)
        val domain = builder.paymentDomainV5For(fixture.environment.state, action)

        domain shouldBe null
        val result = builder.build(
            state = fixture.environment.state,
            perspectivePlayerId = fixture.playerId,
            legalActions = listOf(action),
        )
        result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        result.observation
            .shouldBeInstanceOf<TrainingObservation>()
            .legalActions
            .single()
            .paymentDomain shouldBe null

        val submitted = (action.action as CastSpell).copy(
            paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = PaymentPlanV3()),
        )
        val beforeState = fixture.environment.state
        val beforeStepCount = fixture.environment.stepCount
        val beforeEvents = fixture.environment.lastStepEvents

        shouldThrow<IllegalArgumentException> {
            fixture.environment.stepStrict(submitted)
        }

        fixture.environment.state shouldBe beforeState
        fixture.environment.stepCount shouldBe beforeStepCount
        fixture.environment.lastStepEvents shouldBe beforeEvents
    }

    test("strict Gym accepts an ExplicitV3 payment for fixed alternative cast") {
        val fixture = prepared(mountainCount = 3)
        val gym = GameGymEnv(
            environment = fixture.environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = fixture.registry),
        )
        val observation = gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
        val view = observation.legalActions.single {
            it.kind == "CastWithFlashback" && it.sourceEntityId == fixture.cardId
        }
        val domain = view.paymentDomain ?: error("Expected a Flashback PaymentDomainV5")
        val plan = paymentPlanV3FromPublic(domain)
            ?: error("Expected a complete Flashback PaymentPlanV3")
        val payload = buildJsonObject {
            view.actionSemantics?.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(paymentPlan = plan),
                ),
            )
        }

        val afterCast = gym.step(view.actionId, payload)
        afterCast.observation.shouldBeInstanceOf<TrainingObservation>()
    }

    test("strict Gym rejects a plan that pays only the printed cost atomically") {
        val fixture = prepared(mountainCount = 3)
        val gym = GameGymEnv(
            environment = fixture.environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = fixture.registry),
        )
        val observation = gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
        val view = observation.legalActions.single {
            it.kind == "CastWithFlashback" && it.sourceEntityId == fixture.cardId
        }
        val domain = view.paymentDomain ?: error("Expected a Flashback PaymentDomainV5")
        val source = domain.sourceActivationOptions.first()
        val printedCostPlan = PaymentPlanV3(
            activations = listOf(
                SourceActivationV2(
                    sourceId = source.sourceId,
                    manaAbilityKey = source.manaAbilityKey,
                    productionChoice = source.productionChoices.first(),
                    activationCostOrder = source.activationCostOrderOptions.first(),
                ),
            ),
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(symbolIndex = 1, unitIndexWithinSymbol = 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                ),
            ),
        )
        val beforeState = fixture.environment.state
        val beforeStepCount = fixture.environment.stepCount
        val beforeEvents = fixture.environment.lastStepEvents

        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId, paymentPayload(view, printedCostPlan))
        }

        fixture.environment.state shouldBe beforeState
        fixture.environment.stepCount shouldBe beforeStepCount
        fixture.environment.lastStepEvents shouldBe beforeEvents
    }

    test("normal fixed cast remains on the ordinary V5 path") {
        val fixture = prepared(mountainCount = 1, cardZone = Zone.HAND)
        val result = ObservationBuilder(cardRegistry = fixture.registry).build(
            state = fixture.environment.state,
            perspectivePlayerId = fixture.playerId,
            legalActions = fixture.environment.legalActions().filter {
                it.actionType == "CastSpell" && (it.action as? CastSpell)?.cardId == fixture.cardId
            },
        )
        val view = result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single()

        view.kind shouldBe "CastSpell"
        view.manaCost shouldBe "{R}"
        view.paymentDomain?.requiredCost shouldBe "{R}"
        result.diagnostics shouldBe emptyList()
    }

    test("unsupported alternative shapes remain fail-closed") {
        val fixture = prepared(mountainCount = 3)
        val action = flashbackAction(fixture)
        val cast = action.action as CastSpell
        val builder = ObservationBuilder(cardRegistry = fixture.registry)

        val xAlternative = action.copy(
            manaCostString = "{X}{R}",
            hasXCost = true,
            action = cast.copy(xValue = 1),
        )
        val unselectedAlternative = action.copy(
            action = cast.copy(alternativeCostType = null),
        )

        builder.paymentDomainV5For(fixture.environment.state, xAlternative) shouldBe null
        builder.paymentDomainV5For(fixture.environment.state, unselectedAlternative) shouldBe null
    }

    test("legacy explicit payment remains rejected for an alternative cast") {
        val fixture = prepared(mountainCount = 3)
        val gym = GameGymEnv(
            environment = fixture.environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = fixture.registry),
        )
        val observation = gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
        val view = observation.legalActions.single {
            it.kind == "CastWithFlashback" && it.sourceEntityId == fixture.cardId
        }
        val payload = buildJsonObject {
            view.actionSemantics?.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.Explicit(paymentPlan = PaymentPlanV1()),
                ),
            )
        }
        val beforeState = fixture.environment.state
        val beforeStepCount = fixture.environment.stepCount

        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId, payload)
        }

        fixture.environment.state shouldBe beforeState
        fixture.environment.stepCount shouldBe beforeStepCount
        fixture.environment.lastStepEvents shouldBe emptyList()
    }
})

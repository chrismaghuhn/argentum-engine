package com.wingedsheep.gym

import com.wingedsheep.engine.core.CardCycledEvent
import com.wingedsheep.engine.core.CardsDiscardedEvent
import com.wingedsheep.engine.core.CostUnitAllocationV2
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.ManaSpendReferenceV2
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentPlanV2
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.core.SpendAllocationV2
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.TypecycleCard
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PaymentCostKind
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.ulg.UrzasLegacySet
import com.wingedsheep.mtg.sets.definitions.ulg.cards.Unearth
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

private data class PreparedCycleGym(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val cardId: EntityId,
    val sourceIds: List<EntityId>,
)

/** Public-domain and authoritative Rules regressions for plain fixed-cost CycleCard actions. */
class GameGymEnvCycleCardPaymentDomainTest : FunSpec({

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    val dynamicCycling = card("Gym X Cycling") {
        typeLine = "Artifact"
        keywordAbility(KeywordAbility.cycling("{X}"))
    }

    val hybridCycling = card("Gym Hybrid Cycling") {
        typeLine = "Artifact"
        keywordAbility(KeywordAbility.cycling("{2/R}"))
    }

    val restrictedManaSource = card("Gym Equip-Only Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(
                color = Color.RED,
                amount = 2,
                restriction = ManaRestriction.EquipAbilityActivationOnly,
            )
            manaAbility = true
        }
    }

    fun registry(cycleCard: CardDefinition = Unearth, extraCards: List<CardDefinition> = emptyList()) =
        CardRegistry().apply {
            register(UrzasLegacySet.cards)
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(cycleCard)
            extraCards.forEach(::register)
        }

    fun preparedCycleGym(cycleCard: CardDefinition = Unearth): PreparedCycleGym {
        val cardRegistry = registry(cycleCard)
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of(cycleCard.name to 1, "Mountain" to 6)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 2)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
        )

        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        val playerId = environment.playerIds.first()
        fun moveNamed(name: String, destination: Zone): EntityId {
            val cardId = state.entities.entries.first { (id, container) ->
                id in state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val sourceZone = state.zones.entries.first { (_, ids) -> cardId in ids }.key
            state = state.moveToZone(cardId, sourceZone, ZoneKey(playerId, destination))
            return cardId
        }

        val cardId = moveNamed(cycleCard.name, Zone.HAND)
        val sourceIds = listOf(
            moveNamed("Mountain", Zone.BATTLEFIELD),
            moveNamed("Mountain", Zone.BATTLEFIELD),
            moveNamed("Mountain", Zone.BATTLEFIELD),
        )
        environment.restore(state, environment.playerIds, environment.stepCount)

        return PreparedCycleGym(
            environment = environment,
            gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            ),
            cardId = cardId,
            sourceIds = sourceIds,
        )
    }

    fun cycleView(prepared: PreparedCycleGym): com.wingedsheep.gym.contract.LegalActionView {
        val observed = prepared.gym.observe().observation as TrainingObservation
        return observed.legalActions.single {
            it.kind == "CycleCard" && it.sourceEntityId == prepared.cardId
        }
    }

    fun explicitV3FromPublic(
        view: com.wingedsheep.gym.contract.LegalActionView,
        sourceOffset: Int = 1,
    ): Pair<PaymentStrategy.ExplicitV3, List<EntityId>> {
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV5")
        val selected = domain.sourceActivationOptions.drop(sourceOffset).take(2)
        check(selected.size == 2) { "Expected at least three public source activations: $domain" }
        val plan = PaymentPlanV3(
            activations = selected.map { source ->
                SourceActivationV2(
                    sourceId = source.sourceId,
                    manaAbilityKey = source.manaAbilityKey,
                    productionChoice = source.productionChoices.single(),
                    activationCostOrder = source.activationCostOrderOptions.single(),
                )
            },
            outerAllocation = domain.outerAtomicCostUnits.mapIndexed { index, unit ->
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(
                        symbolIndex = unit.symbolIndex,
                        unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                    ),
                    resource = ManaResourceRefV1.ActivationOutputUnit(
                        activationIndex = index,
                        outputIndex = 0,
                    ),
                )
            },
        )
        return PaymentStrategy.ExplicitV3(paymentPlan = plan) to selected.map { it.sourceId }
    }

    fun payload(
        view: com.wingedsheep.gym.contract.LegalActionView,
        paymentStrategy: PaymentStrategy,
    ): JsonObject = buildJsonObject {
        view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
        put(
            "paymentStrategy",
            actionJson.encodeToJsonElement(PaymentStrategy.serializer(), paymentStrategy),
        )
    }

    test("real Unearth fixed Cycling {2} publishes PaymentDomainV5") {
        val prepared = preparedCycleGym()
        val cycle = cycleView(prepared)
        val domain = cycle.paymentDomain ?: error("Expected a PaymentDomainV5")

        cycle.manaCost shouldBe "{2}"
        domain.version shouldBe 5
        domain.requiredCost shouldBe "{2}"
        domain.outerAtomicCostUnits.map { it.unitIndexWithinSymbol } shouldBe listOf(0, 1)
        domain.sourceActivationOptions.size shouldBe 3
    }

    test("PaymentPlanV3 can be built only from the public CycleCard domain and executes exact sources") {
        val prepared = preparedCycleGym()
        val view = cycleView(prepared)
        val (payment, selectedSourceIds) = explicitV3FromPublic(view)
        val playerId = prepared.environment.playerIds.first()
        val librarySizeBefore = prepared.environment.state.getZone(playerId, Zone.LIBRARY).size
        val stepCountBefore = prepared.environment.stepCount

        prepared.gym.step(view.actionId, payload(view, payment))

        prepared.environment.stepCount shouldBe stepCountBefore + 1
        prepared.environment.state.getZone(playerId, Zone.GRAVEYARD) shouldContain prepared.cardId
        prepared.environment.state.getZone(playerId, Zone.LIBRARY).size shouldBe librarySizeBefore - 1
        selectedSourceIds.forEach { sourceId ->
            prepared.environment.state.getEntity(sourceId)?.has<TappedComponent>() shouldBe true
        }
        prepared.sourceIds.filter { it !in selectedSourceIds }.forEach { sourceId ->
            prepared.environment.state.getEntity(sourceId)?.has<TappedComponent>() shouldBe false
        }
        prepared.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single().reason shouldBe
            "Cycle ${Unearth.name}"
        prepared.environment.lastStepEvents.filterIsInstance<CardCycledEvent>().single().cardId shouldBe
            prepared.cardId
        prepared.environment.lastStepEvents.filterIsInstance<CardsDiscardedEvent>()
            .single().cardIds shouldContain prepared.cardId
    }

    test("invalid plans and native payment strategies reject without advancing or falling back") {
        fun assertRejected(strategy: PaymentStrategy) {
            val prepared = preparedCycleGym()
            val view = cycleView(prepared)
            val stateBefore = prepared.environment.state
            val stepCountBefore = prepared.environment.stepCount

            shouldThrow<IllegalArgumentException> {
                prepared.gym.step(view.actionId, payload(view, strategy))
            }

            (prepared.environment.state === stateBefore) shouldBe true
            prepared.environment.stepCount shouldBe stepCountBefore
            prepared.environment.lastStepEvents shouldBe emptyList()
        }

        val prepared = preparedCycleGym()
        val view = cycleView(prepared)
        val (validPayment, _) = explicitV3FromPublic(view)
        val incomplete = validPayment.copy(
            paymentPlan = validPayment.paymentPlan!!.copy(outerAllocation = emptyList()),
        )
        assertRejected(incomplete)
        assertRejected(PaymentStrategy.AutoPay)
        assertRejected(PaymentStrategy.FromPool)
        assertRejected(PaymentStrategy.Explicit(manaAbilitiesToActivate = listOf(prepared.sourceIds.first())))

        val domain = view.paymentDomain!!
        val source = domain.sourceActivationOptions.first()
        val unpublished = PaymentStrategy.ExplicitV3(
            paymentPlan = PaymentPlanV3(
                activations = listOf(
                    SourceActivationV2(
                        sourceId = EntityId("not-published"),
                        manaAbilityKey = source.manaAbilityKey,
                        productionChoice = source.productionChoices.single(),
                        activationCostOrder = source.activationCostOrderOptions.single(),
                    ),
                ),
                outerAllocation = domain.outerAtomicCostUnits.map { unit ->
                    PaymentAllocationV1(
                        target = PaymentTargetV1.OuterCostUnit(
                            symbolIndex = unit.symbolIndex,
                            unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                        ),
                        resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                    )
                },
            ),
        )
        assertRejected(unpublished)
    }

    test("Cycling context rejects a mana source restricted to equip activations") {
        val cardRegistry = registry(extraCards = listOf(restrictedManaSource))
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(Unearth.name to 1, restrictedManaSource.name to 1, "Mountain" to 1),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 2)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
        )
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }
        val playerId = environment.playerIds.first()
        fun moveNamed(name: String, destination: Zone): EntityId {
            val id = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val from = state.zones.entries.first { (_, ids) -> id in ids }.key
            state = state.moveToZone(id, from, ZoneKey(playerId, destination))
            return id
        }
        moveNamed(Unearth.name, Zone.HAND)
        moveNamed(restrictedManaSource.name, Zone.BATTLEFIELD)
        environment.restore(state, environment.playerIds, environment.stepCount)

        val legalCycle = environment.legalActions().single { it.action is CycleCard }
        legalCycle.affordable shouldBe false
        legalCycle.autoTapPreview shouldBe null
        val builder = ObservationBuilder(cardRegistry = cardRegistry)
        builder.paymentDomainFor(environment.state, legalCycle) shouldBe null
        val view = builder.build(environment.state, playerId, listOf(legalCycle)).observation as TrainingObservation
        view.legalActions.single().paymentDomain shouldBe null
    }

    test("dynamic and typed cycling shapes remain fail-closed") {
        val prepared = preparedCycleGym(dynamicCycling)
        val legalCycle = prepared.environment.legalActions().single { it.action is CycleCard }
        legalCycle.affordable shouldBe true
        legalCycle.hasXCost shouldBe true
        val builder = ObservationBuilder(cardRegistry = registry(dynamicCycling))
        builder.paymentDomainFor(prepared.environment.state, legalCycle) shouldBe null
        val failure = shouldThrow<UnsupportedPathFailure> { prepared.gym.observe() }
        failure.diagnostics.map { it.code } shouldContain DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED

        val typed = LegalAction(
            action = TypecycleCard(prepared.environment.playerIds.first(), prepared.cardId),
            actionType = "TypecycleCard",
            description = "Typecycle ${dynamicCycling.name}",
            affordable = true,
            manaCostString = "{2}",
        )
        builder.paymentDomainFor(prepared.environment.state, typed) shouldBe null

        val hybrid = preparedCycleGym(hybridCycling)
        val hybridAction = hybrid.environment.legalActions().single { it.action is CycleCard }
        hybridAction.affordable shouldBe true
        ObservationBuilder(cardRegistry = registry(hybridCycling))
            .paymentDomainFor(hybrid.environment.state, hybridAction) shouldBe null
    }
})

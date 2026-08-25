package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.CostUnitAllocation
import com.wingedsheep.engine.core.CostUnitAllocationV2
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaSpendReferenceV2
import com.wingedsheep.engine.core.ManaSpendReference
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PaymentPlanV2
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PermanentsSacrificedEvent
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.core.SpendAllocationV2
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TappedEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.`5dn`.cards.WayfarersBauble
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.wth.cards.MindStone
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

private data class PreparedDeterministicAbilityGym(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val cardRegistry: CardRegistry,
    val playerId: EntityId,
    val sourceId: EntityId,
    val abilityId: AbilityId,
    val mountainIds: List<EntityId>,
)

/** RED characterization for public payment of deterministic activated-ability additional costs. */
class GameGymEnvDeterministicActivatedCostPaymentTest : FunSpec({

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    val choiceCostSource = card("Gym Variable Activated Cost Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(
                Costs.Mana("{1}"),
                Costs.TapPermanents(count = 1, filter = GameObjectFilter.Creature),
                Costs.SacrificePermanents(filter = GameObjectFilter.Permanent),
            )
            effect = Effects.GainLife(1)
        }
    }

    val choiceCostCreature = card("Gym Variable Activated Cost Creature") {
        typeLine = "Creature — Probe"
        power = 1
        toughness = 1
    }

    val tapSelfSource = card("Gym Tap-Self Activated Cost Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{1}"), Costs.Tap)
            effect = Effects.GainLife(1)
        }
    }

    fun registry(extraCards: List<CardDefinition> = emptyList()) = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(WayfarersBauble)
        register(MindStone)
        extraCards.forEach(::register)
    }

    fun preparedActivatedAbility(
        sourceCard: CardDefinition,
        abilityIndex: Int = 0,
        battlefieldCardNames: List<String> = emptyList(),
        extraCards: List<CardDefinition> = emptyList(),
    ): PreparedDeterministicAbilityGym {
        val cardRegistry = registry(extraCards)
        val environment = GameEnvironment.create(cardRegistry)
        val deckEntries = buildList {
            add(sourceCard.name to 1)
            battlefieldCardNames.forEach { add(it to 1) }
            add("Mountain" to 6)
        }
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of(*deckEntries.toTypedArray())),
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

        val sourceId = moveNamed(sourceCard.name, Zone.BATTLEFIELD)
        battlefieldCardNames.forEach { moveNamed(it, Zone.BATTLEFIELD) }
        val mountainIds = listOf(
            moveNamed("Mountain", Zone.BATTLEFIELD),
            moveNamed("Mountain", Zone.BATTLEFIELD),
        )
        environment.restore(state, environment.playerIds, environment.stepCount)

        return PreparedDeterministicAbilityGym(
            environment = environment,
            gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            ),
            cardRegistry = cardRegistry,
            playerId = playerId,
            sourceId = sourceId,
            abilityId = sourceCard.activatedAbilities[abilityIndex].id,
            mountainIds = mountainIds,
        )
    }

    fun preparedWayfarer() = preparedActivatedAbility(WayfarersBauble)

    fun preparedMindStone() = preparedActivatedAbility(MindStone, abilityIndex = 1)

    fun preparedChoiceCost() = preparedActivatedAbility(
        sourceCard = choiceCostSource,
        battlefieldCardNames = listOf(choiceCostCreature.name),
        extraCards = listOf(choiceCostSource, choiceCostCreature),
    )

    fun preparedTapSelf() = preparedActivatedAbility(
        sourceCard = tapSelfSource,
        extraCards = listOf(tapSelfSource),
    )

    fun activatedView(prepared: PreparedDeterministicAbilityGym): LegalActionView {
        val action = prepared.environment.legalActions().single { legalAction ->
            val activate = legalAction.action as? ActivateAbility
            activate?.sourceId == prepared.sourceId && activate.abilityId == prepared.abilityId
        }
        return ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .build(prepared.environment.state, prepared.playerId, listOf(action))
            .observation
            .let { it as TrainingObservation }
            .legalActions
            .single()
    }

    fun gymActivatedView(prepared: PreparedDeterministicAbilityGym): LegalActionView =
        (prepared.gym.observe().observation as TrainingObservation).legalActions.single {
            it.kind == "ActivateAbility" && it.sourceEntityId == prepared.sourceId
        }

    fun explicitV2FromPublic(view: LegalActionView): PaymentStrategy.ExplicitV2 {
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        val costUnit = domain.costUnits.single()
        val selected = domain.sourceActivations.take(costUnit.amount)
        selected.size shouldBe costUnit.amount
        return PaymentStrategy.ExplicitV2(
            paymentPlan = PaymentPlanV2(
                sourceActivations = selected.map { source ->
                    SourceActivation(
                        sourceId = source.sourceId,
                        manaAbilityKey = source.manaAbilityKey,
                        productionChoice = source.productionChoices.single(),
                    )
                },
                poolSpend = PoolSpend(),
                spendAllocation = SpendAllocationV2(
                    costUnits = listOf(
                        CostUnitAllocationV2(
                            symbolIndex = costUnit.symbolIndex,
                            spends = selected.map { source ->
                                ManaSpendReferenceV2(sourceId = source.sourceId, amount = 1)
                            },
                        ),
                    ),
                ),
            ),
        )
    }

    fun explicitV1FromPublic(view: LegalActionView): PaymentStrategy.Explicit {
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        val costUnit = domain.costUnits.single()
        val selected = domain.sourceActivations.take(costUnit.amount)
        selected.size shouldBe costUnit.amount
        return PaymentStrategy.Explicit(
            paymentPlan = PaymentPlanV1(
                sourceActivations = selected.map { source ->
                    SourceActivation(
                        sourceId = source.sourceId,
                        manaAbilityKey = source.manaAbilityKey,
                        productionChoice = source.productionChoices.single(),
                    )
                },
                poolSpend = PoolSpend(),
                spendAllocation = com.wingedsheep.engine.core.SpendAllocation(
                    costUnits = listOf(
                        CostUnitAllocation(
                            symbolIndex = costUnit.symbolIndex,
                            spends = selected.map { source ->
                                ManaSpendReference(sourceId = source.sourceId, amount = 1)
                            },
                        ),
                    ),
                ),
            ),
        )
    }

    fun exactSelfCostPayment(view: LegalActionView): AdditionalCostPayment {
        val sourceId = view.sourceEntityId ?: error("Expected sourceEntityId")
        return AdditionalCostPayment(
            tappedPermanents = listOf(sourceId),
            sacrificedPermanents = listOf(sourceId),
        )
    }

    fun payload(
        view: LegalActionView,
        paymentStrategy: PaymentStrategy,
        costPayment: AdditionalCostPayment? = exactSelfCostPayment(view),
    ): JsonObject = buildJsonObject {
        view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
        put(
            "paymentStrategy",
            actionJson.encodeToJsonElement(PaymentStrategy.serializer(), paymentStrategy),
        )
        costPayment?.let {
            put(
                "costPayment",
                actionJson.encodeToJsonElement(AdditionalCostPayment.serializer(), it),
            )
        }
    }

    fun assertRejectedAtomically(
        prepared: PreparedDeterministicAbilityGym,
        view: LegalActionView,
        actionPayload: JsonObject,
    ) {
        val stateBefore = prepared.environment.state
        val stepCountBefore = prepared.environment.stepCount
        val eventsBefore = prepared.environment.lastStepEvents

        shouldThrow<IllegalArgumentException> {
            prepared.gym.step(view.actionId, actionPayload)
        }

        (prepared.environment.state === stateBefore) shouldBe true
        prepared.environment.stepCount shouldBe stepCountBefore
        prepared.environment.lastStepEvents shouldBe eventsBefore
        prepared.environment.state.getZone(prepared.playerId, Zone.BATTLEFIELD)
            .contains(prepared.sourceId) shouldBe true
        prepared.environment.state.getEntity(prepared.sourceId)?.has<TappedComponent>() shouldBe false
    }

    test("real Wayfarer's Bauble publishes a usable PaymentDomainV4") {
        val prepared = preparedWayfarer()
        val view = activatedView(prepared)

        view.requiredPayloadFields shouldBe listOf("paymentStrategy", "costPayment")
        view.sourceEntityId shouldBe prepared.sourceId
        view.validSacrificeTargets shouldBe listOf(prepared.sourceId)
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        domain.version shouldBe 4
        domain.requiredCost shouldBe "{2}"
        domain.sourceActivations.any { it.sourceId == prepared.sourceId } shouldBe false
    }

    test("real Mind Stone uses the same deterministic certificate without a card-name branch") {
        val prepared = preparedMindStone()
        val view = activatedView(prepared)

        view.requiredPayloadFields shouldBe listOf("paymentStrategy", "costPayment")
        view.sourceEntityId shouldBe prepared.sourceId
        view.validSacrificeTargets shouldBe listOf(prepared.sourceId)
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        domain.requiredCost shouldBe "{1}"
    }

    test("TapSelf-only activated costs publish and require the source-bound acknowledgement") {
        val prepared = preparedTapSelf()
        val view = gymActivatedView(prepared)

        view.requiredPayloadFields shouldBe listOf("paymentStrategy", "costPayment")
        view.sourceEntityId shouldBe prepared.sourceId
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        domain.requiredCost shouldBe "{1}"

        val payment = AdditionalCostPayment(tappedPermanents = listOf(prepared.sourceId))
        prepared.gym.step(view.actionId, payload(view, explicitV2FromPublic(view), payment))
        prepared.environment.state.getZone(prepared.playerId, Zone.BATTLEFIELD)
            .contains(prepared.sourceId) shouldBe true
        prepared.environment.state.getEntity(prepared.sourceId)?.has<TappedComponent>() shouldBe true
    }

    test("TapSelf-only ExplicitV2 without costPayment rejects atomically") {
        val prepared = preparedTapSelf()
        val view = gymActivatedView(prepared)

        assertRejectedAtomically(
            prepared = prepared,
            view = view,
            actionPayload = payload(view, explicitV2FromPublic(view), costPayment = null),
        )
    }

    test("public PaymentPlanV2 pays Wayfarer's {2}, then Rules taps and sacrifices the source once") {
        val prepared = preparedWayfarer()
        val view = (prepared.gym.observe().observation as TrainingObservation).legalActions.single {
            it.kind == "ActivateAbility" && it.sourceEntityId == prepared.sourceId &&
                it.paymentDomain?.requiredCost == "{2}"
        }
        val payment = explicitV2FromPublic(view)

        prepared.gym.step(view.actionId, payload(view, payment))

        prepared.environment.state.getZone(prepared.playerId, Zone.GRAVEYARD) shouldContain prepared.sourceId
        prepared.environment.state.getZone(prepared.playerId, Zone.BATTLEFIELD)
            .contains(prepared.sourceId) shouldBe false
        prepared.environment.state.stack.isNotEmpty() shouldBe true
        prepared.environment.lastStepEvents.filterIsInstance<TappedEvent>()
            .count { it.entityId == prepared.sourceId } shouldBe 1
        prepared.environment.lastStepEvents.filterIsInstance<PermanentsSacrificedEvent>()
            .single().permanentIds shouldBe listOf(prepared.sourceId)
        prepared.environment.lastStepEvents.filterIsInstance<TappedEvent>()
            .map { it.entityId }.toSet() shouldBe prepared.mountainIds.toSet() + prepared.sourceId
    }

    test("public PaymentPlanV1 remains accepted for the existing explicit compatibility path") {
        val prepared = preparedWayfarer()
        val view = gymActivatedView(prepared)

        prepared.gym.step(view.actionId, payload(view, explicitV1FromPublic(view)))

        prepared.environment.state.getZone(prepared.playerId, Zone.GRAVEYARD)
            .contains(prepared.sourceId) shouldBe true
    }

    test("native Rules AutoPay with null costPayment remains compatible") {
        val prepared = preparedWayfarer()

        prepared.environment.step(
            ActivateAbility(
                playerId = prepared.playerId,
                sourceId = prepared.sourceId,
                abilityId = prepared.abilityId,
            ),
        )

        prepared.environment.state.getZone(prepared.playerId, Zone.GRAVEYARD)
            .contains(prepared.sourceId) shouldBe true
    }

    test("Rules rejects missing deterministic costPayment atomically for Explicit V1 and V2") {
        fun assertDirectRulesRejection(
            paymentStrategyFor: (LegalActionView) -> PaymentStrategy,
        ) {
            val prepared = preparedWayfarer()
            val view = activatedView(prepared)
            val stateBefore = prepared.environment.state
            val stepCountBefore = prepared.environment.stepCount
            val eventsBefore = prepared.environment.lastStepEvents

            shouldThrow<IllegalArgumentException> {
                prepared.environment.stepStrict(
                    ActivateAbility(
                        playerId = prepared.playerId,
                        sourceId = prepared.sourceId,
                        abilityId = prepared.abilityId,
                        paymentStrategy = paymentStrategyFor(view),
                    ),
                )
            }

            (prepared.environment.state === stateBefore) shouldBe true
            prepared.environment.stepCount shouldBe stepCountBefore
            prepared.environment.lastStepEvents shouldBe eventsBefore
            prepared.environment.state.getZone(prepared.playerId, Zone.BATTLEFIELD)
                .contains(prepared.sourceId) shouldBe true
            prepared.environment.state.getEntity(prepared.sourceId)?.has<TappedComponent>() shouldBe false
        }

        assertDirectRulesRejection(::explicitV1FromPublic)
        assertDirectRulesRejection(::explicitV2FromPublic)
    }

    test("Wayfarer's search continuation resolves after the explicit activation") {
        val prepared = preparedWayfarer()
        val view = (prepared.gym.observe().observation as TrainingObservation).legalActions.single {
            it.kind == "ActivateAbility" && it.sourceEntityId == prepared.sourceId &&
                it.paymentDomain?.requiredCost == "{2}"
        }
        val actionPayload = payload(view, explicitV2FromPublic(view))
        view.requiredPayloadFields.all(actionPayload::containsKey) shouldBe true
        prepared.gym.step(view.actionId, actionPayload)

        var passCount = 0
        while (prepared.environment.pendingDecision == null &&
            prepared.environment.state.stack.isNotEmpty() && passCount++ < 8
        ) {
            val pass = prepared.environment.legalActions().first { it.action is com.wingedsheep.engine.core.PassPriority }
            prepared.environment.step(pass.action)
        }

        val decision = prepared.environment.pendingDecision as? SelectCardsDecision
            ?: error("Expected Wayfarer's search card decision")
        val selected = decision.options.first()
        prepared.environment.step(
            SubmitDecision(
                playerId = prepared.playerId,
                response = CardsSelectedResponse(decision.id, listOf(selected)),
            ),
        )

        prepared.environment.pendingDecision shouldBe null
        prepared.environment.state.stack shouldBe emptyList()
        prepared.environment.state.getZone(prepared.playerId, Zone.BATTLEFIELD) shouldContain selected
        prepared.environment.state.getEntity(selected)?.has<TappedComponent>() shouldBe true
    }

    test("missing or invalid deterministic costPayment rejects atomically") {
        fun freshCase(mutator: (LegalActionView, PaymentStrategy.ExplicitV2) -> JsonObject) {
            val prepared = preparedWayfarer()
            val view = (prepared.gym.observe().observation as TrainingObservation).legalActions.single {
                it.kind == "ActivateAbility" && it.sourceEntityId == prepared.sourceId &&
                    it.paymentDomain?.requiredCost == "{2}"
            }
            val payment = explicitV2FromPublic(view)
            assertRejectedAtomically(prepared, view, mutator(view, payment))
        }

        freshCase { view, payment -> payload(view, payment, costPayment = null) }
        freshCase { view, payment ->
            payload(
                view,
                payment,
                costPayment = exactSelfCostPayment(view).copy(
                    tappedPermanents = listOf(EntityId("wrong-source")),
                    sacrificedPermanents = listOf(EntityId("wrong-source")),
                ),
            )
        }
        freshCase { view, payment ->
            payload(
                view,
                payment,
                costPayment = exactSelfCostPayment(view).copy(
                    discardedCards = listOf(view.sourceEntityId!!),
                ),
            )
        }
        freshCase { view, payment ->
            payload(
                view,
                payment.copy(
                    paymentPlan = payment.paymentPlan!!.copy(spendAllocation = SpendAllocationV2()),
                ),
            )
        }
    }

    test("Gym rejects automatic, pool, and legacy source-ID payment without fallback") {
        fun freshCase(strategy: PaymentStrategy) {
            val prepared = preparedWayfarer()
            val view = (prepared.gym.observe().observation as TrainingObservation).legalActions.single {
                it.kind == "ActivateAbility" && it.sourceEntityId == prepared.sourceId &&
                    it.paymentDomain?.requiredCost == "{2}"
            }
            assertRejectedAtomically(prepared, view, payload(view, strategy))
        }

        freshCase(PaymentStrategy.AutoPay)
        freshCase(PaymentStrategy.FromPool)
        freshCase(
            PaymentStrategy.Explicit(
                manaAbilitiesToActivate = listOf(EntityId("legacy-source-id")),
            ),
        )
    }

    test("selected tap and sacrifice costs remain PAYMENT_DOMAIN_UNSUPPORTED") {
        val prepared = preparedChoiceCost()
        val action = prepared.environment.legalActions().single { legalAction ->
            val activate = legalAction.action as? ActivateAbility
            activate?.sourceId == prepared.sourceId && activate.abilityId == prepared.abilityId
        }
        val result = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .build(prepared.environment.state, prepared.playerId, listOf(action))

        result.diagnostics.map { it.code } shouldBe listOf(DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED)
        result.observation.legalActions.single().paymentDomain shouldBe null
    }
})

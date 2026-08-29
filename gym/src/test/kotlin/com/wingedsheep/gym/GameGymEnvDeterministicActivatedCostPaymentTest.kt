package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.CostUnitAllocation
import com.wingedsheep.engine.core.CostUnitAllocationV2
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.LifeChangedEvent
import com.wingedsheep.engine.core.LifeChangeReason
import com.wingedsheep.engine.core.ManaSpendReferenceV2
import com.wingedsheep.engine.core.ManaSpendReference
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PaymentPlanV2
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PermanentsSacrificedEvent
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.core.SpendAllocationV2
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TappedEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.mrd.cards.LightningGreaves
import com.wingedsheep.mtg.sets.definitions.`5dn`.cards.WayfarersBauble
import com.wingedsheep.mtg.sets.definitions.apc.cards.LlanowarWastes
import com.wingedsheep.mtg.sets.definitions.cmr.cards.WarRoom
import com.wingedsheep.mtg.sets.definitions.c17.cards.RamosDragonEngine
import com.wingedsheep.mtg.sets.definitions.iko.cards.ChevillBaneOfMonsters
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.wth.cards.MindStone
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ReduceEquipCost
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.costs.manaCostOrNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private data class PreparedDeterministicAbilityGym(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val cardRegistry: CardRegistry,
    val playerId: EntityId,
    val sourceId: EntityId,
    val abilityId: AbilityId,
    val mountainIds: List<EntityId>,
)

/** Characterization for public payment of deterministic activated-ability additional costs. */
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

    val dynamicPayLifeSource = card("Gym Dynamic PayLife Activated Cost Source") {
        typeLine = "Creature — Probe"
        power = 2
        toughness = 2
        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{1}"), Costs.PayLife(DynamicAmounts.sourcePower()))
            effect = Effects.GainLife(1)
        }
    }

    val zeroManaEquipment = card("Gym Zero-Mana Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{0}")
    }

    val reducedToZeroEquipment = card("Gym Reduced-To-Zero Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{1}")
    }

    val genericEquipCostReduction = card("Gym Generic Equip Cost Reduction") {
        typeLine = "Enchantment"
        staticAbility {
            ability = ReduceEquipCost(amount = 1)
        }
    }

    val zeroManaEquipTarget = card("Gym Zero-Mana Equip Target") {
        typeLine = "Creature — Probe"
        power = 1
        toughness = 1
    }

    fun registry(extraCards: List<CardDefinition> = emptyList()) = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(WayfarersBauble)
        register(MindStone)
        register(WarRoom)
        register(RamosDragonEngine)
        register(ChevillBaneOfMonsters)
        extraCards.forEach(::register)
    }

    fun preparedActivatedAbility(
        sourceCard: CardDefinition,
        abilityIndex: Int = 0,
        battlefieldCardNames: List<String> = emptyList(),
        extraCards: List<CardDefinition> = emptyList(),
        format: Format = Format.Standard,
        commanderCardName: String? = null,
        manaSourceCount: Int = 2,
        startingLife: Int? = null,
    ): PreparedDeterministicAbilityGym {
        val cardRegistry = registry(extraCards)
        val environment = GameEnvironment.create(cardRegistry)
        val deckEntries = buildList {
            add(sourceCard.name to 1)
            battlefieldCardNames.forEach { add(it to 1) }
            add("Mountain" to (manaSourceCount + 5))
        }
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(*deckEntries.toTypedArray()),
                        commanderCardName = commanderCardName,
                    ),
                    PlayerConfig(
                        "Bob",
                        Deck.of("Mountain" to 2),
                        commanderCardName = commanderCardName,
                    ),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                format = format,
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
        val mountainIds = List(manaSourceCount) { moveNamed("Mountain", Zone.BATTLEFIELD) }
        if (startingLife != null) state = state.withLifeTotal(playerId, startingLife)
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

    fun preparedWarRoom() = preparedActivatedAbility(
        sourceCard = WarRoom,
        abilityIndex = 1,
        format = Format.Commander(),
        commanderCardName = RamosDragonEngine.name,
        manaSourceCount = 3,
    )

    fun preparedWarRoomWithWastes(life: Int) = preparedActivatedAbility(
        sourceCard = WarRoom,
        abilityIndex = 1,
        battlefieldCardNames = listOf(LlanowarWastes.name),
        extraCards = listOf(LlanowarWastes),
        format = Format.Commander(),
        commanderCardName = ChevillBaneOfMonsters.name,
        manaSourceCount = 3,
        startingLife = life,
    )

    fun preparedDynamicPayLife() = preparedActivatedAbility(
        sourceCard = dynamicPayLifeSource,
        extraCards = listOf(dynamicPayLifeSource),
    )

    fun preparedZeroManaEquip() = preparedActivatedAbility(
        sourceCard = zeroManaEquipment,
        battlefieldCardNames = listOf(zeroManaEquipTarget.name),
        extraCards = listOf(zeroManaEquipment, zeroManaEquipTarget),
        manaSourceCount = 1,
    )

    fun preparedReducedToZeroEquip() = preparedActivatedAbility(
        sourceCard = reducedToZeroEquipment,
        battlefieldCardNames = listOf(zeroManaEquipTarget.name, genericEquipCostReduction.name),
        extraCards = listOf(
            reducedToZeroEquipment,
            zeroManaEquipTarget,
            genericEquipCostReduction,
        ),
        manaSourceCount = 1,
    )

    fun preparedLightningGreaves() = preparedActivatedAbility(
        sourceCard = LightningGreaves,
        battlefieldCardNames = listOf(zeroManaEquipTarget.name),
        extraCards = listOf(LightningGreaves, zeroManaEquipTarget),
        manaSourceCount = 1,
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

    fun gymActivatedView(
        prepared: PreparedDeterministicAbilityGym,
        requiredCost: String? = null,
    ): LegalActionView =
        (prepared.gym.observe().observation as TrainingObservation).legalActions.single {
            it.kind == "ActivateAbility" && it.sourceEntityId == prepared.sourceId &&
                (requiredCost == null || it.paymentDomain?.requiredCost == requiredCost)
        }

    fun explicitV2FromPublic(view: LegalActionView): PaymentStrategy.ExplicitV2 {
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        if (domain.costUnits.isEmpty()) {
            return PaymentStrategy.ExplicitV2(
                paymentPlan = PaymentPlanV2(
                    sourceActivations = emptyList(),
                    poolSpend = PoolSpend(),
                    spendAllocation = SpendAllocationV2(),
                ),
            )
        }
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

    fun targetedPayload(
        view: LegalActionView,
        targetId: EntityId,
        paymentStrategy: PaymentStrategy,
    ): JsonObject = buildJsonObject {
        view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
        put(
            "paymentStrategy",
            actionJson.encodeToJsonElement(PaymentStrategy.serializer(), paymentStrategy),
        )
        put(
            "targets",
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "Permanent")
                    put("entityId", targetId.value)
                })
            },
        )
    }

    fun publicTarget(view: LegalActionView): EntityId =
        view.targetDomain?.requirements?.singleOrNull()?.candidates?.singleOrNull()
            ?: error("Expected exactly one public target candidate: $view")

    fun explicitV2WithExtraSource(view: LegalActionView): PaymentStrategy.ExplicitV2 {
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        val source = domain.sourceActivations.first()
        return PaymentStrategy.ExplicitV2(
            paymentPlan = PaymentPlanV2(
                sourceActivations = listOf(
                    SourceActivation(
                        sourceId = source.sourceId,
                        manaAbilityKey = source.manaAbilityKey,
                        productionChoice = source.productionChoices.single(),
                    ),
                ),
                poolSpend = PoolSpend(),
                spendAllocation = SpendAllocationV2(),
            ),
        )
    }

    fun explicitV2WithArtificialZeroSymbolAllocation(
        nonEmptySpend: Boolean,
    ): PaymentStrategy.ExplicitV2 {
        return PaymentStrategy.ExplicitV2(
            paymentPlan = PaymentPlanV2(
                poolSpend = PoolSpend(),
                spendAllocation = SpendAllocationV2(
                    costUnits = listOf(
                        CostUnitAllocationV2(
                            symbolIndex = 0,
                            spends = if (nonEmptySpend) {
                                listOf(
                                    ManaSpendReferenceV2(
                                        poolColor = PaymentManaColor.RED,
                                        amount = 1,
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        ),
                    ),
                ),
            ),
        )
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

    test("real War Room exposes its structural deterministic life cost through public V4 data") {
        val prepared = preparedWarRoom()
        val action = prepared.environment.legalActions().single { legalAction ->
            val activate = legalAction.action as? ActivateAbility
            activate?.sourceId == prepared.sourceId && activate.abilityId == prepared.abilityId
        }
        val result = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .build(prepared.environment.state, prepared.playerId, listOf(action))
        val observation = result.observation as TrainingObservation
        val view = observation.legalActions.single()

        view.requiredPayloadFields shouldBe listOf("paymentStrategy", "costPayment")
        view.sourceEntityId shouldBe prepared.sourceId
        view.actionSemantics.toString().contains("CommanderColorIdentityCount") shouldBe true
        val commandCard = observation.zones.single {
            it.ownerId == prepared.playerId && it.zoneType == Zone.COMMAND
        }.cards.single()
        // Ramos is colorless as an object, while its public oracle text carries the WUBRG
        // identity symbols. The policy still supplies no life amount; Rules resolves the count.
        commandCard.colors shouldBe emptySet()
        commandCard.oracleText.contains("{W}{W}{U}{U}{B}{B}{R}{R}{G}{G}") shouldBe true

        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        domain.version shouldBe 4
        domain.requiredCost shouldBe "{3}"
        domain.sourceActivations.any { it.sourceId == prepared.sourceId } shouldBe false
    }

    test("PAY106-OUTER-COST-01: V5 exposes War Room's life reservation and pain budget") {
        val prepared = preparedWarRoomWithWastes(life = 2)
        val drawAbility = prepared.cardRegistry.requireCard(WarRoom.name).activatedAbilities[1]
        val action = LegalAction(
            action = ActivateAbility(
                playerId = prepared.playerId,
                sourceId = prepared.sourceId,
                abilityId = drawAbility.id,
            ),
            actionType = "ActivateAbility",
            description = "Activate War Room",
            affordable = true,
            manaCostString = "{3}",
        )
        val domain = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, action)
            ?: error("Expected PaymentDomainV5 for War Room")
        val wastesId = prepared.environment.state.getZone(prepared.playerId, Zone.BATTLEFIELD)
            .single { id ->
                prepared.environment.state.getEntity(id)?.get<CardComponent>()?.name == LlanowarWastes.name
            }

        domain.reservedOuterLifePayment shouldBe 2
        domain.fixedSelfDamageBudget shouldBe 0
        domain.sourceActivationOptions.any { it.sourceId == prepared.sourceId } shouldBe false
        domain.sourceActivationOptions.any { it.sourceId == prepared.mountainIds.first() } shouldBe true
        domain.sourceActivationOptions
            .filter { it.sourceId == wastesId && it.fixedSelfDamageAmount == 1 }
            .size shouldBe 2
        domain.sourceActivationOptions
            .filter {
                it.sourceId == wastesId &&
                    it.fixedSelfDamageAmount > checkNotNull(domain.fixedSelfDamageBudget)
            }
            .size shouldBe 2
    }

    test("PAY106-OUTER-COST-02: V5 retains a painful War Room plan when life covers it") {
        val prepared = preparedWarRoomWithWastes(life = 3)
        val drawAbility = prepared.cardRegistry.requireCard(WarRoom.name).activatedAbilities[1]
        val action = LegalAction(
            action = ActivateAbility(
                playerId = prepared.playerId,
                sourceId = prepared.sourceId,
                abilityId = drawAbility.id,
            ),
            actionType = "ActivateAbility",
            description = "Activate War Room",
            affordable = true,
            manaCostString = "{3}",
        )
        val domain = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, action)
            ?: error("Expected PaymentDomainV5 for War Room")

        domain.reservedOuterLifePayment shouldBe 2
        domain.fixedSelfDamageBudget shouldBe 1
        domain.sourceActivationOptions.count { it.fixedSelfDamageAmount == 1 } shouldBe 2
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

    test("fixed ordinary zero-mana Equip publishes V4 and executes an explicit zero-spend plan") {
        val prepared = preparedZeroManaEquip()
        val view = gymActivatedView(prepared, requiredCost = "{0}")
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")

        domain.version shouldBe 4
        domain.requiredCost shouldBe "{0}"
        domain.costUnits shouldBe emptyList()
        view.requiredPayloadFields shouldContain "paymentStrategy"
        view.requiredPayloadFields shouldContain "targets"

        val target = publicTarget(view)
        view.targetDomain!!.requirements.single().candidates.toSet() shouldBe setOf(target)
        val stepCountBefore = prepared.environment.stepCount
        val poolBefore = prepared.environment.state.getEntity(prepared.playerId)
            ?.get<ManaPoolComponent>()
        val zeroPayment = explicitV2FromPublic(view)
        zeroPayment.paymentPlan!!.sourceActivations shouldBe emptyList()
        zeroPayment.paymentPlan!!.poolSpend shouldBe PoolSpend()
        zeroPayment.paymentPlan!!.spendAllocation.costUnits shouldBe emptyList()

        prepared.gym.step(
            view.actionId,
            targetedPayload(view, target, zeroPayment),
        )

        prepared.environment.stepCount shouldBe stepCountBefore + 1
        prepared.environment.state.getEntity(prepared.sourceId)?.has<TappedComponent>() shouldBe false
        prepared.mountainIds.forEach { mountainId ->
            prepared.environment.state.getEntity(mountainId)?.has<TappedComponent>() shouldBe false
        }
        prepared.environment.state.getEntity(prepared.playerId)
            ?.get<ManaPoolComponent>() shouldBe poolBefore

        var passes = 0
        while (prepared.environment.state.stack.isNotEmpty() && passes++ < 8) {
            val pass = prepared.environment.legalActions().first { it.action is PassPriority }
            prepared.environment.step(pass.action)
        }
        prepared.environment.state.stack shouldBe emptyList()
        prepared.environment.state.getEntity(prepared.sourceId)
            ?.get<AttachedToComponent>()
            ?.targetId shouldBe target
    }

    test("reduced positive Equip cost publishes canonical zero and executes an explicit empty plan") {
        val prepared = preparedReducedToZeroEquip()
        val view = gymActivatedView(prepared, requiredCost = "{0}")
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        val target = publicTarget(view)

        reducedToZeroEquipment.activatedAbilities.single().cost.manaCostOrNull shouldBe ManaCost.parse("{1}")
        domain.version shouldBe 4
        domain.requiredCost shouldBe "{0}"
        domain.costUnits shouldBe emptyList()

        val stepCountBefore = prepared.environment.stepCount
        val poolBefore = prepared.environment.state.getEntity(prepared.playerId)
            ?.get<ManaPoolComponent>()
        val zeroPayment = explicitV2FromPublic(view)
        zeroPayment.paymentPlan!!.sourceActivations shouldBe emptyList()
        zeroPayment.paymentPlan!!.poolSpend shouldBe PoolSpend()
        zeroPayment.paymentPlan!!.spendAllocation.costUnits shouldBe emptyList()

        prepared.gym.step(
            view.actionId,
            targetedPayload(view, target, zeroPayment),
        )

        prepared.environment.stepCount shouldBe stepCountBefore + 1
        prepared.environment.state.getEntity(prepared.sourceId)?.has<TappedComponent>() shouldBe false
        prepared.mountainIds.forEach { mountainId ->
            prepared.environment.state.getEntity(mountainId)?.has<TappedComponent>() shouldBe false
        }
        prepared.environment.state.getEntity(prepared.playerId)
            ?.get<ManaPoolComponent>() shouldBe poolBefore

        var passes = 0
        while (prepared.environment.state.stack.isNotEmpty() && passes++ < 8) {
            val pass = prepared.environment.legalActions().first { it.action is PassPriority }
            prepared.environment.step(pass.action)
        }
        prepared.environment.state.stack shouldBe emptyList()
        prepared.environment.state.getEntity(prepared.sourceId)
            ?.get<AttachedToComponent>()
            ?.targetId shouldBe target
    }

    test("zero-mana ExplicitV2 rejects hidden payment choices and incomplete zero allocations atomically") {
        fun freshCase(strategyFor: (LegalActionView) -> PaymentStrategy) {
            val prepared = preparedZeroManaEquip()
            val view = gymActivatedView(prepared, requiredCost = "{0}")
            val target = publicTarget(view)
            assertRejectedAtomically(
                prepared,
                view,
                targetedPayload(view, target, strategyFor(view)),
            )
        }

        freshCase(::explicitV2WithExtraSource)
        freshCase { explicitV2WithArtificialZeroSymbolAllocation(nonEmptySpend = true) }
        freshCase { explicitV2WithArtificialZeroSymbolAllocation(nonEmptySpend = false) }
        freshCase { PaymentStrategy.AutoPay }
        freshCase { PaymentStrategy.FromPool }
        freshCase {
            PaymentStrategy.Explicit(
                manaAbilitiesToActivate = listOf(EntityId("legacy-source-id")),
            )
        }
    }

    test("zero-mana Equip preserves the public target domain and rejects an unpublished target") {
        val prepared = preparedZeroManaEquip()
        val view = gymActivatedView(prepared, requiredCost = "{0}")
        val target = publicTarget(view)
        view.targetDomain!!.requirements.single().candidates.toSet() shouldBe setOf(target)

        assertRejectedAtomically(
            prepared,
            view,
            targetedPayload(
                view,
                EntityId("unpublished-target"),
                explicitV2FromPublic(view),
            ),
        )
    }

    test("real Lightning Greaves publishes and executes its generic zero-mana Equip contract") {
        val prepared = preparedLightningGreaves()
        val view = gymActivatedView(prepared, requiredCost = "{0}")
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        val target = publicTarget(view)

        domain.version shouldBe 4
        domain.requiredCost shouldBe "{0}"
        domain.costUnits shouldBe emptyList()
        view.targetDomain!!.requirements.single().candidates.toSet() shouldBe setOf(target)

        prepared.gym.step(
            view.actionId,
            targetedPayload(view, target, explicitV2FromPublic(view)),
        )
        var passes = 0
        while (prepared.environment.state.stack.isNotEmpty() && passes++ < 8) {
            val pass = prepared.environment.legalActions().first { it.action is PassPriority }
            prepared.environment.step(pass.action)
        }
        prepared.environment.state.stack shouldBe emptyList()
        prepared.environment.state.getEntity(prepared.sourceId)
            ?.get<AttachedToComponent>()
            ?.targetId shouldBe target
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

    test("public PaymentPlanV2 pays War Room mana, then Rules pays commander life and resolves normally") {
        val prepared = preparedWarRoom()
        val view = gymActivatedView(prepared, requiredCost = "{3}")
        val lifeBefore = prepared.environment.state.lifeTotal(prepared.playerId)

        prepared.gym.step(
            view.actionId,
            payload(
                view,
                explicitV2FromPublic(view),
                AdditionalCostPayment(
                    tappedPermanents = listOf(view.sourceEntityId ?: error("Expected sourceEntityId")),
                ),
            ),
        )

        prepared.environment.state.lifeTotal(prepared.playerId) shouldBe lifeBefore - 5
        prepared.environment.lastStepEvents.filterIsInstance<LifeChangedEvent>()
            .single { it.playerId == prepared.playerId } shouldBe LifeChangedEvent(
            playerId = prepared.playerId,
            oldLife = lifeBefore,
            newLife = lifeBefore - 5,
            reason = LifeChangeReason.PAYMENT,
        )
        prepared.environment.lastStepEvents.filterIsInstance<TappedEvent>()
            .count { it.entityId == prepared.sourceId } shouldBe 1
        prepared.environment.lastStepEvents.filterIsInstance<PermanentsSacrificedEvent>()
            .none { it.permanentIds.contains(prepared.sourceId) } shouldBe true
        prepared.environment.state.getEntity(prepared.sourceId)?.has<TappedComponent>() shouldBe true
        prepared.environment.state.getZone(prepared.playerId, Zone.BATTLEFIELD)
            .contains(prepared.sourceId) shouldBe true
        prepared.environment.state.stack.isNotEmpty() shouldBe true

        var passCount = 0
        while (prepared.environment.pendingDecision == null &&
            prepared.environment.state.stack.isNotEmpty() && passCount++ < 8
        ) {
            val pass = prepared.environment.legalActions().first { it.action is PassPriority }
            prepared.environment.step(pass.action)
        }

        prepared.environment.pendingDecision shouldBe null
        prepared.environment.state.stack shouldBe emptyList()
        prepared.environment.lastStepEvents.filterIsInstance<CardsDrawnEvent>()
            .single { it.playerId == prepared.playerId && it.count == 1 }
    }

    test("War Room rejects missing or invalid structured payment atomically") {
        fun freshCase(mutator: (LegalActionView, PaymentStrategy.ExplicitV2) -> JsonObject) {
            val prepared = preparedWarRoom()
            val view = gymActivatedView(prepared, requiredCost = "{3}")
            val payment = explicitV2FromPublic(view)
            assertRejectedAtomically(prepared, view, mutator(view, payment))
        }

        freshCase { view, payment -> payload(view, payment, costPayment = null) }
        freshCase { view, payment ->
            payload(
                view,
                payment,
                costPayment = AdditionalCostPayment(
                    tappedPermanents = listOf(EntityId("wrong-source")),
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

    test("unsupported dynamic PayLife remains PAYMENT_DOMAIN_UNSUPPORTED") {
        val prepared = preparedDynamicPayLife()
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

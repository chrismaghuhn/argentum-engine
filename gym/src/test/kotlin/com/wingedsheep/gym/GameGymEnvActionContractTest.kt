package com.wingedsheep.gym

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.CostUnitAllocation
import com.wingedsheep.engine.core.CostUnitAllocationV2
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaSpendReference
import com.wingedsheep.engine.core.ManaSpendReferenceV2
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PaymentPlanV2
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.core.SpendAllocation
import com.wingedsheep.engine.core.SpendAllocationV2
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.AdditionalCostData
import com.wingedsheep.engine.legalactions.ModalEnumerationMode
import com.wingedsheep.engine.legalactions.ModalLegalEnumeration
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gym.contract.ActionPayloadRequirements
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PaymentDomainV4
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.sth.StrongholdSet
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AlternativePaymentChoice
import com.wingedsheep.sdk.scripting.EquipPaymentChoice
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import com.wingedsheep.sdk.core.Zone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

private data class PreparedEquipGym(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val sourceId: EntityId,
    val targetIds: List<EntityId>,
)

/**
 * Regression coverage for action IDs whose LegalAction is a target/payment template rather than
 * an executable GameAction. The external controller must supply the missing choice explicitly;
 * Gym must not invent it.
 */
class GameGymEnvActionContractTest : FunSpec({

    val payableActionSource = card("Gym Contract Payable Action Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Mana("{1}{R}")
            effect = Effects.GainLife(1)
        }
    }

    val fixedCostEquipment = card("Gym Contract Fixed Cost Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{1}")
    }

    val firstEquipTarget = card("Gym Contract First Equip Target") {
        typeLine = "Creature — Bear"
        power = 1
        toughness = 1
    }

    val secondEquipTarget = card("Gym Contract Second Equip Target") {
        typeLine = "Creature — Bird"
        power = 2
        toughness = 2
    }

    val targetlessSpell = card("Gym Contract Targetless Spell") {
        manaCost = "{R}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val castV2MaterializationSpell = card("Gym Contract V2 Materialization Spell") {
        manaCost = "{2}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val fixedOutputManaSource = card("Gym Contract Fixed Output Mana Source") {
        typeLine = "Land — Cave"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.RED).then(Effects.AddMana(Color.GREEN))
            manaAbility = true
        }
    }

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(StrongholdSet.cards)
        register(payableActionSource)
        register(fixedCostEquipment)
        register(firstEquipTarget)
        register(secondEquipTarget)
        register(targetlessSpell)
        register(castV2MaterializationSpell)
        register(fixedOutputManaSource)
    }

    fun config() = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 1, "Shock" to 1)),
            PlayerConfig("Bob", Deck.of("Mountain" to 1, "Shock" to 1)),
        ),
        startingHandSize = 2,
        skipMulligans = true,
        startingPlayerIndex = 0,
    )

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun paymentStrategyPayload(view: com.wingedsheep.gym.contract.LegalActionView): PaymentStrategy {
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        val source = domain.sourceActivations.first()
        return PaymentStrategy.Explicit(
            paymentPlan = PaymentPlanV1(
                sourceActivations = listOf(
                    SourceActivation(
                        sourceId = source.sourceId,
                        manaAbilityKey = source.manaAbilityKey,
                        productionChoice = ProductionChoice(
                            source.productionChoices.first().producedColor,
                        ),
                    ),
                ),
                poolSpend = PoolSpend(),
                spendAllocation = SpendAllocation(
                    costUnits = listOf(
                        CostUnitAllocation(
                            symbolIndex = domain.costUnits.first().symbolIndex,
                            spends = listOf(
                                ManaSpendReference(
                                    sourceId = source.sourceId,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    fun preparedFixedEquip(): PreparedEquipGym {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            fixedCostEquipment.name to 1,
                            firstEquipTarget.name to 1,
                            secondEquipTarget.name to 1,
                            "Mountain" to 2,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 4)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
        )
        var state = environment.state
        while (state.step != com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }
        val player = environment.playerIds.first()

        fun moveNamed(name: String): EntityId {
            val id = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val from = state.zones.entries.first { (_, ids) -> id in ids }.key
            state = state.moveToZone(id, from, ZoneKey(player, Zone.BATTLEFIELD))
            return id
        }

        val sourceId = moveNamed(fixedCostEquipment.name)
        val targetIds = listOf(
            moveNamed(firstEquipTarget.name),
            moveNamed(secondEquipTarget.name),
        )
        moveNamed("Mountain")
        environment.restore(state, environment.playerIds, environment.stepCount)

        return PreparedEquipGym(
            environment = environment,
            gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            ),
            sourceId = sourceId,
            targetIds = targetIds,
        )
    }

    fun explicitV2Payment(view: com.wingedsheep.gym.contract.LegalActionView): PaymentStrategy.ExplicitV2 {
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        val source = domain.sourceActivations.first()
        val productionChoice = source.productionChoices.single()
        val plan = PaymentPlanV2(
            sourceActivations = listOf(
                SourceActivation(
                    sourceId = source.sourceId,
                    manaAbilityKey = source.manaAbilityKey,
                    productionChoice = productionChoice,
                ),
            ),
            poolSpend = PoolSpend(),
            spendAllocation = SpendAllocationV2(
                costUnits = listOf(
                    CostUnitAllocationV2(
                        symbolIndex = domain.costUnits.single().symbolIndex,
                        spends = listOf(
                            ManaSpendReferenceV2(
                                sourceId = source.sourceId,
                                amount = 1,
                                sourceOutputIndex = productionChoice.fixedOutputs?.first()?.index,
                            ),
                        ),
                    ),
                ),
            ),
        )
        return PaymentStrategy.ExplicitV2(paymentPlan = plan)
    }

    fun incompleteExplicitV2Payment(
        view: com.wingedsheep.gym.contract.LegalActionView,
    ): PaymentStrategy.ExplicitV2 {
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

    fun castV2MaterializationPayment(
        view: com.wingedsheep.gym.contract.LegalActionView,
    ): PaymentStrategy.ExplicitV2 {
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV4")
        val source = domain.sourceActivations.single()
        val productionChoice = source.productionChoices.single()
        val fixedOutputs = productionChoice.fixedOutputs ?: error("Expected fixed output metadata")
        return PaymentStrategy.ExplicitV2(
            paymentPlan = PaymentPlanV2(
                sourceActivations = listOf(
                    SourceActivation(
                        sourceId = source.sourceId,
                        manaAbilityKey = source.manaAbilityKey,
                        productionChoice = productionChoice,
                    ),
                ),
                poolSpend = PoolSpend(colorless = 1),
                spendAllocation = SpendAllocationV2(
                    costUnits = listOf(
                        CostUnitAllocationV2(
                            symbolIndex = domain.costUnits.single().symbolIndex,
                            spends = listOf(
                                ManaSpendReferenceV2(
                                    poolColor = PaymentManaColor.COLORLESS,
                                    amount = 1,
                                ),
                                ManaSpendReferenceV2(
                                    sourceId = source.sourceId,
                                    amount = 1,
                                    sourceOutputIndex = fixedOutputs.first().index,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    fun equipPayload(
        view: com.wingedsheep.gym.contract.LegalActionView,
        targetId: EntityId,
        payment: PaymentStrategy.ExplicitV2,
    ) = buildJsonObject {
        view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
        put(
            "paymentStrategy",
            actionJson.encodeToJsonElement(PaymentStrategy.serializer(), payment),
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

    test("targeted action IDs require an explicit structured action payload") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )

        gym.reset(config())
        var observed = gym.observe()
        var land = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        var setupSteps = 0
        while (land == null && setupSteps++ < 20) {
            // The environment starts before the first main phase. This is test setup only; the
            // production Gym never auto-answers a pending decision or action.
            val pass = environment.legalActions().first { it.action is com.wingedsheep.engine.core.PassPriority }
            environment.step(pass.action)
            observed = gym.observe()
            land = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        }
        val selectedLand = land ?: error(
            "Expected land action, got: ${observed.observation.legalActions}; " +
                "hand=${environment.state.getHand(environment.playerIds[0]).map { id -> environment.state.getEntity(id) }}"
        )
        gym.step(selectedLand.actionId)

        val afterLand = gym.observe()
        val targeted = afterLand.observation.legalActions.firstOrNull {
            it.kind == "CastSpell" && it.minTargets > 0 && it.actionSemantics != null
        } ?: error("Expected targeted action, got: ${observed.observation.legalActions}")
        targeted.requiresStructuredAction shouldBe true
        targeted.requiredPayloadFields shouldContain "targets"
        targeted.requiresStructuredAction shouldBe targeted.requiredPayloadFields.isNotEmpty()
        val stepCountBefore = environment.stepCount

        shouldThrow<IllegalArgumentException> {
            gym.step(targeted.actionId)
        }
        environment.stepCount shouldBe stepCountBefore

        shouldThrow<IllegalArgumentException> {
            gym.step(targeted.actionId, buildJsonObject {})
        }
        environment.stepCount shouldBe stepCountBefore

        val opponent = environment.playerIds[1]
        val payload = buildJsonObject {
            targeted.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    paymentStrategyPayload(targeted),
                ),
            )
            put(
                "targets",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "Player")
                        put("playerId", opponent.value)
                    })
                }
            )
        }

        val injectedPayload = buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put("cardId", "not-the-selected-card")
        }
        shouldThrow<IllegalArgumentException> {
            gym.step(targeted.actionId, injectedPayload)
        }
        environment.stepCount shouldBe stepCountBefore

        val staleActionId = targeted.actionId
        gym.step(staleActionId, payload)
        environment.stepCount shouldBe stepCountBefore + 1

        shouldThrow<IllegalArgumentException> {
            gym.step(staleActionId)
        }
        environment.stepCount shouldBe stepCountBefore + 1
    }

    test("targetless CastSpell rejects extra targets before strict execution") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 1, targetlessSpell.name to 1)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 1, "Shock" to 1)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
        )

        var observed = gym.observe()
        var land = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        var setupSteps = 0
        while (land == null && setupSteps++ < 20) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            observed = gym.observe()
            land = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        }
        val selectedLand = land ?: error("Expected a PlayLand action during setup")
        gym.step(selectedLand.actionId)

        val targetless = gym.observe().observation.legalActions.firstOrNull {
            it.kind == "CastSpell" &&
                it.description.contains(targetlessSpell.name) &&
                it.actionSemantics != null
        } ?: error("Expected targetless CastSpell action")
        val payload = buildJsonObject {
            targetless.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    paymentStrategyPayload(targetless),
                ),
            )
            put(
                "targets",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "Player")
                        put("playerId", environment.playerIds[1].value)
                    })
                },
            )
        }
        val stepCountBefore = environment.stepCount

        shouldThrow<IllegalArgumentException> {
            gym.step(targetless.actionId, payload)
        }
        environment.stepCount shouldBe stepCountBefore
    }

    test("targetless CastSpell ExplicitV2 preserves shared payment materialization") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val setupGym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        setupGym.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(fixedOutputManaSource.name to 1, castV2MaterializationSpell.name to 1),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 1, "Shock" to 1)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
        )

        var observed = setupGym.observe()
        var land = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        var setupSteps = 0
        while (land == null && setupSteps++ < 20) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            observed = setupGym.observe()
            land = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        }
        val selectedLand = land ?: error("Expected a PlayLand action during setup")
        setupGym.step(selectedLand.actionId)
        val playerId = environment.playerIds.first()
        val sourceId = environment.state.getZone(playerId, Zone.BATTLEFIELD).single()

        val stateWithPool = environment.state.updateEntity(playerId) { container ->
            container.with(ManaPoolComponent(colorless = 1))
        }
        environment.restore(stateWithPool, environment.playerIds, environment.stepCount)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )

        val targetless = gym.observe().observation.legalActions.firstOrNull {
            it.kind == "CastSpell" &&
                it.description.contains(castV2MaterializationSpell.name) &&
                it.actionSemantics != null
        } ?: error("Expected targetless CastSpell action")
        val payload = buildJsonObject {
            targetless.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    castV2MaterializationPayment(targetless),
                ),
            )
        }
        val stepCountBefore = environment.stepCount
        gym.step(targetless.actionId, payload)

        environment.stepCount shouldBe stepCountBefore + 1
        environment.state.getEntity(sourceId)?.has<TappedComponent>() shouldBe true
        environment.state.getEntity(playerId)?.get<ManaPoolComponent>()?.green shouldBe 1
        environment.state.getEntity(playerId)?.get<ManaPoolComponent>()?.manaBySource?.get(sourceId) shouldBe 1
        environment.state.stack.isNotEmpty() shouldBe true
        environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single().reason shouldBe
            "Cast ${castV2MaterializationSpell.name}"
        environment.lastStepEvents.filterIsInstance<SpellCastEvent>().single().spentManaSourceIds shouldBe
            setOf(sourceId)
    }

    test("payable structured ActivateAbility publishes an externally usable payment domain") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of(payableActionSource.name to 1, "Mountain" to 1)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 2)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
        )
        var state = environment.state
        while (state.step != com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is com.wingedsheep.engine.core.PassPriority }
            environment.step(pass.action)
            state = environment.state
        }
        val player = environment.playerIds.first()
        val sourceId = state.entities.entries
            .first { (id, container) ->
                id in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == payableActionSource.name
            }
            .key
        val sourceZone = state.zones.entries.first { (_, ids) -> sourceId in ids }.key
        state = state.moveToZone(
            sourceId,
            sourceZone,
            ZoneKey(player, Zone.BATTLEFIELD),
        )
        val mountainId = state.entities.entries
            .first { (id, container) ->
                id in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == "Mountain"
            }
            .key
        val mountainZone = state.zones.entries.first { (_, ids) -> mountainId in ids }.key
        state = state.moveToZone(mountainId, mountainZone, ZoneKey(player, Zone.BATTLEFIELD))
        environment.restore(state, environment.playerIds, environment.stepCount)
        val legalAction = LegalAction(
            action = ActivateAbility(
                playerId = player,
                sourceId = sourceId,
                abilityId = payableActionSource.activatedAbilities.single().id,
            ),
            actionType = "ActivateAbility",
            description = "Activate payable test ability",
            affordable = true,
            manaCostString = "{1}{R}",
        )

        val observation = ObservationBuilder(cardRegistry = cardRegistry)
            .build(environment.state, player, listOf(legalAction))
            .observation as TrainingObservation
        val view = observation.legalActions.single()

        view.manaCost shouldBe "{1}{R}"
        view.requiresStructuredAction shouldBe true
        view.requiredPayloadFields shouldBe listOf("paymentStrategy")
        view.requiresStructuredAction shouldBe view.requiredPayloadFields.isNotEmpty()
        val paymentDomain = view.paymentDomain ?: error("expected PaymentDomainV4")
        paymentDomain.sourceActivations.single().sourceId shouldBe mountainId
        paymentDomain.sourceActivations.single().productionChoices
            .map { it.producedColor } shouldContain PaymentManaColor.RED
        paymentDomain.sourceActivations.single().manaAbilityKey shouldBe "intrinsic:R"
        Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }.encodeToJsonElement(PaymentDomainV4.serializer(), paymentDomain)
            .jsonObject.containsKey("autoPaySuggestion") shouldBe false
        Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }.encodeToString(TrainingObservation.serializer(), observation)
            .contains("\"paymentDomain\"") shouldBe true
    }

    test("fixed Equip executes the complete public TargetDomain to ExplicitV2 to Attach path") {
        val prepared = preparedFixedEquip()
        val observed = prepared.gym.observe().observation
        val view = observed.legalActions.firstOrNull { candidate ->
            candidate.kind == "ActivateAbility" &&
                candidate.paymentDomain?.requiredCost == "{1}" &&
                candidate.targetDomain?.requirements?.singleOrNull()?.candidates?.size == 2
        } ?: error("Expected target-bearing fixed Equip action: ${observed.legalActions}")
        val publicCandidates = view.targetDomain!!.requirements.single().candidates.toSet()
        publicCandidates shouldBe prepared.targetIds.toSet()
        val submittedTarget = prepared.targetIds[1]
        publicCandidates.contains(submittedTarget) shouldBe true

        val stepCountBefore = prepared.environment.stepCount
        prepared.gym.step(
            view.actionId,
            equipPayload(view, submittedTarget, explicitV2Payment(view)),
        )
        prepared.environment.stepCount shouldBe stepCountBefore + 1

        var passes = 0
        while (prepared.environment.state.stack.isNotEmpty() && passes++ < 8) {
            val pass = prepared.environment.legalActions().first { it.action is PassPriority }
            prepared.environment.step(pass.action)
        }
        prepared.environment.state.stack shouldBe emptyList()
        prepared.environment.state.getEntity(prepared.sourceId)
            ?.get<AttachedToComponent>()
            ?.targetId shouldBe submittedTarget
    }

    test("fixed Equip rejects unpublished targets and incomplete ExplicitV2 without advancing") {
        val prepared = preparedFixedEquip()
        val observed = prepared.gym.observe().observation
        val view = observed.legalActions.firstOrNull { candidate ->
            candidate.kind == "ActivateAbility" &&
                candidate.paymentDomain?.requiredCost == "{1}" &&
                candidate.targetDomain?.requirements?.singleOrNull()?.candidates?.size == 2
        } ?: error("Expected target-bearing fixed Equip action: ${observed.legalActions}")
        val unpublishedTarget = EntityId("not-a-published-equip-target")
        view.targetDomain!!.requirements.single().candidates.contains(unpublishedTarget) shouldBe false
        val stepCountBefore = prepared.environment.stepCount

        shouldThrow<IllegalArgumentException> {
            prepared.gym.step(
                view.actionId,
                equipPayload(view, unpublishedTarget, explicitV2Payment(view)),
            )
        }
        prepared.environment.stepCount shouldBe stepCountBefore

        shouldThrow<IllegalArgumentException> {
            prepared.gym.step(
                view.actionId,
                equipPayload(
                    view,
                    prepared.targetIds.first(),
                    incompleteExplicitV2Payment(view),
                ),
            )
        }
        prepared.environment.stepCount shouldBe stepCountBefore
    }

    test("variable sacrifice publishes candidates and complete cardinality in the observation") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(config())

        val playerId = environment.playerIds[0]
        val candidates = listOf(EntityId("creature-b"), EntityId("creature-a"))
        val template = LegalAction(
            action = PassPriority(playerId),
            actionType = "CastSpell",
            description = "Cast Plumb the Forbidden",
            additionalCostInfo = AdditionalCostData(
                description = "Sacrifice one or more creatures",
                costType = "VariableSacrifice",
                validSacrificeTargets = candidates,
                sacrificeCount = 0,
                sacrificeMinCount = 0,
                sacrificeMaxCount = candidates.size,
            ),
        )

        val observed = ObservationBuilder(cardRegistry = cardRegistry)
            .build(environment.state, playerId, listOf(template))
            .observation as com.wingedsheep.gym.contract.TrainingObservation
        val view = observed.legalActions.single()

        view.validSacrificeTargets shouldBe candidates.sortedBy { it.value }
        view.sacrificeCount shouldBe 0
        view.sacrificeMinCount shouldBe 0
        view.sacrificeMaxCount shouldBe 2
        view.requiresStructuredAction shouldBe true
        view.requiredPayloadFields shouldBe listOf("additionalCostPayment")
        view.requiresStructuredAction shouldBe view.requiredPayloadFields.isNotEmpty()
    }

    test("required payload fields are canonical, deduplicated, and structural when unaffordable") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        val player = environment.playerIds.first()
        val action = LegalAction(
            action = PassPriority(player),
            actionType = "CastSpell",
            description = "Plumb-shaped structured action",
            manaCostString = "{X}",
            hasXCost = true,
            additionalCostInfo = AdditionalCostData(
                description = "Sacrifice any number",
                costType = "VariableSacrifice",
                sacrificeMinCount = 0,
                sacrificeMaxCount = 0,
            ),
            requiresForage = true,
        )

        ActionPayloadRequirements.requiredPayloadFields(action) shouldBe
            listOf("xValue", "paymentStrategy", "additionalCostPayment")
        ActionPayloadRequirements.missingRequiredFields(action, buildJsonObject {}) shouldBe
            listOf("xValue", "paymentStrategy", "additionalCostPayment")

        fun viewFor(candidate: LegalAction): TrainingObservation =
            ObservationBuilder(cardRegistry = registry())
                .build(environment.state, player, listOf(candidate))
                .observation as TrainingObservation

        val affordableView = viewFor(action).legalActions.single()
        val unaffordableView = viewFor(action.copy(affordable = false)).legalActions.single()

        affordableView.requiredPayloadFields shouldBe
            listOf("xValue", "paymentStrategy", "additionalCostPayment")
        unaffordableView.requiredPayloadFields shouldBe affordableView.requiredPayloadFields
        affordableView.requiresStructuredAction shouldBe affordableView.requiredPayloadFields.isNotEmpty()
        unaffordableView.requiresStructuredAction shouldBe unaffordableView.requiredPayloadFields.isNotEmpty()
    }

    test("unknown required payload fields fail closed during canonicalization") {
        val failure = shouldThrow<IllegalStateException> {
            ActionPayloadRequirements.canonicalizeRequiredPayloadFields(
                setOf("futureFieldB", "futureFieldA")
            )
        }

        failure.message shouldBe
            "Missing canonical required-payload field(s): [futureFieldA, futureFieldB]"
    }

    test("combat declaration templates require explicit empty-or-populated choices") {
        val player = EntityId("player")
        val cases = listOf(
            LegalAction(DeclareAttackers(player, emptyMap()), "DeclareAttackers", "attackers") to
                listOf("attackers", "bands"),
            LegalAction(DeclareBlockers(player, emptyMap()), "DeclareBlockers", "blockers") to
                listOf("blockers"),
            LegalAction(OrderBlockers(player, EntityId("attacker"), emptyList()), "OrderBlockers", "order") to
                listOf("orderedBlockers")
        )

        cases.forEach { (action, requiredFields) ->
            ActionPayloadRequirements.requiresStructuredAction(action) shouldBe true
            ActionPayloadRequirements.missingRequiredFields(action, buildJsonObject {}) shouldBe requiredFields
        }

        ActionPayloadRequirements.missingRequiredFields(
            LegalAction(
                action = DeclareAttackers(player, emptyMap()),
                actionType = "DeclareAttackers",
                description = "attackers"
            ),
            buildJsonObject { put("attackers", buildJsonObject {}) }
        ) shouldBe listOf("bands")
    }

    test("structured payload fields name the action's actual choice slots") {
        val player = EntityId("player")
        val vehicle = EntityId("vehicle")
        val mount = EntityId("mount")

        ActionPayloadRequirements.requiredPayloadFields(
            LegalAction(
                action = CrewVehicle(player, vehicle, emptyList()),
                actionType = "CrewVehicle",
                description = "crew",
                tapForPower = true
            )
        ) shouldBe listOf("crewCreatures")
        ActionPayloadRequirements.requiredPayloadFields(
            LegalAction(
                action = SaddleMount(player, mount, emptyList()),
                actionType = "SaddleMount",
                description = "saddle",
                tapForPower = true
            )
        ) shouldBe listOf("saddleCreatures")
        ActionPayloadRequirements.requiredPayloadFields(
            LegalAction(
                action = CycleCard(player, EntityId("cycling-card")),
                actionType = "CycleCard",
                description = "cycle",
                manaCostString = "{X}",
                hasXCost = true
            )
        ) shouldBe listOf("xValue", "paymentStrategy")
        ActionPayloadRequirements.requiredPayloadFields(
            LegalAction(
                action = TurnFaceUp(player, EntityId("face-down")),
                actionType = "TurnFaceUp",
                description = "turn face up",
                manaCostString = "{X}",
                hasXCost = true
            )
        ) shouldBe listOf("xValue", "paymentStrategy")

        ActionPayloadRequirements.requiredPayloadFields(
            LegalAction(
                action = ActivateAbility(
                    playerId = player,
                    sourceId = EntityId("equipment"),
                    abilityId = AbilityId("equip"),
                    alternativePayment = AlternativePaymentChoice(
                        equipPayment = EquipPaymentChoice.FREE_FIRST_EQUIP
                    )
                ),
                actionType = "ActivateAbility",
                description = "Equip {0}"
            )
        ) shouldBe listOf("alternativePayment")

        ActionPayloadRequirements.requiredPayloadFields(
            LegalAction(
                action = CastSpell(player, EntityId("modal-spell")),
                actionType = "CastSpellModal",
                description = "choose modes",
                modalEnumeration = ModalLegalEnumeration(
                    chooseCount = 2,
                    minChooseCount = 1,
                    allowRepeat = false,
                    modes = listOf(
                        ModalEnumerationMode(
                            index = 0,
                            description = "mode",
                            available = true,
                        )
                    ),
                    unavailableIndices = emptyList(),
                ),
            )
        ) shouldBe listOf("chosenModes", "modeTargetsOrdered")
    }

    test("structured candidate binding accepts choices but preserves action identity") {
        val environment = GameEnvironment.create(registry())
        val player = EntityId("player")
        val opponent = EntityId("opponent")
        val card = EntityId("card")
        val vehicle = EntityId("vehicle")
        val mount = EntityId("mount")
        val attacker = EntityId("attacker")

        environment.isCurrentActionCandidate(
            OrderBlockers(player, attacker, emptyList()),
            OrderBlockers(player, attacker, listOf(EntityId("blocker")))
        ) shouldBe true
        environment.isCurrentActionCandidate(
            OrderBlockers(player, attacker, emptyList()),
            OrderBlockers(player, EntityId("other-attacker"), emptyList())
        ) shouldBe false
        environment.isCurrentActionCandidate(
            CycleCard(player, card),
            CycleCard(player, card, PaymentStrategy.FromPool, xValue = 2)
        ) shouldBe true
        environment.isCurrentActionCandidate(
            CycleCard(player, card),
            CycleCard(opponent, card, PaymentStrategy.FromPool, xValue = 2)
        ) shouldBe false
        environment.isCurrentActionCandidate(
            CrewVehicle(player, vehicle, emptyList()),
            CrewVehicle(player, vehicle, listOf(EntityId("creature")))
        ) shouldBe true
        environment.isCurrentActionCandidate(
            CrewVehicle(player, vehicle, emptyList(), crewAbilityKey = "crew-1"),
            CrewVehicle(player, vehicle, listOf(EntityId("creature")), crewAbilityKey = "crew-1")
        ) shouldBe true
        environment.isCurrentActionCandidate(
            CrewVehicle(player, vehicle, emptyList(), crewAbilityKey = "crew-1"),
            CrewVehicle(player, vehicle, listOf(EntityId("creature")), crewAbilityKey = "crew-3")
        ) shouldBe false
        environment.isCurrentActionCandidate(
            SaddleMount(player, mount, emptyList()),
            SaddleMount(player, EntityId("other-mount"), listOf(EntityId("creature")))
        ) shouldBe false
        environment.isCurrentActionCandidate(
            TurnFaceUp(player, card),
            TurnFaceUp(player, card, PaymentStrategy.FromPool, xValue = 3)
        ) shouldBe true

        val normalEquip = ActivateAbility(
            playerId = player,
            sourceId = EntityId("equipment"),
            abilityId = AbilityId("equip"),
            alternativePayment = AlternativePaymentChoice(equipPayment = EquipPaymentChoice.NORMAL)
        )
        val freeEquip = normalEquip.copy(
            alternativePayment = AlternativePaymentChoice(equipPayment = EquipPaymentChoice.FREE_FIRST_EQUIP)
        )
        environment.isCurrentActionCandidate(
            normalEquip,
            normalEquip.copy(targets = listOf(com.wingedsheep.engine.state.components.stack.ChosenTarget.Permanent(EntityId("creature"))))
        ) shouldBe true
        environment.isCurrentActionCandidate(normalEquip, freeEquip) shouldBe false
        environment.isCurrentActionCandidate(freeEquip, normalEquip) shouldBe false
    }
})

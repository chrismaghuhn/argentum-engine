package com.wingedsheep.gym

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.CostUnitAllocation
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaSpendReference
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.core.SpendAllocation
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.AdditionalCostData
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ActionPayloadRequirements
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PaymentDomainV2
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.sth.StrongholdSet
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
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

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(StrongholdSet.cards)
        register(payableActionSource)
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
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV2")
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
        val paymentDomain = view.paymentDomain ?: error("expected PaymentDomainV2")
        paymentDomain.sourceActivations.single().sourceId shouldBe mountainId
        paymentDomain.sourceActivations.single().productionChoices
            .map { it.producedColor } shouldContain PaymentManaColor.RED
        paymentDomain.sourceActivations.single().manaAbilityKey shouldBe "intrinsic:R"
        Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }.encodeToJsonElement(PaymentDomainV2.serializer(), paymentDomain)
            .jsonObject.containsKey("autoPaySuggestion") shouldBe false
        Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }.encodeToString(TrainingObservation.serializer(), observation)
            .contains("\"paymentDomain\"") shouldBe true
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
        ) shouldBe setOf("crewCreatures")
        ActionPayloadRequirements.requiredPayloadFields(
            LegalAction(
                action = SaddleMount(player, mount, emptyList()),
                actionType = "SaddleMount",
                description = "saddle",
                tapForPower = true
            )
        ) shouldBe setOf("saddleCreatures")
        ActionPayloadRequirements.requiredPayloadFields(
            LegalAction(
                action = CycleCard(player, EntityId("cycling-card")),
                actionType = "CycleCard",
                description = "cycle",
                manaCostString = "{X}",
                hasXCost = true
            )
        ) shouldBe setOf("xValue", "paymentStrategy")
        ActionPayloadRequirements.requiredPayloadFields(
            LegalAction(
                action = TurnFaceUp(player, EntityId("face-down")),
                actionType = "TurnFaceUp",
                description = "turn face up",
                manaCostString = "{X}",
                hasXCost = true
            )
        ) shouldBe setOf("xValue", "paymentStrategy")

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
        ) shouldBe setOf("alternativePayment")
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

package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.TargetDomainSupport
import com.wingedsheep.engine.legalactions.TargetInfo
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidation
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.TargetPaymentDomainV1
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AlternativePaymentChoice
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetPlayer
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.targets.TargetSpell
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class GameGymEnvTargetPaymentDomainTest : FunSpec({

    val targetBoundActivator = card("Gym Target Bound Activator") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Mana("{1}")
            target = TargetCreature()
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            cost = Costs.Mana("{1}")
            target = TargetPlayer()
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetIsPlayer(),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            cost = Costs.Mana("{1}")
            target = TargetPlayer()
            genericCostReduction = DynamicAmount.Fixed(1)
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{1}"), Costs.Mana("{B}"))
            target = TargetCreature()
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            cost = Costs.Mana("{1}")
            target = TargetCreature(optional = true)
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            cost = Costs.Mana("{1}")
            target = TargetCreature(unlimited = true)
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            val first = target("first creature", TargetCreature())
            target("second creature", TargetCreature())
            cost = Costs.Mana("{1}")
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1, first)
        }
        activatedAbility {
            cost = Costs.Mana("{X}")
            target = TargetCreature()
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            cost = Costs.Mana("{1}")
            target = TargetSpell()
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            cost = Costs.Mana("{1}")
            target = TargetObject(filter = TargetFilter.CardInGraveyard)
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{1}"), Costs.TapAnotherPermanent())
            target = TargetCreature()
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
        activatedAbility {
            cost = Costs.Mana("{1}")
            target = TargetCreature(dynamicMaxCount = DynamicAmount.Fixed(1))
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
    }

    val strictTargetActivator = card("Gym Strict Target Payment Activator") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Mana("{1}")
            target = TargetCreature()
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.TargetMatchesFilter(GameObjectFilter.Artifact),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
            effect = Effects.GainLife(1)
        }
    }

    val artifactCreatureTarget = card("Gym Artifact Creature Target") {
        typeLine = "Artifact Creature — Construct"
        power = 1
        toughness = 1
    }

    val ordinaryCreatureTarget = card("Gym Ordinary Creature Target") {
        typeLine = "Creature — Bear"
        power = 2
        toughness = 2
    }

    val unrepresentableManaSource = card("Gym Unrepresentable Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(Costs.Tap, Costs.TapAnotherPermanent())
            effect = Effects.AddMana(com.wingedsheep.sdk.core.Color.GREEN)
            manaAbility = true
        }
    }

    data class Fixture(
        val environment: GameEnvironment,
        val registry: com.wingedsheep.engine.registry.CardRegistry,
        val activator: CardDefinition,
        val playerId: EntityId,
        val opponentId: EntityId,
        val sourceId: EntityId,
        val artifactTargetId: EntityId,
        val ordinaryTargetId: EntityId,
        val unrepresentableSourceId: EntityId?,
        val basicManaSourceId: EntityId?,
    )

    fun prepared(
        includeUnrepresentableManaSource: Boolean = false,
        activator: CardDefinition = targetBoundActivator,
        includeBasicManaSource: Boolean = false,
    ): Fixture {
        val registry = com.wingedsheep.engine.registry.CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(activator)
            register(artifactCreatureTarget)
            register(ordinaryCreatureTarget)
            register(unrepresentableManaSource)
        }
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            activator.name to 1,
                            artifactCreatureTarget.name to 1,
                            ordinaryCreatureTarget.name to 1,
                            *(if (includeUnrepresentableManaSource) {
                                arrayOf(unrepresentableManaSource.name to 1)
                            } else {
                                emptyArray()
                            }),
                            "Mountain" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 106502L,
            ),
        )

        val playerId = environment.playerIds.first()
        val opponentId = environment.playerIds[1]
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String): EntityId {
            val entityId = (state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY))
                .firstOrNull { id -> state.getEntity(id)?.get<CardComponent>()?.name == name }
                ?: error("Could not locate $name in the fixture")
            val sourceZone = state.zones.entries.first { (_, ids) -> entityId in ids }.key
            state = state.moveToZone(entityId, sourceZone, ZoneKey(playerId, Zone.BATTLEFIELD))
            return entityId
        }

        val sourceId = moveNamed(activator.name)
        val artifactTargetId = moveNamed(artifactCreatureTarget.name)
        val ordinaryTargetId = moveNamed(ordinaryCreatureTarget.name)
        val unrepresentableSourceId = if (includeUnrepresentableManaSource) {
            moveNamed(unrepresentableManaSource.name)
        } else {
            null
        }
        val basicManaSourceId = if (includeBasicManaSource) moveNamed("Mountain") else null
        environment.restore(state, environment.playerIds, environment.stepCount)

        return Fixture(
            environment = environment,
            registry = registry,
            activator = activator,
            playerId = playerId,
            opponentId = opponentId,
            sourceId = sourceId,
            artifactTargetId = artifactTargetId,
            ordinaryTargetId = ordinaryTargetId,
            unrepresentableSourceId = unrepresentableSourceId,
            basicManaSourceId = basicManaSourceId,
        )
    }

    fun targetAction(fixture: Fixture, abilityIndex: Int = 0): LegalAction {
        val sourceCard = fixture.registry.getCard(fixture.activator.name)
            ?: error("Target-bound activator is not registered")
        val ability = sourceCard.script.activatedAbilities[abilityIndex]
        val permanentTargetIds = listOf(fixture.ordinaryTargetId, fixture.artifactTargetId)
        val isPlayerTarget = abilityIndex == 1 || abilityIndex == 2
        val targetRequirements = ability.targetRequirements.mapIndexed { index, requirement ->
            val targetIds = if (isPlayerTarget) {
                listOf(fixture.opponentId)
            } else {
                permanentTargetIds
            }
            TargetInfo(
                index = index,
                description = requirement.description,
                minTargets = if (requirement.optional || requirement.unlimited) 0 else requirement.minCount,
                maxTargets = if (requirement.unlimited) targetIds.size else requirement.count,
                validTargets = targetIds,
            )
        }
        val isTargetIndependentReduction = abilityIndex == 2
        val manaCost = when (abilityIndex) {
            2 -> "{0}"
            3 -> "{1}{B}"
            7 -> "{X}"
            else -> "{1}"
        }
        return LegalAction(
            action = ActivateAbility(fixture.playerId, fixture.sourceId, ability.id),
            actionType = "ActivateAbility",
            description = ability.description,
            affordable = isTargetIndependentReduction || abilityIndex == 7,
            requiresTargets = true,
            targetCount = targetRequirements.sumOf { it.maxTargets },
            minTargets = targetRequirements.sumOf { it.minTargets },
            targetRequirements = targetRequirements,
            targetDomainSupport = TargetDomainSupport.SUPPORTED,
            manaCostString = manaCost,
            hasXCost = abilityIndex == 7,
        )
    }

    fun observe(fixture: Fixture, action: LegalAction) =
        ObservationBuilder(cardRegistry = fixture.registry).build(
            state = fixture.environment.state,
            perspectivePlayerId = fixture.playerId,
            legalActions = listOf(action),
        )

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun strictTargetPaymentView(fixture: Fixture): Pair<GameGymEnv, LegalActionView> {
        val gym = GameGymEnv(
            environment = fixture.environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = fixture.registry),
        )
        val observation = gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
        val view = observation.legalActions.singleOrNull { action ->
            action.sourceEntityId == fixture.sourceId && action.targetPaymentDomain != null
        } ?: error("Expected target-payment action in ${observation.legalActions}")
        return gym to view
    }

    fun targetPaymentPlan(view: LegalActionView, targetId: EntityId): PaymentPlanV3 {
        val binding = view.targetPaymentDomain?.targetBindings?.singleOrNull { it.target == targetId }
            ?: error("No target binding for $targetId in $view")
        return paymentPlanV3FromPublic(binding.paymentDomain)
            ?: error("Expected a complete V5 plan for target $targetId")
    }

    fun targetPaymentPayload(
        view: LegalActionView,
        targetId: EntityId,
        plan: PaymentPlanV3,
    ): JsonObject = buildJsonObject {
        view.actionSemantics?.forEach { (key, value) -> put(key, value) }
        put("targets", buildJsonArray {
            add(buildJsonObject {
                put("type", "Permanent")
                put("entityId", targetId.value)
            })
        })
        put(
            "paymentStrategy",
            actionJson.encodeToJsonElement(
                PaymentStrategy.serializer(),
                PaymentStrategy.ExplicitV3(paymentPlan = plan),
            ),
        )
    }

    test("publishes complete target-bound V5 domains in the mapped candidate order") {
        val fixture = prepared()
        val result = observe(fixture, targetAction(fixture))
        val observation = result.observation.shouldBeInstanceOf<TrainingObservation>()
        val view = observation.legalActions.single()
        val relation: TargetPaymentDomainV1 = view.targetPaymentDomain
            ?: error("expected target payment relation")

        relation.targetBindings.map { it.target } shouldBe
            view.targetDomain!!.requirements.single().candidates
        relation.targetBindings.first { it.target == fixture.artifactTargetId }
            .paymentDomain.requiredCost shouldBe "{0}"
        relation.targetBindings.first { it.target == fixture.ordinaryTargetId }
            .paymentDomain.requiredCost shouldBe "{1}"
        relation.targetBindings.first { it.target == fixture.artifactTargetId }.affordable shouldBe true
        relation.targetBindings.first { it.target == fixture.ordinaryTargetId }.affordable shouldBe false
        view.affordable shouldBe true
        view.manaCost shouldBe null
        view.paymentDomain shouldBe null
        result.diagnostics.none { it.code == DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED } shouldBe true
    }

    test("retains action-level V5 when every target-bound effective cost is equal") {
        val fixture = prepared()
        val result = observe(fixture, targetAction(fixture, abilityIndex = 2))
        val view = result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single()

        view.targetPaymentDomain shouldBe null
        view.paymentDomain shouldNotBe null
        view.manaCost shouldBe "{0}"
        view.affordable shouldBe true
        result.diagnostics.none { it.code == DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED } shouldBe true
    }

    test("fails closed for a target-dependent non-permanent target even when parent is unaffordable") {
        val fixture = prepared()
        val result = observe(fixture, targetAction(fixture, abilityIndex = 1))

        result.diagnostics.map { it.code } shouldContain DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        val view = result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single()
        view.targetPaymentDomain shouldBe null
        view.paymentDomain shouldBe null
        view.manaCost shouldBe null
        view.affordable shouldBe false
    }

    test("fails closed for target-dependent unsupported cardinality, X, and composite shapes") {
        val fixture = prepared()

        listOf(
            3 to "multiple mana components",
            4 to "optional target",
            5 to "unlimited target",
            6 to "multiple target requirements",
            7 to "X cost",
            8 to "spell target",
            9 to "card target",
            10 to "unresolved additional cost",
            11 to "dynamic target cardinality",
        ).forEach { (abilityIndex, shape) ->
            val result = observe(fixture, targetAction(fixture, abilityIndex))
            result.diagnostics.map { it.code } shouldContain DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
            val view = result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single()
            view.targetPaymentDomain shouldBe null
            view.paymentDomain shouldBe null
            if (shape != "X cost") view.manaCost shouldBe null
        }
    }

    test("rejects the whole relation when one public binding is not a battlefield permanent") {
        val fixture = prepared()
        val action = targetAction(fixture).let { legalAction ->
            legalAction.copy(
                targetRequirements = legalAction.targetRequirements.map { requirement ->
                    requirement.copy(validTargets = listOf(fixture.artifactTargetId, fixture.opponentId))
                },
            )
        }

        val result = observe(fixture, action)
        result.diagnostics.map { it.code } shouldContain DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single().targetPaymentDomain shouldBe null
    }

    test("fails closed for an unresolved alternative payment choice") {
        val fixture = prepared()
        val template = targetAction(fixture)
        val action = template.copy(
            action = (template.action as ActivateAbility).copy(
                alternativePayment = AlternativePaymentChoice(
                    harmonizeCreature = fixture.ordinaryTargetId,
                ),
            ),
        )

        val result = observe(fixture, action)
        result.diagnostics.map { it.code } shouldContain DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single().targetPaymentDomain shouldBe null
    }

    test("strict Gym accepts a plan from the selected target binding") {
        val fixture = prepared(
            activator = strictTargetActivator,
            includeBasicManaSource = true,
        )
        val (gym, view) = strictTargetPaymentView(fixture)
        val plan = targetPaymentPlan(view, fixture.artifactTargetId)
        val beforeStepCount = fixture.environment.stepCount

        gym.step(
            view.actionId,
            targetPaymentPayload(view, fixture.artifactTargetId, plan),
        )

        fixture.environment.stepCount shouldBe beforeStepCount + 1
    }

    test("strict Gym rejects an unaffordable target binding before mutation") {
        val fixture = prepared(activator = strictTargetActivator)
        val (gym, view) = strictTargetPaymentView(fixture)
        val relation = view.targetPaymentDomain ?: error("Expected target-payment relation")
        relation.targetBindings.single { it.target == fixture.ordinaryTargetId }.affordable shouldBe false
        val beforeState = fixture.environment.state
        val beforeEvents = fixture.environment.lastStepEvents
        val beforeStepCount = fixture.environment.stepCount

        shouldThrow<IllegalArgumentException> {
            gym.step(
                view.actionId,
                targetPaymentPayload(view, fixture.ordinaryTargetId, PaymentPlanV3()),
            )
        }

        fixture.environment.state shouldBe beforeState
        fixture.environment.lastStepEvents shouldBe beforeEvents
        fixture.environment.stepCount shouldBe beforeStepCount
    }

    test("strict Gym rejects native payment fallback for a target-bound action atomically") {
        val fixture = prepared(
            activator = strictTargetActivator,
            includeBasicManaSource = true,
        )
        val (gym, view) = strictTargetPaymentView(fixture)
        val beforeState = fixture.environment.state
        val beforeEvents = fixture.environment.lastStepEvents
        val beforeStepCount = fixture.environment.stepCount
        val payload = buildJsonObject {
            view.actionSemantics?.forEach { (key, value) -> put(key, value) }
            put("targets", buildJsonArray {
                add(buildJsonObject {
                    put("type", "Permanent")
                    put("entityId", fixture.artifactTargetId.value)
                })
            })
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(PaymentStrategy.serializer(), PaymentStrategy.AutoPay),
            )
        }

        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId, payload)
        }

        fixture.environment.state shouldBe beforeState
        fixture.environment.lastStepEvents shouldBe beforeEvents
        fixture.environment.stepCount shouldBe beforeStepCount
    }

    test("strict Gym rejects a payment plan from a different target binding atomically") {
        val fixture = prepared(
            activator = strictTargetActivator,
            includeBasicManaSource = true,
        )
        val (gym, view) = strictTargetPaymentView(fixture)
        val planForArtifact = targetPaymentPlan(view, fixture.artifactTargetId)
        val beforeState = fixture.environment.state
        val beforeEvents = fixture.environment.lastStepEvents
        val beforeStepCount = fixture.environment.stepCount

        shouldThrow<IllegalArgumentException> {
            gym.step(
                view.actionId,
                targetPaymentPayload(view, fixture.ordinaryTargetId, planForArtifact),
            )
        }

        fixture.environment.state shouldBe beforeState
        fixture.environment.lastStepEvents shouldBe beforeEvents
        fixture.environment.stepCount shouldBe beforeStepCount
    }

    test("strict Gym rejects missing, duplicate, and off-domain target submissions atomically") {
        val fixture = prepared(
            activator = strictTargetActivator,
            includeBasicManaSource = true,
        )
        val (gym, view) = strictTargetPaymentView(fixture)
        val plan = targetPaymentPlan(view, fixture.artifactTargetId)

        fun assertRejected(payload: JsonObject) {
            val beforeState = fixture.environment.state
            val beforeEvents = fixture.environment.lastStepEvents
            val beforeStepCount = fixture.environment.stepCount

            shouldThrow<IllegalArgumentException> {
                gym.step(view.actionId, payload)
            }

            fixture.environment.state shouldBe beforeState
            fixture.environment.lastStepEvents shouldBe beforeEvents
            fixture.environment.stepCount shouldBe beforeStepCount
        }

        assertRejected(buildJsonObject {
            view.actionSemantics?.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(paymentPlan = plan),
                ),
            )
        })
        assertRejected(targetPaymentPayload(view, EntityId("not-a-published-target"), plan))
        assertRejected(buildJsonObject {
            view.actionSemantics?.forEach { (key, value) -> put(key, value) }
            put("targets", buildJsonArray {
                listOf(fixture.artifactTargetId, fixture.ordinaryTargetId).forEach { targetId ->
                    add(buildJsonObject {
                        put("type", "Permanent")
                        put("entityId", targetId.value)
                    })
                }
            })
            put(
                "paymentStrategy",
                actionJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(paymentPlan = plan),
                ),
            )
        })
    }

    test("strict Gym rejects a stale target-payment snapshot before mutation") {
        val fixture = prepared(
            activator = strictTargetActivator,
            includeBasicManaSource = true,
        )
        val (gym, view) = strictTargetPaymentView(fixture)
        val plan = targetPaymentPlan(view, fixture.artifactTargetId)
        val staleState = fixture.environment.state.moveToZone(
            fixture.ordinaryTargetId,
            ZoneKey(fixture.playerId, Zone.BATTLEFIELD),
            ZoneKey(fixture.playerId, Zone.GRAVEYARD),
        )
        fixture.environment.restore(
            staleState,
            fixture.environment.playerIds,
            fixture.environment.stepCount,
        )
        val beforeState = fixture.environment.state
        val beforeEvents = fixture.environment.lastStepEvents
        val beforeStepCount = fixture.environment.stepCount

        shouldThrow<IllegalArgumentException> {
            gym.step(
                view.actionId,
                targetPaymentPayload(view, fixture.artifactTargetId, plan),
            )
        }

        fixture.environment.state shouldBe beforeState
        fixture.environment.lastStepEvents shouldBe beforeEvents
        fixture.environment.stepCount shouldBe beforeStepCount
    }

    test("strict Gym rejects a stale target-bound cost before mutation") {
        val fixture = prepared(
            activator = strictTargetActivator,
            includeBasicManaSource = true,
        )
        val (gym, view) = strictTargetPaymentView(fixture)
        val plan = targetPaymentPlan(view, fixture.artifactTargetId)
        val changedTargetState = fixture.environment.state.updateEntity(fixture.artifactTargetId) { container ->
            val card = container.get<CardComponent>() ?: error("Expected target card component")
            container.with(
                card.copy(
                    typeLine = card.typeLine.copy(
                        cardTypes = card.typeLine.cardTypes - CardType.ARTIFACT,
                    ),
                ),
            )
        }
        fixture.environment.restore(
            changedTargetState,
            fixture.environment.playerIds,
            fixture.environment.stepCount,
        )
        val beforeState = fixture.environment.state
        val beforeEvents = fixture.environment.lastStepEvents
        val beforeStepCount = fixture.environment.stepCount

        shouldThrow<IllegalArgumentException> {
            gym.step(
                view.actionId,
                targetPaymentPayload(view, fixture.artifactTargetId, plan),
            )
        }

        fixture.environment.state shouldBe beforeState
        fixture.environment.lastStepEvents shouldBe beforeEvents
        fixture.environment.stepCount shouldBe beforeStepCount
    }

    test("strict Gym rejects nested V5 drift while the target-payment relation remains present") {
        val fixture = prepared(
            activator = strictTargetActivator,
            includeBasicManaSource = true,
        )
        val (gym, view) = strictTargetPaymentView(fixture)
        val plan = targetPaymentPlan(view, fixture.artifactTargetId)
        val manaSourceId = fixture.basicManaSourceId ?: error("Expected a basic mana source")
        val driftedState = fixture.environment.state.updateEntity(manaSourceId) { container ->
            container.with(TappedComponent)
        }
        fixture.environment.restore(
            driftedState,
            fixture.environment.playerIds,
            fixture.environment.stepCount,
        )

        val freshObservation = ObservationBuilder(cardRegistry = fixture.registry).build(
            state = fixture.environment.state,
            perspectivePlayerId = fixture.playerId,
            legalActions = fixture.environment.legalActions(),
        ).observation.shouldBeInstanceOf<TrainingObservation>()
        val freshView = freshObservation.legalActions.single {
            it.sourceEntityId == fixture.sourceId
        }
        freshView.targetPaymentDomain shouldNotBe null
        freshView.targetPaymentDomain shouldNotBe view.targetPaymentDomain

        val beforeState = fixture.environment.state
        val beforeEvents = fixture.environment.lastStepEvents
        val beforeStepCount = fixture.environment.stepCount
        shouldThrow<IllegalArgumentException> {
            gym.step(
                view.actionId,
                targetPaymentPayload(view, fixture.artifactTargetId, plan),
            )
        }

        fixture.environment.state shouldBe beforeState
        fixture.environment.lastStepEvents shouldBe beforeEvents
        fixture.environment.stepCount shouldBe beforeStepCount
    }

    test("does not mark a binding affordable through an unrepresentable legacy source") {
        val fixture = prepared(includeUnrepresentableManaSource = true)
        ManaSolver(fixture.registry).canPay(
            state = fixture.environment.state,
            playerId = fixture.playerId,
            cost = ManaCost.parse("{1}"),
        ) shouldBe true

        val result = observe(fixture, targetAction(fixture))
        result.diagnostics.map { it.code } shouldContain DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        val view = result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single()
        view.targetPaymentDomain shouldBe null
        view.paymentDomain shouldBe null
        view.affordable shouldBe false
    }

    test("target-bound preflight re-resolves the cost and delegates to the V3 validator") {
        val fixture = prepared()
        val template = targetAction(fixture)
        val submitted = (template.action as ActivateAbility).copy(
            targets = listOf(ChosenTarget.Permanent(fixture.artifactTargetId)),
        )

        val validation = ObservationBuilder(cardRegistry = fixture.registry).validateTargetPaymentPlanV3(
            state = fixture.environment.state,
            template = template,
            submitted = submitted,
            plan = PaymentPlanV3(),
        )

        validation.shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()

        val unaffordableBindingValidation = ObservationBuilder(cardRegistry = fixture.registry)
            .validateTargetPaymentPlanV3(
                state = fixture.environment.state,
                template = template,
                submitted = submitted.copy(
                    targets = listOf(ChosenTarget.Permanent(fixture.ordinaryTargetId)),
                ),
                plan = PaymentPlanV3(),
            )
        unaffordableBindingValidation.shouldBeInstanceOf<PaymentPlanValidation.Rejected>()
    }
})

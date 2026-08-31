package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.c19.cards.SevinnesReclamation
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.CostModification
import com.wingedsheep.sdk.scripting.CostReductionSource
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.ModifySpellCost
import com.wingedsheep.sdk.scripting.SpellCostTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class GameGymEnvTargetedAlternativeCastPaymentTest : FunSpec({
    val targetDependentFlashback = card("Gym Target Dependent Flashback") {
        manaCost = "{3}{U}"
        colorIdentity = "U"
        typeLine = "Instant"
        staticAbility {
            ability = ModifySpellCost(
                target = SpellCostTarget.SelfCast,
                modification = CostModification.ReduceGenericBy(
                    CostReductionSource.FixedIfAnyTargetMatches(
                        amount = 1,
                        filter = GameObjectFilter.Creature.withKeyword(Keyword.FLYING),
                    ),
                ),
            )
        }
        spell {
            target = Targets.Creature
            effect = Effects.GainLife(1)
        }
        keywordAbility(KeywordAbility.flashback("{2}{U}"))
    }

    val flyingCreature = card("Gym Flashback Flying Creature") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Creature — Bird"
        power = 1
        toughness = 1
        keywords(Keyword.FLYING)
    }

    data class Fixture(
        val environment: GameEnvironment,
        val registry: CardRegistry,
        val playerId: EntityId,
        val spellId: EntityId,
        val supportId: EntityId,
    )

    fun prepared(
        cardDefinition: CardDefinition,
        supportCard: CardDefinition,
        supportZone: Zone,
    ): Fixture {
        val registry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(cardDefinition)
            register(supportCard)
            if (cardDefinition == SevinnesReclamation) register(BasiliskCollar)
        }
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        com.wingedsheep.sdk.model.Deck.of(
                            cardDefinition.name to 1,
                            supportCard.name to 1,
                            "Mountain" to 8,
                            "Plains" to 8,
                            "Island" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", com.wingedsheep.sdk.model.Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 61103L,
            ),
        )
        val playerId = environment.playerIds.first()
        var state = environment.state
        while (state.step != com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is com.wingedsheep.engine.core.PassPriority }
            environment.step(pass.action)
            state = environment.state
        }
        val hand = ZoneKey(playerId, Zone.HAND)
        val library = ZoneKey(playerId, Zone.LIBRARY)
        state.getZone(hand).forEach { id -> state = state.moveToZone(id, hand, library) }

        fun moveNamed(name: String, zone: Zone): EntityId {
            val id = state.getZone(library).firstOrNull { candidate ->
                state.getEntity(candidate)?.get<CardComponent>()?.name == name
            } ?: error("Could not find $name in prepared library")
            state = state.moveToZone(id, library, ZoneKey(playerId, zone))
            return id
        }

        val spellId = moveNamed(cardDefinition.name, Zone.GRAVEYARD)
        val supportId = moveNamed(supportCard.name, supportZone)
        repeat(5) { moveNamed("Mountain", Zone.BATTLEFIELD) }
        repeat(5) { moveNamed("Plains", Zone.BATTLEFIELD) }
        repeat(5) { moveNamed("Island", Zone.BATTLEFIELD) }
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Fixture(environment, registry, playerId, spellId, supportId)
    }

    fun flashbackAction(fixture: Fixture) = fixture.environment.legalActions().single {
        it.actionType == "CastWithFlashback" && (it.action as? CastSpell)?.cardId == fixture.spellId
    }

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun targetPayload(
        view: com.wingedsheep.gym.contract.LegalActionView,
        targetId: EntityId,
        plan: com.wingedsheep.engine.core.PaymentPlanV3,
        ownerId: EntityId,
    ) = buildJsonObject {
        view.actionSemantics?.forEach { (key, value) -> put(key, value) }
        put("targets", buildJsonArray {
            add(buildJsonObject {
                put("type", "Card")
                put("cardId", targetId.value)
                put("ownerId", ownerId.value)
                put("zone", "Graveyard")
            })
        })
        put(
            "paymentStrategy",
            actionJson.encodeToJsonElement(
                PaymentStrategy.serializer(),
                PaymentStrategy.ExplicitV3(plan),
            ),
        )
    }

    test("target-independent fixed Flashback publishes V5 and executes through strict Gym") {
        val fixture = prepared(SevinnesReclamation, BasiliskCollar, Zone.GRAVEYARD)
        val action = flashbackAction(fixture)
        val builder = ObservationBuilder(cardRegistry = fixture.registry)
        val result = builder.build(
            state = fixture.environment.state,
            perspectivePlayerId = fixture.playerId,
            legalActions = listOf(action),
        )
        val view = result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single()

        action.manaCostString shouldBe "{4}{W}"
        action.affordable shouldBe true
        result.diagnostics shouldBe emptyList()
        view.targetDomain shouldNotBe null
        view.paymentDomain shouldNotBe null
        view.paymentDomain!!.requiredCost shouldBe "{4}{W}"
        val gym = GameGymEnv(
            environment = fixture.environment,
            perspectivePlayerIndex = 0,
            observationBuilder = builder,
        )
        val gymView = gym.observe().observation
            .shouldBeInstanceOf<TrainingObservation>()
            .legalActions
            .single { it.kind == "CastWithFlashback" && it.sourceEntityId == fixture.spellId }
        val gymPlan = paymentPlanV3FromPublic(gymView.paymentDomain!!)
            ?: error("Expected a complete registered targeted Flashback PaymentPlanV3")
        val targetId = gymView.targetDomain!!.requirements.single().candidates.first()
        val beforeStepCount = fixture.environment.stepCount
        gym.step(gymView.actionId, targetPayload(gymView, targetId, gymPlan, fixture.playerId))
        fixture.environment.stepCount shouldBe beforeStepCount + 1
    }

    test("target-dependent fixed Flashback remains unsupported") {
        val fixture = prepared(targetDependentFlashback, flyingCreature, Zone.BATTLEFIELD)
        val action = flashbackAction(fixture)
        val builder = ObservationBuilder(cardRegistry = fixture.registry)
        val result = builder.build(
            state = fixture.environment.state,
            perspectivePlayerId = fixture.playerId,
            legalActions = listOf(action),
        )
        val view = result.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single()

        action.manaCostString shouldBe "{2}{U}"
        result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        view.paymentDomain shouldBe null

        val ordinaryControlAction = action.copy(
            actionType = "CastSpell",
            manaCostString = "{3}{U}",
            validTargets = emptyList(),
            requiresTargets = false,
            targetCount = 0,
            minTargets = 0,
            targetDescription = null,
            targetRequirements = emptyList(),
            action = (action.action as CastSpell).copy(
                targets = emptyList(),
                useAlternativeCost = false,
                alternativeCostType = null,
            ),
        )
        val ordinaryDomain = builder.paymentDomainV5For(fixture.environment.state, ordinaryControlAction)
            ?: error("Expected an ordinary V5 control domain")
        val ordinaryPlan = paymentPlanV3FromPublic(ordinaryDomain)
            ?: error("Expected an ordinary V5 control plan")
        val submitted = (action.action as CastSpell).copy(
            targets = listOf(ChosenTarget.Permanent(fixture.supportId)),
            paymentStrategy = PaymentStrategy.ExplicitV3(ordinaryPlan),
        )
        val beforeState = fixture.environment.state
        val beforeStepCount = fixture.environment.stepCount
        val beforeEvents = fixture.environment.lastStepEvents
        val failure = shouldThrow<IllegalArgumentException> {
            fixture.environment.stepStrict(submitted)
        }
        failure.message shouldBe "PAYMENT_DOMAIN_UNSUPPORTED: alternative mana costs are not representable"
        fixture.environment.state shouldBe beforeState
        fixture.environment.stepCount shouldBe beforeStepCount
        fixture.environment.lastStepEvents shouldBe beforeEvents
    }
})

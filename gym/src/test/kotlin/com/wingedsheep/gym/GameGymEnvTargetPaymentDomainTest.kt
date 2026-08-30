package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.TargetDomainSupport
import com.wingedsheep.engine.legalactions.TargetInfo
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TargetPaymentDomainV1
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.targets.TargetPlayer
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

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
            genericCostReduction = DynamicAmount.Fixed(1)
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

    data class Fixture(
        val environment: GameEnvironment,
        val registry: com.wingedsheep.engine.registry.CardRegistry,
        val playerId: EntityId,
        val opponentId: EntityId,
        val sourceId: EntityId,
        val artifactTargetId: EntityId,
        val ordinaryTargetId: EntityId,
    )

    fun prepared(): Fixture {
        val registry = com.wingedsheep.engine.registry.CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(targetBoundActivator)
            register(artifactCreatureTarget)
            register(ordinaryCreatureTarget)
        }
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            targetBoundActivator.name to 1,
                            artifactCreatureTarget.name to 1,
                            ordinaryCreatureTarget.name to 1,
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

        val sourceId = moveNamed(targetBoundActivator.name)
        val artifactTargetId = moveNamed(artifactCreatureTarget.name)
        val ordinaryTargetId = moveNamed(ordinaryCreatureTarget.name)
        environment.restore(state, environment.playerIds, environment.stepCount)

        return Fixture(
            environment = environment,
            registry = registry,
            playerId = playerId,
            opponentId = opponentId,
            sourceId = sourceId,
            artifactTargetId = artifactTargetId,
            ordinaryTargetId = ordinaryTargetId,
        )
    }

    fun targetAction(fixture: Fixture, abilityIndex: Int = 0): LegalAction {
        val sourceCard = fixture.registry.getCard(targetBoundActivator.name)
            ?: error("Target-bound activator is not registered")
        val ability = sourceCard.script.activatedAbilities[abilityIndex]
        val targetIds = if (abilityIndex == 0) {
            listOf(fixture.ordinaryTargetId, fixture.artifactTargetId)
        } else {
            listOf(fixture.opponentId)
        }
        val targetRequirement = ability.targetRequirements.single()
        return LegalAction(
            action = ActivateAbility(fixture.playerId, fixture.sourceId, ability.id),
            actionType = "ActivateAbility",
            description = ability.description,
            affordable = false,
            requiresTargets = true,
            targetCount = 1,
            minTargets = 1,
            targetRequirements = listOf(
                TargetInfo(
                    index = 0,
                    description = targetRequirement.description,
                    minTargets = 1,
                    maxTargets = 1,
                    validTargets = targetIds,
                ),
            ),
            targetDomainSupport = TargetDomainSupport.SUPPORTED,
            manaCostString = "{1}",
        )
    }

    fun observe(fixture: Fixture, action: LegalAction) =
        ObservationBuilder(cardRegistry = fixture.registry).build(
            state = fixture.environment.state,
            perspectivePlayerId = fixture.playerId,
            legalActions = listOf(action),
        )

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
})

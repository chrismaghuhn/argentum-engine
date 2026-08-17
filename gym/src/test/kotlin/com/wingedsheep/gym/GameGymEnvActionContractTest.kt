package com.wingedsheep.gym

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ActionPayloadRequirements
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.sth.StrongholdSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Regression coverage for action IDs whose LegalAction is a target/payment template rather than
 * an executable GameAction. The external controller must supply the missing choice explicitly;
 * Gym must not invent it.
 */
class GameGymEnvActionContractTest : FunSpec({

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(StrongholdSet.cards)
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
            SaddleMount(player, mount, emptyList()),
            SaddleMount(player, EntityId("other-mount"), listOf(EntityId("creature")))
        ) shouldBe false
        environment.isCurrentActionCandidate(
            TurnFaceUp(player, card),
            TurnFaceUp(player, card, PaymentStrategy.FromPool, xValue = 3)
        ) shouldBe true
    }
})

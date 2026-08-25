package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GameGymEnvAttackDeclarationSubmissionTest : FunSpec({
    test("out-of-domain attack choices are rejected atomically before Rules execution") {
        val registry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 99, "Raging Goblin" to 1)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 99)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
            )
        )
        val alice = environment.playerIds[0]
        val bob = environment.playerIds[1]
        moveCardsIntoHand(environment, alice, listOf("Mountain", "Raging Goblin"))

        val land = findEnvironmentAction(environment) { it.action is PlayLand }
        environment.step(land.action)
        val goblin = findEnvironmentAction(environment) {
            val action = it.action as? CastSpell
            action?.cardId != null && cardName(environment, action.cardId) == "Raging Goblin"
        }
        environment.step(goblin.action)
        advanceToAttackers(environment, alice)

        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )
        val observation = gym.observe().observation as TrainingObservation
        val attack = observation.legalActions.first { it.kind == "DeclareAttackers" }
        val domain = attack.attackDeclarationDomain
        domain shouldNotBe null
        val attacker = domain!!.attackerToDefenders.keys.single()
        val payload = buildJsonObject {
            attack.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put(
                "attackers",
                buildJsonObject { put(attacker.value, "unpublished-defender") },
            )
            put("bands", buildJsonArray {})
        }

        val stateBefore = environment.state
        val stepCountBefore = environment.stepCount
        val lastStepEventsBefore = environment.lastStepEvents

        val failure = shouldThrow<IllegalArgumentException> {
            gym.step(attack.actionId, payload)
        }

        failure.message shouldBe
            "Attack declaration is outside the registered domain: INVALID_DEFENDER"
        environment.state shouldBe stateBefore
        environment.stepCount shouldBe stepCountBefore
        environment.lastStepEvents shouldBe lastStepEventsBefore
        environment.state.step shouldBe Step.DECLARE_ATTACKERS
        environment.state.priorityPlayerId shouldBe alice
        environment.playerIds shouldBe listOf(alice, bob)
    }
})

private fun cardName(environment: GameEnvironment, entityId: EntityId): String? =
    environment.state.getEntity(entityId)?.get<CardComponent>()?.name

private fun moveCardsIntoHand(
    environment: GameEnvironment,
    playerId: EntityId,
    names: List<String>,
) {
    var state = environment.state
    names.groupingBy { it }.eachCount().forEach { (name, requiredCount) ->
        val inHand = state.getHand(playerId).count { cardName(environment, it) == name }
        repeat(requiredCount - inHand) {
            val cardId = state.getZone(playerId, Zone.LIBRARY).firstOrNull { id ->
                cardName(environment, id) == name
            } ?: error("Expected $requiredCount copies of $name for $playerId")
            state = state.moveToZone(
                cardId,
                ZoneKey(playerId, Zone.LIBRARY),
                ZoneKey(playerId, Zone.HAND),
            )
        }
    }
    environment.restore(state, environment.playerIds, environment.stepCount)
}

private fun advanceToAttackers(environment: GameEnvironment, playerId: EntityId) {
    repeat(200) {
        if (environment.agentToAct == playerId && environment.state.step == Step.DECLARE_ATTACKERS) {
            return
        }
        val action = environment.legalActions().firstOrNull { it.action is PassPriority }
            ?: environment.legalActions().firstOrNull {
                val attack = it.action as? DeclareAttackers
                attack?.attackers?.isEmpty() == true
            }
            ?: error(
                "Could not advance to attackers: step=${environment.state.step}, " +
                    "priority=${environment.state.priorityPlayerId}, actions=${environment.legalActions()}"
            )
        environment.step(action.action)
    }
    error("Could not reach DeclareAttackers for $playerId")
}

private fun findEnvironmentAction(
    environment: GameEnvironment,
    predicate: (LegalAction) -> Boolean,
): LegalAction {
    repeat(200) {
        environment.legalActions().firstOrNull(predicate)?.let { return it }
        val action = environment.legalActions().firstOrNull { it.action is PassPriority }
            ?: environment.legalActions().firstOrNull {
                val attack = it.action as? DeclareAttackers
                attack?.attackers?.isEmpty() == true
            }
            ?: error(
                "Could not find action: step=${environment.state.step}, " +
                    "priority=${environment.state.priorityPlayerId}, actions=${environment.legalActions()}"
            )
        environment.step(action.action)
    }
    error("Could not find requested environment action")
}

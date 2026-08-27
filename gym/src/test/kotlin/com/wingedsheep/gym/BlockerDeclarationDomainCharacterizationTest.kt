package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/**
 * Issue #102 regression characterization: a real DeclareBlockers action requires a blockers
 * payload, and the strict public observation now publishes the complete blocker-declaration domain.
 */
class BlockerDeclarationDomainCharacterizationTest : FunSpec({

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun advanceThroughPriority(environment: GameEnvironment) {
        val action = environment.legalActions().firstOrNull { it.action is PassPriority }?.action
            ?: environment.legalActions().firstOrNull {
                (it.action as? DeclareAttackers)?.attackers?.isEmpty() == true
            }?.action
            ?: environment.legalActions().firstOrNull {
                (it.action as? DeclareBlockers)?.blockers?.isEmpty() == true
            }?.action
            ?: error(
                "No setup action at ${environment.state.phase}/${environment.state.step}; " +
                    "priority=${environment.state.priorityPlayerId}"
            )
        environment.step(action)
    }

    fun advanceToMain(environment: GameEnvironment, playerId: com.wingedsheep.sdk.model.EntityId, afterTurn: Int) {
        repeat(160) {
            if (environment.state.activePlayerId == playerId &&
                environment.state.priorityPlayerId == playerId &&
                environment.state.step == Step.PRECOMBAT_MAIN &&
                environment.turnNumber > afterTurn
            ) return
            advanceThroughPriority(environment)
        }
        error("Could not reach ${playerId.value}'s precombat main phase")
    }

    fun advanceToStep(environment: GameEnvironment, playerId: com.wingedsheep.sdk.model.EntityId, step: Step) {
        repeat(100) {
            if (environment.state.priorityPlayerId == playerId && environment.state.step == step) return
            advanceThroughPriority(environment)
        }
        error("Could not reach $step for ${playerId.value}")
    }

    test("required blockers payload has a complete public domain on the strict path") {
        val environment = GameEnvironment.create(registry())
        environment.reset(
            GameConfig(
                format = Format.Commander(commanderDamageThreshold = 1),
                startingHandSize = 3,
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin"),
                    PlayerConfig("Bob", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin"),
                ),
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 7L,
            ),
        )

        val alice = environment.playerIds[0]
        val bob = environment.playerIds[1]
        val aliceCommander = environment.state.getZone(alice, Zone.COMMAND).single()
        val bobCommander = environment.state.getZone(bob, Zone.COMMAND).single()

        advanceToMain(environment, alice, afterTurn = 0)
        val aliceLand = environment.legalActions().first { it.action is PlayLand }
        environment.step(aliceLand.action)
        val aliceCast = environment.legalActions().first {
            (it.action as? CastSpell)?.cardId == aliceCommander && it.affordable
        }
        environment.step(aliceCast.action)

        advanceToMain(environment, bob, afterTurn = environment.turnNumber)
        val bobLand = environment.legalActions().first { it.action is PlayLand }
        environment.step(bobLand.action)
        val bobCast = environment.legalActions().first {
            (it.action as? CastSpell)?.cardId == bobCommander && it.affordable
        }
        environment.step(bobCast.action)

        val firstAliceTurn = environment.turnNumber
        advanceToMain(environment, alice, afterTurn = firstAliceTurn)
        advanceToStep(environment, alice, Step.DECLARE_ATTACKERS)
        val attackTemplate = environment.legalActions().first {
            it.action is DeclareAttackers
        }.action as DeclareAttackers
        environment.step(attackTemplate.copy(attackers = mapOf(aliceCommander to bob)))

        advanceToStep(environment, bob, Step.DECLARE_BLOCKERS)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 1,
            observationBuilder = ObservationBuilder(cardRegistry = registry()),
        )
        val observation = gym.observe().observation as TrainingObservation
        val blockerAction = observation.legalActions.single { it.kind == "DeclareBlockers" }
        blockerAction.requiredPayloadFields shouldContain "blockers"

        val json = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }.encodeToJsonElement(TrainingObservation.serializer(), observation).jsonObject
        val encodedAction = json["legalActions"]!!.jsonArray.single {
            it.jsonObject["actionId"]!!.jsonPrimitive.int == blockerAction.actionId
        }.jsonObject

        encodedAction.containsKey("blockerDeclarationDomain") shouldBe true
        val domain = checkNotNull(blockerAction.blockerDeclarationDomain)
        domain.attackerOrder shouldContain aliceCommander
        domain.blockerOrder.isNotEmpty() shouldBe true
        domain.blockerToAttackers.values.flatten() shouldContain aliceCommander
    }
})

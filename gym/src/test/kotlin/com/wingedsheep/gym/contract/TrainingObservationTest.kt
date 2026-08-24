package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TrainingObservationTest : FunSpec({

    fun createRegistry(): CardRegistry {
        val registry = CardRegistry()
        registry.register(PortalSet.cards)
        registry.register(PortalSet.basicLands)
        return registry
    }

    fun simpleDeck() = Deck.of("Mountain" to 17, "Raging Goblin" to 3)

    fun newEnv(): GameEnvironment {
        val env = GameEnvironment.create(createRegistry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", simpleDeck()),
                    PlayerConfig("Bob", simpleDeck())
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        return env
    }

    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    test("observation includes all basic state fields and round-trips through JSON") {
        val env = newEnv()
        val perspective = env.playerIds[0]
        val result = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, perspective, env.legalActions())

        val obs = result.observation as TrainingObservation
        obs.perspectivePlayerId shouldBe perspective
        obs.players.size shouldBe 2
        obs.agentToAct.shouldNotBeNull()
        obs.terminated.shouldBeFalse()
        obs.schemaHash shouldBe SchemaHash.CURRENT
        obs.stateDigest shouldMatch Regex("[0-9a-f]{64}")
        obs.legalActions.shouldNotBeEmpty()
        obs.legalActions.forEach { action ->
            val targetDomain = action.targetDomain.shouldNotBeNull()
            targetDomain.version shouldBe ACTION_TARGET_DOMAIN_VERSION
            targetDomain.composition shouldBe ActionTargetComposition.FIXED
        }

        // Hand + library + graveyard + exile + battlefield + command for each player.
        obs.zones.size shouldBe 2 * 6

        val encoded = json.encodeToString(TrainingObservation.serializer(), obs)
        encoded shouldContain "\"targetDomain\""
        encoded shouldContain "\"requiredPayloadFields\""
        val decoded = json.decodeFromString(TrainingObservation.serializer(), encoded)
        decoded shouldBe obs
    }

    test("an actionId from the observation resolves to a steppable action") {
        val env = newEnv()
        val perspective = env.playerIds[0]
        val result = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, perspective, env.legalActions())

        val passView = result.observation.legalActions.first { it.description.contains("Pass", ignoreCase = true) || it.kind.contains("Pass", ignoreCase = true) }
        val resolved = result.registry.resolve(passView.actionId)
        resolved.shouldNotBe(ResolvedAction.Unknown)

        val legal = resolved as ResolvedAction.Legal
        (legal.action is PassPriority).shouldBeTrue()

        val stepResult = env.step(legal.action)
        stepResult.state.shouldNotBeNull()
        env.stepCount shouldBeGreaterThan 0
    }

    test("opponent hand and every library stay masked") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]

        val masked = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions())
            .observation as TrainingObservation
        val myHand = masked.zones.single { it.ownerId == me && it.zoneType == Zone.HAND }
        val theirHand = masked.zones.single { it.ownerId == opponent && it.zoneType == Zone.HAND }
        myHand.hidden.shouldBeFalse()
        myHand.cards.size shouldBe myHand.size
        theirHand.hidden.shouldBeTrue()
        theirHand.cards.size shouldBe 0
        theirHand.size shouldBeGreaterThan 0

        // Every library is hidden regardless of perspective.
        masked.zones.filter { it.zoneType == Zone.LIBRARY }.forEach { it.hidden.shouldBeTrue() }

    }

    test("oracle text is serialized for cards in visible zones") {
        // Raging Goblin's printed text is "Haste" — that's what the agent
        // needs to see to know the creature can attack the turn it enters.
        val env = newEnv()
        val me = env.playerIds[0]

        val obs = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions()).observation as TrainingObservation
        val myHand = obs.zones.single { it.ownerId == me && it.zoneType == Zone.HAND }

        // At least one card should be in the opening hand; every card there
        // is fully visible to the perspective player including its oracle text.
        myHand.cards.shouldNotBeEmpty()
        val rages = myHand.cards.filter { it.name == "Raging Goblin" }
        if (rages.isNotEmpty()) {
            rages.first().oracleText shouldContain "Haste"
        }
        // Round-trip through JSON — oracle text must survive.
        val encoded = json.encodeToString(TrainingObservation.serializer(), obs)
        val decoded = json.decodeFromString(TrainingObservation.serializer(), encoded)
        decoded.zones.flatMap { it.cards }.forEach { c ->
            // Every serialized card either has empty oracle text or preserves it.
            c.oracleText shouldBe (obs.zones.flatMap { it.cards }.first { it.entityId == c.entityId }.oracleText)
        }
    }

    test("stateDigest is stable for equivalent observations and changes when state changes") {
        val env = newEnv()
        val me = env.playerIds[0]

        val a = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions()).observation
        val b = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions()).observation
        a.stateDigest shouldBe b.stateDigest

        // Advance a step and verify the digest changes.
        val pass = env.legalActions().first { it.action is PassPriority }
        env.step(pass.action)
        val c = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions()).observation
        c.stateDigest shouldNotBe a.stateDigest
    }

    test("structured delayed-trigger candidates survive observation, digest, and wire round-trip") {
        val env = newEnv()
        val chooser = env.playerIds[0]
        val firstTriggeringPlayer = env.playerIds[0]
        val secondTriggeringPlayer = env.playerIds[1]
        val decision = ChooseOptionDecision(
            id = "delayed-occurrence",
            playerId = chooser,
            prompt = "Choose a simultaneous occurrence",
            context = DecisionContext(phase = DecisionPhase.TRIGGER),
            options = listOf(
                "Trigger for player ${firstTriggeringPlayer.value}",
                "Trigger for player ${secondTriggeringPlayer.value}"
            ),
            optionMetadata = listOf(
                OptionMetadata(
                    id = firstTriggeringPlayer.value,
                    description = "Trigger for player ${firstTriggeringPlayer.value}",
                    triggeringPlayerId = firstTriggeringPlayer
                ),
                OptionMetadata(
                    id = secondTriggeringPlayer.value,
                    description = "Trigger for player ${secondTriggeringPlayer.value}",
                    triggeringPlayerId = secondTriggeringPlayer
                )
            )
        )
        val obs = ObservationBuilder(cardRegistry = createRegistry())
            .build(env.state.copy(pendingDecision = decision), chooser, emptyList())
            .observation as TrainingObservation

        val firstAction = obs.legalActions.first()
        firstAction.description shouldBe "Trigger for player ${firstTriggeringPlayer.value}"
        firstAction.actionSemantics!!["optionMetadata"]!!.jsonObject["triggeringPlayerId"]!!
            .jsonPrimitive.content shouldBe firstTriggeringPlayer.value

        val encoded = json.encodeToString(TrainingObservation.serializer(), obs)
        json.decodeFromString(TrainingObservation.serializer(), encoded) shouldBe obs

        val changed = obs.copy(
            legalActions = obs.legalActions.mapIndexed { index, action ->
                if (index == 0) {
                    val semantics = action.actionSemantics!!
                    val metadata = semantics["optionMetadata"]!!.jsonObject
                    action.copy(
                        actionSemantics = JsonObject(
                            semantics + (
                                "optionMetadata" to JsonObject(
                                    metadata + ("triggeringPlayerId" to JsonPrimitive(secondTriggeringPlayer.value))
                                )
                            )
                        )
                    )
                } else action
            }
        )
        StateDigest.compute(changed) shouldNotBe StateDigest.compute(obs)
    }
})

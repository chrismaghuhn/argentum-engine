package com.wingedsheep.gym.service

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.Observation
import com.wingedsheep.gym.contract.SchemaHash
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private val Observation.argentMlGame: TrainingObservation
    get() = this as TrainingObservation

class ArgentMlGymContractTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun deck(): DeckSpec = DeckSpec.Explicit(
        mapOf(
            "Mountain" to 17,
            "Raging Goblin" to 3,
        )
    )

    fun config(
        perspectiveMode: PerspectiveMode,
        seed: Long? = 7L,
        format: Format = Format.Standard,
        commanderCardName: String? = null,
        playerDeck: DeckSpec = deck(),
    ): EnvConfig = EnvConfig(
        players = listOf(
            PlayerSpec("Alice", playerDeck, commanderCardName = commanderCardName),
            PlayerSpec("Bob", playerDeck, commanderCardName = commanderCardName),
        ),
        skipMulligans = true,
        startingPlayerIndex = 0,
        perspectiveMode = perspectiveMode,
        format = format,
        seed = seed,
    )

    fun passActionId(observation: TrainingObservation): Int =
        observation.legalActions.first {
            it.kind.contains("Pass", ignoreCase = true) ||
                it.description.contains("Pass", ignoreCase = true)
        }.actionId

    test("agent-to-act perspective follows priority and remasks both hands") {
        val service = MultiEnvService(registry())
        val created = service.create(config(PerspectiveMode.AGENT_TO_ACT))
        val opening = created.observation.observation.argentMlGame

        opening.schemaHash shouldBe SchemaHash.CURRENT
        SchemaHash.CURRENT shouldBe "argentum-gym-contract@v2-commander-selfplay"
        opening.perspectivePlayerId shouldBe opening.agentToAct
        opening.legalActions.shouldNotBeEmpty()

        val previousPerspective = opening.perspectivePlayerId
        val afterPass = service.step(
            StepRequest(created.envId, passActionId(opening))
        ).observation.argentMlGame

        afterPass.agentToAct.shouldNotBeNull()
        afterPass.perspectivePlayerId shouldBe afterPass.agentToAct
        afterPass.perspectivePlayerId shouldNotBe previousPerspective
        afterPass.legalActions.shouldNotBeEmpty()

        val previousHand = afterPass.zones.first {
            it.ownerId == previousPerspective && it.zoneType == Zone.HAND
        }
        previousHand.hidden.shouldBeTrue()
        previousHand.cards.shouldBeEmpty()

        val currentHand = afterPass.zones.first {
            it.ownerId == afterPass.perspectivePlayerId && it.zoneType == Zone.HAND
        }
        currentHand.hidden.shouldBeFalse()
        currentHand.cards.shouldNotBeEmpty()
    }

    test("fixed-seat mode remains available and reset can opt into self-play") {
        val service = MultiEnvService(registry())
        val fixedConfig = config(PerspectiveMode.FIXED_SEAT)
        val created = service.create(fixedConfig)
        val opening = created.observation.observation.argentMlGame

        val fixedPerspective = opening.perspectivePlayerId
        val afterPass = service.step(
            StepRequest(created.envId, passActionId(opening))
        ).observation.argentMlGame

        afterPass.perspectivePlayerId shouldBe fixedPerspective
        afterPass.agentToAct shouldNotBe fixedPerspective
        afterPass.legalActions.shouldBeEmpty()

        val reset = service.reset(
            created.envId,
            config(PerspectiveMode.AGENT_TO_ACT, seed = 8L),
        ).observation.argentMlGame

        reset.perspectivePlayerId shouldBe reset.agentToAct
        reset.legalActions.shouldNotBeEmpty()
    }

    test("commander format and commander names reach the engine") {
        val service = MultiEnvService(registry())
        val created = service.create(
            config(
                perspectiveMode = PerspectiveMode.AGENT_TO_ACT,
                seed = 4242L,
                format = Format.Commander(),
                commanderCardName = "Raging Goblin",
            )
        )
        val observation = created.observation.observation.argentMlGame

        created.seed shouldBe 4242L
        observation.players.forEach { it.lifeTotal shouldBe 40 }

        val commandZones = observation.zones.filter { it.zoneType == Zone.COMMAND }
        commandZones shouldHaveSize 2
        commandZones.forEach { zone ->
            zone.hidden.shouldBeFalse()
            zone.cards shouldHaveSize 1
            zone.cards.single().name shouldBe "Raging Goblin"
        }
    }

    test("two fixed Commander seats can complete a perspective-safe pass-only game") {
        val service = MultiEnvService(registry())
        val shortDeck = DeckSpec.Explicit(mapOf("Mountain" to 7))
        val created = service.create(
            config(
                perspectiveMode = PerspectiveMode.AGENT_TO_ACT,
                seed = 5150L,
                format = Format.Commander(),
                commanderCardName = "Raging Goblin",
                playerDeck = shortDeck,
            )
        )

        var observation = created.observation.observation.argentMlGame
        val perspectivesSeen = mutableSetOf(observation.perspectivePlayerId)
        var decisions = 0

        while (!observation.terminated && decisions < 128) {
            observation.perspectivePlayerId shouldBe observation.agentToAct
            observation.legalActions.shouldNotBeEmpty()

            val opponentId = observation.players.first { !it.isPerspective }.id
            val opponentHand = observation.zones.first {
                it.ownerId == opponentId && it.zoneType == Zone.HAND
            }
            opponentHand.hidden.shouldBeTrue()
            opponentHand.cards.shouldBeEmpty()

            observation = service.step(
                StepRequest(created.envId, passActionId(observation))
            ).observation.argentMlGame
            perspectivesSeen += observation.perspectivePlayerId
            decisions++
        }

        observation.terminated.shouldBeTrue()
        perspectivesSeen shouldHaveSize 2
    }

    test("the same explicit seed yields the same opening observation") {
        val service = MultiEnvService(registry())
        val first = service.create(config(PerspectiveMode.AGENT_TO_ACT, seed = 99L))
        val second = service.create(config(PerspectiveMode.AGENT_TO_ACT, seed = 99L))

        first.seed shouldBe 99L
        second.seed shouldBe 99L
        first.observation.observation.stateDigest shouldBe
            second.observation.observation.stateDigest
    }

    test("reset metadata returns the exact replacement episode seed") {
        val service = MultiEnvService(registry())
        val created = service.create(config(PerspectiveMode.FIXED_SEAT, seed = 1L))

        val reset = service.resetWithMetadata(
            created.envId,
            config(PerspectiveMode.AGENT_TO_ACT, seed = 123_456L),
        )

        reset.seed shouldBe 123_456L
        val observation = reset.observation.observation.argentMlGame
        observation.perspectivePlayerId shouldBe observation.agentToAct
        observation.legalActions.shouldNotBeEmpty()
    }

    test("entropy-generated create and reset seeds are returned and replayable") {
        val service = MultiEnvService(registry())
        val created = service.create(config(PerspectiveMode.AGENT_TO_ACT, seed = null))
        val reset = service.resetWithMetadata(
            created.envId,
            config(PerspectiveMode.AGENT_TO_ACT, seed = null),
        )
        val replay = service.create(
            config(PerspectiveMode.AGENT_TO_ACT, seed = reset.seed),
        )

        created.seed.shouldNotBeNull()
        reset.observation.observation.stateDigest shouldBe
            replay.observation.observation.stateDigest
    }
})

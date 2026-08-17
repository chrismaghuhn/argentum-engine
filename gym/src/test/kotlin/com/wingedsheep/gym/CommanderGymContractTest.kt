package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** Focused A3 contract tests. The commander card is only a registry fixture. */
class CommanderGymContractTest : FunSpec({

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun player(name: String) = PlayerSpec(
        name = name,
        deck = DeckSpec.Explicit(mapOf("Mountain" to 99)),
        commanderCardName = "Raging Goblin"
    )

    fun commanderEnvConfig(maxSteps: Int? = null) = EnvConfig(
        players = listOf(player("Alice"), player("Bob")),
        format = Format.Commander(),
        seed = 7L,
        maxSteps = maxSteps,
        skipMulligans = true,
        startingPlayerIndex = 0,
        perspectivePlayerIndex = 0
    )

    test("Commander Gym configuration is exactly two-player") {
        shouldThrow<IllegalArgumentException> {
            EnvConfig(
                players = listOf(player("A"), player("B"), player("C")),
                format = Format.Commander()
            )
        }
        shouldThrow<IllegalArgumentException> {
            EnvConfig(
                players = listOf(player("A"), player("B").copy(commanderCardName = " ")),
                format = Format.Commander()
            )
        }
    }

    test("service maps Commander format, commander identity, and deterministic seed") {
        val created = MultiEnvService(registry()).create(commanderEnvConfig())
        val observation = created.observation.observation as TrainingObservation
        val sameSeed = MultiEnvService(registry()).create(commanderEnvConfig())

        observation.players shouldHaveSize 2
        observation.players.forEach { it.lifeTotal shouldBe 40 }
        val commandZone = observation.zones.first {
            it.ownerId == observation.perspectivePlayerId && it.zoneType == Zone.COMMAND
        }
        commandZone.cards.single().name shouldBe "Raging Goblin"
        observation.terminated.shouldBeFalse()
        observation.truncated.shouldBeFalse()
        observation.stateDigest shouldBe sameSeed.observation.observation.stateDigest
    }

    test("maxSteps reports truncation and rejects post-horizon actions") {
        val environment = GameEnvironment.create(registry())
        val config = GameConfig(
            format = Format.Commander(),
            players = listOf(
                PlayerConfig("Alice", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin"),
                PlayerConfig("Bob", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin")
            ),
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 7L
        )

        environment.reset(config, maxSteps = 1)
        environment.isTruncated.shouldBeFalse()
        val result = environment.step(environment.legalActions().first().action)

        result.terminated.shouldBeFalse()
        result.truncated.shouldBeTrue()
        environment.legalActions() shouldBe emptyList()
        shouldThrow<IllegalStateException> {
            environment.step(com.wingedsheep.engine.core.PassPriority(environment.playerIds.first()))
        }
    }

    test("snapshot and fork preserve horizon state") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry)
        )
        val config = GameConfig(
            format = Format.Commander(),
            players = listOf(
                PlayerConfig("Alice", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin"),
                PlayerConfig("Bob", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin")
            ),
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 7L
        )
        gym.reset(config, maxSteps = 2)
        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        val fork = environment.fork()

        fork.stepCount shouldBe environment.stepCount
        fork.isTruncated shouldBe environment.isTruncated

        environment.step(environment.legalActions().first().action)
        gym.restore(codec, handle)
        environment.stepCount shouldBe 0
        gym.observe().observation.let { (it as TrainingObservation).truncated.shouldBeFalse() }
    }
})

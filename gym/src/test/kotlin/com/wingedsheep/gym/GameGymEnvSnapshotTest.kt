package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GameGymEnvSnapshotTest : FunSpec({

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun config() = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 60)),
            PlayerConfig("Bob", Deck.of("Mountain" to 60)),
        ),
        skipMulligans = true,
        startingPlayerIndex = 0,
    )

    fun advanceWithPassOrFirstAction(env: GameGymEnv) {
        val legalActions = env.environment.legalActions()
        val action = legalActions
            .firstOrNull { it.action is PlayLand }
            ?.action
            ?: legalActions.firstOrNull { it.action is PassPriority }
            ?.action
            ?: legalActions.first().action
        env.environment.step(action)
        env.observe()
    }

    test("snapshot restore preserves step count and observation at the captured point") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config())
        repeat(11) { advanceWithPassOrFirstAction(gym) }

        val capturedStep = environment.stepCount
        val capturedDigest = gym.observe().observation.stateDigest
        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)

        repeat(4) { advanceWithPassOrFirstAction(gym) }
        gym.restore(codec, handle)

        environment.stepCount shouldBe capturedStep
        gym.observe().observation.stateDigest shouldBe capturedDigest
    }

    test("snapshot at step 73 leaves only 27 steps in a 100-step episode budget") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config())
        repeat(73) { advanceWithPassOrFirstAction(gym) }

        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        repeat(3) { advanceWithPassOrFirstAction(gym) }
        gym.restore(codec, handle)

        var restoredSteps = 0
        while (!gym.isTerminal && environment.stepCount < 100) {
            advanceWithPassOrFirstAction(gym)
            restoredSteps++
        }

        restoredSteps shouldBe 27
        environment.stepCount shouldBe 100
    }
})

package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
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

    fun passOrFirstAction(env: GameGymEnv): Int {
        val observation = env.observe().observation
        return observation.legalActions
            .firstOrNull { it.kind.contains("pass", ignoreCase = true) }
            ?.actionId
            ?: observation.legalActions.first().actionId
    }

    test("snapshot restore preserves step count and observation at the captured point") {
        val environment = GameEnvironment.create(registry())
        val gym = GameGymEnv(environment, perspectivePlayerIndex = 0, defaultRevealAll = false)
        gym.reset(config())
        repeat(11) { gym.step(passOrFirstAction(gym)) }

        val capturedStep = environment.stepCount
        val capturedDigest = gym.observe().observation.stateDigest
        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)

        repeat(4) { gym.step(passOrFirstAction(gym)) }
        gym.restore(codec, handle)

        environment.stepCount shouldBe capturedStep
        gym.observe().observation.stateDigest shouldBe capturedDigest
    }

    test("snapshot at step 73 leaves only 27 steps in a 100-step episode budget") {
        val environment = GameEnvironment.create(registry())
        val gym = GameGymEnv(environment, perspectivePlayerIndex = 0, defaultRevealAll = false)
        gym.reset(config())
        repeat(73) { gym.step(passOrFirstAction(gym)) }

        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        repeat(3) { gym.step(passOrFirstAction(gym)) }
        gym.restore(codec, handle)

        var restoredSteps = 0
        while (!gym.isTerminal && environment.stepCount < 100) {
            gym.step(passOrFirstAction(gym))
            restoredSteps++
        }

        restoredSteps shouldBe 27
        environment.stepCount shouldBe 100
    }
})

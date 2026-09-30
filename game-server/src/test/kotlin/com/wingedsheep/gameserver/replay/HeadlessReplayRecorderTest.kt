package com.wingedsheep.gameserver.replay

import com.wingedsheep.gym.GameEnvironment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * A game played headlessly through the LEGACY [GameEnvironment] and recorded by
 * [HeadlessReplayRecorder] must re-simulate EXACT through the replay verification the viewer uses.
 */
class HeadlessReplayRecorderTest : FunSpec({

    val registry = HeadlessEngineAiGame.registry

    test("an engine-AI Commander game round-trips through the codec and re-simulates EXACT") {
        // Bounded so this stays a unit test; the opt-in showcase generator plays whole games.
        val recorded = HeadlessEngineAiGame.play(gameId = "headless-recorder-test", seed = 7L, maxSteps = 60)
        val replay = recorded.replay

        // A legacy step also commits the automatic passes and forced answers of its quiet-state
        // loop. They are part of the input stream, or the replay would not re-simulate.
        replay.actions.size shouldBeGreaterThan recorded.submittedSteps
        replay.version shouldBe CompactReplay.CURRENT_VERSION
        replay.checkpoints.first().afterActionCount shouldBe 0
        replay.checkpoints.last().afterActionCount shouldBe replay.actions.size

        val decoded = ReplayCodec.decode(ReplayCodec.encode(replay))
        decoded shouldBe replay

        // The viewer's path — the replay verification every live game goes through, wired exactly
        // as the server wires it (token art is fingerprinted state). The Gym trajectory binding,
        // GymReplayFrameSource, is the stricter TRUSTED-data gate and rejects engine auto-pay by
        // design; LEGACY showcase games are for watching, not for training.
        val reconstructed = HeadlessEngineAiGame.serverReconstructor().reconstruct(decoded)
        reconstructed.divergenceReason shouldBe null
        reconstructed.fidelity shouldBe ReplayFidelity.EXACT
        reconstructed.frameCount shouldBe replay.frameCount
    }

    test("finish refuses a recording whose environment was reset underneath it") {
        val environment = HeadlessEngineAiGame.environment()
        val config = HeadlessEngineAiGame.config(seed = 3L)
        val recorder = HeadlessReplayRecorder.start(environment, registry, config)
        environment.reset(config)

        shouldThrow<IllegalStateException> { recorder.finish(gameId = "reset-under-recorder") }
        environment.committedTransitionListener shouldBe null
    }

    test("start requires a fixed seed and explicit seat ids") {
        val environment = HeadlessEngineAiGame.environment()
        val config = HeadlessEngineAiGame.config(seed = 3L)

        shouldThrow<IllegalArgumentException> {
            HeadlessReplayRecorder.start(environment, registry, config.copy(seed = null))
        }
        shouldThrow<IllegalArgumentException> {
            HeadlessReplayRecorder.start(
                environment,
                registry,
                config.copy(players = config.players.map { it.copy(playerId = null) }),
            )
        }
    }
})

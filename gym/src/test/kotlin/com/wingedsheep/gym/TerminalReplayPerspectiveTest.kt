package com.wingedsheep.gym

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TerminalReplayPerspectiveTest : FunSpec({
    test("terminal replay projection uses configured fallback instead of stale priority") {
        val configuredFallback = EntityId("player-0")
        val stalePriority = EntityId("player-1")
        val state = GameState(
            turnOrder = listOf(configuredFallback, stalePriority),
            activePlayerId = stalePriority,
            priorityPlayerId = stalePriority,
            winnerId = configuredFallback,
            gameOver = true,
        )
        val builder = ObservationBuilder(cardRegistry = CardRegistry())
        val gymProjection = project(builder, state, configuredFallback)

        val replayPerspective = ObservationPerspective.resolve(
            state = state,
            playerIds = listOf(configuredFallback, stalePriority),
            fallbackPerspectivePlayerIndex = 0,
        ) ?: error("Replay projection has no configured fallback player")
        replayPerspective shouldBe configuredFallback
        val replayProjection = project(builder, state, replayPerspective)

        replayProjection.stateDigest shouldBe gymProjection.stateDigest
    }
})

private fun project(
    builder: ObservationBuilder,
    state: GameState,
    perspective: EntityId,
): TrainingObservation = builder.build(
    state = state,
    perspectivePlayerId = perspective,
    legalActions = emptyList(),
).observation as TrainingObservation

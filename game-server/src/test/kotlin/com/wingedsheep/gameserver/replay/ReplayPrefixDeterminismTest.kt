package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gameserver.session.SpectatorSeat
import com.wingedsheep.gameserver.session.SpectatorStateBuilder
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession
import java.time.Instant

/**
 * Prefix-level proof that the recorded input stream and v3 state fingerprints describe the same
 * immutable positions that the live session reached. Viewer comparisons use the existing
 * spectator projection; no replay-specific observation shape is introduced here.
 */
class ReplayPrefixDeterminismTest : ScenarioTestBase() {

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    private data class Recording(
        val replay: CompactReplay,
        val liveStates: Map<Int, GameState>,
    )

    private fun recording(): Recording {
        val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
        val p1 = EntityId.of("prefix-p1")
        val p2 = EntityId.of("prefix-p2")
        session.addPlayer(PlayerSession(mockWs("prefix-ws1"), p1, "Alice"), mapOf("Forest" to 40))
        session.addPlayer(PlayerSession(mockWs("prefix-ws2"), p2, "Bob"), mapOf("Forest" to 40))
        session.startGame()

        val liveStates = linkedMapOf<Int, GameState>()
        fun capture() {
            liveStates[session.getRecordedActions().size] = session.getStateForTesting().shouldNotBeNull()
        }

        capture()
        session.keepHand(p1)
        capture()
        session.keepHand(p2)
        capture()
        repeat(45) {
            val state = session.getStateForTesting() ?: return@repeat
            if (state.gameOver) return@repeat
            state.priorityPlayerId?.let { session.executeAutoPass(it) }
            capture()
        }

        val snapshot = session.replayRecordingSnapshot().shouldNotBeNull()
        val replay = CompactReplay(
            version = snapshot.version,
            gameId = session.sessionId,
            players = session.getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
            startedAt = (snapshot.startedAt ?: Instant.now()).toString(),
            endedAt = Instant.now().toString(),
            winnerName = null,
            setup = snapshot.setup,
            actions = snapshot.actions,
            yields = snapshot.yields,
            checkpoints = ReplayCheckpointPolicy.withV3Tail(
                checkpoints = snapshot.checkpoints,
                actionCount = snapshot.actions.size,
                fingerprint = snapshot.fingerprint,
            ),
        )
        replay.actions.shouldNotBeEmpty()
        return Recording(replay, liveStates)
    }

    init {
        test("v3 replay reconstruction matches live fingerprints at early, middle, and terminal prefixes") {
            val recording = recording()
            val replay = recording.replay
            val reconstructor = ReplayReconstructor(cardRegistry, null)
            val prefixes = listOf(0, 1, replay.actions.size / 2, replay.actions.size).distinct()

            prefixes.forEach { prefix ->
                val live = recording.liveStates[prefix].shouldNotBeNull()
                val prefixReplay = replay.copy(
                    actions = replay.actions.take(prefix),
                    yields = replay.yields.filter { it.afterActionCount <= prefix },
                    checkpoints = emptyList(),
                )
                val reconstructed = reconstructor.reconstructStateAt(prefixReplay, prefix).shouldNotBeNull()

                ReplayFingerprint.of(reconstructed, 3) shouldBe ReplayFingerprint.of(live, 3)

                val setup = replay.setup
                val seats = setup.players.map { SpectatorSeat(EntityId(it.playerId), it.name) }
                val builder = SpectatorStateBuilder(cardRegistry, ClientStateTransformer(cardRegistry))
                builder.buildState(reconstructed, seats, setup.seatRoster, replay.gameId) shouldBe
                    builder.buildState(live, seats, setup.seatRoster, replay.gameId)
            }

            reconstructor.reconstruct(replay).fidelity shouldBe ReplayFidelity.EXACT
        }
    }
}
